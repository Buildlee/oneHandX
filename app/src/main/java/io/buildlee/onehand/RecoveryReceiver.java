package io.buildlee.onehand;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import io.buildlee.onehand.takeover.LocalRecovery;

/** Best-effort recovery only; broadcasts are not guaranteed after a force-stop. */
public final class RecoveryReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        PendingResult pending = goAsync();
        Context application = context.getApplicationContext();
        new Thread(() -> {
            try {
                LocalRecovery.Result result = new LocalRecovery(application).restore();
                Log.i("OneHandRecovery", "residual recovery: " + result);
            } catch (RuntimeException e) {
                Log.w("OneHandRecovery", "residual recovery unavailable", e);
            } finally {
                pending.finish();
            }
        }, "gesture-recovery").start();
    }
}
