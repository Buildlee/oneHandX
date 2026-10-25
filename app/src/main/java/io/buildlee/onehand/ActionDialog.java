package io.buildlee.onehand;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** v0.5：底部圆角弹层（抓手条 + 勾选式列表，ColorOS / Apple 语言）。 */
public class ActionDialog {

    public interface OnPick {
        void onPicked(GestureAction action);
    }

    public static void show(Context ctx, String title, String currentId, OnPick cb) {
        show(ctx, title, currentId, GestureAction.PICKER_ORDER, cb);
    }

    public static void show(Context ctx, String title, String currentId, GestureAction[] items, OnPick cb) {
        final Dialog dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout sheet = new LinearLayout(ctx);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(UiKit.dp(ctx, 28));
        bg.setColor(UiKit.col(ctx, R.color.card));
        sheet.setBackground(bg);
        sheet.setPadding(UiKit.dp(ctx, 18), UiKit.dp(ctx, 6), UiKit.dp(ctx, 18), UiKit.dp(ctx, 16));

        View grab = new View(ctx);
        GradientDrawable gd = new GradientDrawable();
        gd.setCornerRadius(UiKit.dp(ctx, 3));
        gd.setColor(UiKit.col(ctx, R.color.switch_off));
        grab.setBackground(gd);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(UiKit.dp(ctx, 38), UiKit.dp(ctx, 5));
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        glp.topMargin = UiKit.dp(ctx, 9);
        glp.bottomMargin = UiKit.dp(ctx, 4);
        sheet.addView(grab, glp);

        TextView titleTv = new TextView(ctx);
        titleTv.setText(title);
        titleTv.setTextSize(17f);
        titleTv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleTv.setTextColor(UiKit.col(ctx, R.color.text_primary));
        titleTv.setPadding(UiKit.dp(ctx, 2), UiKit.dp(ctx, 10), UiKit.dp(ctx, 2), UiKit.dp(ctx, 8));
        sheet.addView(titleTv);

        ScrollView scroller = new ScrollView(ctx) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int max = (int) (getResources().getDisplayMetrics().heightPixels * 0.60f);
                heightSpec = MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, heightSpec);
            }
        };
        scroller.setVerticalScrollBarEnabled(false);

        LinearLayout list = new UiKit.CardLayout(ctx);
        list.setBackground(null);
        for (final GestureAction a : items) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(UiKit.dp(ctx, 54));
            row.setPadding(UiKit.dp(ctx, 2), UiKit.dp(ctx, 10), UiKit.dp(ctx, 2), UiKit.dp(ctx, 10));
            row.setBackgroundResource(R.drawable.bg_row);

            TextView label = new TextView(ctx);
            label.setText(a.label);
            label.setTextSize(16.5f);
            label.setLetterSpacing(-0.01f);
            label.setTextColor(UiKit.col(ctx, R.color.text_primary));
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView check = new TextView(ctx);
            check.setText("\u2713");
            check.setTextSize(18f);
            check.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            check.setTextColor(UiKit.col(ctx, R.color.accent));
            check.setVisibility(a.id.equals(currentId) ? View.VISIBLE : View.INVISIBLE);
            row.addView(check);

            row.setOnClickListener(v -> {
                dialog.dismiss();
                cb.onPicked(a);
            });
            list.addView(row);
        }
        scroller.addView(list);
        sheet.addView(scroller);

        FrameLayout container = new FrameLayout(ctx);
        container.setPadding(UiKit.dp(ctx, 8), 0, UiKit.dp(ctx, 8), UiKit.dp(ctx, 8));
        container.addView(sheet, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        dialog.setContentView(container);

        Window win = dialog.getWindow();
        if (win != null) {
            win.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            win.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            win.setDimAmount(0.45f);
            win.setWindowAnimations(R.style.SheetAnim);
            WindowManager.LayoutParams wlp = win.getAttributes();
            wlp.gravity = Gravity.BOTTOM;
            wlp.width = WindowManager.LayoutParams.MATCH_PARENT;
            wlp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            win.setAttributes(wlp);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                win.setDecorFitsSystemWindows(false);
            }
        }
        dialog.show();
        if (win != null) {
            ViewCompat.setOnApplyWindowInsetsListener(win.getDecorView(), (v, insets) -> {
                int bot = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                int padBottom = UiKit.dp(ctx, 8) + Math.max(bot, UiKit.dp(ctx, 12));
                container.setPadding(UiKit.dp(ctx, 8), 0, UiKit.dp(ctx, 8), padBottom);
                return insets;
            });
            ViewCompat.requestApplyInsets(win.getDecorView());
        }
    }
}
