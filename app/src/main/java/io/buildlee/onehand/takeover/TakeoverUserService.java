package io.buildlee.onehand.takeover;

import android.content.Context;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.IBinder;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Log;

import androidx.annotation.Keep;

import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/** First-stage Shizuku service: tests identity and cross-UID lock passing only. */
public final class TakeoverUserService extends IRecoveryGuard.Stub {
    private final int applicationUid;
    private final String applicationPackage;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private ParcelFileDescriptor sessionFile;
    private IBinder client;
    private IBinder.DeathRecipient death;
    private String sessionId, left, right, lockPath;
    private long lastSequence, heartbeatDeadline, trialDeadline;
    private long lockDevice, lockInode;
    private boolean applied, confirmed;

    @Keep public TakeoverUserService(Context context) {
        applicationUid = context.getApplicationInfo().uid;
        applicationPackage = context.getPackageName();
        timer.scheduleAtFixedRate(this::expireIfNecessary, 1, 1, TimeUnit.SECONDS);
    }

    @Override public boolean grantRecoveryPermission() {
        checkCaller();
        if (!"io.buildlee.onehand".equals(applicationPackage)) return false;
        java.lang.Process command = null;
        try {
            command = new ProcessBuilder("/system/bin/pm", "grant", "--user",
                    String.valueOf(applicationUid / 100000), applicationPackage,
                    "android.permission.WRITE_SECURE_SETTINGS").redirectErrorStream(true).start();
            if (!command.waitFor(4, TimeUnit.SECONDS)) {
                command.destroyForcibly();
                return false;
            }
            return command.exitValue() == 0;
        } catch (Exception e) {
            Log.w("RecoveryGuard", "permission grant failed", e);
            return false;
        }
    }

    private void checkCaller() {
        if (Binder.getCallingUid() != applicationUid || Process.myUid() != 2000) {
            throw new SecurityException("Unsupported caller or Shizuku identity");
        }
    }

    @Override public int identity() {
        checkCaller();
        return Process.myUid();
    }

    @Override public boolean tryLock(ParcelFileDescriptor file) {
        checkCaller();
        if (file == null) return false;
        try (ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(file)) {
            FileLock lock;
            try { lock = stream.getChannel().tryLock(); }
            catch (OverlappingFileLockException e) { Log.w("RecoveryGuard", "lock overlaps", e); return false; }
            if (lock == null) { Log.w("RecoveryGuard", "lock currently held"); return false; }
            lock.release();
            return true;
        } catch (Exception e) {
            Log.w("RecoveryGuard", "lock probe failed", e);
            return false;
        }
    }

    private interface LockedWork { boolean run(java.io.FileDescriptor fd) throws Exception; }

    private boolean locked(LockedWork work) throws Exception {
        if (sessionFile == null) return false;
        ParcelFileDescriptor descriptor = sessionFile.dup();
        try (ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            long end = SystemClock.elapsedRealtime() + 2000;
            FileLock lock = null;
            while (lock == null && SystemClock.elapsedRealtime() < end) {
                try { lock = stream.getChannel().tryLock(); } catch (OverlappingFileLockException ignored) { }
                if (lock == null) Thread.sleep(25);
            }
            if (lock == null) return false;
            try (FileLock acquired = lock) {
                StructStat open = Os.fstat(stream.getFD());
                StructStat path = Os.stat(lockPath);
                if (open.st_dev != path.st_dev || open.st_ino != path.st_ino
                        || open.st_dev != lockDevice || open.st_ino != lockInode) return false;
                return work.run(stream.getFD());
            }
        }
    }

    private String control(java.io.FileDescriptor fd) throws Exception {
        Os.lseek(fd, 0, OsConstants.SEEK_SET);
        byte[] data = new byte[128];
        int count = Os.read(fd, data, 0, data.length);
        return new String(data, 0, count, StandardCharsets.UTF_8);
    }

    private void cancel(java.io.FileDescriptor fd) throws Exception {
        byte[] value = (sessionId + "\nCANCELLED\n").getBytes(StandardCharsets.UTF_8);
        Os.lseek(fd, 0, OsConstants.SEEK_SET);
        Os.ftruncate(fd, 0);
        if (Os.write(fd, value, 0, value.length) != value.length) throw new IllegalStateException("control write incomplete");
        Os.fsync(fd);
    }

