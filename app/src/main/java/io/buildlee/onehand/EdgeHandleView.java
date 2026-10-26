package io.buildlee.onehand;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import androidx.core.graphics.ColorUtils;

/**
 * 屏幕边缘的手柄指示条（v0.5：淡入淡出、拖动高亮、长滑阈值光晕）。
 * 窗口本身可以比指示条宽，触控范围 = 窗口范围。
 * 方向反馈由独立的非触摸窗口绘制，指示条不扩大触摸热区。
 */
public class EdgeHandleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean leftSide;
    private int baseAlpha = 115;
    private int color = 0xFFFFFFFF;
    private int animColor = 0xFFFFFFFF;

    private float activeAmt = 0f;
    private float hotAmt = 0f;
    private float actTarget = 0f;
    private float hotTarget = 0f;
    private ValueAnimator animator;

    public EdgeHandleView(Context context, boolean leftSide) {
        super(context);
        this.leftSide = leftSide;
        paint.setColor(color);
    }

    public void setBaseAlpha(int a) {
        baseAlpha = Math.max(0, Math.min(255, a));
        invalidate();
    }

    public void setActive(boolean a) {
        actTarget = a ? 1f : 0f;
        if (!a) hotTarget = 0f;
        startAnim();
    }

    public void setHot(boolean h) {
        hotTarget = h ? 1f : 0f;
        if (h) actTarget = 1f;
        startAnim();
    }

    public void setColor(int c) {
        color = c;
        paint.setColor(color);
        invalidate();
    }

    public void setAnimColor(int c) {
        animColor = c;
        invalidate();
    }

    private void startAnim() {
        if (animator != null) animator.cancel();
        if (!Motion.animationsEnabled(getContext())) {
            activeAmt = actTarget;
            hotAmt = hotTarget;
            invalidate();
            return;
        }
        final float a0 = activeAmt, h0 = hotAmt;
        final float a1 = actTarget, h1 = hotTarget;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(actTarget > activeAmt || hotTarget > hotAmt ? Motion.PRESS_MS : Motion.EXIT_MS);
        animator.setInterpolator(Motion.EMPHASIS);
        animator.addUpdateListener(v -> {
            float t = (float) v.getAnimatedValue();
            activeAmt = a0 + (a1 - a0) * t;
            hotAmt = h0 + (h1 - h0) * t;
            invalidate();
        });
        animator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float barW = Math.max(w * 0.16f, dp(3));
        float barH = h * 0.94f;
        float x = leftSide ? w * 0.18f : w * 0.82f - barW;
        float top = (h - barH) / 2f;
        float rad = barW / 2f;

        float glow = Math.max(activeAmt * 0.45f, hotAmt);
        if (glow > 0.02f) {
            glowPaint.setColor(animColor);
            glowPaint.setAlpha((int) (0x30 * glow));
            canvas.drawRoundRect(x - dp(7), top - dp(3), x + barW + dp(7), top + barH + dp(3),
                    rad + dp(4), rad + dp(4), glowPaint);
            glowPaint.setAlpha((int) (0x66 * glow));
            canvas.drawRoundRect(x - dp(3), top - dp(1), x + barW + dp(3), top + barH + dp(1),
                    rad + dp(2), rad + dp(2), glowPaint);
        }

        int alpha = Math.min(255, baseAlpha + (int) (activeAmt * 80) + (int) (hotAmt * 60));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.blendARGB(color, animColor,
                Math.min(0.55f, 0.25f * activeAmt + 0.45f * hotAmt)));
        paint.setAlpha(alpha);
        canvas.drawRoundRect(x, top, x + barW, top + barH, rad, rad, paint);

    }

    @Override protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
