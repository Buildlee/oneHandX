package io.buildlee.onehand;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/** Cross-process journal in private device-protected storage; excluded from backups. */
final class BudgetStore {
    private final File directory;
    private final AtomicFile journal;
    BudgetStore(Context context) {
        directory = new File(context.createDeviceProtectedStorageContext().getNoBackupFilesDir(), "exclusion-budget");
        journal = new AtomicFile(new File(directory, "session.json"));
    }
    boolean pending() { return journal.getBaseFile().exists() || new File(directory,"session.json.bak").exists(); }
    interface Work<T> { T run() throws Exception; }
    private <T> T locked(Work<T> work) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("恢复记录目录不可用");
        try (RandomAccessFile lock = new RandomAccessFile(new File(directory,"session.lock"),"rw");
             FileLock held = lock.getChannel().lock()) { return work.run(); }
    }
    JSONObject read() throws Exception { return locked(() -> readLocked()); }
    private JSONObject readLocked() throws Exception {
        if (!pending()) return null;
        JSONObject value = new JSONObject(new String(journal.readFully(), StandardCharsets.UTF_8));
        if (value.getInt("schema") != 1 || !value.getString("id").matches("[a-zA-Z0-9-]{1,80}"))
            throw new IllegalStateException("恢复记录格式不支持");
        return value;
    }
    void prepare(String id, String original, int target) throws Exception {
        locked(() -> {
            if (pending()) throw new IllegalStateException("请先恢复上次的临时额度");
            JSONObject value = new JSONObject().put("schema",1).put("id",id)
                    .put("original", original == null ? JSONObject.NULL : original).put("target",target);
            FileOutputStream output = journal.startWrite();
            try { output.write(value.toString().getBytes(StandardCharsets.UTF_8)); journal.finishWrite(output); }
            catch (Exception failure) { journal.failWrite(output); throw failure; }
            return null;
        });
    }
    void clear(String id) throws Exception {
        locked(() -> { JSONObject current=readLocked(); if (current!=null && id.equals(current.getString("id"))) journal.delete(); return null; });
    }
    static String original(JSONObject value) throws Exception { return value.isNull("original") ? null : value.getString("original"); }
}
