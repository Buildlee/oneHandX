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

import androidx.core.graphics.ColorUtils;

/**
 * v0.5 自绘开关（ColorOS / Apple 语言）：50x30dp、白色圆钮、阻尼过渡、按压反馈。
 * API 与 SwitchCompat 对齐（isChecked / setChecked / setOnCheckedChangeListener）。
 */
public class SoftSwitch extends View {

    public interface OnCheckedChangeListener {
        void onCheckedChanged(SoftSwitch sw, boolean checked);
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final int colorOff;
    private final int colorOn;
    private final int colorThumb;
    private final int colorShadow;

    private boolean checked;
    private float anim = -1f;
    private float press = 1f;
    private OnCheckedChangeListener listener;
    private ValueAnimator animator;

    public SoftSwitch(Context c) {
        this(c, null);
    }

    public SoftSwitch(Context c, AttributeSet attrs) {
        super(c, attrs);
        colorOff = UiKit.col(c, R.color.switch_off);
        colorOn = UiKit.col(c, R.color.accent);
        colorThumb = UiKit.col(c, R.color.switch_thumb);
        colorShadow = UiKit.col(c, R.color.thumb_shadow);
        setClickable(true);
        setFocusable(true);
    }

    public boolean isChecked() {
        return checked;
    }

    @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(android.widget.Switch.class.getName());
        info.setCheckable(true);
        info.setChecked(checked);
    }

    public void setChecked(boolean v) {
        setChecked(v, true);
    }

    public void setChecked(boolean v, boolean animate) {
        float target = v ? 1f : 0f;
        if (checked == v && anim >= 0f) return;
        checked = v;
        float from = anim < 0f ? target : anim;
        if (animator != null) animator.cancel();
        if (animate && from != target && Motion.animationsEnabled(getContext())) {
            animator = ValueAnimator.ofFloat(from, target);
            animator.setDuration(240);
            animator.setInterpolator(new PathInterpolator(0.3f, 0.9f, 0.3f, 1f));
            animator.addUpdateListener(a -> {
                anim = (float) a.getAnimatedValue();
                invalidate();
            });
            animator.start();
        } else {
            anim = target;
            invalidate();
        }
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener l) {
        listener = l;
    }

    @Override
    public boolean performClick() {
        setChecked(!checked, true);
        if (listener != null) listener.onCheckedChanged(this, checked);
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                press = 0.94f;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                press = 1f;
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                press = 1f;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h / 2f;
        float m = Math.max(1f, r * 0.09f);
        float t = Math.max(0f, Math.min(1f, anim < 0f ? (checked ? 1f : 0f) : anim));

        paint.setColor(ColorUtils.blendARGB(colorOff, colorOn, t));
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, paint);

        float thumbR = (r - m) * press;
        float cy = h / 2f;
        float cx = r + (w - h) * t;
        paint.setColor(colorShadow);
        canvas.drawCircle(cx, cy + m * 0.6f, thumbR, paint);
        paint.setColor(colorThumb);
        canvas.drawCircle(cx, cy, thumbR, paint);
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(
                resolveSize(UiKit.dp(getContext(), 50), wSpec),
                resolveSize(UiKit.dp(getContext(), 30), hSpec));
    }
}
