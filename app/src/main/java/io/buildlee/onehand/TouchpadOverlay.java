package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * 虚拟触控板（对齐 One Hand Operation+ 的「虚拟触控板」）：
 * 浮动触控板拖动光标，点按=点击、长按=长按，通过 dispatchGesture 注入到光标位置。
 * 需要无障碍服务声明 canPerformGestures="true"。
 */
public class TouchpadOverlay {

    private static final String TAG = "OneHand";
    private static final int PAD_DP = 190;
    private static final int HANDLE_DP = 30;
    private static final int CURSOR_DP = 26;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Runnable onClose;
    private View padView;
    private View cursorView;
    private WindowManager.LayoutParams padLp;
    private WindowManager.LayoutParams cursorLp;
    private final int screenW;
    private final int screenH;
    private float cursorX;
    private float cursorY;
    private boolean visible;

    public TouchpadOverlay(AccessibilityService svc, Runnable onClose) {
        this.svc = svc;
        this.onClose = onClose;
        this.wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
        this.screenW = svc.getResources().getDisplayMetrics().widthPixels;
        this.screenH = svc.getResources().getDisplayMetrics().heightPixels;
        this.cursorX = screenW / 2f;
        this.cursorY = screenH / 2f;
    }

    public boolean isVisible() { return visible; }

    public void toggle() {
        if (visible) hide();
        else show();
    }

    public void show() {
        if (visible) return;
        try {
            createCursor();
            createPad();
            visible = true;
            Log.i(TAG, "touchpad shown");
        } catch (Exception e) {
            Log.w(TAG, "touchpad show failed", e);
            hide();
        }
    }

    public void hide() {
        visible = false;
        final View pv = padView;
        final View cv = cursorView;
        padView = null;
        cursorView = null;
        padLp = null;
        cursorLp = null;
        try { if (cv != null) wm.removeView(cv); } catch (Exception ignored) {}
        if (pv != null) {
            Motion.fadeOut(pv, () -> {
                try { wm.removeViewImmediate(pv); } catch (Exception ignored) {}
            });
        }
        Log.i(TAG, "touchpad hidden");
    }

    private int dp(float v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }

    private void createCursor() {
        View v = new View(svc);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x55FFFFFF);
        g.setStroke(dp(2), 0xCCFFFFFF);
        v.setBackground(g);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(CURSOR_DP), dp(CURSOR_DP),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = (int) cursorX - dp(CURSOR_DP / 2f);
        lp.y = (int) cursorY - dp(CURSOR_DP / 2f);
        cursorLp = lp;
        cursorView = v;
        wm.addView(v, lp);
        v.setAlpha(0f);
        v.animate().alpha(1f).setDuration(220).start();
    }

    private void createPad() {
        FrameLayout root = new FrameLayout(svc);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(22));
        bg.setColor(0xE61C1C1E);
        bg.setStroke(dp(1), 0x33FFFFFF);
        root.setBackground(bg);

        TextView handle = new TextView(svc);
        handle.setText("触控板");
        handle.setTextSize(11);
        handle.setTextColor(0x99FFFFFF);
        handle.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(HANDLE_DP));
        root.addView(handle, hlp);

        TextView close = new TextView(svc);
        close.setText("×");
        close.setTextSize(16);
        close.setTextColor(0xCCFFFFFF);
        close.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(34), dp(34));
        clp.gravity = Gravity.TOP | Gravity.END;
        root.addView(close, clp);
        close.setOnClickListener(v -> {
            hide();
            if (onClose != null) onClose.run();
        });

        TextView hint = new TextView(svc);
        hint.setText("拖动移动光标\n点按点击 · 长按长按");
        hint.setTextSize(10);
        hint.setTextColor(0x66FFFFFF);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(HANDLE_DP + 26);
        root.addView(hint, tlp);

        root.setOnTouchListener(new View.OnTouchListener() {
            float lastX, lastY, downX, downY;
            long downT;
            boolean movedPad;
            boolean inHandle;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        lastX = downX = e.getRawX();
                        lastY = downY = e.getRawY();
                        downT = System.currentTimeMillis();
                        movedPad = false;
                        inHandle = e.getY() < dp(HANDLE_DP) && e.getX() < padLp.width - dp(34);
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - lastX;
                        float dy = e.getRawY() - lastY;
                        lastX = e.getRawX();
                        lastY = e.getRawY();
                        if (inHandle) {
                            padLp.x += (int) dx;
                            padLp.y += (int) dy;
                            try { wm.updateViewLayout(padView, padLp); } catch (Exception ignored) {}
                        } else {
                            if (Math.abs(e.getRawX() - downX) > dp(4) || Math.abs(e.getRawY() - downY) > dp(4)) {
                                movedPad = true;
                            }
                            cursorX = clamp(cursorX + dx * 1.8f, 0, screenW);
                            cursorY = clamp(cursorY + dy * 1.8f, 0, screenH);
                            cursorLp.x = (int) cursorX - dp(CURSOR_DP / 2f);
                            cursorLp.y = (int) cursorY - dp(CURSOR_DP / 2f);
                            try { wm.updateViewLayout(cursorView, cursorLp); } catch (Exception ignored) {}
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP: {
                        long dt = System.currentTimeMillis() - downT;
                        if (!inHandle && !movedPad) {
                            if (dt >= 600) longPress(cursorX, cursorY);
                            else if (dt < 350) click(cursorX, cursorY);
                        }
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(PAD_DP), dp(PAD_DP + 40),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = screenW - dp(PAD_DP) - dp(12);
        lp.y = screenH - dp(PAD_DP + 40) - dp(140);
        padLp = lp;
        padView = root;
        wm.addView(root, lp);
        Motion.popIn(root, padLp.x < screenW / 2);
    }

    private float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private void click(float x, float y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(x + 1f, y + 1f);
            GestureDescription gd = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 60))
                    .build();
            boolean ok = svc.dispatchGesture(gd, null, null);
            Log.i(TAG, "touchpad click x=" + Math.round(x) + " y=" + Math.round(y) + " ok=" + ok);
        } catch (Exception e) {
            Log.w(TAG, "touchpad click failed", e);
        }
    }

    private void longPress(float x, float y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            p.lineTo(x + 1f, y + 1f);
            GestureDescription gd = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(p, 0, 650))
                    .build();
            boolean ok = svc.dispatchGesture(gd, null, null);
            Log.i(TAG, "touchpad longpress x=" + Math.round(x) + " y=" + Math.round(y) + " ok=" + ok);
        } catch (Exception e) {
            Log.w(TAG, "touchpad longpress failed", e);
        }
    }
}
