package io.buildlee.onehand;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bound only by our accessibility service, in the independent recovery process. */
public final class HandlePriorityGuardService extends Service {
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private String session;
    private IBinder client;
    private IBinder.DeathRecipient death;
    private long sequence, deadline;

    private void checkCaller() {
        if (Binder.getCallingUid() != Process.myUid()) throw new SecurityException("Only this app may manage the lease");
    }

    private final IHandlePriorityGuard.Stub binder = new IHandlePriorityGuard.Stub() {
        @Override public void arm(IBinder token, String id) throws RemoteException {
            checkCaller();
            synchronized (HandlePriorityGuardService.this) {
                if (token == null || id == null || id.length() > 64 || session != null || HandlePrioritySafety.isPaused(HandlePriorityGuardService.this)) {
                    throw new IllegalStateException("手柄守护未就绪");
                }
                session = id; client = token; sequence = 0; deadline = SystemClock.elapsedRealtime() + 4000;
                death = () -> fail(id);
                try { token.linkToDeath(death, 0); }
                catch (RemoteException e) { clear(); throw e; }
                Log.i("HandlePriorityGuard", "armed");
            }
        }
        @Override public boolean renew(String id, long next) {
            checkCaller();
            synchronized (HandlePriorityGuardService.this) {
                if (session == null || !Objects.equals(session, id) || next <= sequence
                        || SystemClock.elapsedRealtime() >= deadline) return false;
                sequence = next; deadline = SystemClock.elapsedRealtime() + 4000; return true;
            }
        }
        @Override public void disarm(String id) {
            checkCaller();
            synchronized (HandlePriorityGuardService.this) { if (Objects.equals(session, id)) clear(); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        timer.scheduleAtFixedRate(() -> {
            synchronized (this) {
                if (session != null && SystemClock.elapsedRealtime() >= deadline) fail(session);
            }
        }, 500, 500, TimeUnit.MILLISECONDS);
    }

    private synchronized void fail(String expected) {
        if (session == null || !Objects.equals(session, expected)) return;
        clear();
        try { HandlePrioritySafety.pauseAndStopMain(this); Log.w("HandlePriorityGuard", "lease expired or client died; priority paused"); }
        catch (Exception e) { Log.e("HandlePriorityGuard", "recovery requires user action", e); }
    }

    private void clear() {
        if (client != null && death != null) try { client.unlinkToDeath(death, 0); } catch (RuntimeException ignored) { }
        session = null; client = null; death = null;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public void onDestroy() {
        synchronized (this) { if (session != null) fail(session); }
        timer.shutdownNow(); super.onDestroy();
    }
}
