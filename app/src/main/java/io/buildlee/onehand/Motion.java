package io.buildlee.onehand;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.provider.Settings;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** v0.5 动效工具：统一缓动、入场错峰、按压反馈、浮层动画、刷新率探测。 */
public class Motion {

    public static final Interpolator EMPHASIS = new PathInterpolator(0.2f, 0f, 0f, 1f);

    /** 尊重系统「移除动画」无障碍设置。 */
    public static boolean animationsEnabled(Context c) {
        if (c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.REDUCE_MOTION, false)) return false;
        try {
            return Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0.001f;
        } catch (Exception e) {
            return true;
        }
    }

    /** 列表入场：轻微上移 + 淡入，按顺序错峰（<=240ms）。 */
    public static void enterStagger(ViewGroup container) {
        if (container == null || !animationsEnabled(container.getContext())) return;
        int n = container.getChildCount();
        float d = container.getResources().getDisplayMetrics().density;
        for (int i = 0; i < n; i++) {
            final View v = container.getChildAt(i);
            v.setAlpha(0f);
            v.setTranslationY(12f * d);
            v.animate().alpha(1f).translationY(0f)
                    .setStartDelay(Math.min(i * 34L, 240L))
                    .setDuration(280).setInterpolator(EMPHASIS).start();
        }
    }

    /** 按压缩放反馈（不消费事件，不影响点击触发）。 */
    public static void pressScale(final View v) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            if (!animationsEnabled(view.getContext())) return false;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start();
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
                    return false;
                default:
                    return false;
            }
        });
    }

    /** 浮层弹入：从侧缘锚点缩放 + 淡入（auto-reverse 的安全做法：pivot 在 post 中设置）。 */
    public static void popIn(final View v, final boolean fromLeft) {
        if (v == null) return;
        if (!animationsEnabled(v.getContext())) {
            v.setAlpha(1f);
            return;
        }
        v.setAlpha(0f);
        v.setScaleX(0.92f);
        v.setScaleY(0.92f);
        v.post(() -> {
            v.setPivotX(fromLeft ? 0f : v.getWidth());
            v.setPivotY(v.getHeight() / 2f);
        });
        v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(EMPHASIS).start();
    }

    /** 浮层淡出后回调（用于移除窗口）。 */
    public static void fadeOut(final View v, final Runnable onDone) {
        if (v == null) {
            if (onDone != null) onDone.run();
            return;
        }
        if (!animationsEnabled(v.getContext())) {
            if (onDone != null) onDone.run();
            return;
        }
        v.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(140)
                .withEndAction(onDone).start();
    }

    private static float cachedRr = -1f;

    /** 设备支持的最高刷新率（浮窗/面板声明首选帧率，适配高刷）。 */
    public static float maxRefreshRate(Context c) {
        if (cachedRr > 0) return cachedRr;
        cachedRr = 0f;
        try {
            DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
            if (d != null) {
                for (Display.Mode m : d.getSupportedModes()) {
                    cachedRr = Math.max(cachedRr, m.getRefreshRate());
                }
            }
        } catch (Exception ignored) {
        }
        return cachedRr;
    }
}