    private String command(String operation, String key, String value) throws Exception {
        if (!RecoveryStore.LEFT.equals(key) && !RecoveryStore.RIGHT.equals(key)) throw new SecurityException("unsupported key");
        java.util.ArrayList<String> args = new java.util.ArrayList<>();
        java.util.Collections.addAll(args, "/system/bin/settings", "--user", String.valueOf(applicationUid / 100000),
                operation, "secure", key);
        if (value != null) args.add(value);
        java.lang.Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
        if (!p.waitFor(4, TimeUnit.SECONDS)) { p.destroyForcibly(); throw new IllegalStateException("settings timeout"); }
        String output;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            String line = reader.readLine(); output = line == null ? "" : line.trim();
        }
        if (p.exitValue() != 0) throw new IllegalStateException("settings failed");
        return "get".equals(operation) && "null".equals(output) ? null : output;
    }

    private boolean validOriginal(String value) {
        if (value == null) return true;
        try { float number = Float.parseFloat(value); return Float.isFinite(number) && number > 0 && number <= 10; }
        catch (NumberFormatException e) { return false; }
    }

    @Override public synchronized boolean prepare(String id, String originalLeft, String originalRight,
                                                   String path, long expectedDevice, long expectedInode,
                                                   ParcelFileDescriptor file, IBinder token) {
        checkCaller();
        String expected = "/storage/emulated/" + (applicationUid / 100000) + "/Android/data/"
                + applicationPackage + "/files/.gesture-recovery.lock";
        if (sessionFile != null || id == null || !id.matches("[a-zA-Z0-9-]{1,80}")
                || !expected.equals(path) || file == null || token == null
                || !validOriginal(originalLeft) || !validOriginal(originalRight)) {
            try { if (file != null) file.close(); } catch (Exception ignored) { }
            return false;
        }
        try {
            sessionFile = file.dup();
            sessionId = id; left = originalLeft; right = originalRight; lockPath = path;
            lockDevice = expectedDevice; lockInode = expectedInode;
            if (!locked(fd -> control(fd).equals(sessionId + "\nACTIVE\n"))) { clearSession(); return false; }
            client = token;
            death = () -> timer.execute(this::recoverIfNecessary);
            client.linkToDeath(death, 0);
            long now = SystemClock.elapsedRealtime();
            heartbeatDeadline = now + 4000; trialDeadline = now + 30000;
            return true;
        } catch (Exception e) {
            Log.w("RecoveryGuard", "prepare failed", e);
            clearSession();
            return false;
        } finally {
            try { if (file != null) file.close(); } catch (Exception ignored) { }
        }
    }

    @Override public synchronized boolean apply(String id) {
        checkCaller();
        if (!Objects.equals(id, sessionId) || sessionFile == null || applied
                || SystemClock.elapsedRealtime() >= heartbeatDeadline) return false;
        try {
            return locked(fd -> {
                if (!control(fd).equals(sessionId + "\nACTIVE\n")) return false;
                if (!Objects.equals(command("get", RecoveryStore.LEFT, null), left)
                        || !Objects.equals(command("get", RecoveryStore.RIGHT, null), right)) return false;
                applied = true; // Set before either write so a partial change can be rolled back.
                try {
                    command("put", RecoveryStore.LEFT, "0");
                    if (!control(fd).equals(sessionId + "\nACTIVE\n")) throw new IllegalStateException("session cancelled");
                    command("put", RecoveryStore.RIGHT, "0");
                    if (!"0".equals(command("get", RecoveryStore.LEFT, null))
                            || !"0".equals(command("get", RecoveryStore.RIGHT, null))) throw new IllegalStateException("readback mismatch");
                    return true;
                } catch (Exception e) {
                    restoreLocked(fd);
                    return false;
                }
            });
        } catch (Exception e) {
            Log.w("RecoveryGuard", "apply failed", e);
            return false;
        }
    }

    @Override public synchronized boolean renew(String id, long sequence) {
        checkCaller();
        long now = SystemClock.elapsedRealtime();
        if (!Objects.equals(id, sessionId) || sessionFile == null || sequence <= lastSequence
                || now >= heartbeatDeadline || (!confirmed && now >= trialDeadline) || !controlActive()) return false;
        lastSequence = sequence; heartbeatDeadline = now + 4000;
        return true;
    }

    @Override public synchronized boolean activate(String id) {
        checkCaller();
        if (!Objects.equals(id, sessionId) || !applied || SystemClock.elapsedRealtime() >= trialDeadline
                || SystemClock.elapsedRealtime() >= heartbeatDeadline || !controlActive()) return false;
        confirmed = true;
        return true;
    }

    @Override public synchronized boolean restore(String id) {
        checkCaller();
        return Objects.equals(id, sessionId) && recoverIfNecessary();
    }

    private boolean restoreLocked(java.io.FileDescriptor fd) throws Exception {
        String control = control(fd);
        if (!control.equals(sessionId + "\nACTIVE\n") && !control.equals(sessionId + "\nCANCELLED\n")) return false;
        cancel(fd);
        boolean restored = true;
        String[] keys = {RecoveryStore.LEFT, RecoveryStore.RIGHT};
        String[] originals = {left, right};
        for (int i = 0; i < keys.length; i++) {
            String current = command("get", keys[i], null);
            if (Objects.equals(current, originals[i])) continue;
            if (!"0".equals(current)) { restored = false; continue; }
            command(originals[i] == null ? "delete" : "put", keys[i], originals[i]);
            if (!Objects.equals(command("get", keys[i], null), originals[i])) restored = false;
        }
        return restored;
    }

    private synchronized boolean recoverIfNecessary() {
        if (sessionFile == null) return true;
        try {
            boolean restored = locked(this::restoreLocked);
            if (restored) clearSession();
            return restored;
        } catch (Exception e) {
            Log.w("RecoveryGuard", "automatic recovery failed", e);
            return false;
        }
    }

    private boolean controlActive() {
        try { return locked(fd -> control(fd).equals(sessionId + "\nACTIVE\n")); }
        catch (Exception e) { return false; }
    }

    private synchronized void expireIfNecessary() {
        long now = SystemClock.elapsedRealtime();
        if (sessionFile != null && (now >= heartbeatDeadline || (!confirmed && now >= trialDeadline))) recoverIfNecessary();
    }

    private void clearSession() {
        if (client != null && death != null) client.unlinkToDeath(death, 0);
        try { if (sessionFile != null) sessionFile.close(); } catch (Exception ignored) { }
        sessionFile = null; client = null; death = null; sessionId = null;
        left = null; right = null; lockPath = null; lastSequence = 0; applied = false; confirmed = false;
        lockDevice = 0; lockInode = 0;
    }

    @Override public void destroy() {
        if (recoverIfNecessary()) System.exit(0);
        Log.e("RecoveryGuard", "service retained: settings recovery is still pending");
    }
}
