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

/** Restrained native motion: immediate input, short transitions, interruptible state. */
public class Motion {

    public static final Interpolator EMPHASIS = new PathInterpolator(0.22f, 1f, 0.36f, 1f);
    public static final Interpolator EXIT = new PathInterpolator(0.4f, 0f, 1f, 1f);
    public static final long ENTER_MS = 220;
    public static final long CHANGE_MS = 180;
    public static final long EXIT_MS = 160;
    public static final long PRESS_MS = 90;

    /** 尊重系统「移除动画」无障碍设置。 */
    public static boolean animationsEnabled(Context c) {
        if (c.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE).getBoolean(Prefs.REDUCE_MOTION, false)) return false;
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return false;
        try {
            return Settings.Global.getFloat(c.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0.001f;
        } catch (Exception e) {
            return true;
        }
    }

    /** Cancel before rebuilding a surface so an old completion cannot hide new content. */
    public static void reset(View view) {
        view.animate().withEndAction(null).withStartAction(null).setListener(null).cancel();
        view.animate().setStartDelay(0);
        view.setAlpha(1f);
        view.setTranslationX(0f); view.setTranslationY(0f);
        view.setScaleX(1f); view.setScaleY(1f);
    }

    /** New content is committed immediately; only presentation moves. 0 = peer tab. */
    public static void contentIn(View view, int direction) {
        reset(view);
        if (!animationsEnabled(view.getContext())) return;
        float density = view.getResources().getDisplayMetrics().density;
        view.setAlpha(.88f);
        if (direction == 0) view.setTranslationY(4 * density);
        else view.setTranslationX(direction * 14 * density);
        view.animate().alpha(1f).translationX(0f).translationY(0f)
                .setStartDelay(0).setDuration(CHANGE_MS).setInterpolator(EMPHASIS).start();
    }

    public static void openActivity(android.app.Activity activity) {
        boolean enabled = animationsEnabled(activity);
        activity.overridePendingTransition(enabled ? R.anim.push_in : 0, enabled ? R.anim.fade_out_slight : 0);
    }

    public static void closeActivity(android.app.Activity activity) {
        boolean enabled = animationsEnabled(activity);
        activity.overridePendingTransition(enabled ? R.anim.fade_in_slight : 0, enabled ? R.anim.push_out : 0);
    }

    /** One bounded disclosure group; keep its current height when reversing mid-flight. */
    public static void expand(View content, boolean expanded) {
        if (content.getTag() instanceof android.animation.ValueAnimator) {
            android.animation.ValueAnimator previous = (android.animation.ValueAnimator) content.getTag();
            previous.removeAllListeners(); previous.cancel(); content.setTag(null);
        }
        int width = content.getWidth();
        if (width <= 0 && content.getParent() instanceof View) {
            View parent = (View) content.getParent(); width = parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight();
        }
        if (!animationsEnabled(content.getContext()) || width <= 0) {
            content.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            content.setAlpha(1f); content.setVisibility(expanded ? View.VISIBLE : View.GONE);
            content.requestLayout(); return;
        }
        int start = content.getVisibility() == View.VISIBLE ? content.getHeight() : 0;
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int fullHeight = content.getMeasuredHeight();
        int end = expanded ? fullHeight : 0;
        content.setVisibility(View.VISIBLE);
        content.getLayoutParams().height = start;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofInt(start, end);
        content.setTag(animator);
        animator.setDuration(expanded ? ENTER_MS : EXIT_MS);
        animator.setInterpolator(expanded ? EMPHASIS : EXIT);
        animator.addUpdateListener(a -> {
            content.getLayoutParams().height = (int) a.getAnimatedValue();
            content.setAlpha(Math.min(1f, .3f + .7f * content.getLayoutParams().height / Math.max(1f, fullHeight)));
            content.requestLayout();
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                content.setTag(null); content.setAlpha(1f);
                content.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
                content.setVisibility(expanded ? View.VISIBLE : View.GONE); content.requestLayout();
            }
        });
        animator.start();
    }

    /** 按压缩放反馈（不消费事件，不影响点击触发）。 */
    public static void pressScale(final View v) {
        if (v == null) return;
        v.setOnTouchListener((view, e) -> {
            if (!animationsEnabled(view.getContext())) { reset(view); return false; }
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().withEndAction(null).setStartDelay(0).scaleX(0.985f).scaleY(0.985f)
                            .setDuration(PRESS_MS).setInterpolator(EMPHASIS).start();
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().withEndAction(null).setStartDelay(0).scaleX(1f).scaleY(1f)
                            .setDuration(EXIT_MS).setInterpolator(EMPHASIS).start();
                    return false;
                default:
                    return false;
            }
        });
    }

    /** Floating tools enter from their side anchor; layout sets the pivot before drawing. */
    public static void popIn(final View v, final boolean fromLeft) {
        if (v == null) return;
        reset(v);
        if (!animationsEnabled(v.getContext())) {
            v.setAlpha(1f);
            return;
        }
        v.setAlpha(0f);
        v.setScaleX(0.98f); v.setScaleY(0.98f);
        v.setTranslationX((fromLeft ? -6 : 6) * v.getResources().getDisplayMetrics().density);
        if (v.getWidth() > 0) {
            v.setPivotX(fromLeft ? 0f : v.getWidth()); v.setPivotY(v.getHeight() / 2f);
        } else {
            v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                    view.setPivotX(fromLeft ? 0f : view.getWidth()); view.setPivotY(view.getHeight() / 2f);
                    view.removeOnLayoutChangeListener(this);
                }
            });
        }
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f)
                .setStartDelay(0).setDuration(ENTER_MS).setInterpolator(EMPHASIS).start();
    }

    /** 浮层淡出后回调（用于移除窗口）。 */
    public static void fadeOut(final View v, final Runnable onDone) {
        if (v == null) {
            if (onDone != null) onDone.run();
            return;
        }
        if (!animationsEnabled(v.getContext())) {
            reset(v);
            if (onDone != null) onDone.run();
            return;
        }
        v.animate().withEndAction(null).setListener(null).cancel();
        v.animate().alpha(0f).scaleX(0.985f).scaleY(0.985f).setStartDelay(0)
                .setDuration(EXIT_MS).setInterpolator(EXIT)
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
