package io.buildlee.onehand;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.buildlee.onehand.takeover.LocalRecovery;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal recovery entry in its own process. Opening it never changes system settings. */
public final class RecoveryActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button restore;

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        int savedTheme = 0;
        try { savedTheme = getSharedPreferences(Prefs.NAME, MODE_PRIVATE).getInt(Prefs.THEME, 0); }
        catch (RuntimeException ignored) { }
        boolean dark = savedTheme == 2 || (savedTheme == 0 && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        int pageColor = dark ? Color.rgb(16, 25, 25) : Color.rgb(246, 248, 249);
        getWindow().setStatusBarColor(pageColor);
        getWindow().setNavigationBarColor(pageColor);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(pageColor);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(28), dp(58), dp(28), dp(36));
        scroll.addView(content);

        TextView overline = new TextView(this);
        overline.setText("ONEHANDX  /  恢复入口");
        overline.setTextSize(12);
        overline.setLetterSpacing(.08f);
        overline.setTextColor(dark ? Color.rgb(142, 213, 187) : Color.rgb(28, 104, 84));
        overline.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(overline);

        TextView title = new TextView(this);
        title.setText("恢复系统手势");
        title.setTextSize(30);
        title.setTextColor(dark ? Color.rgb(232, 242, 237) : Color.rgb(28, 39, 46));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.topMargin = dp(18);
        content.addView(title, titleParams);

        TextView description = new TextView(this);
        description.setText("如果边缘手势不能使用，可以在这里恢复接管前的系统设置。只有点击按钮后才会执行恢复。");
        description.setTextSize(16);
        description.setTextColor(dark ? Color.rgb(177, 196, 189) : Color.rgb(79, 94, 104));
        description.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams descParams = new LinearLayout.LayoutParams(-1, -2);
        descParams.topMargin = dp(18);
        content.addView(description, descParams);

        status = new TextView(this);
        status.setText("准备就绪。恢复时会检查原始记录和系统权限。");
        status.setTextColor(dark ? Color.rgb(218, 234, 226) : Color.rgb(37, 56, 64));
        status.setTextSize(15);
        status.setLineSpacing(dp(4), 1f);
        status.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable card = new GradientDrawable();
        card.setColor(dark ? Color.rgb(27, 42, 39) : Color.WHITE);
        card.setCornerRadius(dp(16));
        status.setBackground(card);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(28);
        content.addView(status, statusParams);

        restore = new Button(this);
        restore.setText("恢复系统手势");
        restore.setAllCaps(false);
        restore.setTextSize(16);
        restore.setTextColor(Color.WHITE);
        restore.setGravity(Gravity.CENTER);
        GradientDrawable button = new GradientDrawable();
        button.setColor(Color.rgb(27, 103, 82));
        button.setCornerRadius(dp(16));
        restore.setBackground(button);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, dp(56));
        buttonParams.topMargin = dp(22);
        content.addView(restore, buttonParams);
        restore.setOnClickListener(v -> doRestore());
        setContentView(scroll);
        restore.setEnabled(false);
        io.execute(() -> {
            LocalRecovery.Status current = new LocalRecovery(getApplicationContext()).inspect();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                switch (current) {
                    case NO_RECORD: status.setText("没有待恢复的接管记录；系统设置未修改。"); break;
                    case PENDING: status.setText("发现未完成的接管记录。点击下方按钮恢复原始设置。"); break;
                    default: status.setText("暂时无法读取恢复记录，请解锁设备并检查存储后重试。");
                }
                restore.setEnabled(true);
            });
        });
    }

    private void doRestore() {
        restore.setEnabled(false);
        status.setText("正在检查并恢复原始设置…");
        io.execute(() -> {
            LocalRecovery.Result result = new LocalRecovery(getApplicationContext()).restore();
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                switch (result) {
                    case RESTORED:
                        status.setText("原始设置已还原。请从左右屏幕边缘滑动，确认系统返回手势可用。");
                        break;
                    case NO_RECORD:
                        status.setText("没有待恢复的接管记录；系统设置未修改。");
                        break;
                    case CONFLICT:
                        status.setText("系统设置已发生变化，冲突值未被覆盖，其余可恢复的一侧已尝试还原。请检查系统导航设置。");
                        break;
                    case NEEDS_PERMISSION:
                        status.setText("缺少恢复系统设置的权限。请重新授权或使用电脑按备份资料恢复。");
                        break;
                    default:
                        status.setText("恢复未完成，原始记录已保留。请检查权限和恢复资料后重试。");
                }
                restore.setEnabled(true);
            });
        });
    }

    @Override protected void onDestroy() {
        io.shutdown();
        super.onDestroy();
    }
}
