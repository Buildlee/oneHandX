package io.buildlee.onehand.takeover;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

/** Optional bridge, not used unless the user starts the future takeover setup. */
public final class ShizukuBridge {
    private final Shizuku.UserServiceArgs args;

    public ShizukuBridge(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, TakeoverUserService.class))
                .daemon(true).processNameSuffix("recovery_guard").tag("onehand-recovery-guard").version(1);
    }

    public boolean available() {
        try {
            return Shizuku.pingBinder() && !Shizuku.isPreV11() && Shizuku.getUid() == 2000
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void bind(ServiceConnection connection) {
        if (!available()) throw new IllegalStateException("Shizuku 授权或 shell 服务不可用");
        Shizuku.bindUserService(args, connection);
    }

    public void remove(ServiceConnection connection) {
        if (Shizuku.pingBinder()) Shizuku.unbindUserService(args, connection, true);
    }
}
