package io.buildlee.onehand;

import android.app.Application;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import androidx.appcompat.app.AppCompatDelegate;

/** Apply the saved theme before any Activity creates its views. */
public class OneHandApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        if (isRecoveryProcess()) return;
        int theme = new Prefs(this).getInt(Prefs.THEME, 0);
        AppCompatDelegate.setDefaultNightMode(theme == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : theme == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    private boolean isRecoveryProcess() {
        String name = null;
        if (Build.VERSION.SDK_INT >= 28) name = Application.getProcessName();
        else {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null && manager.getRunningAppProcesses() != null) {
                for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
                    if (process.pid == Process.myPid()) { name = process.processName; break; }
                }
            }
        }
        return (getPackageName() + ":recovery").equals(name);
    }
}
