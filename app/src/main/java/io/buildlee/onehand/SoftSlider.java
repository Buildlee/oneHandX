package io.buildlee.onehand;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.PathInterpolator;

import java.util.function.IntConsumer;

/** v0.5 自绘滑块：细轨 + 白色大圆钮，抓取放大与光环（ColorOS / Apple 语言）。 */
public class SoftSlider extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int colorFill;
    private final int colorTrack;
    private final int colorThumb;
    private final int colorHalo;
    private final int colorShadow;

    private int min = 0;
    private int max = 100;
    private int progress = 0;
    private float grab = 0f;
    private boolean tracking;
    private IntConsumer listener;
    private ValueAnimator grabAnimator;

    public SoftSlider(Context c) {
        this(c, null);
    }

    public SoftSlider(Context c, AttributeSet attrs) {
        super(c, attrs);
        colorFill = UiKit.col(c, R.color.accent);
        colorTrack = UiKit.col(c, R.color.switch_off);
        colorThumb = UiKit.col(c, R.color.switch_thumb);
        colorHalo = UiKit.col(c, R.color.accent_soft);
        colorShadow = UiKit.col(c, R.color.thumb_shadow);
    }

    public void setRange(int min, int max) {
        this.min = min;
        this.max = Math.max(min + 1, max);
        invalidate();
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int p) {
        progress = Math.max(min, Math.min(max, p));
        invalidate();
    }

    public void setOnChangeListener(IntConsumer l) {
        listener = l;
    }

    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.SeekBar.class.getName());
        info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo.RangeInfo.obtain(0, min, max, progress));
        if (isEnabled()) {
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (isEnabled() && (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            int step = Math.max(1, (max - min) / 20);
            setProgress(progress + (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? step : -step));
            if (listener != null) listener.accept(progress);
            sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(resolveSize(UiKit.dp(getContext(), 220), wSpec),
                resolveSize(UiKit.dp(getContext(), 34), hSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float cy = h / 2f;
        float baseR = dp(13.5f);
        float pad = baseR + dp(1);
        float radius = baseR * (1f + 0.15f * grab);
        float frac = (progress - min) / (float) (max - min);
        float cx = pad + (w - 2 * pad) * frac;

        float trackH = dp(7f);
        rect.set(pad, cy - trackH / 2f, w - pad, cy + trackH / 2f);
        paint.setColor(colorTrack);
        canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
        if (cx > pad + 0.5f) {
            rect.set(pad, cy - trackH / 2f, cx, cy + trackH / 2f);
            paint.setColor(colorFill);
            canvas.drawRoundRect(rect, trackH / 2f, trackH / 2f, paint);
        }
        if (grab > 0.01f) {
            paint.setColor(colorHalo);
            canvas.drawCircle(cx, cy, radius + dp(7f), paint);
        }
        paint.setColor(colorShadow);
        canvas.drawCircle(cx, cy + dp(1f), radius, paint);
        paint.setColor(colorThumb);
        canvas.drawCircle(cx, cy, radius, paint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                tracking = true;
                animateGrab(1f);
                updateFromX(e.getX());
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (tracking) updateFromX(e.getX());
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                tracking = false;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                animateGrab(0f);
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void updateFromX(float x) {
        float w = getWidth();
        float pad = dp(13.5f) + dp(1);
        float frac = (x - pad) / Math.max(1f, w - 2 * pad);
        frac = Math.max(0f, Math.min(1f, frac));
        int v = Math.round(min + frac * (max - min));
        if (v != progress) {
            progress = v;
            invalidate();
            if (listener != null) listener.accept(v);
        }
    }

    private void animateGrab(float target) {
        if (!Motion.animationsEnabled(getContext())) {
            grab = target;
            invalidate();
            return;
        }
        if (grabAnimator != null) grabAnimator.cancel();
        grabAnimator = ValueAnimator.ofFloat(grab, target);
        grabAnimator.setDuration(target > grab ? 160 : 220);
        grabAnimator.setInterpolator(new PathInterpolator(0.3f, 0.9f, 0.3f, 1f));
        grabAnimator.addUpdateListener(a -> {
            grab = (float) a.getAnimatedValue();
            invalidate();
        });
        grabAnimator.start();
    }
}
