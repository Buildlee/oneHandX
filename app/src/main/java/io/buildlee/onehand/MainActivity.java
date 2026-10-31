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
    private final android.os.Handler statusHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() { refreshStatus(); statusHandler.postDelayed(this, 1000); }
    };
    private String page = "手势";
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
        if (savedInstanceState != null) page = savedInstanceState.getString("page", "手势");

        exportLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                uri -> {
                    if (uri == null) return;
                    try {
                        String json = prefs.dumpJson();
                        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                            if (os == null) throw new java.io.IOException("无法写入所选文件");
                            os.write(json.getBytes(StandardCharsets.UTF_8));
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
                            java.io.Reader reader = new java.io.InputStreamReader(is, StandardCharsets.UTF_8);
                            char[] buf = new char[8192];
                            int len;
                            while ((len = reader.read(buf)) > 0) {
                                sb.append(buf, 0, len);
                                if (sb.length() > 2_000_000) throw new IllegalStateException("文件过大");
                            }
                        }
                        String json = sb.toString();
                        String preview = prefs.inspectJson(json);
                        new AlertDialog.Builder(this).setTitle("恢复预览").setMessage(preview)
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确认恢复", (dialog, which) -> {
                                    try {
                                        int n = prefs.applyJson(json);
                                        int theme = prefs.getInt(Prefs.THEME, 0);
                                        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(theme == 1
                                                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO : theme == 2
                                                ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                                        buildContent(); refreshAll();
                                        Toast.makeText(this, "已恢复 " + n + " 项设置", Toast.LENGTH_SHORT).show();
                                    } catch (Exception e) { Toast.makeText(this, "恢复失败，当前配置未更改", Toast.LENGTH_LONG).show(); }
                                }).show();
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
        statusHandler.removeCallbacks(statusRefresh);
        statusHandler.postDelayed(statusRefresh, 1000);
    }

    @Override protected void onPause() {
        statusHandler.removeCallbacks(statusRefresh);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        prefs.unregisterListener(prefListener);
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("page", page);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (!page.equals("手势") && !page.equals("面板") && !page.equals("设置")) navigate(page.startsWith("快速") ? "面板" : "设置");
        else super.onBackPressed();
    }

    private void navigate(String next) {
        if (page.equals(next)) return;
        boolean peer = (page.equals("手势") || page.equals("面板") || page.equals("设置"))
                && (next.equals("手势") || next.equals("面板") || next.equals("设置"));
        int direction = peer ? 0 : (next.equals("设置") || next.equals("面板") ? -1 : 1);
        page = next;
        buildContent(); refreshAll();
        ((android.widget.ScrollView) findViewById(R.id.scroll)).scrollTo(0, 0);
        Motion.contentIn(root, direction);
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
        if (prefs.handlesSafetyPaused()) {
            statusLine.setText("已安全暂停 · 在系统手势共存中选择继续方式");
            if (statusAction != null) statusAction.setText("检查");
        } else if (a11yOn() && prefs.masterEnabled() && EdgeService.connectedService() != null) {
            statusLine.setText("服务已连接 · 从已启用的手柄向内滑动");
            if (statusAction != null) statusAction.setText("已开启");
        } else if (a11yOn() && prefs.masterEnabled()) {
            statusLine.setText("已授权，服务尚未连接 · 可先编辑配置");
            if (statusAction != null) statusAction.setText("检查服务");
        } else if (a11yOn()) {
            statusLine.setText("已暂停 · 开启下方总开关即可恢复");
            if (statusAction != null) statusAction.setText("已授权");
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
        if (id.startsWith("G")) return groupSummary(Integer.parseInt(id.substring(1)));
        boolean left = id.startsWith("L");
        String s = (left ? "↗  " : "↖  ") + prefs.displayFor(Prefs.slot(id, "S", "U"))
                + "\n" + (left ? "→  " : "←  ") + prefs.displayFor(Prefs.slot(id, "S", "H"))
                + "\n" + (left ? "↘  " : "↙  ") + prefs.displayFor(Prefs.slot(id, "S", "D"));
        if (!prefs.handleEnabled(id)) s += " · 已停用";
        return s;
    }

    private void openA11ySettings() {
        if (!a11yOn()) {
            new AlertDialog.Builder(this).setTitle("开启手势服务")
                    .setMessage("OneHandX 使用无障碍服务接收边缘手势、执行返回等操作，并识别前台应用以应用例外规则。应用无网络权限，配置与观察到的应用历史仅保存在本机。\n\n下一步会打开系统设置，请自行选择 OneHandX 并授权；拒绝不影响查看和编辑配置。")
                    .setNegativeButton("稍后", null).setPositiveButton("前往系统设置", (d, w) -> launchA11ySettings()).show();
        } else launchA11ySettings();
    }

    private void launchA11ySettings() {
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
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
                    buildContent();
                    refreshAll();
                    Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 界面构建 ----------------

    private void buildContent() {
        Motion.reset(root);
        root.removeAllViews();
        handleSummary.clear();
        qtRows.clear();
        qsRows.clear();
        statusLine = null; statusAction = null; excludeCountTv = null; navbarSummaryTv = null;
        boolean topLevel = page.equals("手势") || page.equals("面板") || page.equals("设置");
        ((TextView) findViewById(R.id.toolbarTitle)).setText(page.equals("手势") ? "OneHandX" : topLevel ? page : "‹ " + page);
        findViewById(R.id.toolbarTitle).setOnClickListener(topLevel ? null : v -> navigate(page.startsWith("快速") ? "面板" : "设置"));
        findViewById(R.id.btnReset).setVisibility(page.equals("设置") ? View.VISIBLE : View.GONE);
        LinearLayout nav = findViewById(R.id.bottomNav);
        nav.removeAllViews();
        for (String tab : new String[]{"手势", "面板", "设置"}) {
            TextView button = new TextView(this);
            button.setText(tab); button.setTextSize(15); button.setGravity(Gravity.CENTER);
            button.setMinHeight(UiKit.dp(this, 52));
            boolean selected = tab.equals(page);
            button.setSelected(selected);
            button.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            button.setTextColor(UiKit.col(this, selected ? R.color.accent : R.color.text_secondary));
            button.setBackgroundResource(R.drawable.bg_row);
            button.setOnClickListener(v -> navigate(tab));
            nav.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        switch (page) {
            case "手势":
                root.addView(topCard());
                root.addView(UiKit.sectionHeader(this, "初始设置"));
                root.addView(setupCard());
                root.addView(UiKit.sectionHeader(this, "我的手势"));
                root.addView(handlesCard());
                LinearLayout tryCard = UiKit.card(this);
                tryCard.addView(UiKit.clickRow(this, "试用手势", "练习当前手柄动作，不执行系统操作", v -> {
                    Intent intent = new Intent(this, HandleEditActivity.class);
                    intent.putExtra(HandleEditActivity.EXTRA_HANDLE_ID, prefs.sideEnabled("L") ? "L1" : "R1");
                    startActivity(intent); Motion.openActivity(this);
                }));
                tryCard.addView(UiKit.clickRow(this, "避免与系统返回冲突", "默认内移 26dp，可按手机调整", v -> navigate("系统手势共存")));
                root.addView(tryCard);
                break;
            case "面板":
                LinearLayout panels = UiKit.card(this);
                panels.addView(UiKit.subLabel(this, "把面板绑定到手势，即可随时从边缘唤出。"));
                panels.addView(UiKit.clickRow(this, "快速工具", "配置亮度调节与常用动作按钮", v -> navigate("快速工具")));
                panels.addView(UiKit.clickRow(this, "快速启动器", "固定常用应用，滑动后点按打开", v -> navigate("快速启动器")));
                panels.addView(UiKit.clickRow(this, "虚拟触控板", "移动指针、点按与长按；在手柄中绑定动作", v -> panelHelp("虚拟触控板", "在任意手柄中选择「虚拟触控板」动作。唤出后滑动移动指针，点按点击，按住执行长按。")));
                panels.addView(UiKit.clickRow(this, "浮动导航", "返回、主屏幕、最近任务", v -> panelHelp("浮动导航", "在手柄中绑定「浮动导航按钮」，执行后显示三键浮层。应用不会自动更改系统导航方式。")));
                panels.addView(UiKit.clickRow(this, "应用历史", "仅包含服务观察到的应用", v -> panelHelp("应用历史", "在手柄中绑定「任务切换器」。列表来自本服务观察到的应用，不是完整的系统最近任务列表。")));
                root.addView(panels);
                break;
            case "设置":
                LinearLayout settings = UiKit.card(this);
                settings.addView(UiKit.clickRow(this, "外观与反馈", Prefs.FEEDBACK_NAMES[prefs.feedbackStyle()] + " · 配色与透明度", v -> openAppearance()));
                settings.addView(UiKit.clickRow(this, "手势识别", "距离、角度、长滑与振动", v -> navigate("手势识别")));
                settings.addView(UiKit.clickRow(this, "使用场景", "键盘、横屏与应用例外", v -> navigate("使用场景")));
                settings.addView(UiKit.clickRow(this, "系统手势共存", "边距调节与兼容性说明", v -> navigate("系统手势共存")));
                if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                        && (android.os.Build.HARDWARE.contains("ranchu") || android.os.Build.HARDWARE.contains("goldfish"))) {
                    settings.addView(UiKit.clickRow(this, "双侧接管（模拟器实验）", "校准、试用与恢复", v -> startActivity(new Intent(this, TakeoverActivity.class))));
                }
                settings.addView(UiKit.clickRow(this, "恢复系统手势", "检查并还原接管前的系统设置", v -> startActivity(new Intent(this, RecoveryActivity.class))));
                settings.addView(UiKit.clickRow(this, "备份与高级设置", "导出、恢复与实验功能", v -> navigate("备份与高级设置")));
                root.addView(settings);
                root.addView(aboutCard());
                break;
            case "手势识别":
                root.addView(feedbackCard()); root.addView(gesturePrefsCard());
                root.addView(advancedCard()); break;
            case "使用场景":
                root.addView(sceneCard()); break;
            case "系统手势共存":
                LinearLayout conflict = UiKit.card(this);
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    conflict.addView(UiKit.switchRow(this, "手柄优先（实验）", "手柄贴边，范围外保留系统返回", prefs.handlePriorityEnabled(), enabled -> {
                        if (!prefs.setHandlePriorityEnabled(enabled)) Toast.makeText(this, "暂时无法解除安全暂停，请稍后重试", Toast.LENGTH_LONG).show();
                        buildContent(); refreshAll();
                    }));
                    conflict.addView(UiKit.subLabel(this, prefs.handlesSafetyPaused()
                            ? "全部手柄已安全暂停，原配置仍保留。可以重新开启实验，或选择下方的内移共存。"
                            : prefs.handlePriorityEnabled()
                            ? "每侧最多 200dp，默认优先主手柄。点按首页手柄，在「位置与外观」中调整接管高度和位置；其他手柄可选择共享额度或继续内移共存。"
                            : "实验模式不需要 Shizuku，不修改系统导航设置。启用后请验证手柄内的三方向滑动；兼容性依设备而异。"));
                }
                if (prefs.handlesSafetyPaused()) {
                    conflict.addView(UiKit.clickRow(this, "使用内移共存", "恢复原有长度和边距", v -> {
                        prefs.setHandlePriorityEnabled(false); buildContent(); refreshAll();
                    }));
                } else if (!prefs.handlePriorityEnabled()) {
                    conflict.addView(UiKit.subLabel(this, "最外侧留给系统返回，从内移后的手柄起滑。先在此调整，再回到手势页试用。不同手机的热区宽度不同，26dp 是起始建议。"));
                    conflict.addView(UiKit.sliderRow(this, "距屏幕边缘", 4, 80, prefs.handleMarginDp(), "dp", prefs::setHandleMarginDp));
                }
                conflict.addView(UiKit.subLabel(this, "此模式仅申请手柄覆盖的区域，不接管整条屏幕侧边。若设备不接受申请，可关闭实验模式，继续使用内移共存。"));
                root.addView(conflict); break;
            case "备份与高级设置": root.addView(enhancedCard()); break;
            case "快速工具": case "快速启动器": root.addView(quickPanelCard()); break;
        }
    }

    private void openAppearance() { startActivity(new Intent(this, AppearanceActivity.class)); Motion.openActivity(this); }
    private void panelHelp(String title, String message) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("知道了", null).show();
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
        statusLine.setTextColor(UiKit.col(this, R.color.text_secondary));
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
        statusRow.setOnClickListener(v -> {
            if (prefs.handlesSafetyPaused()) navigate("系统手势共存"); else openA11ySettings();
        });
        c.addView(statusRow);

        c.addView(UiKit.switchRow(this, "启用 OneHandX",
                a11yOn() ? "总开关；关闭后全部手柄停止工作" : "总开关已开，但需先开启无障碍服务才会生效",
                prefs.masterEnabled(), v -> prefs.setBool(Prefs.MASTER_ENABLED, v)));
        return c;
    }


    /** 「初始设置」清单：参考 gulugulu 的首页结构，把必要授权集中在一张卡里。 */
    private View setupCard() {
        LinearLayout c = UiKit.card(this);
        boolean a11y = a11yOn();
        c.addView(UiKit.clickRow(this, "无障碍服务",
                a11y ? "已开启 · 手势服务运行中" : "未开启 · 点按前往系统设置",
                v -> openA11ySettings()));
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        boolean ignoring = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        c.addView(UiKit.clickRow(this, "忽略电池优化",
                ignoring ? "已允许 · 后台更稳定" : "未允许 · 系统可能暂停手势服务",
                v -> requestIgnoreBattery()));
        c.addView(UiKit.clickRow(this, "自启动 / 后台运行",
                "未设置 · 点按前往系统设置，允许后台运行",
                v -> openAppDetails()));
        return c;
    }

    private void requestIgnoreBattery() {
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "无法打开电池优化设置", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开应用设置", Toast.LENGTH_SHORT).show();
        }
    }

    private View handlesCard() {
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        final int count = prefs.groupCount();

        if (Prefs.hasUndo()) {
            LinearLayout undo = UiKit.clickRow(this, "已删除「" + Prefs.undoLabel() + "」", "点按撤销（30 秒内有效）",
                    v -> { prefs.performUndo(); rebuildHandles(); });
            LinearLayout ut = (LinearLayout) undo.getChildAt(0);
            ((TextView) ut.getChildAt(0)).setTextColor(UiKit.col(this, R.color.accent));
            group.addView(undo);
            undo.postDelayed(() -> { Prefs.clearUndo(); rebuildHandles(); }, 30000L);
        }

        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.subLabel(this, "每组包含左、右各一个手柄；删除会同时移除两侧。"));
        for (int i = 1; i <= count; i++) c.addView(groupRow(i));
        if (count < Prefs.MAX_HANDLES_PER_SIDE) {
            c.addView(UiKit.clickRow(this, "＋ 添加手柄组", "左右各新增一个手柄，映射默认相同", v -> {
                prefs.addGroup();
                rebuildHandles();
            }));
        }
        if (count <= 1) c.addView(UiKit.subLabel(this, "至少保留 1 组手柄。"));
        group.addView(c);

        LinearLayout s = UiKit.card(this);
        for (String side : Prefs.SIDES) {
            final String fs = side;
            s.addView(UiKit.switchRow(this, "启用" + (side.equals("L") ? "左手柄" : "右手柄"),
                    "关闭后该侧全部手柄停用，组与配置仍保留",
                    prefs.sideEnabled(side), v -> { prefs.setSideEnabled(fs, v); refreshAll(); }));
        }
        group.addView(s);
        return group;
    }

    private void rebuildHandles() {
        buildContent();
        refreshAll();
    }

    private String sideState(String side, int index) {
        String id = Prefs.handleId(side, index);
        if (!prefs.sideEnabled(side) || !prefs.handleEnabled(id)) return "已停用";
        return "长 " + prefs.handleLen(id) + "% · 位 " + prefs.handlePos(id) + "%";
    }

    private String groupSummary(int index) {
        return "左 · " + sideState("L", index) + " ｜ 右 · " + sideState("R", index);
    }

    private View groupRow(final int index) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 64));
        row.setPadding(0, UiKit.dp(this, 10), 0, UiKit.dp(this, 10));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText("手柄组 " + index);
        t1.setTextSize(16.5f);
        t1.setLetterSpacing(-0.01f);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        texts.addView(t1);
        TextView t2 = new TextView(this);
        t2.setTextSize(13);
        t2.setLineSpacing(UiKit.dp(this, 3), 1f);
        t2.setPadding(0, UiKit.dp(this, 3), UiKit.dp(this, 8), 0);
        t2.setTextColor(UiKit.col(this, R.color.text_secondary));
        t2.setText(groupSummary(index));
        handleSummary.put("G" + index, t2);
        texts.addView(t2);
        row.addView(texts);

        TextView more = new TextView(this);
        more.setText("⋯");
        more.setTextSize(20);
        more.setGravity(Gravity.CENTER);
        more.setMinWidth(UiKit.dp(this, 44));
        more.setMinHeight(UiKit.dp(this, 44));
        more.setTextColor(UiKit.col(this, R.color.text_secondary));
        more.setOnClickListener(v -> showGroupMenu(index));
        more.setContentDescription("手柄组 " + index + " 的更多操作");
        row.addView(more);
        row.setOnClickListener(v -> openHandle(Prefs.handleId("L", index)));
        return row;
    }

    private void openHandle(String id) {
        Intent i = new Intent(this, HandleEditActivity.class);
        i.putExtra(HandleEditActivity.EXTRA_HANDLE_ID, id);
        startActivity(i);
        Motion.openActivity(this);
    }

    private void showGroupMenu(final int index) {
        boolean both = prefs.bothSidesEnabled(index);
        String delete = both ? "删除手柄组（左右同时删除）" : "删除手柄组";
        new AlertDialog.Builder(this).setTitle("手柄组 " + index)
                .setItems(new String[]{"编辑左手柄", "编辑右手柄", "复制左手柄到右手柄", "复制右手柄到左手柄", delete},
                        (d, which) -> {
                            switch (which) {
                                case 0: openHandle(Prefs.handleId("L", index)); break;
                                case 1: openHandle(Prefs.handleId("R", index)); break;
                                case 2: prefs.copySideToOther(index, "L"); rebuildHandles();
                                    Toast.makeText(this, "已复制到右手柄", Toast.LENGTH_SHORT).show(); break;
                                case 3: prefs.copySideToOther(index, "R"); rebuildHandles();
                                    Toast.makeText(this, "已复制到左手柄", Toast.LENGTH_SHORT).show(); break;
                                default: confirmDeleteGroup(index); break;
                            }
                        }).show();
    }

    private void confirmDeleteGroup(final int index) {
        if (prefs.groupCount() <= 1) {
            Toast.makeText(this, "至少保留 1 个手柄组", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean both = prefs.bothSidesEnabled(index);
        new AlertDialog.Builder(this)
                .setTitle("删除手柄组 " + index)
                .setMessage(both ? "将同时删除左手柄和右手柄，是否继续？" : "将删除该手柄，是否继续？")
                .setPositiveButton("删除", (d, w) -> {
                    prefs.beginUndo("手柄组 " + index);
                    prefs.deleteGroup(index);
                    rebuildHandles();
                    Toast.makeText(this, "已删除，可撤销", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private View quickPanelCard() {
        LinearLayout c = UiKit.card(this);
        if (page.equals("快速工具")) {
        c.addView(UiKit.subLabel(this, "快速工具按钮（手势动作「快速工具」打开）"));
        for (int i = 1; i <= QUICK_SLOTS; i++) {
            final int idx = i;
            UiKit.SlotRow r = UiKit.slotRow(this, "按钮 " + i, 0, prefs.displayFor(prefs.quickToolSlot(i)), false,
                    v -> ActionDialog.show(this, "快速工具 · 按钮 " + idx,
                            prefs.actionId(prefs.quickToolSlot(idx)),
                            GestureAction.pickerWithoutPanels(),
                            action -> {
                                String s = prefs.quickToolSlot(idx);
                                if (action.needsApp) {
                                    Intent it = new Intent(this, AppPickerActivity.class);
                                    it.putExtra(AppPickerActivity.EXTRA_SLOT, s);
                                    it.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, "快速工具 · 按钮 " + idx);
                                    startActivity(it);
                                    Motion.openActivity(this);
                                } else {
                                    prefs.setActionId(s, action.id);
                                    refreshSummaries();
                                }
                            }));
            qtRows.put(i, r);
            c.addView(r.row);
        }
        } else {
        c.addView(UiKit.subLabel(this, "快速启动器应用（手势动作「快速启动器」打开）"));
        for (int i = 1; i <= QUICK_SLOTS; i++) {
            final int idx = i;
            UiKit.SlotRow r = UiKit.slotRow(this, "应用 " + i, 0, prefs.displayFor(prefs.quickAppSlot(i)), false,
                    v -> {
                        Intent it = new Intent(this, AppPickerActivity.class);
                        it.putExtra(AppPickerActivity.EXTRA_SLOT, prefs.quickAppSlot(idx));
                        it.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, "快速启动器 · 应用 " + idx);
                        startActivity(it);
                        Motion.openActivity(this);
                    });
            qsRows.put(i, r);
            c.addView(r.row);
        }
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
        return c;
    }

    private View sceneCard() {
        LinearLayout c = UiKit.card(this);
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
        c.addView(UiKit.clickRow(this, "适配键盘", keyboardModeLabel(), v -> showKeyboardModeDialog()));
        c.addView(UiKit.switchRow(this, "主屏幕时隐藏触钮", "回到桌面后自动隐藏手柄",
                prefs.hideOnHome(), v -> prefs.setBool(Prefs.HIDE_ON_HOME, v)));
        c.addView(UiKit.switchRow(this, "对齐触钮", "两侧保持相同尺寸与位置（编辑时联动）",
                prefs.syncHandles(), v -> prefs.setBool(Prefs.SYNC_HANDLES, v)));
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
        return c;
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
        t1.setText("OneHandX");
        try { t1.setText("OneHandX v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName); }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        t1.setTextSize(14);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        TextView t2 = new TextView(this);
        t2.setText("独立的边缘手势工具 · 全部数据仅保存在本机，无网络权限。\n"
                + "用法：从屏幕左/右侧边缘向内滑动 → 直滑 / 斜上 / 斜下；短滑触发短滑动作，拖得更远触发长滑动作；滑入后停住不动可触发「滑动保持」动作；按住手柄不动可触发「长按」动作。\n"
                + "每侧最多 3 个手柄，可分别设置外观与手势映射。\n"
                + "贴边曲面、六种滑动样式与独立配色。部分系统动作受 Android 版本及手机权限限制。");
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
