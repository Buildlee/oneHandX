package io.buildlee.onehand.takeover;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

/** Main-process coordinator. The touch service remains the owner of the heartbeat. */
public final class TakeoverManager {
    public interface Host {
        boolean prepareTakeoverWindows();
        boolean takeoverWindowsHealthy();
        boolean sceneTemporarilyBlocked();
        boolean canResumeTakeover();
        void finishTakeoverWindows();
    }
    public interface Listener { void onStateChanged(); }
    public enum Status { IDLE, STARTING, TRIAL, ACTIVE, PAUSED, RESTORING, RECOVERY_REQUIRED }

    private static TakeoverManager instance;
    public static synchronized TakeoverManager get(Context context) {
        if (instance == null) instance = new TakeoverManager(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ShizukuBridge bridge;
    private volatile Status status = Status.IDLE;
    private volatile boolean leftDone, rightDone;
    private Host host;
    private Listener listener;
    private TakeoverTransaction transaction;
    private IRecoveryGuard guard;
    private int generation;
    private boolean heartbeatPending;
    private boolean connectionBound;
    private boolean resumeWanted, reuseCalibration;
    private volatile String lastError = "";

    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(() -> {
        if (connectionBound && (status == Status.STARTING || status == Status.TRIAL || status == Status.ACTIVE)) stop();
    });
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (status != Status.STARTING) return;
            guard = IRecoveryGuard.Stub.asInterface(binder);
            final int current = generation;
            io.execute(() -> {
                boolean allowed;
                try {
                    allowed = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                            == PackageManager.PERMISSION_GRANTED || guard.grantRecoveryPermission();
                } catch (Exception e) { allowed = false; }
                boolean granted = allowed && context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                        == PackageManager.PERMISSION_GRANTED;
                main.post(() -> {
                    if (generation != current || status != Status.STARTING) return;
                    if (!granted || host == null || !host.prepareTakeoverWindows()) { lastError = granted ? "手势窗口未准备好" : "无法获得本地恢复权限"; abortBeforeApply(); return; }
                    transaction = new TakeoverTransaction(context, guard);
                    TakeoverTransaction tx = transaction;
                    TakeoverTransaction.Preconditions checks = new TakeoverTransaction.Preconditions() {
                        public boolean gestureNavigationSupported() { return navigationVerified; }
                        public boolean recoveryEntryVerified() { return recoveryVerified; }
                        public boolean inputWindowsReady() { return host != null && host.takeoverWindowsHealthy(); }
                        public boolean accessibilityActionReady() { return actionVerified; }
                    };
                    io.execute(() -> {
                        boolean started = tx.start(checks);
                        if (started && !reuseCalibration) {
                            // Prove local recovery before offering the user a real trial.
                            boolean rehearsed = tx.rehearseLocalRecovery();
                            started = rehearsed && tx.start(checks);
                        }
                        boolean resumed = false;
                        if (started && reuseCalibration) {
                            tx.recordCalibratedSide(true); tx.recordCalibratedSide(false);
                            resumed = tx.activate();
                            if (!resumed) { tx.stop(); started = false; }
                        }
                        final boolean startedResult = started;
                        final boolean resumedResult = resumed;
                        main.post(() -> {
                            if (generation != current) { io.execute(tx::stop); return; }
                            if (!startedResult) {
                                if (tx.state() == TakeoverTransaction.State.RECOVERY_REQUIRED) {
                                    status = Status.RECOVERY_REQUIRED; notifyListener();
                                } else { lastError = "系统设置、恢复记录或守护准备失败"; abortBeforeApply(); }
                                return;
                            }
                            if (resumedResult) { leftDone = rightDone = true; status = Status.ACTIVE; }
                            else status = Status.TRIAL;
                            reuseCalibration = false;
                            main.postDelayed(heartbeat, 1000);
                            notifyListener();
                        });
                    });
                });
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (connectionBound && (status == Status.STARTING || status == Status.TRIAL || status == Status.ACTIVE)) stop();
        }
    };
    private boolean navigationVerified, recoveryVerified, actionVerified;
    private Boolean pendingCalibrationSide;

    private TakeoverManager(Context context) { this.context = context; this.bridge = new ShizukuBridge(context); }
    public Status status() { return status; }
    public String lastError() { return lastError; }
    public boolean leftCalibrated() { return leftDone; }
    public boolean rightCalibrated() { return rightDone; }
    public boolean isPausedForScene() { return status == Status.PAUSED && resumeWanted; }
    public boolean isPausingScene() { return resumeWanted && (status == Status.PAUSED || status == Status.RESTORING); }
    public boolean reusingCalibration() { return reuseCalibration; }
    public void setListener(Listener listener) { this.listener = listener; }
    private void notifyListener() {
        Log.i("OneHandTakeover", "status=" + status + " left=" + leftDone + " right=" + rightDone + " error=" + lastError);
        if (listener != null) listener.onStateChanged();
    }

    /** Called on main thread after user consent, real recovery-entry and back-action checks. */
    public void begin(Host host, boolean navigation, boolean recovery, boolean action) {
        if (status != Status.IDLE || host == null || !navigation || !recovery || !action || !bridge.available()) return;
        this.host = host; navigationVerified = navigation; recoveryVerified = recovery; actionVerified = action; lastError = "";
        leftDone = rightDone = false;
        generation++; status = Status.STARTING; notifyListener();
        try {
            Shizuku.addBinderDeadListener(binderDead);
            connectionBound = true;
            bridge.bind(connection);
        } catch (RuntimeException e) { lastError = "无法连接 Shizuku 守护"; abortBeforeApply(); }
    }

    /** A trial side is recorded only after the calibration Activity actually receives Back. */
    public void recordCalibration(boolean left) {
        if (status != Status.TRIAL || transaction == null) return;
        if (left) leftDone = true; else rightDone = true;
        TakeoverTransaction current = transaction;
        io.execute(() -> current.recordCalibratedSide(left));
        notifyListener();
    }

    public void expectCalibrationBack(boolean left) { pendingCalibrationSide = status == Status.TRIAL ? left : null; }
    public void cancelCalibrationBack() { pendingCalibrationSide = null; }
    public boolean consumeCalibrationBack() {
        Boolean side = pendingCalibrationSide;
        pendingCalibrationSide = null;
        if (side == null || status != Status.TRIAL) return false;
        recordCalibration(side);
        return true;
    }

    public void confirm() {
        if (status != Status.TRIAL || !leftDone || !rightDone || transaction == null) return;
        final int current = generation;
        TakeoverTransaction tx = transaction;
        io.execute(() -> {
            boolean ok = tx.activate();
            main.post(() -> {
                if (generation != current || status != Status.TRIAL) return;
                if (ok) { status = Status.ACTIVE; notifyListener(); } else stop();
            });
        });
    }

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (status != Status.TRIAL && status != Status.ACTIVE) return;
            boolean healthy = host != null && host.takeoverWindowsHealthy();
            if (!healthy) {
                if (host != null && host.sceneTemporarilyBlocked()) pauseForScene(); else stop();
                return;
            }
            if (!heartbeatPending && transaction != null) {
                heartbeatPending = true;
                TakeoverTransaction tx = transaction;
                final int current = generation;
                io.execute(() -> {
                    boolean renewed = tx.renew(true);
                    main.post(() -> {
                        heartbeatPending = false;
                        if (generation == current && !renewed) stop();
                    });
                });
            }
            main.postDelayed(this, 1000);
        }
    };

