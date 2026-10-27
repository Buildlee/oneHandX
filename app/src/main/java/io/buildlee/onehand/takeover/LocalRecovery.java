package io.buildlee.onehand.takeover;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Objects;

/** Idempotent local recovery. It never invents a default or overwrites another value. */
public final class LocalRecovery {
    public interface SettingsAccess {
        String get(String key) throws Exception;
        void put(String key, String value) throws Exception;
    }

    public enum Result { NO_RECORD, RESTORED, CONFLICT, NEEDS_PERMISSION, FAILED }
    public enum Status { NO_RECORD, PENDING, FAILED }

    private final RecoveryStore store;
    private final SettingsAccess settings;

    public LocalRecovery(Context context) {
        this(new RecoveryStore(context), new SettingsAccess() {
            @Override public String get(String key) { return Settings.Secure.getString(context.getContentResolver(), key); }
            @Override public void put(String key, String value) throws Exception {
                if (!Settings.Secure.putString(context.getContentResolver(), key, value)) {
                    throw new IllegalStateException("系统拒绝写入");
                }
            }
        });
    }

    public LocalRecovery(RecoveryStore store, SettingsAccess settings) {
        this.store = store;
        this.settings = settings;
    }

    public Status inspect() {
        try {
            return store.locked(locked -> {
                JSONObject session = locked.readLocked();
                return session == null || "RESTORED".equals(session.getString("state"))
                        ? Status.NO_RECORD : Status.PENDING;
            });
        } catch (Exception e) {
            return Status.FAILED;
        }
    }

    public Result restore() {
        try {
            return store.locked(locked -> {
                JSONObject session = locked.readLocked();
                if (session == null || "RESTORED".equals(session.getString("state"))) return Result.NO_RECORD;
                // Persist cancellation before checking either key: stale sessions must not reapply.
                locked.cancelControlLocked(session);
                session.put("cancelRequested", true);
                session.put("state", "RESTORING");
                locked.writeLocked(session);
                String[] keys = {RecoveryStore.LEFT, RecoveryStore.RIGHT};
                boolean conflict = false;
                try {
                    for (String key : keys) {
                        locked.verifyLockLocked(session);
                        String original = RecoveryStore.original(session, key);
                        String current = settings.get(key);
                        if (!Objects.equals(current, original) && !"0".equals(current)) {
                            conflict = true;
                            continue;
                        }
                        if (!Objects.equals(current, original)) settings.put(key, original);
                        if (!Objects.equals(settings.get(key), original)) throw new IllegalStateException("恢复后读回不一致");
                    }
                } catch (SecurityException e) {
                    session.put("state", "RECOVERY_REQUIRED");
                    locked.writeLocked(session);
                    return Result.NEEDS_PERMISSION;
                } catch (Exception e) {
                    session.put("state", "RECOVERY_REQUIRED");
                    locked.writeLocked(session);
                    return Result.FAILED;
                }
                if (conflict) {
                    session.put("state", "RECOVERY_REQUIRED");
                    locked.writeLocked(session);
                    return Result.CONFLICT;
                }
                session.put("state", "RESTORED");
                locked.writeLocked(session);
                return Result.RESTORED;
            });
        } catch (SecurityException e) {
            return Result.NEEDS_PERMISSION;
        } catch (Exception e) {
            return Result.FAILED;
        }
    }
}
