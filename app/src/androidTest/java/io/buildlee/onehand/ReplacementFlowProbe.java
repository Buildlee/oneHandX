package io.buildlee.onehand;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import java.io.File;
import rikka.shizuku.Shizuku;

/** Host drives real edge input and recovery; no calibration is synthesized here. */
final class ReplacementFlowProbe {
    static void run(Instrumentation test) throws Exception {
        if (!(android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish")))
            throw new IllegalStateException("Dedicated emulator only");
        test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long until=SystemClock.elapsedRealtime()+20000;
        while ((!Shizuku.pingBinder() || EdgeService.connectedService()==null) && SystemClock.elapsedRealtime()<until) Thread.sleep(100);
        if (!Shizuku.pingBinder()) throw new IllegalStateException("Start Shizuku before this probe");
        if (Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) {
            test.runOnMainSync(() -> Shizuku.requestPermission(837));
            Log.i("OneHandReplacementTest","permission requested");
            until=SystemClock.elapsedRealtime()+30000;
            while (Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED && SystemClock.elapsedRealtime()<until) Thread.sleep(100);
            if (Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) throw new IllegalStateException("Authorize Shizuku before proceeding");
        }
        until=SystemClock.elapsedRealtime()+10000;
        while (EdgeService.connectedService()==null && SystemClock.elapsedRealtime()<until) Thread.sleep(100);
        ExclusionSession session=ExclusionSession.get(test.getTargetContext());
        test.runOnMainSync(() -> {
            test.getTargetContext().startActivity(new Intent(test.getTargetContext(),ReplacementActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            session.start();
        });
        File cache=test.getTargetContext().getCacheDir();
        until=SystemClock.elapsedRealtime()+250000;
        boolean readyReported=false;
        while (SystemClock.elapsedRealtime()<until) {
            if (ExclusionSession.active() && !readyReported) { Log.i("OneHandReplacementTest","trial ready budget="+ExclusionSession.budget()); readyReported=true; }
            if (!ExclusionSession.active()) readyReported=false;
            File confirm=new File(cache,"replacement-confirm");
            if (confirm.delete()) {
                if (!session.canConfirm()) throw new AssertionError("Real input has not calibrated both sides: "+session.status());
                test.runOnMainSync(session::confirm);
                long deadline=SystemClock.elapsedRealtime()+5000;
                while (!session.confirmed() && SystemClock.elapsedRealtime()<deadline) Thread.sleep(100);
                if (!session.confirmed()) throw new AssertionError("Confirmation failed: "+session.status());
                Log.i("OneHandReplacementTest","confirmed");
            }
            if (new File(cache,"replacement-restart").delete()) { test.runOnMainSync(session::start); Log.i("OneHandReplacementTest","restart requested"); }
            if (new File(cache,"replacement-zero-left").delete()) {
                test.runOnMainSync(() -> new Prefs(test.getTargetContext()).setPriorityRequestedDp("L1",0));
                Log.i("OneHandReplacementTest","left zero requested");
            }
            if (new File(cache,"replacement-full-left").delete()) {
                test.runOnMainSync(() -> new Prefs(test.getTargetContext()).setPriorityRequestedDp("L1",ExclusionSession.budget()));
                Log.i("OneHandReplacementTest","left full requested");
            }
            if (new File(cache,"replacement-stall").delete()) {
                if (!session.confirmed()) throw new AssertionError("Confirm before fault injection");
                test.runOnMainSync(() -> { Log.i("OneHandReplacementTest","main blocked"); SystemClock.sleep(30000); });
                throw new AssertionError("Blocked input process was not stopped");
            }
            if (new File(cache,"replacement-close").delete()) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Host probe timed out: "+session.status());
    }
}
