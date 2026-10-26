package io.buildlee.onehand;

import android.content.Context;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Shared native renderer for the preview, direction samples and non-touchable service overlay. */
public class FeedbackView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path body = new Path();
    private final Path arrow = new Path();
    private final Prefs prefs;
    private final int contrastColor;
    private float origin, center, distance, deltaY;
    private boolean left = true;
    private boolean visible;
    private int sampleStyle = -1;
    private float release = 1f;
    private ValueAnimator releaseAnimator;
    private ValueAnimator demoAnimator;
    private final Runnable endDemo = this::dismiss;

    public FeedbackView(Context context) {
        super(context);
        prefs = new Prefs(context);
        contrastColor = UiKit.col(context, R.color.bg);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setSampleStyle(int style) { sampleStyle = style; }

    public void show(boolean fromLeft, float edge, float y, float inward, float dy) {
        cancelMotion();
        setAlpha(1f);
        release = 1f;
        updateGeometry(fromLeft, edge, y, inward, dy);
    }

    private void updateGeometry(boolean fromLeft, float edge, float y, float inward, float dy) {
        left = fromLeft;
        origin = edge;
        center = y;
        distance = Math.max(0, inward);
        deltaY = dy;
        visible = true;
        postInvalidateOnAnimation();
    }

    /** User-requested demo only; real dragging always updates geometry directly. */
    public void demonstrate(boolean fromLeft, float edge, float y, float inward, float dy) {
        show(fromLeft, edge, y, 0, 0);
        if (!Motion.animationsEnabled(getContext())) { show(fromLeft, edge, y, inward, dy); return; }
        demoAnimator = ValueAnimator.ofFloat(0f, 1f);
        demoAnimator.setDuration(Motion.ENTER_MS);
        demoAnimator.setInterpolator(Motion.EMPHASIS);
        demoAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            setAlpha(t);
            updateGeometry(fromLeft, edge, y, inward * t, dy * t);
        });
        demoAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { demoAnimator = null; postDelayed(endDemo, 100); }
        });
        demoAnimator.start();
    }

    public void dismiss() {
        if (!Motion.animationsEnabled(getContext())) { clear(); return; }
        if (!visible || releaseAnimator != null) return;
        cancelMotion();
        releaseAnimator = ValueAnimator.ofFloat(release, 0f);
        releaseAnimator.setDuration(Motion.EXIT_MS);
        releaseAnimator.setInterpolator(Motion.EXIT);
        releaseAnimator.addUpdateListener(a -> {
            release = (float) a.getAnimatedValue(); setAlpha(release); postInvalidateOnAnimation();
        });
        releaseAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { releaseAnimator = null; clear(); }
        });
        releaseAnimator.start();
    }

    public void clear() {
        cancelMotion();
        visible = false;
        release = 1f;
        setAlpha(1f);
        invalidate();
    }

    private void cancelMotion() {
        removeCallbacks(endDemo);
        if (releaseAnimator != null) {
            releaseAnimator.removeAllListeners(); releaseAnimator.removeAllUpdateListeners(); releaseAnimator.cancel(); releaseAnimator = null;
        }
        if (demoAnimator != null) {
            demoAnimator.removeAllListeners(); demoAnimator.removeAllUpdateListeners(); demoAnimator.cancel(); demoAnimator = null;
        }
    }

    @Override protected void onDetachedFromWindow() { clear(); super.onDetachedFromWindow(); }

    @Override protected void onDraw(Canvas c) {
        if (!visible) return;
        float d = getResources().getDisplayMetrics().density;
        int style = sampleStyle >= 0 ? sampleStyle : prefs.feedbackStyle();
        float halfHeight = Math.min(96 * d, getHeight() * .48f);
        float w = Math.min(halfHeight * .396f, Math.max(10 * d, Math.min(38 * d, distance * .34f))) * release;
        float cy = Math.max(halfHeight, Math.min(getHeight() - halfHeight, center + deltaY * .25f));
        c.save();
        c.translate(origin, cy);
        if (!left) c.scale(-1, 1);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        if (style == 0) {
            // Same normalized profile as the approved prototype; only the arrow rotates.
            body.reset();
            body.moveTo(0, -halfHeight);
            body.cubicTo(0, -halfHeight * .625f, w * .12f, -halfHeight * .4375f, w * .62f, -halfHeight * .24f);
            body.cubicTo(w, -halfHeight * .094f, w, halfHeight * .094f, w * .62f, halfHeight * .24f);
            body.cubicTo(w * .12f, halfHeight * .4375f, 0, halfHeight * .625f, 0, halfHeight);
            body.close();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(prefs.waveFill());
            paint.setAlpha(Math.round(255 * (100 - prefs.waveTransparency()) / 100f));
            c.drawPath(body, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(prefs.waveOutline());
            paint.setStrokeWidth(d);
            c.drawPath(body, paint);
        }
        float x = style == 0 ? w * .48f : Math.min(64 * d, Math.max(24 * d, distance * .48f)) * release;
        c.translate(x, 0);
        c.scale(.8f + .2f * release, .8f + .2f * release);
        c.rotate((float) Math.toDegrees(Math.atan2(deltaY, Math.max(distance, d))));
        paint.setColor(style == 0 ? prefs.waveArrow() : prefs.animColor());
        paint.setStrokeWidth(2.4f * d);
        if (style == 2 || style == 3) {
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(style == 2 ? 220 : 100);
            if (style == 2) c.drawCircle(0, 0, 17 * d, paint);
            else c.drawRoundRect(-20 * d, -24 * d, 20 * d, 24 * d, 20 * d, 20 * d, paint);
            paint.setColor(contrastColor);
            if (style == 3) paint.setColor(prefs.animColor());
        }
        paint.setStyle(Paint.Style.STROKE);
        if (style == 4) {
            arrow.reset(); arrow.moveTo(-12 * d, 12 * d);
            arrow.cubicTo(-2 * d, 14 * d, 10 * d, 3 * d, 6 * d, -10 * d);
            c.drawPath(arrow, paint);
        } else if (style == 5) {
            c.drawLine(0, -14 * d, 0, 14 * d, paint);
        } else {
            float r = (style == 0 ? 5 : 7) * d;
            c.drawLine(-r, 0, r, 0, paint);
            arrow.reset(); arrow.moveTo(0, -r); arrow.lineTo(r, 0); arrow.lineTo(0, r);
            c.drawPath(arrow, paint);
        }
        c.restore();
    }
}
