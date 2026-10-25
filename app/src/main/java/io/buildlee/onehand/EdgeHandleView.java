package io.buildlee.onehand;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.PathInterpolator;

import androidx.core.graphics.ColorUtils;

/**
 * 屏幕边缘的手柄指示条（v0.5：淡入淡出、拖动高亮、长滑阈值光晕）。
 * 窗口本身可以比指示条宽，触控范围 = 窗口范围。
 * 风格：0 = 指示条（默认，带方向箭头） 1 = 箭头 2 = 曲线
 */
public class EdgeHandleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean leftSide;
    private int baseAlpha = 115;
    private int style = 0;
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

    public void setStyle(int s) {
        style = s;
        invalidate();
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
        if (!Motion.animationsEnabled(getContext())) {
            activeAmt = actTarget;
            hotAmt = hotTarget;
            invalidate();
            return;
        }
        if (animator != null) animator.cancel();
        final float a0 = activeAmt, h0 = hotAmt;
        final float a1 = actTarget, h1 = hotTarget;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(220);
        animator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
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
            canvas.drawRoundRect(new RectF(x - dp(7), top - dp(3), x + barW + dp(7), top + barH + dp(3)),
                    rad + dp(4), rad + dp(4), glowPaint);
            glowPaint.setAlpha((int) (0x66 * glow));
            canvas.drawRoundRect(new RectF(x - dp(3), top - dp(1), x + barW + dp(3), top + barH + dp(1)),
                    rad + dp(2), rad + dp(2), glowPaint);
        }

        int alpha = Math.min(255, baseAlpha + (int) (activeAmt * 80) + (int) (hotAmt * 60));
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(ColorUtils.blendARGB(color, animColor,
                Math.min(0.55f, 0.25f * activeAmt + 0.45f * hotAmt)));
        paint.setAlpha(alpha);
        canvas.drawRoundRect(new RectF(x, top, x + barW, top + barH), rad, rad, paint);

        float act = Math.min(1f, activeAmt + hotAmt * 0.5f);
        if (act > 0.03f) {
            paint.setColor(animColor);
            float cy = h / 2f;
            float dir = leftSide ? 1f : -1f;
            int actAlpha = (int) (Math.min(255, baseAlpha + 150) * act);
            if (style == 1) {
                float ah = Math.max(h * 0.10f, dp(18));
                float aw = ah * 0.9f;
                float ax = leftSide ? w * 0.55f : w * 0.45f;
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(actAlpha);
                Path p = new Path();
                p.moveTo(ax + dir * aw / 2f, cy);
                p.lineTo(ax - dir * aw / 2f, cy - ah / 2f);
                p.lineTo(ax - dir * aw / 2f, cy + ah / 2f);
                p.close();
                canvas.drawPath(p, paint);
            } else if (style == 2) {
                float ah = Math.max(h * 0.10f, dp(18));
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Math.max(dp(2), barW * 0.5f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setAlpha(actAlpha);
                RectF arc = new RectF(
                        leftSide ? w * 0.5f : w * 0.5f - ah * 1.6f,
                        cy - ah,
                        leftSide ? w * 0.5f + ah * 1.6f : w * 0.5f,
                        cy + ah);
                canvas.drawArc(arc, leftSide ? 90 : 180, 90, false, paint);
                paint.setStyle(Paint.Style.FILL);
            } else {
                float aw = Math.max(w * 0.46f, dp(8));
                float ah = aw * 0.95f;
                float ax = leftSide ? w * 0.53f : w * 0.47f;
                paint.setAlpha(actAlpha);
                paint.setStrokeWidth(Math.max(dp(2), barW * 0.34f));
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawLine(ax - dir * aw / 2f, cy - ah / 2f, ax + dir * aw / 2f, cy, paint);
                canvas.drawLine(ax + dir * aw / 2f, cy, ax - dir * aw / 2f, cy + ah / 2f, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            paint.setColor(color);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