    public void stop() {
        resumeWanted = false; reuseCalibration = false;
        main.removeCallbacks(resumeWhenReady);
        stopInternal();
    }

    public void pauseForScene() {
        if (resumeWanted && (status == Status.RESTORING || status == Status.PAUSED)) return;
        if (status != Status.ACTIVE) { stop(); return; }
        resumeWanted = true;
        stopInternal();
    }

    private void stopInternal() {
        if (status == Status.IDLE || status == Status.RESTORING) return;
        generation++; main.removeCallbacks(heartbeat); heartbeatPending = false;
        pendingCalibrationSide = null;
        status = Status.RESTORING; notifyListener();
        TakeoverTransaction tx = transaction;
        io.execute(() -> {
            boolean restored = tx == null || tx.stop();
            main.post(() -> {
                if (restored) {
                    if (host != null) host.finishTakeoverWindows();
                    releaseConnection();
                    transaction = null;
                    if (resumeWanted && host != null) { status = Status.PAUSED; sceneMayHaveChanged(); }
                    else { host = null; status = Status.IDLE; }
                } else status = Status.RECOVERY_REQUIRED;
                notifyListener();
            });
        });
    }

    public void sceneMayHaveChanged() {
        if (!isPausedForScene()) return;
        main.removeCallbacks(resumeWhenReady);
        main.postDelayed(resumeWhenReady, 500);
    }

    private final Runnable resumeWhenReady = new Runnable() {
        @Override public void run() {
            if (!isPausedForScene()) return;
            if (host == null || !host.canResumeTakeover() || !bridge.available()) {
                main.postDelayed(this, 1000);
                return;
            }
            Host currentHost = host;
            reuseCalibration = true;
            resumeWanted = false;
            status = Status.IDLE;
            begin(currentHost, navigationVerified, recoveryVerified, actionVerified);
        }
    };

    private void abortBeforeApply() {
        main.removeCallbacks(heartbeat);
        main.removeCallbacks(resumeWhenReady);
        resumeWanted = reuseCalibration = false;
        if (host != null) host.finishTakeoverWindows();
        releaseConnection();
        host = null; transaction = null; status = Status.IDLE; notifyListener();
    }

    private void releaseConnection() {
        Shizuku.removeBinderDeadListener(binderDead);
        if (connectionBound) {
            connectionBound = false;
            try { bridge.remove(connection); } catch (RuntimeException ignored) { }
        }
        guard = null;
    }
}
