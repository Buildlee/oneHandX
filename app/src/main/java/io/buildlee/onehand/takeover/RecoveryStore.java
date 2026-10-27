package io.buildlee.onehand.takeover;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.StructStat;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Durable recovery journal. All read-modify-write operations use the stable lock file. */
public final class RecoveryStore {
    public static final String LEFT = "back_gesture_inset_scale_left";
    public static final String RIGHT = "back_gesture_inset_scale_right";
    private static final int SCHEMA = 1;
    private final File directory;
    private final AtomicFile journal;
    private final File lockFile;
    private long lockDevice;
    private long lockInode;
    private RandomAccessFile activeLock;

    public RecoveryStore(Context context) {
        this(new File(context.createDeviceProtectedStorageContext().getNoBackupFilesDir(), "gesture-recovery"),
                context.getExternalFilesDir(null) == null ? null
                        : new File(context.getExternalFilesDir(null), ".gesture-recovery.lock"));
    }

    // Allows instrumentation to exercise a separate journal without touching the real one.
    public RecoveryStore(File directory) {
        this(directory, new File(directory, "session.lock"));
    }

    private RecoveryStore(File directory, File lockFile) {
        this.directory = directory;
        this.journal = new AtomicFile(new File(directory, "session.json"));
        this.lockFile = lockFile;
    }

    public interface LockedOperation<T> { T run(RecoveryStore store) throws Exception; }

    public ParcelFileDescriptor openLockDescriptor() throws IOException {
        if (lockFile == null || !lockFile.isFile()) throw new IOException("恢复锁不可用");
        return ParcelFileDescriptor.open(lockFile, ParcelFileDescriptor.MODE_READ_WRITE);
    }

    public String lockPath() throws IOException {
        if (lockFile == null) throw new IOException("恢复锁所在存储不可用");
        return lockFile.getAbsolutePath();
    }

