package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowInsets;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import io.buildlee.onehand.takeover.TakeoverManager;

import rikka.shizuku.Shizuku;

/** Emulator-only setup while the full compatibility and recovery matrix is incomplete. */
public final class TakeoverActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private TakeoverManager manager;
    private TextView status;
    private CheckBox recoveryConfirmed;
    private Button testBack, begin, confirm, stop;
    private boolean backVerified, waitingBack, waitingPermission;
    private boolean dark;
    private final Shizuku.OnRequestPermissionResultListener permissionResult = (request, result) -> {
        if (request != 77 || !waitingPermission) return;
        waitingPermission = false;
        if (result == PackageManager.PERMISSION_GRANTED) beginTrial();
        else { status.setText("Shizuku 未授权，系统手势未接管。"); refresh(); }
    };

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        int theme = new Prefs(this).getInt(Prefs.THEME, 0);
        dark = theme == 2 || (theme == 0 && (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES);
        int pageColor = dark ? Color.rgb(16, 25, 25) : Color.rgb(246, 248, 249);
        int foreground = dark ? Color.rgb(232, 242, 237) : Color.rgb(30, 45, 50);
        int muted = dark ? Color.rgb(177, 196, 189) : Color.rgb(75, 90, 96);
        getWindow().setStatusBarColor(pageColor); getWindow().setNavigationBarColor(pageColor);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        manager = TakeoverManager.get(this);
        manager.setListener(this::refresh);
        Shizuku.addRequestPermissionResultListener(permissionResult);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(pageColor);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(32), dp(24), dp(36));
        scroll.addView(root);
        TextView title = new TextView(this);
        title.setText("双侧接管 · 模拟器实验"); title.setTextSize(25); title.setTextColor(foreground);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title);
        TextView guide = new TextView(this);
        guide.setText("试用会暂时接管系统左右返回。请先确认可以不用侧边返回，从桌面应用列表打开「恢复系统手势」。\n\n然后测试返回动作，开始后分别从左右边缘向内滑动，30 秒内完成校准。");
        guide.setTextSize(15); guide.setTextColor(muted); guide.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(-1, -2); guideParams.topMargin = dp(16); root.addView(guide, guideParams);
        recoveryConfirmed = new CheckBox(this);
        recoveryConfirmed.setText("我已确认能从桌面打开恢复入口");
        recoveryConfirmed.setTextSize(15); recoveryConfirmed.setTextColor(foreground);
        recoveryConfirmed.setButtonTintList(ColorStateList.valueOf(dark ? Color.rgb(142, 213, 187) : Color.rgb(27, 103, 82)));
        root.addView(recoveryConfirmed);
        testBack = button("测试系统返回动作", root, false);
        testBack.setOnClickListener(v -> testBackAction());
        begin = button("开始 30 秒校准", root, true);
        begin.setOnClickListener(v -> requestStart());
        confirm = button("确认接管", root, true);
        confirm.setOnClickListener(v -> manager.confirm());
        stop = button("停止并恢复系统手势", root, false);
        stop.setOnClickListener(v -> manager.stop());
        status = new TextView(this);
        status.setTextSize(15); status.setTextColor(dark ? Color.rgb(171, 221, 200) : Color.rgb(30, 82, 68)); status.setLineSpacing(dp(4), 1f);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setBackground(shape(dark ? Color.rgb(27, 42, 39) : Color.WHITE,
                dark ? Color.rgb(48, 71, 63) : Color.rgb(225, 232, 230)));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2); statusParams.topMargin = dp(18); root.addView(status, statusParams);
        setContentView(scroll);
        refresh();
    }

    private GradientDrawable shape(int color, int stroke) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(14));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        return background;
    }

    private Button button(String text, LinearLayout root, boolean primary) {
        Button button = new Button(this); button.setAllCaps(false); button.setText(text);
        button.setTextSize(15); button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int active = primary ? Color.rgb(27, 103, 82) : dark ? Color.rgb(27, 42, 39) : Color.WHITE;
        int pressed = primary ? Color.rgb(19, 82, 65) : dark ? Color.rgb(39, 61, 53) : Color.rgb(229, 237, 233);
        int stroke = primary ? 0 : dark ? Color.rgb(69, 100, 87) : Color.rgb(200, 216, 208);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled}, shape(dark ? Color.rgb(38, 48, 45) : Color.rgb(232, 236, 235), 0));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(pressed, stroke));
        states.addState(new int[]{}, shape(active, stroke));
        button.setBackground(states); button.setStateListAnimator(null);
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{dark ? Color.rgb(116, 133, 126) : Color.rgb(155, 164, 161),
                        primary ? Color.WHITE : dark ? Color.rgb(177, 225, 203) : Color.rgb(27, 91, 72)}));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52)); params.topMargin = dp(10);
        root.addView(button, params); return button;
    }

    private boolean emulatorDebug() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && (Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
    }

    private boolean navigationVerified() {
        int resource = getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
        if (resource != 0 && getResources().getInteger(resource) != 2) return false;
        WindowInsets insets = getWindow().getDecorView().getRootWindowInsets();
        if (insets == null || Build.VERSION.SDK_INT < 29) return false;
        android.graphics.Insets gestures = Build.VERSION.SDK_INT >= 30
                ? insets.getInsets(WindowInsets.Type.systemGestures()) : insets.getSystemGestureInsets();
        return gestures.left > 0 && gestures.right > 0;
    }

    private void testBackAction() {
        EdgeService service = EdgeService.connectedService();
        if (service == null) { status.setText("请先启用 OneHandX 无障碍服务。"); return; }
        waitingBack = true;
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) {
            waitingBack = false; status.setText("系统未接受返回动作，请保留共存模式。"); return;
        }
        main.postDelayed(() -> { if (waitingBack) { waitingBack = false; status.setText("未收到返回回调，请保留共存模式。"); } }, 2500);
    }

    private void requestStart() {
        Log.i("OneHandTakeover", "request start recovery=" + recoveryConfirmed.isChecked() + " back=" + backVerified + " navigation=" + navigationVerified());
        if (!emulatorDebug()) { status.setText("当前只允许在调试模拟器中试用。"); return; }
        if (!recoveryConfirmed.isChecked() || !backVerified || !navigationVerified()) {
            status.setText("请确认恢复入口、测试返回，并使用双侧手势导航。"); return;
        }
        if (new Prefs(this).hideNavbar() || NavbarHelper.isHidden(this)) {
            status.setText("请先关闭隐藏导航栏的实验选项，再试用双侧接管。"); return;
        }
        if (EdgeService.connectedService() == null) { status.setText("请先启用 OneHandX 无障碍服务。"); return; }
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11() || Shizuku.getUid() != 2000) {
                status.setText("请在模拟器中启动非 Root 的 Shizuku 服务。"); return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                if (Shizuku.shouldShowRequestPermissionRationale()) { status.setText("请在 Shizuku 中重新允许 OneHandX。"); return; }
                waitingPermission = true; Shizuku.requestPermission(77); return;
            }
            beginTrial();
        } catch (RuntimeException e) { status.setText("Shizuku 不可用，系统手势未接管。"); }
    }

    private void beginTrial() {
        EdgeService service = EdgeService.connectedService();
        if (service == null || !recoveryConfirmed.isChecked() || !backVerified || !navigationVerified()) return;
        manager.begin(service, true, true, true);
        refresh();
    }

    private void refresh() {
        TakeoverManager.Status current = manager.status();
        boolean idle = current == TakeoverManager.Status.IDLE;
        testBack.setEnabled(idle && emulatorDebug()); begin.setEnabled(idle && emulatorDebug());
        recoveryConfirmed.setEnabled(idle); stop.setEnabled(!idle);
        confirm.setEnabled(current == TakeoverManager.Status.TRIAL && manager.leftCalibrated() && manager.rightCalibrated());
        switch (current) {
            case IDLE: status.setText(!manager.lastError().isEmpty() ? manager.lastError() : emulatorDebug() ? "尚未接管。请先完成上面的检查。" : "此实验仅在调试模拟器中开放。"); break;
            case STARTING: status.setText("正在准备恢复能力和左右输入窗口…"); break;
            case TRIAL: status.setText("校准中：左侧 " + (manager.leftCalibrated() ? "已完成" : "待滑动")
                    + " · 右侧 " + (manager.rightCalibrated() ? "已完成" : "待滑动") + "。完成后点击确认。"); break;
            case ACTIVE: status.setText("双侧接管运行中。关闭服务或点击停止会恢复原始系统手势。"); break;
            case PAUSED: status.setText("当前场景已暂停接管，系统手势已归还。条件恢复后会重新检查。"); break;
            case RESTORING: status.setText("正在恢复系统手势…"); break;
            case RECOVERY_REQUIRED: status.setText("自动恢复未完成，请打开桌面的「恢复系统手势」并检查权限。"); break;
        }
    }

    @Override public void onBackPressed() {
        if (waitingBack) { waitingBack = false; backVerified = true; status.setText("系统返回动作已验证。"); Log.i("OneHandTakeover", "system back verified"); return; }
        if (manager.consumeCalibrationBack()) { refresh(); return; }
        if (manager.status() == TakeoverManager.Status.TRIAL) manager.stop();
        super.onBackPressed();
    }

    @Override protected void onPause() {
        if (manager.status() == TakeoverManager.Status.TRIAL) manager.stop();
        super.onPause();
    }

    @Override protected void onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permissionResult);
        manager.setListener(null);
        super.onDestroy();
    }
}
