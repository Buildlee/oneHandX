package io.buildlee.onehand;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.Log;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Permission-free recovery of app-owned exclusion windows; no system settings are changed. */
final class HandlePrioritySafety {
    static final String ACTION_PAUSE = "io.buildlee.onehand.PAUSE_INPUT_WINDOWS";
    static final String EXTRA_ACK = "pause_ack";
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

    static IOException persistPause(Context context) {
        try {
            AtomicFile file = new AtomicFile(marker(context));
            FileOutputStream output = file.startWrite();
            try { output.write(1); file.finishWrite(output); }
            catch (IOException | RuntimeException e) { file.failWrite(output); throw e; }
            if (!isPaused(context)) throw new IOException("无法保存安全暂停状态");
            return null;
        } catch (IOException | RuntimeException e) { return new IOException("无法保存安全暂停状态", e); }
    }

    /** Normal manual recovery preserves a healthy accessibility binding. */
    static void pauseForRecovery(Context context) throws IOException {
        IOException failure = persistPause(context);
        CountDownLatch acknowledged = new CountDownLatch(1);
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (code == 1) acknowledged.countDown();
            }
        };
        boolean graceful = false;
        try {
            context.sendBroadcast(new Intent(ACTION_PAUSE).setPackage(context.getPackageName())
                    .putExtra(EXTRA_ACK, receiver));
            graceful = acknowledged.await(1500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        catch (RuntimeException ignored) { }
        if (failure == null && !isPaused(context)) throw new IOException("暂停已被后续操作撤销");
        if (!graceful || failure != null) stopMain(context);
        Log.i("OneHandRecovery", graceful ? "manual pause acknowledged; service kept bound" : "manual pause used emergency stop");
        if (failure != null) throw failure;
    }

    /** Unresponsive input still has an independent emergency exit. */
    static void pauseAndStopMain(Context context) throws IOException {
        IOException pauseFailure = persistPause(context);
        stopMain(context);
        if (pauseFailure != null) throw pauseFailure;
    }

    private static void stopMain(Context context) throws IOException {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        java.util.List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes == null) throw new IOException("无法检查手势进程");
        for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.uid == Process.myUid() && process.pid != Process.myPid()
                    && context.getPackageName().equals(process.processName)) {
                Process.killProcess(process.pid);
            }
        }
    }
}
