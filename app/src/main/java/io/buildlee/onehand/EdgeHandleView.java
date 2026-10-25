package io.buildlee.onehand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 屏幕边缘的手柄指示条/动画。窗口本身可以比指示条宽，触控范围 = 窗口范围。
 * 支持三种动画风格（对齐 One Hand Operation+ 的动画概念）：
 * 0 = 指示条（默认，带方向箭头）  1 = 箭头  2 = 曲线
 */
public class EdgeHandleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean leftSide;
    private int baseAlpha = 115;
    private boolean active = false;
    private int style = 0;
    private int color = 0xFFFFFFFF;
    private int animColor = 0xFFFFFFFF;

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
        if (active != a) {
            active = a;
            invalidate();
        }
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

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float barW = Math.max(w * 0.16f, dp(3));
        float barH = h * 0.94f;
        float x = leftSide ? w * 0.18f : w * 0.82f - barW;
        float top = (h - barH) / 2f;

        int alpha = active ? Math.min(255, baseAlpha + 110) : baseAlpha;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        paint.setAlpha(alpha);
        RectF r = new RectF(x, top, x + barW, top + barH);
        float rad = barW / 2f;
        canvas.drawRoundRect(r, rad, rad, paint);

        if (active) {
            paint.setColor(animColor);
            float cy = h / 2f;
            float dir = leftSide ? 1f : -1f;
            int actAlpha = Math.min(255, baseAlpha + 150);
            if (style == 1) {
                // 箭头
                float ah = Math.max(h * 0.10f, dp(18));
                float aw = ah * 0.9f;
                float ax = leftSide ? w * 0.55f : w * 0.45f;
                paint.setStyle(Paint.Style.FILL);
                paint.setAlpha(actAlpha);
                android.graphics.Path p = new android.graphics.Path();
                p.moveTo(ax + dir * aw / 2f, cy);
                p.lineTo(ax - dir * aw / 2f, cy - ah / 2f);
                p.lineTo(ax - dir * aw / 2f, cy + ah / 2f);
                p.close();
                canvas.drawPath(p, paint);
            } else if (style == 2) {
                // 曲线
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
                // 指示条：方向箭头（旧样式）
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
