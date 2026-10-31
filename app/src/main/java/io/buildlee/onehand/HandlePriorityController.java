package io.buildlee.onehand;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Only the input service main thread can renew the independent recovery lease. */
final class HandlePriorityController {
    private final Context context;
    private final Runnable changed;
    private final BooleanSupplier healthy;
    private final BooleanSupplier sceneAllowed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IBinder client = new Binder();
    private IHandlePriorityGuard guard;
    private String session;
    private long sequence;
    private boolean bound;
    private volatile boolean ready;

    HandlePriorityController(Context context, BooleanSupplier healthy, BooleanSupplier sceneAllowed, Runnable changed) {
        this.context = context; this.healthy = healthy; this.sceneAllowed = sceneAllowed; this.changed = changed;
    }
    boolean isReady() { return ready; }
    private final Runnable timeout = this::fail;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!bound) return;
            try {
                guard = IHandlePriorityGuard.Stub.asInterface(binder);
                session = UUID.randomUUID().toString(); sequence = 0;
                guard.arm(client, session); ready = true;
                main.removeCallbacks(timeout);
                changed.run(); main.postDelayed(pulse, 1000);
            } catch (Exception e) { fail(); }
        }
        @Override public void onServiceDisconnected(ComponentName name) { if (bound) fail(); }
        @Override public void onBindingDied(ComponentName name) { if (bound) fail(); }
        @Override public void onNullBinding(ComponentName name) { if (bound) fail(); }
    };
    private final Runnable pulse = new Runnable() {
        @Override public void run() {
            if (!ready) return;
            if (!sceneAllowed.getAsBoolean()) { stop(); changed.run(); return; }
            try {
                if (!healthy.getAsBoolean() || !guard.renew(session, ++sequence)) { fail(); return; }
            } catch (Exception e) { fail(); return; }
            main.postDelayed(this, 1000);
        }
    };
    void start() {
        if (bound) return;
        try {
            bound = context.bindService(new Intent(context, HandlePriorityGuardService.class), connection, Context.BIND_AUTO_CREATE);
            if (!bound) { fail(); return; }
            main.postDelayed(timeout, 5000);
        } catch (RuntimeException e) { fail(); }
    }
    void stop() {
        ready = false; main.removeCallbacks(timeout); main.removeCallbacks(pulse);
        if (guard != null && session != null) try { guard.disarm(session); } catch (Exception ignored) { }
        if (bound) { bound = false; try { context.unbindService(connection); } catch (RuntimeException ignored) { } }
        guard = null; session = null;
    }
    private void fail() {
        stop();
        new Prefs(context).setBool(Prefs.HANDLE_PRIORITY, false);
        changed.run();
    }
}
