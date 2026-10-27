package io.buildlee.onehand;

import android.app.Instrumentation;

import io.buildlee.onehand.takeover.LocalRecovery;
import io.buildlee.onehand.takeover.RecoveryStore;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Device-side tests use an isolated journal and fake settings, never the device navigation settings. */
public final class RecoveryInstrumentation {
    private final Instrumentation instrumentation;
    private int checks;
    public RecoveryInstrumentation(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    private void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
    private static final class FakeSettings implements LocalRecovery.SettingsAccess {
        final Map<String, String> values = new HashMap<>();
        String failKey;
        boolean deny;
        @Override public String get(String key) { return values.get(key); }
        @Override public void put(String key, String value) {
            if (deny) throw new SecurityException("denied");
            if (key.equals(failKey)) throw new IllegalStateException("failed");
            if (value == null) values.remove(key); else values.put(key, value);
        }
    }
    private RecoveryStore isolated() {
        return new RecoveryStore(new File(instrumentation.getTargetContext().getCacheDir(), "recovery-test-"+UUID.randomUUID()));
    }
    private void prepare(RecoveryStore store, String left, String right) throws Exception {
        store.locked(s -> { s.prepareLocked(left, right); return null; });
    }
    private String state(RecoveryStore store) throws Exception {
        return store.locked(s -> s.readLocked().getString("state"));
    }
    public int run() throws Exception {
            FakeSettings settings = new FakeSettings();
            RecoveryStore store = isolated();
            LocalRecovery recovery = new LocalRecovery(store, settings);
            check(recovery.restore() == LocalRecovery.Result.NO_RECORD, "empty journal");
            prepare(store, null, "0.7");
            boolean blocked = false;
            try { prepare(store, null, null); } catch (IllegalStateException expected) { blocked = true; }
            check(blocked, "pending journal cannot be overwritten");
            settings.values.put(RecoveryStore.LEFT, "0"); settings.values.put(RecoveryStore.RIGHT, "0");
            check(recovery.restore() == LocalRecovery.Result.RESTORED, "restore absent/asymmetric values");
            check(settings.get(RecoveryStore.LEFT) == null && "0.7".equals(settings.get(RecoveryStore.RIGHT)), "original values retained");
            check("RESTORED".equals(state(store)), "terminal state persisted");
            check(recovery.restore() == LocalRecovery.Result.NO_RECORD, "repeat restore idempotent");

            store = isolated(); settings = new FakeSettings(); recovery = new LocalRecovery(store, settings);
            prepare(store, "0.8", "1.3");
            settings.values.put(RecoveryStore.LEFT, "0"); settings.values.put(RecoveryStore.RIGHT, "1.1");
            check(recovery.restore() == LocalRecovery.Result.CONFLICT, "external change detected");
            check("0.8".equals(settings.get(RecoveryStore.LEFT)) && "1.1".equals(settings.get(RecoveryStore.RIGHT)), "safe side restored and external change preserved");
            check("RECOVERY_REQUIRED".equals(state(store)), "conflict retains journal");

            store = isolated(); settings = new FakeSettings(); recovery = new LocalRecovery(store, settings);
            prepare(store, "0.8", "1.3");
            settings.values.put(RecoveryStore.LEFT, "0"); settings.values.put(RecoveryStore.RIGHT, "0");
            settings.failKey = RecoveryStore.RIGHT;
            check(recovery.restore() == LocalRecovery.Result.FAILED, "partial restore fails safely");
            check("0.8".equals(settings.get(RecoveryStore.LEFT)) && "0".equals(settings.get(RecoveryStore.RIGHT)), "partial result retained");
            settings.failKey = null;
            check(recovery.restore() == LocalRecovery.Result.RESTORED, "retry after partial failure");

            store = isolated(); settings = new FakeSettings(); recovery = new LocalRecovery(store, settings);
            prepare(store, null, null); settings.values.put(RecoveryStore.LEFT, "0"); settings.values.put(RecoveryStore.RIGHT, "0");
            settings.deny = true;
            check(recovery.restore() == LocalRecovery.Result.NEEDS_PERMISSION, "permission failure reported");
            check("RECOVERY_REQUIRED".equals(state(store)), "permission failure preserves journal");
            settings.deny = false;
            check(recovery.restore() == LocalRecovery.Result.RESTORED, "permission retry restores");

            store = isolated(); boolean invalid = false;
            try { prepare(store, "0", null); } catch (org.json.JSONException expected) { invalid = true; }
            check(invalid, "zero baseline refused");
            invalid = false;
            try { prepare(store, "NaN", null); } catch (org.json.JSONException expected) { invalid = true; }
            check(invalid, "invalid baseline refused");

            File dir = new File(instrumentation.getTargetContext().getCacheDir(), "recovery-corrupt-"+UUID.randomUUID());
            check(dir.mkdirs(), "create corrupt fixture");
            try (FileOutputStream out = new FileOutputStream(new File(dir, "session.json"))) { out.write("invalid".getBytes()); }
            store = new RecoveryStore(dir); recovery = new LocalRecovery(store, new FakeSettings());
            check(recovery.restore() == LocalRecovery.Result.FAILED, "corrupt journal does not report no record");
            check(new File(dir, "session.json").exists(), "corrupt record retained");
            dir = new File(instrumentation.getTargetContext().getCacheDir(), "recovery-lock-replaced-"+UUID.randomUUID());
            store = new RecoveryStore(dir);
            prepare(store, null, null);
            File originalLock = new File(dir, "session.lock");
            check(originalLock.renameTo(new File(dir, "previous.lock")), "move old test lock");
            check(originalLock.createNewFile(), "create replacement test lock");
            recovery = new LocalRecovery(store, new FakeSettings());
            check(recovery.restore() == LocalRecovery.Result.FAILED, "replacement lock blocks recovery");
            dir = new File(instrumentation.getTargetContext().getCacheDir(), "recovery-newboot-"+UUID.randomUUID());
            store = new RecoveryStore(dir);
            prepare(store, null, "0.7");
            JSONObject oldBoot = store.locked(s -> s.readLocked());
            oldBoot.put("bootId", "00000000-0000-0000-0000-000000000000");
            oldBoot.put("lockDevice", -1);
            try (FileOutputStream out = new FileOutputStream(new File(dir, "session.json"))) {
                out.write(oldBoot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            settings = new FakeSettings();
            settings.values.put(RecoveryStore.LEFT, "0"); settings.values.put(RecoveryStore.RIGHT, "0");
            recovery = new LocalRecovery(store, settings);
            check(recovery.restore() == LocalRecovery.Result.RESTORED, "new boot rebinds lock and restores");
            check(settings.get(RecoveryStore.LEFT) == null && "0.7".equals(settings.get(RecoveryStore.RIGHT)), "new boot retains originals");
            return checks;
    }
}
