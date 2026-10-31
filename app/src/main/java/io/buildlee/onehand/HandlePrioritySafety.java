package io.buildlee.onehand;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Process;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Permission-free recovery of app-owned exclusion windows; no system settings are changed. */
final class HandlePrioritySafety {
    private static File marker(Context context) {
        return new File(context.createDeviceProtectedStorageContext().getNoBackupFilesDir(), "handle-priority-paused");
    }

    static boolean isPaused(Context context) {
        File file = marker(context);
        return file.exists() || new File(file + ".bak").exists() || new File(file + ".new").exists();
    }

    static boolean clearPause(Context context) {
        File file = marker(context);
        for (File candidate : new File[]{file, new File(file + ".bak"), new File(file + ".new")}) {
            if (candidate.exists() && !candidate.delete()) return false;
        }
        return !isPaused(context);
    }

    static void pauseAndStopMain(Context context) throws IOException {
        IOException pauseFailure = null;
        try {
            AtomicFile file = new AtomicFile(marker(context));
            FileOutputStream output = file.startWrite();
            try { output.write(1); file.finishWrite(output); }
            catch (IOException | RuntimeException e) { file.failWrite(output); throw e; }
            if (!isPaused(context)) throw new IOException("无法保存安全暂停状态");
        } catch (IOException | RuntimeException e) { pauseFailure = new IOException("无法保存安全暂停状态", e); }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null || manager.getRunningAppProcesses() == null) throw new IOException("无法检查手势进程");
        for (ActivityManager.RunningAppProcessInfo process : manager.getRunningAppProcesses()) {
            if (process.uid == Process.myUid() && process.pid != Process.myPid()
                    && context.getPackageName().equals(process.processName)) {
                Process.killProcess(process.pid);
            }
        }
        if (pauseFailure != null) throw pauseFailure;
    }
}
