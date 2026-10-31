package io.buildlee.onehand;

import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** A local dry run of this handle's short-swipe mapping. Never executes an action or requests permission. */
public final class GesturePracticeView extends FrameLayout {
    private final Prefs prefs;
    private final String handleId;
    private final boolean left;
    private final FeedbackView feedback;
    private final TextView result;
    private float startX, startY;
    private boolean tracking;
    private String lastSlot;

    public GesturePracticeView(Context context, String handleId) {
        super(context);
        this.prefs = new Prefs(context);
        this.handleId = handleId;
        left = handleId.startsWith("L");
        setMinimumHeight(dp(156));
        setContentDescription((left ? "左" : "右") + "手柄短滑练习区，不执行系统动作");
        feedback = new FeedbackView(context);
        addView(feedback, new LayoutParams(-1, -1));
        TextView guide = new TextView(context);
        guide.setText(left ? "从蓝色起点向右滑动" : "从蓝色起点向左滑动");
        guide.setTextSize(13);
        guide.setTextColor(UiKit.col(context, R.color.text_secondary));
        guide.setGravity(Gravity.CENTER);
        LayoutParams gp = new LayoutParams(-1, -2, Gravity.TOP);
        gp.topMargin = dp(12);
        addView(guide, gp);
        View origin = new View(context);
        android.graphics.drawable.GradientDrawable bar = new android.graphics.drawable.GradientDrawable();
        bar.setColor(UiKit.col(context, R.color.accent)); bar.setCornerRadius(dp(3));
        origin.setBackground(bar);
        LayoutParams op = new LayoutParams(dp(4), dp(56), Gravity.CENTER_VERTICAL | (left ? Gravity.LEFT : Gravity.RIGHT));
        op.leftMargin = op.rightMargin = dp(20);
        addView(origin, op);
        result = new TextView(context);
        result.setText("向内滑动\n查看对应动作");
        result.setTextSize(16);
        result.setMaxLines(3);
        result.setEllipsize(android.text.TextUtils.TruncateAt.END);
        result.setGravity(Gravity.CENTER);
        result.setTextColor(UiKit.col(context, R.color.text_primary));
        result.setPadding(dp(52), dp(36), dp(52), 0);
        addView(result, new LayoutParams(-1, -1));
    }

    private int dp(int value) { return UiKit.dp(getContext(), value); }
    public String lastSlot() { return lastSlot; }

    public void refreshMapping() {
        if (lastSlot != null) showMapping(lastSlot, false);
    }

    private void showMapping(String slot, boolean completed) {
        String action = prefs.displayFor(slot);
        result.setText((completed ? "已识别 · " : "松手预览 · ") + action + "\n未执行系统动作");
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastSlot = null;
                if ((left && event.getX() > dp(52)) || (!left && event.getX() < getWidth() - dp(52))) {
                    result.setText(left ? "请从左侧蓝色起点滑入" : "请从右侧蓝色起点滑入");
                    return false;
                }
                tracking = true; startX = event.getX(); startY = event.getY();
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!tracking) return false;
                float inward = left ? event.getX() - startX : startX - event.getX();
                float dy = event.getY() - startY;
                boolean complete = event.getActionMasked() == MotionEvent.ACTION_UP;
                if (inward >= dp(prefs.shortTriggerDp())) {
                    lastSlot = Prefs.slot(handleId, "S", GestureRules.direction(inward, dy, prefs.angleDeg()));
                    showMapping(lastSlot, complete);
                } else {
                    lastSlot = null;
                    result.setText(complete ? "已取消 · 滑动距离不足" : "继续向内滑动");
                }
                if (complete) { release(); performClick(); }
                else feedback.show(left, left ? dp(22) : getWidth() - dp(22), startY, inward, dy);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!tracking) return false;
                lastSlot = null; result.setText("已取消 · 未执行系统动作"); release(); return true;
            default: return super.onTouchEvent(event);
        }
    }

    private void release() {
        tracking = false; feedback.dismiss();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDetachedFromWindow() { release(); super.onDetachedFromWindow(); }
}
