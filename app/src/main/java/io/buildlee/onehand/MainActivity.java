package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityServiceInfo;
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
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

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
    private SwitchCompat masterSwitch;
    private TextView excludeCountTv;
    private final Map<String, TextView> handleSummary = new HashMap<>();
    private final Map<Integer, UiKit.SlotRow> qtRows = new HashMap<>();
    private final Map<Integer, UiKit.SlotRow> qsRows = new HashMap<>();

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> refreshAll();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(R.layout.activity_main);
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
            statusLine.setText("服务运行中 · 从屏幕边缘滑动即可触动手势");
            statusLine.setTextColor(UiKit.col(this, R.color.accent));
        } else {
            statusLine.setText("服务未运行 · 点击下方按钮开启无障碍服务");
            statusLine.setTextColor(UiKit.col(this, R.color.text_secondary));
        }
        if (masterSwitch != null && masterSwitch.isChecked() != prefs.masterEnabled()) {
            masterSwitch.setChecked(prefs.masterEnabled());
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
        root.addView(UiKit.sectionHeader(this, "高级"));
        root.addView(advancedCard());
        root.addView(UiKit.sectionHeader(this, "关于"));
        root.addView(aboutCard());
        root.addView(footerNote());
    }

    private View topCard() {
        LinearLayout c = UiKit.card(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 16), UiKit.dp(this, 8));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText("启用 OneHand+");
        t1.setTextSize(16);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        texts.addView(t1);
        statusLine = new TextView(this);
        statusLine.setTextSize(12);
        statusLine.setPadding(0, UiKit.dp(this, 3), 0, 0);
        texts.addView(statusLine);
        row.addView(texts);

        masterSwitch = new SwitchCompat(this);
        masterSwitch.setChecked(prefs.masterEnabled());
        masterSwitch.setOnClickListener(v -> prefs.setBool(Prefs.MASTER_ENABLED, masterSwitch.isChecked()));
        row.addView(masterSwitch);
        c.addView(row);

        Button btn = new Button(this);
        btn.setText("打开无障碍设置");
        btn.setAllCaps(false);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(UiKit.dp(this, 12), UiKit.dp(this, 4), UiKit.dp(this, 12), UiKit.dp(this, 12));
        btn.setLayoutParams(blp);
        btn.setOnClickListener(v -> openA11ySettings());
        c.addView(btn);
        return c;
    }

    private View handlesCard() {
        LinearLayout c = UiKit.card(this);
        c.addView(UiKit.sliderRow(this, "手柄宽度", 8, 48, prefs.handleWidthDp(), "dp",
                v -> prefs.setInt(Prefs.HANDLE_WIDTH_DP, v)));
        c.addView(UiKit.switchRow(this, "显示指示条", "关闭后手柄不可见，但手势仍然可用",
                prefs.showIndicator(), v -> prefs.setBool(Prefs.SHOW_INDICATOR, v)));

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
        row.setPadding(UiKit.dp(this, 14), UiKit.dp(this, 12), UiKit.dp(this, 14), UiKit.dp(this, 12));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText((side.equals("L") ? "左手柄 " : "右手柄 ") + index);
        t1.setTextSize(15);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        texts.addView(t1);
        TextView t2 = new TextView(this);
        t2.setTextSize(12);
        t2.setTextColor(UiKit.col(this, R.color.text_secondary));
        t2.setPadding(0, UiKit.dp(this, 2), 0, 0);
        t2.setText(summaryOf(id));
        handleSummary.put(id, t2);
        texts.addView(t2);
        row.addView(texts);

        SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(prefs.handleEnabled(id));
        sw.setOnClickListener(v -> prefs.setHandleEnabled(id, sw.isChecked()));
        row.addView(sw);

        row.setOnClickListener(v -> {
            Intent i = new Intent(this, HandleEditActivity.class);
            i.putExtra(HandleEditActivity.EXTRA_HANDLE_ID, id);
            startActivity(i);
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
        t1.setText("OneHand+ v0.2.0");
        t1.setTextSize(14);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(UiKit.col(this, R.color.text_primary));
        TextView t2 = new TextView(this);
        t2.setText("单手操作手势（Samsung One Hand Operation+ 核心功能复刻）\n用法：从屏幕左/右侧边缘向内滑动 → 直滑 / 斜上 / 斜下；短滑触发短滑动作，拖得更远触发长滑动作。\n每侧最多 3 个手柄，可分别设置外观与手势映射。");
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
        t.setText("提示：系统手势导航的「返回」也使用屏幕边缘，两者可能相互抢占。如果边缘手势不易触发，可降低系统返回手势灵敏度，或改用三键导航。\n「快速工具 / 快速启动器 / 任务切换器」通过手势动作打开浮窗，内容可在上方配置。\n全部数据仅保存在本机，应用不需要网络权限。");
        t.setTextSize(12);
        t.setTextColor(UiKit.col(this, R.color.text_secondary));
        t.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 20), UiKit.dp(this, 8), 0);
        t.setLineSpacing(UiKit.dp(this, 3), 1f);
        return t;
    }
}
