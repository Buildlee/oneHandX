package io.buildlee.onehand;

import android.app.Instrumentation;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

/** Emulator-only raw-window experiment; it never changes system settings itself. */
final class ExpandedEdgeProbe {
    static void run(Instrumentation test) throws Exception {
        if (!(android.os.Build.HARDWARE.contains("ranchu")
                || android.os.Build.HARDWARE.contains("goldfish")))
            throw new IllegalStateException("Dedicated emulator only");
        long until = SystemClock.elapsedRealtime() + 15000;
        while (EdgeService.connectedService() == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100);
        EdgeService service = EdgeService.connectedService();
        if (service == null) throw new IllegalStateException("Service not connected");
        WindowManager wm = service.getSystemService(WindowManager.class);
        ArrayList<View> views = new ArrayList<>();
        File stop = new File(test.getTargetContext().getCacheDir(), "expanded-probe-stop");
        File stall = new File(test.getTargetContext().getCacheDir(), "expanded-probe-stall");
        try {
            test.runOnMainSync(() -> {
                int height = service.getResources().getDisplayMetrics().heightPixels;
                int width = Math.round(24 * service.getResources().getDisplayMetrics().density);
                for (boolean left : new boolean[]{true, false}) {
                    View view = new View(service);
                    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height - 180,
                            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                            PixelFormat.TRANSLUCENT);
                    lp.gravity = Gravity.TOP | (left ? Gravity.LEFT : Gravity.RIGHT); lp.y = 90;
                    view.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) ->
                            v.setSystemGestureExclusionRects(Collections.singletonList(new Rect(0,0,r-l,b-t))));
                    view.setOnTouchListener(new View.OnTouchListener() {
                        float x, y;
                        public boolean onTouch(View v, MotionEvent event) {
                            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { x=event.getRawX(); y=event.getRawY(); }
                            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                                float dx = Math.abs(event.getRawX()-x), dy = event.getRawY()-y;
                                if (dx > 60) {
                                    String direction = Math.abs(dy) < 80 ? "H" : dy < 0 ? "U" : "D";
                                    boolean ok = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                                    Log.i("OneHandExpandedProbe", "input="+(left?"L":"R")+direction+" y="+Math.round(y)+" back="+ok);
                                }
                            }
                            return true;
                        }
                    });
                    wm.addView(view, lp); views.add(view);
                }
            });
            Log.i("OneHandExpandedProbe", "windows ready");
            until = SystemClock.elapsedRealtime() + 240000;
            while (!stop.exists() && SystemClock.elapsedRealtime() < until) {
                if (stall.exists()) {
                    if (!service.isHandlePriorityGuardReady()) throw new IllegalStateException("Guard not armed before fault");
                    stall.delete();
                    test.runOnMainSync(() -> { Log.i("OneHandExpandedProbe", "main blocked"); SystemClock.sleep(30000); });
                    throw new IllegalStateException("Guard failed to stop blocked process");
                }
                Thread.sleep(100);
            }
        } finally {
            test.runOnMainSync(() -> { for (View view : views) if (view.isAttachedToWindow()) wm.removeViewImmediate(view); });
            stop.delete(); stall.delete();
        }
    }
}
