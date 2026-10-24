package io.buildlee.onehand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 屏幕边缘的手柄指示条。窗口本身可以比指示条宽，触控范围 = 窗口范围。
 */
public class EdgeHandleView extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean leftSide;
    private int baseAlpha = 115;
    private boolean active = false;

    public EdgeHandleView(Context context, boolean leftSide) {
        super(context);
        this.leftSide = leftSide;
        paint.setColor(Color.WHITE);
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
        paint.setAlpha(alpha);
        RectF r = new RectF(x, top, x + barW, top + barH);
        float rad = barW / 2f;
        canvas.drawRoundRect(r, rad, rad, paint);

        if (active) {
            float cy = h / 2f;
            float aw = Math.max(w * 0.46f, dp(8));
            float ah = aw * 0.95f;
            float ax = leftSide ? w * 0.53f : w * 0.47f;
            float dir = leftSide ? 1f : -1f;
            paint.setAlpha(Math.min(255, baseAlpha + 150));
            paint.setStrokeWidth(Math.max(dp(2), barW * 0.34f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(ax - dir * aw / 2f, cy - ah / 2f, ax + dir * aw / 2f, cy, paint);
            canvas.drawLine(ax + dir * aw / 2f, cy, ax - dir * aw / 2f, cy + ah / 2f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
