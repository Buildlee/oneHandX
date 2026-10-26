package io.buildlee.onehand;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

/** Appearance controls share the exact renderer used by real gestures. */
public class AppearanceActivity extends AppCompatActivity {
    private Prefs prefs;
    private FeedbackView preview;
    private LinearLayout controls;
    private boolean left = true;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(UiKit.col(this, R.color.bg));
        setContentView(page);
        UiKit.applyInsets(page);
        TextView back = text("‹   外观与反馈", 23, true);
        back.setContentDescription("返回，外观与反馈");
        back.setPadding(dp(20), dp(16), dp(20), dp(16));
        back.setOnClickListener(v -> finish());
        page.addView(back);

        FrameLayout stage = new FrameLayout(this);
        stage.setBackgroundColor(UiKit.col(this, R.color.card));
        preview = new FeedbackView(this);
        stage.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = text("从两侧向内拖动，试试手感", 13, false);
        hint.setTextColor(UiKit.col(this, R.color.text_secondary));
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        hlp.topMargin = dp(10);
        stage.addView(hint, hlp);
        TextView result = text("返回", 22, true);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        stage.addView(result, rlp);
        stage.setContentDescription("滑动反馈试用区，不执行系统动作");
        stage.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    sx = e.getX(); sy = e.getY(); left = sx < v.getWidth() / 2f;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    float dx = Math.max(dp(30), left ? e.getX() - sx : sx - e.getX());
                    preview.show(left, left ? dp(10) : v.getWidth() - dp(10), sy, dx, e.getY() - sy);
                    result.setText(e.getY() - sy < -dp(28) ? "主屏幕" : e.getY() - sy > dp(28) ? "最近任务" : "返回");
                } else if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    preview.dismiss();
                    v.getParent().requestDisallowInterceptTouchEvent(false);
                    if (e.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
                }
                return true;
            }
        });
        page.addView(stage, new LinearLayout.LayoutParams(-1, dp(208)));
        LinearLayout demo = new LinearLayout(this);
        for (int i = -1; i <= 1; i++) {
            final int direction = i;
            TextView b = text(i < 0 ? "斜上" : i > 0 ? "斜下" : "直滑", 14, false);
            b.setGravity(Gravity.CENTER); b.setMinHeight(dp(48));
            b.setTextColor(UiKit.col(this, R.color.accent));
            b.setBackgroundResource(R.drawable.bg_row);
            b.setOnClickListener(v -> preview.show(left, left ? dp(10) : preview.getWidth() - dp(10), dp(104), dp(112), dp(42 * direction)));
            demo.addView(b, new LinearLayout.LayoutParams(0, -2, 1));
        }
        page.addView(demo);
        ScrollView scroll = new ScrollView(this);
        controls = new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(controls);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        buildControls();
        preview.post(this::refreshPreview);
    }

    private void refreshPreview() {
        preview.show(left, left ? dp(10) : preview.getWidth() - dp(10), dp(104), dp(112), 0);
    }

    private void buildControls() {
        controls.removeAllViews();
        if (prefs.feedbackStyle() == 0) {
            controls.addView(UiKit.sectionHeader(this, "曲面外观"));
            LinearLayout colors = UiKit.card(this);
            colors.addView(colorRow("背景颜色", Prefs.WAVE_FILL, prefs.waveFill()));
            colors.addView(UiKit.sliderRow(this, "背景透明度", 0, 100, prefs.waveTransparency(), "%", v -> {
                prefs.setInt(Prefs.WAVE_TRANSPARENCY, v); refreshPreview();
            }));
            colors.addView(UiKit.subLabel(this, "0% 不透明 · 100% 完全透明；箭头与轮廓独立显示"));
            colors.addView(colorRow("箭头颜色", Prefs.WAVE_ARROW, prefs.waveArrow()));
            colors.addView(colorRow("轮廓颜色", Prefs.WAVE_OUTLINE, prefs.waveOutline()));
            colors.addView(UiKit.clickRow(this, "恢复默认配色", "仅恢复曲面外观，保留手势映射", v -> {
                prefs.resetFeedback(); buildControls(); refreshPreview();
            }));
            controls.addView(colors);
        }
        controls.addView(UiKit.sectionHeader(this, "滑动样式"));
        LinearLayout styles = UiKit.card(this);
        String[] descriptions = {"圆润曲面 · 半透明背景与独立轮廓", "轻巧线条 · 方向清晰", "圆形底色 · 对比鲜明", "柔和弧面 · 包裹箭头", "轻盈曲线 · 留白更多", "一条短线 · 低干扰"};
        for (int i = 0; i < Prefs.FEEDBACK_NAMES.length; i++) {
            final int style = i;
            LinearLayout row = UiKit.clickRow(this, (prefs.feedbackStyle() == i ? "✓  " : "") + Prefs.FEEDBACK_NAMES[i], descriptions[i], v -> {
                prefs.setInt(Prefs.FEEDBACK_STYLE, style); buildControls(); refreshPreview();
            });
            FeedbackView sample = new FeedbackView(this);
            sample.setSampleStyle(i);
            row.addView(sample, 0, new LinearLayout.LayoutParams(dp(64), dp(72)));
            sample.post(() -> sample.show(true, dp(4), dp(36), dp(75), 0));
            styles.addView(row);
        }
        controls.addView(styles);
        if (prefs.feedbackStyle() != 0) {
            LinearLayout indicator = UiKit.card(this);
            indicator.addView(UiKit.clickRow(this, "指示颜色", Prefs.COLOR_NAMES[prefs.animColorIndex()], v ->
                    new AlertDialog.Builder(this).setTitle("指示颜色")
                            .setSingleChoiceItems(Prefs.COLOR_NAMES, prefs.animColorIndex(), (d, which) -> {
                                prefs.setInt(Prefs.ANIM_COLOR, which); d.dismiss(); buildControls(); refreshPreview();
                            }).show()));
            controls.addView(indicator);
        }
        controls.addView(UiKit.sectionHeader(this, "显示与动态效果"));
        LinearLayout display = UiKit.card(this);
        display.addView(UiKit.clickRow(this, "界面主题", themeName(), v -> new AlertDialog.Builder(this)
                .setTitle("界面主题").setSingleChoiceItems(new String[]{"跟随系统", "浅色", "深色"}, prefs.getInt(Prefs.THEME, 0), (d, which) -> {
                    prefs.setInt(Prefs.THEME, which); d.dismiss();
                    AppCompatDelegate.setDefaultNightMode(which == 1 ? AppCompatDelegate.MODE_NIGHT_NO : which == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                }).show()));
        display.addView(UiKit.switchRow(this, "减少动态效果", "保留跟手反馈，关闭入场与收回动画", prefs.reduceMotion(), v -> prefs.setBool(Prefs.REDUCE_MOTION, v)));
        controls.addView(display);
        controls.addView(UiKit.sectionHeader(this, "手柄指示条"));
        LinearLayout handle = UiKit.card(this);
        handle.addView(UiKit.sliderRow(this, "手柄宽度", 8, 48, prefs.handleWidthDp(), "dp", v -> prefs.setInt(Prefs.HANDLE_WIDTH_DP, v)));
        handle.addView(UiKit.switchRow(this, "显示指示条", "隐藏后仍可从原来的手柄位置滑动", prefs.showIndicator(), v -> prefs.setBool(Prefs.SHOW_INDICATOR, v)));
        handle.addView(UiKit.clickRow(this, "手柄颜色", Prefs.COLOR_NAMES[prefs.handleColorIndex()], v ->
                new AlertDialog.Builder(this).setTitle("手柄颜色")
                        .setSingleChoiceItems(Prefs.COLOR_NAMES, prefs.handleColorIndex(), (d, which) -> {
                            prefs.setInt(Prefs.HANDLE_COLOR, which); d.dismiss(); buildControls();
                        }).show()));
        controls.addView(handle);
    }

    private String themeName() { return new String[]{"跟随系统", "浅色", "深色"}[Math.max(0, Math.min(2, prefs.getInt(Prefs.THEME, 0)))]; }

    private View colorRow(String name, String key, int value) {
        LinearLayout row = UiKit.clickRow(this, name, String.format("#%06X", value & 0xFFFFFF), v -> chooseColor(name, key, value));
        View swatch = new View(this);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(value); bg.setCornerRadius(dp(16)); bg.setStroke(dp(1), UiKit.col(this, R.color.icon_tint));
        swatch.setBackground(bg);
        row.addView(swatch, 0, new LinearLayout.LayoutParams(dp(32), dp(32)));
        ((LinearLayout.LayoutParams) row.getChildAt(1).getLayoutParams()).leftMargin = dp(12);
        return row;
    }

    private void chooseColor(String name, String key, int original) {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        EditText hex = new EditText(this);
        hex.setSingleLine(true); hex.setHint("#RRGGBB"); hex.setText(String.format("#%06X", original & 0xFFFFFF));
        hex.setContentDescription(name + "十六进制色值");
        box.addView(hex);
        int[] channels = {Color.red(original), Color.green(original), Color.blue(original)};
        View swatch = new View(this); swatch.setBackgroundColor(original);
        box.addView(swatch, new LinearLayout.LayoutParams(-1, dp(40)));
        String[] labels = {"红", "绿", "蓝"};
        for (int i = 0; i < 3; i++) {
            final int channel = i;
            box.addView(UiKit.sliderRow(this, labels[i], 0, 255, channels[i], "", v -> {
                channels[channel] = v;
                int color = Color.rgb(channels[0], channels[1], channels[2]);
                hex.setText(String.format("#%06X", color & 0xFFFFFF)); swatch.setBackgroundColor(color);
            }));
        }
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(name).setView(scroll)
                .setPositiveButton("应用", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String input = hex.getText().toString().trim();
            if (!input.matches("#?[0-9a-fA-F]{6}")) { hex.setError("请输入 6 位颜色，例如 #32495D"); return; }
            prefs.setInt(key, Color.parseColor(input.startsWith("#") ? input : "#" + input));
            dialog.dismiss(); buildControls(); refreshPreview();
        }));
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private int dp(int value) { return UiKit.dp(this, value); }
    private TextView text(String value, int size, boolean bold) {
        TextView tv = new TextView(this); tv.setText(value); tv.setTextSize(size);
        tv.setTextColor(UiKit.col(this, R.color.text_primary));
        if (bold) tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tv;
    }
}
