package io.buildlee.onehand;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** v0.5 设置界面共享 UI 构建工具（ColorOS 令牌 + Apple 式细节）。 */
public class UiKit {

    public static final String TAG_NODIV = "nodiv";
    public static final String TAG_SUBLABEL = "sublabel";

    public static int dp(Context c, int v) {
        return Du.dp(c, v);
    }

    public static int col(Context c, int id) {
        return ContextCompat.getColor(c, id);
    }

    /** Android 15+ 强制 edge-to-edge：为主内容根补上系统栏内边距（状态栏 / 导航条）。 */
    public static void applyInsets(final View root) {
        if (root == null) return;
        final int baseTop = root.getPaddingTop();
        final int baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(v.getPaddingLeft(), baseTop + bars.top, v.getPaddingRight(), baseBottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** 圆角卡片容器：子项之间自动绘制 1px 分隔线（贴齐内容区、伸缩线不打断分组标题）。 */
    public static class CardLayout extends LinearLayout {
        private final Paint linePaint = new Paint();

        public CardLayout(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackgroundResource(R.drawable.bg_card);
            linePaint.setColor(col(c, R.color.card_stroke));
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            int n = getChildCount();
            for (int i = 1; i < n; i++) {
                View prev = getChildAt(i - 1);
                View cur = getChildAt(i);
                if (prev.getVisibility() != VISIBLE || cur.getVisibility() != VISIBLE) continue;
                if (TAG_SUBLABEL.equals(prev.getTag()) || TAG_SUBLABEL.equals(cur.getTag())) continue;
                if (TAG_NODIV.equals(cur.getTag())) continue;
                float y = cur.getTop();
                canvas.drawRect(cur.getLeft(), y, cur.getRight(), y + 1f, linePaint);
            }
        }
    }

    public static LinearLayout card(Context c) {
        CardLayout card = new CardLayout(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        card.setLayoutParams(lp);
        int ph = dp(c, 16), pv = dp(c, 4);
        card.setPadding(ph, pv, ph, pv);
        return card;
    }

    public static TextView sectionHeader(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(col(c, R.color.text_secondary));
        t.setLetterSpacing(0.03f);
        t.setPadding(dp(c, 4), dp(c, 22), dp(c, 4), dp(c, 7));
        return t;
    }

    public static TextView subLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12.5f);
        t.setTextColor(col(c, R.color.text_secondary));
        t.setLineSpacing(dp(c, 2), 1f);
        t.setPadding(0, dp(c, 12), dp(c, 4), dp(c, 4));
        t.setTag(TAG_SUBLABEL);
        return t;
    }

    public static LinearLayout switchRow(Context c, String title, String subtitle, boolean checked, Consumer<Boolean> onChanged) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 58));
        row.setPadding(0, dp(c, 10), 0, dp(c, 10));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextSize(16.5f);
        t1.setLetterSpacing(-0.01f);
        t1.setTextColor(col(c, R.color.text_primary));
        texts.addView(t1);
        if (subtitle != null) {
            TextView t2 = new TextView(c);
            t2.setText(subtitle);
            t2.setTextSize(13f);
            t2.setTextColor(col(c, R.color.text_secondary));
            t2.setLineSpacing(dp(c, 2), 1f);
            t2.setPadding(dp(c, 8), dp(c, 3), dp(c, 12), 0);
            texts.addView(t2);
        }
        row.addView(texts);

        SoftSwitch sw = new SoftSwitch(c);
        sw.setChecked(checked);
        row.addView(sw);
        sw.setOnCheckedChangeListener((s, isChecked) -> {
            if (onChanged != null) onChanged.accept(isChecked);
        });
        row.setOnClickListener(v -> {
            boolean nv = !sw.isChecked();
            sw.setChecked(nv, true);
            if (onChanged != null) onChanged.accept(nv);
        });
        return row;
    }

    public static LinearLayout sliderRow(Context c, String title, int min, int max, int value, String unit, IntConsumer onChanged) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 10), 0, dp(c, 10));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(16.5f);
        t.setLetterSpacing(-0.01f);
        t.setTextColor(col(c, R.color.text_primary));
        header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(c);
        val.setTextSize(15f);
        val.setTextColor(col(c, R.color.text_secondary));
        header.addView(val);
        box.addView(header);

        SoftSlider sb = new SoftSlider(c);
        sb.setRange(min, max);
        int cur = Math.max(min, Math.min(max, value));
        sb.setProgress(cur);
        val.setText(cur + unit);
        sb.setOnChangeListener(v -> {
            val.setText(v + unit);
            if (onChanged != null) onChanged.accept(v);
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(c, 2);
        box.addView(sb, slp);
        return box;
    }

    public static class SlotRow {
        public final LinearLayout row;
        public final TextView value;

        public SlotRow(LinearLayout row, TextView value) {
            this.row = row;
            this.value = value;
        }
    }

    public static SlotRow slotRow(Context c, String title, int iconRes, String valueText, boolean dim, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 58));
        row.setPadding(0, dp(c, 11), 0, dp(c, 11));
        row.setBackgroundResource(R.drawable.bg_row);

        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageResource(iconRes);
            icon.setColorFilter(col(c, R.color.icon_tint));
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
            row.addView(icon);
        }

        TextView name = new TextView(c);
        name.setText(title);
        name.setTextSize(16.5f);
        name.setLetterSpacing(-0.01f);
        name.setTextColor(col(c, R.color.text_primary));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = iconRes != 0 ? dp(c, 12) : 0;
        name.setLayoutParams(nlp);
        row.addView(name);

        TextView val = new TextView(c);
        val.setTextSize(15f);
        val.setText(valueText);
        val.setTextColor(col(c, dim ? R.color.text_secondary : R.color.text_primary));
        val.setMaxLines(1);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        val.setMaxWidth(dp(c, 170));
        row.addView(val);

        row.addView(chevron(c));

        if (click != null) row.setOnClickListener(click);
        return new SlotRow(row, val);
    }

    public static LinearLayout clickRow(Context c, String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 58));
        row.setPadding(0, dp(c, 10), 0, dp(c, 10));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextSize(16.5f);
        t1.setLetterSpacing(-0.01f);
        t1.setTextColor(col(c, R.color.text_primary));
        texts.addView(t1);
        if (subtitle != null) {
            TextView t2 = new TextView(c);
            t2.setText(subtitle);
            t2.setTextSize(13f);
            t2.setTextColor(col(c, R.color.text_secondary));
            t2.setLineSpacing(dp(c, 2), 1f);
            t2.setPadding(dp(c, 8), dp(c, 3), dp(c, 12), 0);
            texts.addView(t2);
        }
        row.addView(texts);
        row.addView(chevron(c));
        if (click != null) row.setOnClickListener(click);
        return row;
    }

    public static ImageView chevron(Context c) {
        ImageView iv = new ImageView(c);
        iv.setImageResource(R.drawable.ic_chevron);
        iv.setColorFilter(col(c, R.color.icon_tint));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16));
        lp.leftMargin = dp(c, 8);
        iv.setLayoutParams(lp);
        return iv;
    }

    /** 主按钮：强调色胶囊（Apple 式文字按钮的实心变体）。 */
    public static TextView pillButton(Context c, String text, View.OnClickListener click) {
        TextView b = new TextView(c);
        b.setText(text);
        b.setTextSize(15f);
        b.setTextColor(0xFFFFFFFF);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_pill);
        b.setPadding(dp(c, 22), dp(c, 11), dp(c, 22), dp(c, 11));
        if (click != null) {
            b.setOnClickListener(click);
            Motion.pressScale(b);
        }
        return b;
    }
}
