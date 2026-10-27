package io.buildlee.onehand;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Binder;
import android.provider.Settings;

import io.buildlee.onehand.takeover.IRecoveryGuard;
import io.buildlee.onehand.takeover.ShizukuBridge;
import io.buildlee.onehand.takeover.RecoveryStore;
import io.buildlee.onehand.takeover.LocalRecovery;
import io.buildlee.onehand.takeover.TakeoverTransaction;
import org.json.JSONObject;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import rikka.shizuku.Shizuku;

/** Isolated emulator integration checks; the user service only tests lock/identity. */
public final class ShizukuChecks {
    private final Instrumentation instrumentation;
    private final boolean hold;
    private final boolean transactions;
    private final boolean stress;
    private IBinder clientToken;
    private int checks;
    public ShizukuChecks(Instrumentation instrumentation, boolean hold, boolean transactions, boolean stress) {
        this.instrumentation = instrumentation; this.hold = hold; this.transactions = transactions; this.stress = stress;
    }
    private void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
        checks++;
    }
    public int run() throws Exception {
        if (stress && !transactions) throw new IllegalArgumentException("stress requires emulator-only transaction checks");
        boolean permissionInitiallyGranted = instrumentation.getTargetContext().checkSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
        Intent intent = new Intent(instrumentation.getTargetContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        long end = System.currentTimeMillis() + 10000;
        while (!Shizuku.pingBinder() && System.currentTimeMillis() < end) Thread.sleep(100);
        check(Shizuku.pingBinder(), "Shizuku binder available");
        check(Shizuku.getUid() == 2000, "non-root shell mode");
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            CountDownLatch permission = new CountDownLatch(1);
            Shizuku.OnRequestPermissionResultListener listener = (request, result) -> permission.countDown();
            Shizuku.addRequestPermissionResultListener(listener);
            instrumentation.runOnMainSync(() -> Shizuku.requestPermission(77));
            permission.await(30, TimeUnit.SECONDS);
            Shizuku.removeRequestPermissionResultListener(listener);
        }
        check(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED, "Shizuku permission granted");
        ShizukuBridge bridge = new ShizukuBridge(instrumentation.getTargetContext());
        check(bridge.available(), "bridge availability");
        AtomicReference<IRecoveryGuard> service = new AtomicReference<>();
        CountDownLatch connected = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                service.set(IRecoveryGuard.Stub.asInterface(binder)); connected.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        instrumentation.runOnMainSync(() -> bridge.bind(connection));
        try {
            check(connected.await(15, TimeUnit.SECONDS) && service.get() != null, "UserService connected");
            check(service.get().identity() == 2000, "UserService runs as shell UID");
            // A private app-data descriptor could not be locked by the shell process here.
            // Probe an app-scoped external file; only the lock lives here, never the journal.
            File file = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "shizuku-lock-test");
            try (RandomAccessFile local = new RandomAccessFile(file, "rw")) {
                try (FileLock initial = local.getChannel().tryLock()) {
                    check(initial != null, "local process can acquire initially free lock");
                }
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)) {
                    check(service.get().tryLock(descriptor), "worker can acquire initially free lock");
                }
                try (FileLock held = local.getChannel().lock();
                     ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)) {
                    check(!service.get().tryLock(descriptor), "worker cannot acquire held lock");
                }
                Thread.sleep(200);
                try (FileLock recheck = local.getChannel().tryLock()) {
                    check(recheck != null, "local lock was released");
                }
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)) {
                    check(service.get().tryLock(descriptor), "worker can acquire released lock");
                }
            }
            File productionLock = new File(instrumentation.getTargetContext().getExternalFilesDir(null), ".gesture-recovery.lock");
            if (!productionLock.exists()) new RecoveryStore(instrumentation.getTargetContext()).locked(s -> null);
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(productionLock, ParcelFileDescriptor.MODE_READ_WRITE)) {
                RecoveryStore store = new RecoveryStore(instrumentation.getTargetContext());
                boolean blocked = store.locked(s -> !service.get().tryLock(descriptor));
                check(blocked, "UserService respects RecoveryStore lock");
                check(service.get().tryLock(descriptor), "UserService can lock after RecoveryStore releases");
            }
            check(service.get().grantRecoveryPermission(), "Shizuku grants local recovery permission");
            check(instrumentation.getTargetContext().checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED, "app observes recovery permission");
            if (transactions) testTransactions(service.get(), productionLock);
            if (stress) stressTimeoutRecovery(service.get(), productionLock);
            if (hold) {
                if (transactions) prepareDeathScenario(service.get(), productionLock);
                android.util.Log.i("ShizukuChecks", "GUARD_READY_FOR_LIFECYCLE_TEST");
                Thread.sleep(60000);
            }
        } finally {
            instrumentation.runOnMainSync(() -> bridge.remove(connection));
            instrumentation.runOnMainSync(activity::finish);
            if (!permissionInitiallyGranted && new LocalRecovery(instrumentation.getTargetContext()).inspect()
                    == LocalRecovery.Status.NO_RECORD) {
                try (ParcelFileDescriptor result = instrumentation.getUiAutomation().executeShellCommand(
                        "pm revoke io.buildlee.onehand android.permission.WRITE_SECURE_SETTINGS");
                     java.io.InputStream output = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
                    while (output.read() != -1) { }
                }
            }
        }
        return checks;
    }

    private void testTransactions(IRecoveryGuard guard, File lockFile) throws Exception {
        try (ParcelFileDescriptor command = instrumentation.getUiAutomation().executeShellCommand("getprop ro.kernel.qemu");
             java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                     new ParcelFileDescriptor.AutoCloseInputStream(command)))) {
            check("1".equals(reader.readLine()), "transaction tests restricted to emulator");
        }
        android.content.Context context = instrumentation.getTargetContext();
        RecoveryStore store = new RecoveryStore(context);
        String left = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT);
        String right = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT);
        try {
            JSONObject session = store.locked(s -> s.prepareLocked(left, right));
            String id = session.getString("sessionId");
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(lockFile, ParcelFileDescriptor.MODE_READ_WRITE)) {
                clientToken = new Binder();
                check(guard.prepare(id, left, right, lockFile.getAbsolutePath(), session.getLong("lockDevice"), session.getLong("lockInode"), descriptor, clientToken), "guard prepared");
            }
            check(guard.apply(id), "guard applies both settings");
            check("0".equals(Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                    && "0".equals(Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT)), "both sides are zero");
            check(guard.renew(id, 1), "healthy heartbeat accepted");
            check(!guard.renew(id, 1), "duplicate heartbeat rejected");
            check(new LocalRecovery(context).restore() == LocalRecovery.Result.RESTORED, "local restore while guard alive");
            check(!guard.apply(id), "old apply cannot run after recovery");
            check(guard.restore(id), "guard clears already restored session");
            check(java.util.Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                    && java.util.Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT)), "originals restored");

            session = store.locked(s -> s.prepareLocked(left, right));
            id = session.getString("sessionId");
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(lockFile, ParcelFileDescriptor.MODE_READ_WRITE)) {
                clientToken = new Binder();
                check(guard.prepare(id, left, right, lockFile.getAbsolutePath(), session.getLong("lockDevice"), session.getLong("lockInode"), descriptor, clientToken), "watchdog session prepared");
            }
            check(guard.apply(id), "watchdog session applied");
            long deadline = android.os.SystemClock.elapsedRealtime() + 11000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (java.util.Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                        && java.util.Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT))) break;
                Thread.sleep(250);
            }
            check(java.util.Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                    && java.util.Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT)), "watchdog restores after missed heartbeat");
            check(new LocalRecovery(context).restore() == LocalRecovery.Result.RESTORED, "journal reconciles after watchdog");

            session = store.locked(s -> s.prepareLocked(left, right));
            id = session.getString("sessionId");
            clientToken = new Binder();
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(lockFile, ParcelFileDescriptor.MODE_READ_WRITE)) {
                check(!guard.prepare(id, left, right, lockFile.getAbsolutePath(), session.getLong("lockDevice"),
                        session.getLong("lockInode") + 1, descriptor, clientToken), "wrong lock inode rejected");
            }
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(lockFile, ParcelFileDescriptor.MODE_READ_WRITE)) {
                check(guard.prepare(id, left, right, lockFile.getAbsolutePath(), session.getLong("lockDevice"),
                        session.getLong("lockInode"), descriptor, clientToken), "cancel race session prepared");
            }
            check(new LocalRecovery(context).restore() == LocalRecovery.Result.RESTORED, "cancel before apply returns originals");
            check(!guard.apply(id) && !guard.renew(id, 1), "cancel fence rejects delayed apply and heartbeat");
            check(guard.restore(id), "cancelled guard releases session");

            TakeoverTransaction coordinator = new TakeoverTransaction(context, guard);
            TakeoverTransaction.Preconditions ready = new TakeoverTransaction.Preconditions() {
                public boolean gestureNavigationSupported() { return true; }
                public boolean recoveryEntryVerified() { return true; }
                public boolean inputWindowsReady() { return true; }
                public boolean accessibilityActionReady() { return true; }
            };
            check(coordinator.start(ready), "coordinator starts trial");
            check(coordinator.state() == TakeoverTransaction.State.TRIAL, "trial state reported");
            check(coordinator.rehearseLocalRecovery(), "app UID independently rehearses recovery");
            check(java.util.Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                    && java.util.Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT)), "rehearsal restores original values");
            check(coordinator.start(ready), "new session starts after recovery rehearsal");
            check(!coordinator.activate(), "activation requires both calibrated sides");
            coordinator.recordCalibratedSide(true);
            check(!coordinator.activate(), "one calibrated side is insufficient");
            coordinator.recordCalibratedSide(false);
            check(coordinator.activate(), "two calibrated sides permit activation");
            check(coordinator.renew(true), "healthy service renews");
            check(!coordinator.renew(false), "unhealthy service stops takeover");
            check(coordinator.state() == TakeoverTransaction.State.IDLE, "coordinator returns to idle");
            check(java.util.Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                    && java.util.Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT)), "coordinator restores original values");
        } finally {
            new LocalRecovery(context).restore();
        }
    }

    private void prepareDeathScenario(IRecoveryGuard guard, File lockFile) throws Exception {
        android.content.Context context = instrumentation.getTargetContext();
        String left = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT);
        String right = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT);
        JSONObject session = new RecoveryStore(context).locked(s -> s.prepareLocked(left, right));
        String id = session.getString("sessionId");
        clientToken = new Binder();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(lockFile, ParcelFileDescriptor.MODE_READ_WRITE)) {
            check(guard.prepare(id, left, right, lockFile.getAbsolutePath(), session.getLong("lockDevice"), session.getLong("lockInode"), descriptor, clientToken), "death scenario prepared");
        }
        check(guard.apply(id), "death scenario applied");
        check(guard.renew(id, 1), "death scenario heartbeat");
    }

    private void stressTimeoutRecovery(IRecoveryGuard guard, File lockFile) throws Exception {
        android.content.Context context = instrumentation.getTargetContext();
        RecoveryStore store = new RecoveryStore(context);
        String left = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT);
        String right = Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT);
        try {
            for (int i = 1; i <= 30; i++) {
                JSONObject session = store.locked(s -> s.prepareLocked(left, right));
                String id = session.getString("sessionId");
                clientToken = new Binder();
                try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(lockFile, ParcelFileDescriptor.MODE_READ_WRITE)) {
                    check(guard.prepare(id, left, right, lockFile.getAbsolutePath(), session.getLong("lockDevice"),
                            session.getLong("lockInode"), descriptor, clientToken), "stress prepare " + i);
                }
                check(guard.apply(id), "stress apply " + i);
                long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    if (java.util.Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                            && java.util.Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT))) break;
                    Thread.sleep(200);
                }
                check(java.util.Objects.equals(left, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.LEFT))
                        && java.util.Objects.equals(right, Settings.Secure.getString(context.getContentResolver(), RecoveryStore.RIGHT)), "stress timeout restore " + i);
                check(new LocalRecovery(context).restore() == LocalRecovery.Result.RESTORED, "stress journal reconcile " + i);
                if (i % 5 == 0) android.util.Log.i("ShizukuChecks", "timeout recovery cycle " + i + "/30");
            }
        } finally { new LocalRecovery(context).restore(); }
    }
}
