package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int QUICK_SLOTS = 6;

    private Prefs prefs;
    private LinearLayout root;
    private TextView statusLine;
    private TextView statusAction;
    private boolean firstBuildAnim = true;
    private TextView excludeCountTv;
    private TextView navbarSummaryTv;
    private final Map<String, TextView> handleSummary = new HashMap<>();
    private final Map<Integer, UiKit.SlotRow> qtRows = new HashMap<>();
    private final Map<Integer, UiKit.SlotRow> qsRows = new HashMap<>();

    private ActivityResultLauncher<String> exportLauncher;
    private ActivityResultLauncher<String[]> importLauncher;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> refreshAll();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri == null) return;
                    try {
                        String json = prefs.dumpJson();
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os != null) os.write(json.getBytes(StandardCharsets.UTF_8));
                        }
                        Toast.makeText(this, "已备份 " + prefs.countEntries() + " 项设置", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "备份失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });

        importLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    try {
                        StringBuilder sb = new StringBuilder();
                        try (InputStream is = getContentResolver().openInputStream(uri)) {
                            if (is == null) return;
                            byte[] buf = new byte[65536];
                            int len;
                            while ((len = is.read(buf)) > 0) {
                                sb.append(new String(buf, 0, len, StandardCharsets.UTF_8));
                                if (sb.length() > 2_000_000) throw new IllegalStateException("文件过大");
                            }
                        }
                        int n = prefs.applyJson(sb.toString());
                        buildContent();
                        refreshAll();
                        Toast.makeText(this, "已恢复 " + n + " 项设置", Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(this, "恢复失败：不是有效的 OneHandX 备份文件", Toast.LENGTH_LONG).show();
                    }
                });

        setContentView(R.layout.activity_main);
        UiKit.applyInsets(findViewById(android.R.id.content));
        root = findViewById(R.id.contentRoot);
        findViewById(R.id.btnReset).setOnClickListener(v -> confirmReset());
        prefs.registerListener(prefListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        buildContent();
        refreshAll();
    }

    @Override
    protected void onDestroy() {
        prefs.unregisterListener(prefListener);
        super.onDestroy();
    }

    // ---------------- 状态 ----------------

    private boolean a11yOn() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getResolveInfo() != null && info.getResolveInfo().serviceInfo != null
                    && getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private void refreshAll() {
        refreshStatus();
        refreshSummaries();
    }

    private void refreshStatus() {
        if (statusLine == null) return;
        if (a11yOn()) {
            statusLine.setText("运行中 · 左 / 右手柄已就绪");
            if (statusAction != null) statusAction.setText("已开启");
        } else {
            statusLine.setText("未运行，点按开启");
            if (statusAction != null) statusAction.setText("去开启");
        }
    }

    private void refreshSummaries() {
        for (Map.Entry<String, TextView> e : handleSummary.entrySet()) {
            e.getValue().setText(summaryOf(e.getKey()));
        }
        for (int i = 1; i <= QUICK_SLOTS; i++) {
            UiKit.SlotRow r = qtRows.get(i);
            if (r != null) {
                String slot = prefs.quickToolSlot(i);
                boolean none = GestureAction.NONE.id.equals(prefs.actionId(slot));
                r.value.setText(prefs.displayFor(slot));
                r.value.setTextColor(UiKit.col(this, none ? R.color.text_secondary : R.color.text_primary));
            }
            UiKit.SlotRow q = qsRows.get(i);
            if (q != null) {
                String slot = prefs.quickAppSlot(i);
                boolean none = GestureAction.NONE.id.equals(prefs.actionId(slot));
                q.value.setText(prefs.displayFor(slot));
                q.value.setTextColor(UiKit.col(this, none ? R.color.text_secondary : R.color.text_primary));
            }
        }
        if (excludeCountTv != null) excludeCountTv.setText(countTextExcluded());
        if (navbarSummaryTv != null) navbarSummaryTv.setText(navbarStateText());
    }

    private String summaryOf(String id) {
        String s = "长度 " + prefs.handleLen(id) + "% · 位置 " + prefs.handlePos(id) + "%";
        if (!prefs.handleEnabled(id)) s += " · 已停用";
        return s;
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开无障碍设置，请手动前往：设置 → 辅助功能", Toast.LENGTH_LONG).show();
        }
    }

    private void openWriteSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "请手动前往：应用信息 → 修改系统设置", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认设置")
                .setMessage("将清空全部手柄、手势映射、快速面板与反馈设置，是否继续？")
                .setPositiveButton("恢复", (d, w) -> {
                    prefs.resetAll();
                    buildContent();
                    refreshAll();
                    Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 界面构建 ----------------

    private void buildContent() {
        root.removeAllViews();
        handleSummary.clear();
        qtRows.clear();
        qsRows.clear();
        root.addView(topCard());
        root.addView(UiKit.sectionHeader(this, "手柄"));
        root.addView(handlesCard());
        root.addView(UiKit.sectionHeader(this, "快速面板"));
        root.addView(quickPanelCard());
        root.addView(UiKit.sectionHeader(this, "反馈"));
        root.addView(feedbackCard());
        root.addView(UiKit.sectionHeader(this, "手势设置"));
        root.addView(gesturePrefsCard());
        root.addView(UiKit.sectionHeader(this, "高级"));
        root.addView(advancedCard());
        root.addView(UiKit.sectionHeader(this, "系统增强"));
        root.addView(enhancedCard());
        root.addView(UiKit.sectionHeader(this, "关于"));
        root.addView(aboutCard());
        root.addView(footerNote());
        if (firstBuildAnim) {
            firstBuildAnim = false;
            Motion.enterStagger(root);
        }
    }

    private View topCard() {
        LinearLayout c = UiKit.card(this);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setMinimumHeight(UiKit.dp(this, 58));
        statusRow.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 10));
        statusRow.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText("手势服务");
        t1.setTextSize(16.5f);
        t1.setLetterSpacing(-0.01f);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        texts.addView(t1);
        statusLine = new TextView(this);
        statusLine.setTextSize(13);
        statusLine.setPadding(0, UiKit.dp(this, 3), UiKit.dp(this, 12), 0);
        texts.addView(statusLine);
        statusRow.addView(texts);

        statusAction = new TextView(this);
        statusAction.setTextSize(15);
        statusAction.setTypeface(Typeface.DEFAULT_BOLD);
        statusAction.setTextColor(UiKit.col(this, R.color.accent));
        statusAction.setPadding(UiKit.dp(this, 8), 0, 0, 0);
        statusRow.addView(statusAction);
        statusRow.addView(UiKit.chevron(this));
        statusRow.setOnClickListener(v -> openA11ySettings());
        c.addView(statusRow);

        c.addView(UiKit.switchRow(this, "启用 OneHandX", "总开关；关闭后全部手柄停止工作",
                prefs.masterEnabled(), v -> prefs.setBool(Prefs.MASTER_ENABLED, v)));
        return c;
    }

    private View handlesCard() {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.sliderRow(this, "手柄宽度", 8, 48, prefs.handleWidthDp(), "dp",
                v -> prefs.setInt(Prefs.HANDLE_WIDTH_DP, v)));
        c.addView(UiKit.switchRow(this, "显示指示条", "关闭后手柄不可见，但手势仍然可用",
                prefs.showIndicator(), v -> prefs.setBool(Prefs.SHOW_INDICATOR, v)));
        c.addView(UiKit.clickRow(this, "指示动画", animLabel(), v -> showAnimStyleDialog()));
        c.addView(UiKit.clickRow(this, "指示颜色", Prefs.COLOR_NAMES[prefs.animColorIndex()],
                v -> showColorDialog(Prefs.ANIM_COLOR, "指示颜色")));
        c.addView(UiKit.clickRow(this, "手柄颜色", Prefs.COLOR_NAMES[prefs.handleColorIndex()],
                v -> showColorDialog(Prefs.HANDLE_COLOR, "手柄颜色")));

        for (String side : Prefs.SIDES) {
            final String fs = side;
            c.addView(UiKit.subLabel(this, side.equals("L") ? "左手柄" : "右手柄"));
            c.addView(UiKit.switchRow(this, "启用" + (side.equals("L") ? "左手柄" : "右手柄"), null,
                    prefs.sideEnabled(side), v -> prefs.setSideEnabled(fs, v)));
            int count = prefs.handleCount(side);
            for (int i = 1; i <= count; i++) {
                c.addView(handleRow(side, i));
            }
            if (count < Prefs.MAX_HANDLES_PER_SIDE) {
                c.addView(UiKit.clickRow(this, "＋ 添加手柄", "每侧最多 " + Prefs.MAX_HANDLES_PER_SIDE + " 个，各自独立映射",
                        v -> {
                            prefs.setHandleCount(fs, prefs.handleCount(fs) + 1);
                            buildContent();
                            refreshAll();
                        }));
            }
        }
        return c;
    }

    private View handleRow(String side, int index) {
        final String id = Prefs.handleId(side, index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 58));
        row.setPadding(0, UiKit.dp(this, 11), 0, UiKit.dp(this, 11));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText((side.equals("L") ? "左手柄 " : "右手柄 ") + index);
        t1.setTextSize(16.5f);
        t1.setLetterSpacing(-0.01f);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        texts.addView(t1);
        TextView t2 = new TextView(this);
        t2.setTextSize(13);
        t2.setTextColor(UiKit.col(this, R.color.text_secondary));
        t2.setPadding(0, UiKit.dp(this, 3), 0, 0);
        t2.setText(summaryOf(id));
        handleSummary.put(id, t2);
        texts.addView(t2);
        row.addView(texts);

        SoftSwitch sw = new SoftSwitch(this);
        sw.setChecked(prefs.handleEnabled(id));
        sw.setOnCheckedChangeListener((s, v) -> prefs.setHandleEnabled(id, v));
        row.addView(sw);

        row.setOnClickListener(v -> {
            Intent i = new Intent(this, HandleEditActivity.class);
            i.putExtra(HandleEditActivity.EXTRA_HANDLE_ID, id);
            startActivity(i);
            overridePendingTransition(R.anim.push_in, R.anim.fade_out_slight);
        });
        return row;
    }

    private View quickPanelCard() {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.subLabel(this, "快速工具按钮（手势动作「快速工具」打开）"));
        for (int i = 1; i <= QUICK_SLOTS; i++) {
            final int idx = i;
            UiKit.SlotRow r = UiKit.slotRow(this, "按钮 " + i, 0, prefs.displayFor(prefs.quickToolSlot(i)), false,
                    v -> ActionDialog.show(this, "快速工具 · 按钮 " + idx,
                            prefs.actionId(prefs.quickToolSlot(idx)),
                            GestureAction.pickerWithoutPanels(),
                            action -> {
                                String s = prefs.quickToolSlot(idx);
                                prefs.setActionId(s, action.id);
                                if (action.needsApp) {
                                    Intent it = new Intent(this, AppPickerActivity.class);
                                    it.putExtra(AppPickerActivity.EXTRA_SLOT, s);
                                    it.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, "快速工具 · 按钮 " + idx);
                                    startActivity(it);
                                    overridePendingTransition(R.anim.push_in, R.anim.fade_out_slight);
                                } else {
                                    refreshSummaries();
                                }
                            }));
            qtRows.put(i, r);
            c.addView(r.row);
        }
        c.addView(UiKit.subLabel(this, "快速启动器应用（手势动作「快速启动器」打开）"));
        for (int i = 1; i <= QUICK_SLOTS; i++) {
            final int idx = i;
            UiKit.SlotRow r = UiKit.slotRow(this, "应用 " + i, 0, prefs.displayFor(prefs.quickAppSlot(i)), false,
                    v -> {
                        Intent it = new Intent(this, AppPickerActivity.class);
                        it.putExtra(AppPickerActivity.EXTRA_SLOT, prefs.quickAppSlot(idx));
                        it.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, "快速启动器 · 应用 " + idx);
                        startActivity(it);
                        overridePendingTransition(R.anim.push_in, R.anim.fade_out_slight);
                    });
            qsRows.put(i, r);
            c.addView(r.row);
        }
        return c;
    }

    private View feedbackCard() {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.switchRow(this, "震动反馈", "触发动作时轻微震动",
                prefs.vibrate(), v -> prefs.setBool(Prefs.VIBRATE, v)));
        c.addView(UiKit.switchRow(this, "长滑震动提示", "滑动超过长滑距离时轻震一次",
                prefs.longHint(), v -> prefs.setBool(Prefs.LONG_HINT, v)));
        return c;
    }

    private View advancedCard() {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.sliderRow(this, "触发距离", 12, 60, prefs.shortTriggerDp(), "dp",
                v -> prefs.setInt(Prefs.SHORT_TRIGGER_DP, v)));
        c.addView(UiKit.sliderRow(this, "长滑距离", 100, 420, prefs.longTriggerDp(), "dp",
                v -> prefs.setInt(Prefs.LONG_TRIGGER_DP, v)));
        c.addView(UiKit.sliderRow(this, "滑动保持时长", 200, 800, prefs.holdMs(), "ms",
                v -> prefs.setInt(Prefs.HOLD_MS, v)));
        c.addView(UiKit.switchRow(this, "横屏时启用手势", null,
                prefs.landscapeEnabled(), v -> prefs.setBool(Prefs.LANDSCAPE_ENABLED, v)));

        LinearLayout exRow = UiKit.clickRow(this, "在下列应用中停用", " ",
                v -> showExcludeDialog());
        LinearLayout exTexts = (LinearLayout) exRow.getChildAt(0);
        excludeCountTv = (TextView) exTexts.getChildAt(1);
        excludeCountTv.setText(countTextExcluded());
        c.addView(exRow);

        c.addView(UiKit.clickRow(this, "系统权限（屏幕超时 / 旋转 / 亮度）",
                "需要授予「修改系统设置」", v -> openWriteSettings()));
        return c;
    }

    private View enhancedCard() {
        LinearLayout c = UiKit.card(this);
        LinearLayout navRow = UiKit.clickRow(this, "隐藏系统导航栏（实验）", navbarStateText(),
                v -> showNavbarDialog());
        LinearLayout navTexts = (LinearLayout) navRow.getChildAt(0);
        navbarSummaryTv = (TextView) navTexts.getChildAt(1);
        c.addView(navRow);
        c.addView(UiKit.clickRow(this, "备份设置到文件", "导出手柄、手势映射与全部配置（JSON）",
                v -> {
                    try {
                        exportLauncher.launch("onehand-backup.json");
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开保存对话框", Toast.LENGTH_SHORT).show();
                    }
                }));
        c.addView(UiKit.clickRow(this, "从文件恢复设置", "从 JSON 备份恢复全部配置（覆盖当前设置）",
                v -> {
                    try {
                        importLauncher.launch(new String[]{"*/*"});
                    } catch (Exception e) {
                        Toast.makeText(this, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
                    }
                }));
        return c;
    }

    private String navbarStateText() {
        if (!NavbarHelper.granted(this)) return "未授权 · 需 ADB 授权";
        return NavbarHelper.isHidden(this) ? "已隐藏（若无效果说明系统不支持）" : "未隐藏";
    }

    private void showNavbarDialog() {
        boolean granted = NavbarHelper.granted(this);
        boolean hidden = NavbarHelper.isHidden(this);
        String msg = "机制：写入系统设置 policy_control（immersive.navigation），需一次 ADB 授权：\n\n"
                + NavbarHelper.grantCommand(this)
                + "\n\n当前状态：" + (granted ? "已授权" : "未授权")
                + " · " + (hidden ? "导航栏隐藏中" : "导航栏显示中")
                + "\n\n注意：该机制为历史方案，部分新版 Android（13+）可能已忽略；"
                + "若设置后无效果，请改用系统「手势导航」。关闭本功能或停用服务时会自动恢复导航栏；"
                + "卸载应用前请先点「恢复显示」。";
        new AlertDialog.Builder(this)
                .setTitle("隐藏系统导航栏")
                .setMessage(msg)
                .setPositiveButton("隐藏", (d, w) -> applyNavbar(true))
                .setNegativeButton("恢复显示", (d, w) -> applyNavbar(false))
                .setNeutralButton("复制 ADB 命令", (d, w) -> copyToClipboard(NavbarHelper.grantCommand(this)))
                .show();
    }

    private void applyNavbar(boolean hide) {
        if (!NavbarHelper.granted(this)) {
            Toast.makeText(this, "请先执行 ADB 授权命令（可点「复制 ADB 命令」）", Toast.LENGTH_LONG).show();
            return;
        }
        boolean ok = NavbarHelper.setHidden(this, hide);
        prefs.setBool(Prefs.HIDE_NAVBAR, hide && ok);
        if (ok && hide) {
            Toast.makeText(this, "已设置隐藏（若导航栏未消失说明系统不支持该机制）", Toast.LENGTH_LONG).show();
        } else if (ok) {
            Toast.makeText(this, "已恢复导航栏", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "设置失败", Toast.LENGTH_SHORT).show();
        }
        refreshSummaries();
    }

    private void copyToClipboard(String s) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("adb", s));
        Toast.makeText(this, "已复制命令", Toast.LENGTH_SHORT).show();
    }

    private View gesturePrefsCard() {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.switchRow(this, "快捷操作（短滑）", "越过触发距离立即执行，不用等松手",
                prefs.instantShort(), v -> prefs.setBool(Prefs.INSTANT_SHORT, v)));
        c.addView(UiKit.switchRow(this, "快捷操作（长滑）", "达到长滑距离即执行（可配合按住时长）",
                prefs.instantLong(), v -> prefs.setBool(Prefs.INSTANT_LONG, v)));
        c.addView(UiKit.sliderRow(this, "手势角度", 15, 60, prefs.angleDeg(), "°",
                v -> prefs.setInt(Prefs.ANGLE_DEG, v)));
        c.addView(UiKit.switchRow(this, "启用长距离滑动", "关闭后只有短距离滑动动作",
                prefs.longEnabled(), v -> prefs.setBool(Prefs.LONG_ENABLED, v)));
        c.addView(UiKit.sliderRow(this, "长滑按住持续时间", 0, 1000, prefs.longHoldMs(), "ms",
                v -> prefs.setInt(Prefs.LONG_HOLD_MS, v)));
        c.addView(UiKit.switchRow(this, "快速振动", "识别到手势立即振动（不等松手）",
                prefs.quickVibrate(), v -> prefs.setBool(Prefs.QUICK_VIBRATE, v)));
        c.addView(UiKit.sliderRow(this, "振动强度", 40, 255, prefs.vibrateAmp(), "",
                v -> prefs.setInt(Prefs.VIBRATE_AMP, v)));
        c.addView(UiKit.clickRow(this, "适配键盘", keyboardModeLabel(), v -> showKeyboardModeDialog()));
        c.addView(UiKit.switchRow(this, "主屏幕时隐藏触钮", "回到桌面后自动隐藏手柄",
                prefs.hideOnHome(), v -> prefs.setBool(Prefs.HIDE_ON_HOME, v)));
        c.addView(UiKit.switchRow(this, "对齐触钮", "左右手柄保持相同长度与位置",
                prefs.syncHandles(), v -> prefs.setBool(Prefs.SYNC_HANDLES, v)));
        return c;
    }

    private String animLabel() {
        String[] names = {"指示条", "箭头", "曲线"};
        return names[prefs.animStyle()];
    }

    private String keyboardModeLabel() {
        String m = prefs.keyboardMode();
        if (Prefs.KB_HIDE.equals(m)) return "隐藏手柄";
        if (Prefs.KB_OFF.equals(m)) return "不处理";
        return "移到键盘上方";
    }

    private void showKeyboardModeDialog() {
        String[] labels = {"移到键盘上方（推荐）", "隐藏手柄", "不处理"};
        String[] values = {Prefs.KB_ABOVE, Prefs.KB_HIDE, Prefs.KB_OFF};
        int cur = 0;
        String mode = prefs.keyboardMode();
        for (int i = 0; i < values.length; i++) if (values[i].equals(mode)) cur = i;
        new AlertDialog.Builder(this)
                .setTitle("适配键盘")
                .setSingleChoiceItems(labels, cur, (d, which) -> {
                    prefs.setStr(Prefs.KEYBOARD_MODE, values[which]);
                    d.dismiss();
                    buildContent();
                })
                .show();
    }

    private void showAnimStyleDialog() {
        String[] labels = {"指示条", "箭头", "曲线"};
        new AlertDialog.Builder(this)
                .setTitle("指示动画")
                .setSingleChoiceItems(labels, prefs.animStyle(), (d, which) -> {
                    prefs.setInt(Prefs.ANIM_STYLE, which);
                    d.dismiss();
                    buildContent();
                })
                .show();
    }

    private void showColorDialog(String key, String title) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(Prefs.COLOR_NAMES, prefs.getInt(key, 0), (d, which) -> {
                    prefs.setInt(key, which);
                    d.dismiss();
                    buildContent();
                })
                .show();
    }

    private String countTextExcluded() {
        int n = prefs.excludedSet().size();
        return n == 0 ? "未选择" : n + " 个应用";
    }

    private void showExcludeDialog() {
        Toast.makeText(this, "正在载入应用列表…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            List<AppPickerActivity.Entry> apps = AppPickerActivity.loadEntries(this);
            runOnUiThread(() -> {
                if (isFinishing() || apps.isEmpty()) return;
                String[] names = new String[apps.size()];
                final boolean[] checked = new boolean[apps.size()];
                Set<String> ex = prefs.excludedSet();
                for (int i = 0; i < apps.size(); i++) {
                    names[i] = apps.get(i).label;
                    checked[i] = ex.contains(apps.get(i).pkg);
                }
                new AlertDialog.Builder(this)
                        .setTitle("在下列应用中停用")
                        .setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked)
                        .setPositiveButton("保存", (d, w) -> {
                            Set<String> sel = new HashSet<>();
                            for (int i = 0; i < apps.size(); i++) {
                                if (checked[i]) sel.add(apps.get(i).pkg);
                            }
                            prefs.setExcludedApps(sel);
                            if (excludeCountTv != null) excludeCountTv.setText(countTextExcluded());
                        })
                        .setNegativeButton("取消", null)
                        .show();
            });
        }).start();
    }

    private View aboutCard() {
        LinearLayout c = UiKit.card(this);
        c.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 14), UiKit.dp(this, 16), UiKit.dp(this, 14));
        TextView t1 = new TextView(this);
        t1.setText("OneHandX v0.5.1");
        t1.setTextSize(14);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        TextView t2 = new TextView(this);
        t2.setText("单手操作手势（Samsung One Hand Operation+ 核心功能复刻；参考 Fluid Navigation Gestures / Akis Gesture / EdgeGesture 等开源项目）\n"
                + "用法：从屏幕左/右侧边缘向内滑动 → 直滑 / 斜上 / 斜下；短滑触发短滑动作，拖得更远触发长滑动作；滑入后停住不动可触发「滑动保持」动作；按住手柄不动可触发「长按」动作。\n"
                + "每侧最多 3 个手柄，可分别设置外观与手势映射。\n"
                + "v0.5：界面按 ColorOS 17 真机采样重绘，Apple 式细节、优雅动效与高刷适配。");
        t2.setTextSize(13);
        t2.setTextColor(UiKit.col(this, R.color.text_secondary));
        t2.setPadding(0, UiKit.dp(this, 6), 0, 0);
        t2.setLineSpacing(UiKit.dp(this, 3), 1f);
        c.addView(t1);
        c.addView(t2);
        return c;
    }

    private TextView footerNote() {
        TextView t = new TextView(this);
        t.setText("提示：系统手势导航的「返回」也使用屏幕边缘，两者可能相互抢占。\n"
                + "「滑动保持」「长按」需在手柄编辑页配置对应动作后才会生效；「滑动保持」的等待时长可在「高级」中调整。\n"
                + "手柄默认内移 26dp 以避开系统返回手势区（可在手柄编辑页调整“距屏幕边缘”）；如仍不易触发，可改用三键导航。\n"
                + "「隐藏系统导航栏」为实验功能（旧版 Android / 部分厂商有效），需要 ADB 授权；关闭开关或停用服务会自动恢复。\n"
                + "全部数据仅保存在本机，应用不需要网络权限。");
        t.setTextSize(12);
        t.setTextColor(UiKit.col(this, R.color.text_secondary));
        t.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 20), UiKit.dp(this, 8), 0);
        t.setLineSpacing(UiKit.dp(this, 3), 1f);
        return t;
    }
}
