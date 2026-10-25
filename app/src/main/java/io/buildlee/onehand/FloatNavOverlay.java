package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 浮动导航按钮（返回 / 主屏 / 最近），可拖动，对齐 One Hand Operation+ 的「浮动导航按钮」 */
public class FloatNavOverlay {

    private static final String TAG = "OneHand";

    private final AccessibilityService svc;
    private final WindowManager wm;
    private View root;
    private WindowManager.LayoutParams lp;
    private boolean visible;

    public FloatNavOverlay(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
    }

    public boolean isVisible() { return visible; }

    public void toggle() {
        if (visible) hide();
        else show();
    }

    public void show() {
        if (visible) return;
        try {
            LinearLayout bar = new LinearLayout(svc);
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(6), dp(6), dp(6), dp(6));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(26));
            bg.setColor(0xE61C1C1E);
            bg.setStroke(dp(1), 0x33FFFFFF);
            bar.setBackground(bg);

            TextView grip = makeBtn("⠿", null);
            grip.setOnTouchListener(new View.OnTouchListener() {
                float lx, ly;
                @Override
                public boolean onTouch(View v, MotionEvent e) {
                    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        lx = e.getRawX();
                        ly = e.getRawY();
                        return true;
                    }
                    if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                        lp.x += (int) (e.getRawX() - lx);
                        lp.y += (int) (e.getRawY() - ly);
                        lx = e.getRawX();
                        ly = e.getRawY();
                        try { wm.updateViewLayout(root, lp); } catch (Exception ignored) {}
                        return true;
                    }
                    return true;
                }
            });
            bar.addView(grip);
            bar.addView(makeBtn("◁", () -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)));
            bar.addView(makeBtn("○", () -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)));
            bar.addView(makeBtn("▢", () -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)));
            bar.addView(makeBtn("×", this::hide));

            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            p.x = dp(14);
            p.y = svc.getResources().getDisplayMetrics().heightPixels - dp(150);
            lp = p;
            root = bar;
            wm.addView(bar, p);
            Motion.popIn(bar, p.x < svc.getResources().getDisplayMetrics().widthPixels / 2);
            visible = true;
            Log.i(TAG, "float nav shown");
        } catch (Exception e) {
            Log.w(TAG, "float nav show failed", e);
            hide();
        }
    }

    public void hide() {
        visible = false;
        final View rv = root;
        root = null;
        lp = null;
        if (rv != null) {
            Motion.fadeOut(rv, () -> {
                try { wm.removeViewImmediate(rv); } catch (Exception ignored) {}
            });
        }
        Log.i(TAG, "float nav hidden");
    }

    private TextView makeBtn(String text, Runnable action) {
        TextView t = new TextView(svc);
        t.setText(text);
        t.setTextSize(16);
        t.setTextColor(0xE6FFFFFF);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(36), dp(40));
        lp2.setMargins(dp(2), 0, dp(2), 0);
        t.setLayoutParams(lp2);
        if (action != null) t.setOnClickListener(v -> action.run());
        return t;
    }

    private int dp(float v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }
}