    public <T> T locked(LockedOperation<T> operation) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建恢复目录");
        }
        if (lockFile == null || !lockFile.getParentFile().isDirectory()) throw new IOException("恢复锁所在存储不可用");
        if (!lockFile.exists() && journal.getBaseFile().exists()) {
            // A lost lock within the same boot may still have a live holder. After a
            // reboot, all holders are gone and a new lock can safely fence recovery.
            JSONObject existing = new JSONObject(new String(journal.readFully(), StandardCharsets.UTF_8));
            if (bootId().equals(existing.getString("bootId"))) throw new IOException("恢复锁文件丢失");
        }
        // RandomAccessFile does not truncate: the inode used for locking is never replaced.
        try (RandomAccessFile file = new RandomAccessFile(lockFile, "rw");
             FileChannel channel = file.getChannel()) {
            long deadline = SystemClock.elapsedRealtime() + 2000;
            while (true) {
                FileLock lock = null;
                try { lock = channel.tryLock(); } catch (OverlappingFileLockException ignored) { }
                if (lock != null) {
                    try (FileLock acquired = lock) {
                        StructStat descriptor = Os.fstat(file.getFD());
                        StructStat path = Os.stat(lockFile.getAbsolutePath());
                        if (descriptor.st_dev != path.st_dev || descriptor.st_ino != path.st_ino) {
                            throw new IOException("恢复锁文件已替换");
                        }
                        lockDevice = descriptor.st_dev;
                        lockInode = descriptor.st_ino;
                        activeLock = file;
                        try { return operation.run(this); }
                        finally { activeLock = null; }
                    }
                }
                if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("恢复事务正忙，请稍后重试");
                Thread.sleep(25);
            }
        }
    }

    /** Must only be called while holding locked(). Null means the key did not exist. */
    public JSONObject readLocked() throws Exception {
        if (!journal.getBaseFile().exists()
                && !new File(directory, "session.json.bak").exists()
                && !new File(directory, "session.json.new").exists()) return null;
        JSONObject data = new JSONObject(new String(journal.readFully(), StandardCharsets.UTF_8));
        validate(data);
        String currentBoot = bootId();
        if (!currentBoot.equals(data.getString("bootId"))) {
            // No old process survives a reboot. Cancel the old lease before rebinding
            // the external-storage lock, whose mount device number may have changed.
            data.put("cancelRequested", true);
            if (!"RESTORED".equals(data.getString("state"))) data.put("state", "RECOVERY_REQUIRED");
            data.put("bootId", currentBoot);
            data.put("lockDevice", lockDevice);
            data.put("lockInode", lockInode);
            writeControlLocked(data.getString("sessionId"), true);
            writeLocked(data);
        }
        verifyLockLocked(data);
        return data;
    }

    /** Saves original values before any settings write. A pending record is never overwritten. */
    public JSONObject prepareLocked(String left, String right) throws Exception {
        JSONObject old = readLocked();
        if (old != null && !"RESTORED".equals(old.getString("state"))) {
            throw new IllegalStateException("尚有未恢复的手势记录");
        }
        validateOriginal(left);
        validateOriginal(right);
        JSONObject original = new JSONObject();
        original.put(LEFT, left == null ? JSONObject.NULL : left);
        original.put(RIGHT, right == null ? JSONObject.NULL : right);
        JSONObject session = new JSONObject();
        session.put("schemaVersion", SCHEMA);
        session.put("sessionId", UUID.randomUUID().toString());
        session.put("userId", Process.myUid() / 100000);
        session.put("buildFingerprint", Build.FINGERPRINT);
        session.put("bootId", bootId());
        session.put("lockDevice", lockDevice);
        session.put("lockInode", lockInode);
        session.put("state", "PREPARED");
        session.put("cancelRequested", false);
        session.put("original", original);
        writeLocked(session);
        writeControlLocked(session.getString("sessionId"), false);
        return session;
    }

    public void writeLocked(JSONObject session) throws IOException, JSONException {
        validate(session);
        try { verifyLockLocked(session); } catch (Exception e) { throw new IOException("恢复锁不可用", e); }
        FileOutputStream output = journal.startWrite();
        try {
            output.write(session.toString().getBytes(StandardCharsets.UTF_8));
            journal.finishWrite(output);
        } catch (IOException | RuntimeException e) {
            journal.failWrite(output);
            throw e;
        }
    }

    public void verifyLockLocked(JSONObject session) throws Exception {
        StructStat path = Os.stat(lockFile.getAbsolutePath());
        if (path.st_dev != lockDevice || path.st_ino != lockInode
                || session.getLong("lockDevice") != lockDevice || session.getLong("lockInode") != lockInode) {
            throw new IOException("恢复锁文件已变化");
        }
    }

    /** The stable lock itself holds a minimal cancellation fence, never original values. */
    public void writeControlLocked(String sessionId, boolean cancelled) throws Exception {
        if (activeLock == null || sessionId == null || !sessionId.matches("[a-zA-Z0-9-]{1,80}")) {
            throw new IOException("恢复控制记录无效");
        }
        activeLock.seek(0);
        activeLock.setLength(0);
        activeLock.write((sessionId + "\n" + (cancelled ? "CANCELLED" : "ACTIVE") + "\n")
                .getBytes(StandardCharsets.UTF_8));
        activeLock.getFD().sync();
    }

    public void cancelControlLocked(JSONObject session) throws Exception {
        verifyLockLocked(session);
        if (activeLock == null || activeLock.length() > 128) throw new IOException("恢复控制记录损坏");
        activeLock.seek(0);
        byte[] bytes = new byte[(int) activeLock.length()];
        activeLock.readFully(bytes);
        String value = new String(bytes, StandardCharsets.UTF_8);
        String id = session.getString("sessionId");
        if (!value.equals(id + "\nACTIVE\n") && !value.equals(id + "\nCANCELLED\n")) {
            throw new IOException("恢复会话不匹配");
        }
        writeControlLocked(id, true);
    }

    public static String original(JSONObject session, String key) throws JSONException {
        if (!LEFT.equals(key) && !RIGHT.equals(key)) throw new JSONException("未知设置项");
        JSONObject values = session.getJSONObject("original");
        return values.isNull(key) ? null : values.getString(key);
    }

    private static String bootId() throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/sys/kernel/random/boot_id"))) {
            String value = reader.readLine();
            if (value == null || !value.matches("[0-9a-fA-F-]{36}")) throw new IOException("无法确认系统启动标识");
            return value;
        }
    }

    private static void validateOriginal(String value) throws JSONException {
        if (value == null) return;
        try {
            float number = Float.parseFloat(value);
            if (!Float.isFinite(number) || number <= 0f || number > 10f) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new JSONException("原始手势灵敏度无效或已关闭");
        }
    }

    private static void validate(JSONObject session) throws JSONException {
        if (session.getInt("schemaVersion") != SCHEMA || session.getString("sessionId").isEmpty()
                || session.getInt("userId") != Process.myUid() / 100000) {
            throw new JSONException("恢复记录版本或用户不匹配");
        }
        String state = session.getString("state");
        if (!state.equals("PREPARED") && !state.equals("APPLYING") && !state.equals("TRIAL")
                && !state.equals("ACTIVE") && !state.equals("RESTORING")
                && !state.equals("RECOVERY_REQUIRED") && !state.equals("RESTORED")) {
            throw new JSONException("恢复记录状态无效");
        }
        JSONObject original = session.getJSONObject("original");
        if (!original.has(LEFT) || !original.has(RIGHT)) throw new JSONException("恢复记录缺少原始值");
        validateOriginal(original.isNull(LEFT) ? null : original.getString(LEFT));
        validateOriginal(original.isNull(RIGHT) ? null : original.getString(RIGHT));
        session.getBoolean("cancelRequested");
        if (!session.getString("bootId").matches("[0-9a-fA-F-]{36}")) throw new JSONException("系统启动标识无效");
    }
}
