package io.buildlee.onehand.takeover;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;

import org.json.JSONObject;

import java.util.Objects;

/** Background-thread transaction coordinator. UI and input routing are intentionally separate. */
public final class TakeoverTransaction {
    public enum State { IDLE, TRIAL, ACTIVE, RECOVERY_REQUIRED }

    /** All checks must be verified by the caller; absence of a check fails closed. */
    public interface Preconditions {
        boolean gestureNavigationSupported();
        boolean recoveryEntryVerified();
        boolean inputWindowsReady();
        boolean accessibilityActionReady();
    }

    private final Context context;
    private final RecoveryStore store;
    private final LocalRecovery local;
    private final IRecoveryGuard guard;
    private final IBinder clientToken = new Binder(); // Must remain strongly referenced until restoration.
    private String sessionId;
    private long sequence;
    private State state = State.IDLE;
    private boolean leftCalibrated, rightCalibrated;

    public TakeoverTransaction(Context context, IRecoveryGuard guard) {
        this.context = context.getApplicationContext();
        this.store = new RecoveryStore(this.context);
        this.local = new LocalRecovery(this.context);
        this.guard = guard;
    }

    public synchronized State state() { return state; }

    /** Execute off the main thread. No caller may omit recovery/overlay checks. */
    public synchronized boolean start(Preconditions checks) {
        if (state != State.IDLE || guard == null || checks == null
                || !checks.gestureNavigationSupported() || !checks.recoveryEntryVerified()
                || !checks.inputWindowsReady() || !checks.accessibilityActionReady()
                || context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        try {
            if (guard.identity() != 2000) return false;
            final String left = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT);
            final String right = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT);
            JSONObject session = store.locked(s -> s.prepareLocked(left, right));
            sessionId = session.getString("sessionId");
            store.locked(s -> {
                JSONObject current = s.readLocked();
                if (!sessionId.equals(current.getString("sessionId")) || current.getBoolean("cancelRequested")
                        || !Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                        || !Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT))) {
                    throw new IllegalStateException("baseline changed");
                }
                current.put("state", "APPLYING");
                s.writeLocked(current);
                return null;
            });
            try (ParcelFileDescriptor fd = store.openLockDescriptor()) {
                if (!guard.prepare(sessionId, left, right, store.lockPath(), session.getLong("lockDevice"),
                        session.getLong("lockInode"), fd, clientToken)) throw new IllegalStateException("guard not ready");
            }
            if (!guard.apply(sessionId)) throw new IllegalStateException("system did not accept takeover");
            store.locked(s -> {
                JSONObject current = s.readLocked();
                if (!sessionId.equals(current.getString("sessionId")) || current.getBoolean("cancelRequested")) {
                    throw new IllegalStateException("session cancelled during apply");
                }
                current.put("state", "TRIAL");
                s.writeLocked(current);
                return null;
            });
            if (!guard.renew(sessionId, ++sequence)) throw new IllegalStateException("guard lease expired");
            state = State.TRIAL;
            return true;
        } catch (Exception failure) {
            restoreInternal();
            return false;
        }
    }

    /** Only call for a real, successfully executed trial gesture from the corresponding side. */
    public synchronized void recordCalibratedSide(boolean left) {
        if (state != State.TRIAL) return;
        if (left) leftCalibrated = true; else rightCalibrated = true;
    }

    public synchronized boolean activate() {
        if (state != State.TRIAL || !leftCalibrated || !rightCalibrated) return false;
        try {
            if (!guard.activate(sessionId)) { restoreInternal(); return false; }
            store.locked(s -> {
                JSONObject current = s.readLocked();
                if (!sessionId.equals(current.getString("sessionId")) || current.getBoolean("cancelRequested")) {
                    throw new IllegalStateException("session cancelled during calibration");
                }
                current.put("state", "ACTIVE"); s.writeLocked(current); return null;
            });
            state = State.ACTIVE;
            return true;
        } catch (Exception e) { restoreInternal(); return false; }
    }

    /** Caller must schedule this only after a main-thread health check of both input windows. */
    public synchronized boolean renew(boolean serviceHealthy) {
        if ((state != State.TRIAL && state != State.ACTIVE) || !serviceHealthy) { restoreInternal(); return false; }
        try {
            if (guard.renew(sessionId, ++sequence)) return true;
        } catch (Exception ignored) { }
        restoreInternal();
        return false;
    }

    public synchronized boolean stop() { return restoreInternal(); }

    /** Verify that the independent app-UID recovery path really restores this device. */
    public synchronized boolean rehearseLocalRecovery() {
        if (state != State.TRIAL || sessionId == null) return false;
        LocalRecovery.Result localResult = local.restore();
        boolean guardReleased = false;
        try { guardReleased = guard.restore(sessionId); } catch (Exception ignored) { }
        if (localResult == LocalRecovery.Result.RESTORED && guardReleased
                && local.restore() == LocalRecovery.Result.NO_RECORD) {
            sessionId = null; sequence = 0; state = State.IDLE;
            return true;
        }
        restoreInternal();
        return false;
    }

    private boolean restoreInternal() {
        LocalRecovery.Result first = local.restore();
        if (sessionId != null) {
            try { guard.restore(sessionId); } catch (Exception ignored) { }
        }
        LocalRecovery.Result result = local.restore();
        boolean success = result == LocalRecovery.Result.RESTORED || result == LocalRecovery.Result.NO_RECORD;
        if (success && first != LocalRecovery.Result.CONFLICT) {
            sessionId = null; sequence = 0; leftCalibrated = false; rightCalibrated = false; state = State.IDLE;
            return true;
        }
        state = State.RECOVERY_REQUIRED;
        return false;
    }
}
