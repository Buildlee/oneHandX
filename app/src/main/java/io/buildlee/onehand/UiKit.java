package io.buildlee.onehand;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** 设置界面共享的 UI 构建工具（MainActivity / HandleEditActivity 共用） */
public class UiKit {

    public static int dp(Context c, int v) { return Du.dp(c, v); }

    public static int col(Context c, int id) { return ContextCompat.getColor(c, id); }

    public static LinearLayout card(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 8);
        card.setLayoutParams(lp);
        int p = dp(c, 6);
        card.setPadding(p, p, p, p);
        return card;
    }

    public static TextView sectionHeader(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col(c, R.color.text_secondary));
        t.setPadding(dp(c, 10), dp(c, 22), dp(c, 10), dp(c, 8));
        return t;
    }

    public static TextView subLabel(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(col(c, R.color.text_secondary));
        t.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 2));
        return t;
    }

    public static LinearLayout switchRow(Context c, String title, String subtitle, boolean checked, Consumer<Boolean> onChanged) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextSize(15);
        t1.setTextColor(col(c, R.color.text_primary));
        texts.addView(t1);
        if (subtitle != null) {
            TextView t2 = new TextView(c);
            t2.setText(subtitle);
            t2.setTextSize(12);
            t2.setTextColor(col(c, R.color.text_secondary));
            t2.setPadding(0, dp(c, 2), 0, 0);
            texts.addView(t2);
        }
        row.addView(texts);

        SwitchCompat sw = new SwitchCompat(c);
        sw.setChecked(checked);
        row.addView(sw);
        row.setOnClickListener(v -> {
            boolean nv = !sw.isChecked();
            sw.setChecked(nv);
            if (onChanged != null) onChanged.accept(nv);
        });
        sw.setOnClickListener(v -> {
            if (onChanged != null) onChanged.accept(sw.isChecked());
        });
        return row;
    }

    public static LinearLayout sliderRow(Context c, String title, int min, int max, int value, String unit, IntConsumer onChanged) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 6));

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(col(c, R.color.text_primary));
        header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(c);
        val.setTextSize(14);
        val.setTextColor(col(c, R.color.text_secondary));
        header.addView(val);
        box.addView(header);

        SeekBar sb = new SeekBar(c);
        sb.setMax(max - min);
        int cur = Math.max(min, Math.min(max, value));
        sb.setProgress(cur - min);
        val.setText(cur + unit);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                val.setText(v + unit);
                if (fromUser && onChanged != null) onChanged.accept(v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
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
        row.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        row.setBackgroundResource(R.drawable.bg_row);

        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageResource(iconRes);
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 20), dp(c, 20)));
            row.addView(icon);
        }

        TextView name = new TextView(c);
        name.setText(title);
        name.setTextSize(15);
        name.setTextColor(col(c, R.color.text_primary));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = iconRes != 0 ? dp(c, 12) : 0;
        name.setLayoutParams(nlp);
        row.addView(name);

        TextView val = new TextView(c);
        val.setTextSize(14);
        val.setText(valueText);
        val.setTextColor(col(c, dim ? R.color.text_secondary : R.color.text_primary));
        val.setMaxLines(1);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        val.setMaxWidth(dp(c, 150));
        row.addView(val);

        row.addView(chevron(c));

        if (click != null) row.setOnClickListener(click);
        return new SlotRow(row, val);
    }

    public static LinearLayout clickRow(Context c, String title, String subtitle, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(c);
        t1.setText(title);
        t1.setTextSize(15);
        t1.setTextColor(col(c, R.color.text_primary));
        texts.addView(t1);
        if (subtitle != null) {
            TextView t2 = new TextView(c);
            t2.setText(subtitle);
            t2.setTextSize(12);
            t2.setTextColor(col(c, R.color.text_secondary));
            t2.setPadding(0, dp(c, 2), 0, 0);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16));
        lp.leftMargin = dp(c, 8);
        iv.setLayoutParams(lp);
        return iv;
    }
}
