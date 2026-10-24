package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private Prefs prefs;
    private LinearLayout root;
    private TextView statusLine;
    private SwitchCompat masterSwitch;
    private TextView excludeCountTv;
    private final Map<String, TextView> slotViews = new HashMap<>();

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (sp, key) -> {
                refreshStatus();
                refreshSlotLabels();
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(R.layout.activity_main);
        root = findViewById(R.id.contentRoot);
        findViewById(R.id.btnReset).setOnClickListener(v -> confirmReset());
        prefs.registerListener(prefListener);
        buildContent();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
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

    private void refreshStatus() {
        if (statusLine == null) return;
        if (a11yOn()) {
            statusLine.setText("服务运行中 · 从屏幕边缘滑动即可触动手势");
            statusLine.setTextColor(col(R.color.accent));
        } else {
            statusLine.setText("服务未运行 · 点击下方按钮开启无障碍服务");
            statusLine.setTextColor(col(R.color.text_secondary));
        }
        if (masterSwitch != null && masterSwitch.isChecked() != prefs.masterEnabled()) {
            masterSwitch.setChecked(prefs.masterEnabled());
        }
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "无法打开无障碍设置，请手动前往：设置 → 辅助功能", Toast.LENGTH_LONG).show();
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("恢复默认设置")
                .setMessage("将清空全部手势映射、手柄外观与反馈设置，是否继续？")
                .setPositiveButton("恢复", (d, w) -> {
                    prefs.resetAll();
                    buildContent();
                    refreshStatus();
                    Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ---------------- 界面构建 ----------------

    private void buildContent() {
        root.removeAllViews();
        slotViews.clear();
        root.addView(topCard());
        root.addView(sectionHeader("手柄"));
        root.addView(handlesCard());
        root.addView(sectionHeader("手势"));
        root.addView(gestureCard("L"));
        root.addView(gestureCard("R"));
        root.addView(sectionHeader("反馈"));
        root.addView(feedbackCard());
        root.addView(sectionHeader("高级"));
        root.addView(advancedCard());
        root.addView(sectionHeader("关于"));
        root.addView(aboutCard());
        root.addView(footerNote());
        refreshSlotLabels();
    }

    private View topCard() {
        LinearLayout c = card();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(16), dp(16), dp(8));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText("启用 OneHand+");
        t1.setTextSize(16);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(col(R.color.text_primary));
        texts.addView(t1);
        statusLine = new TextView(this);
        statusLine.setTextSize(12);
        statusLine.setPadding(0, dp(3), 0, 0);
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
        blp.setMargins(dp(12), dp(4), dp(12), dp(12));
        btn.setLayoutParams(blp);
        btn.setOnClickListener(v -> openA11ySettings());
        c.addView(btn);
        return c;
    }

    private View handlesCard() {
        LinearLayout c = card();
        c.addView(switchRow("左手柄", null, Prefs.LEFT_ENABLED, true));
        c.addView(switchRow("右手柄", null, Prefs.RIGHT_ENABLED, true));
        c.addView(sliderRow("手柄宽度", Prefs.HANDLE_WIDTH_DP, 8, 48, "dp", Prefs.DEF_WIDTH_DP));
        c.addView(sliderRow("手柄长度", Prefs.HANDLE_HEIGHT_PCT, 30, 95, "%", Prefs.DEF_HEIGHT_PCT));
        c.addView(sliderRow("垂直位置", Prefs.HANDLE_POS_PCT, 0, 100, "%", Prefs.DEF_POS_PCT));
        c.addView(sliderRow("不透明度", Prefs.HANDLE_ALPHA, 0, 100, "%", Prefs.DEF_ALPHA));
        c.addView(switchRow("显示指示条", "关闭后手柄不可见，但手势仍然可用", Prefs.SHOW_INDICATOR, true));
        return c;
    }

    private View feedbackCard() {
        LinearLayout c = card();
        c.addView(switchRow("震动反馈", "触发动作时轻微震动", Prefs.VIBRATE, true));
        c.addView(switchRow("长滑震动提示", "滑动超过长滑距离时轻震一次", Prefs.LONG_HINT, true));
        return c;
    }

    private View advancedCard() {
        LinearLayout c = card();
        c.addView(sliderRow("触发距离", Prefs.SHORT_TRIGGER_DP, 12, 60, "dp", Prefs.DEF_SHORT_DP));
        c.addView(sliderRow("长滑距离", Prefs.LONG_TRIGGER_DP, 100, 420, "dp", Prefs.DEF_LONG_DP));
        c.addView(switchRow("横屏时启用手势", null, Prefs.LANDSCAPE_ENABLED, false));
        c.addView(excludeRow());
        return c;
    }

    private View gestureCard(String side) {
        LinearLayout c = card();
        String sideName = side.equals("L") ? "左手柄" : "右手柄";
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.VERTICAL);
        h.setPadding(dp(14), dp(12), dp(14), dp(2));
        TextView t1 = new TextView(this);
        t1.setText(sideName + " · 手势映射");
        t1.setTextSize(15);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(col(R.color.text_primary));
        TextView t2 = new TextView(this);
        t2.setText("点击任意一行修改动作；长滑 = 从边缘向内拖得更远");
        t2.setTextSize(12);
        t2.setTextColor(col(R.color.text_secondary));
        t2.setPadding(0, dp(2), 0, 0);
        h.addView(t1);
        h.addView(t2);
        c.addView(h);
        c.addView(groupLabel("短滑"));
        for (String dir : Prefs.DIRS) c.addView(slotRow(side, "S", dir));
        c.addView(groupLabel("长滑"));
        for (String dir : Prefs.DIRS) c.addView(slotRow(side, "L", dir));
        return c;
    }

    private TextView groupLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(col(R.color.text_secondary));
        t.setPadding(dp(14), dp(8), dp(14), dp(2));
        return t;
    }

    private View slotRow(String side, String len, String dir) {
        final String slot = Prefs.slot(side, len, dir);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_row);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconForDir(dir));
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        row.addView(icon);

        TextView name = new TextView(this);
        name.setText(dirName(dir));
        name.setTextSize(15);
        name.setTextColor(col(R.color.text_primary));
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nlp.leftMargin = dp(12);
        name.setLayoutParams(nlp);
        row.addView(name);

        TextView val = new TextView(this);
        val.setTextSize(14);
        val.setMaxLines(1);
        val.setEllipsize(android.text.TextUtils.TruncateAt.END);
        val.setMaxWidth(dp(150));
        slotViews.put(slot, val);
        row.addView(val);
        row.addView(chevron());

        row.setOnClickListener(v -> ActionDialog.show(this,
                slotTitle(side, len, dir),
                prefs.actionId(slot),
                action -> {
                    if (action.needsApp) {
                        prefs.setActionId(slot, action.id);
                        refreshSlotLabels();
                        Intent i = new Intent(this, AppPickerActivity.class);
                        i.putExtra(AppPickerActivity.EXTRA_SLOT, slot);
                        i.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, slotTitle(side, len, dir));
                        startActivity(i);
                    } else {
                        prefs.setActionId(slot, action.id);
                        refreshSlotLabels();
                    }
                }));
        return row;
    }

    private String slotTitle(String side, String len, String dir) {
        return (side.equals("L") ? "左手柄" : "右手柄") + " · " + lenName(len) + " · " + dirName(dir);
    }

    private String dirName(String dir) {
        if ("U".equals(dir)) return "上滑";
        if ("D".equals(dir)) return "下滑";
        return "直滑";
    }

    private String lenName(String len) {
        return "L".equals(len) ? "长滑" : "短滑";
    }

    private int iconForDir(String dir) {
        if ("U".equals(dir)) return R.drawable.ic_swipe_u;
        if ("D".equals(dir)) return R.drawable.ic_swipe_d;
        return R.drawable.ic_swipe_h;
    }

    private ImageView chevron() {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.ic_chevron);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(16), dp(16));
        lp.leftMargin = dp(8);
        iv.setLayoutParams(lp);
        return iv;
    }

    private View excludeRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText("在下列应用中停用");
        t1.setTextSize(15);
        t1.setTextColor(col(R.color.text_primary));
        texts.addView(t1);
        excludeCountTv = new TextView(this);
        excludeCountTv.setTextSize(12);
        excludeCountTv.setTextColor(col(R.color.text_secondary));
        excludeCountTv.setPadding(0, dp(2), 0, 0);
        excludeCountTv.setText(countTextExcluded());
        texts.addView(excludeCountTv);
        row.addView(texts);
        row.addView(chevron());
        row.setOnClickListener(v -> showExcludeDialog());
        return row;
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

    private LinearLayout switchRow(String title, String subtitle, String key, boolean def) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_row);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextSize(15);
        t1.setTextColor(col(R.color.text_primary));
        texts.addView(t1);
        if (subtitle != null) {
            TextView t2 = new TextView(this);
            t2.setText(subtitle);
            t2.setTextSize(12);
            t2.setTextColor(col(R.color.text_secondary));
            t2.setPadding(0, dp(2), 0, 0);
            texts.addView(t2);
        }
        row.addView(texts);

        SwitchCompat sw = new SwitchCompat(this);
        sw.setChecked(prefs.getBool(key, def));
        row.addView(sw);
        row.setOnClickListener(v -> {
            sw.setChecked(!sw.isChecked());
            prefs.setBool(key, sw.isChecked());
        });
        sw.setOnClickListener(v -> prefs.setBool(key, sw.isChecked()));
        return row;
    }

    private LinearLayout sliderRow(String title, String key, int min, int max, String unit, int def) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(10), dp(14), dp(6));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(col(R.color.text_primary));
        header.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView val = new TextView(this);
        val.setTextSize(14);
        val.setTextColor(col(R.color.text_secondary));
        header.addView(val);
        box.addView(header);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        int cur = Math.max(min, Math.min(max, prefs.getInt(key, def)));
        sb.setProgress(cur - min);
        val.setText(cur + unit);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int v = min + progress;
                val.setText(v + unit);
                if (fromUser) prefs.setInt(key, v);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        box.addView(sb, slp);
        return box;
    }

    private View aboutCard() {
        LinearLayout c = card();
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView t1 = new TextView(this);
        t1.setText("OneHand+ v0.1.0");
        t1.setTextSize(14);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        t1.setTextColor(col(R.color.text_primary));
        TextView t2 = new TextView(this);
        t2.setText("单手操作手势（Samsung One Hand Operation+ 核心功能复刻）\n用法：从屏幕左/右侧边缘向内滑动 → 直滑 / 斜上 / 斜下；短滑触发短滑动作，拖得更远触发长滑动作。");
        t2.setTextSize(13);
        t2.setTextColor(col(R.color.text_secondary));
        t2.setPadding(0, dp(6), 0, 0);
        t2.setLineSpacing(dp(3), 1f);
        c.addView(t1);
        c.addView(t2);
        return c;
    }

    private TextView footerNote() {
        TextView t = new TextView(this);
        t.setText("提示：系统手势导航的「返回」也使用屏幕边缘，两者可能相互抢占。如果边缘手势不易触发，可降低系统返回手势灵敏度，或改用三键导航。\n全部数据仅保存在本机，应用不需要网络权限。");
        t.setTextSize(12);
        t.setTextColor(col(R.color.text_secondary));
        t.setPadding(dp(8), dp(20), dp(8), 0);
        t.setLineSpacing(dp(3), 1f);
        return t;
    }

    private void refreshSlotLabels() {
        for (String side : Prefs.SIDES) {
            for (String len : Prefs.LENGTHS) {
                for (String dir : Prefs.DIRS) {
                    String slot = Prefs.slot(side, len, dir);
                    TextView v = slotViews.get(slot);
                    if (v == null) continue;
                    boolean none = GestureAction.NONE.id.equals(prefs.actionId(slot));
                    v.setText(prefs.displayFor(slot));
                    v.setTextColor(col(none ? R.color.text_secondary : R.color.text_primary));
                }
            }
        }
    }

    // ---------------- 小工具 ----------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        int p = dp(6);
        c.setPadding(p, p, p, p);
        return c;
    }

    private TextView sectionHeader(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(col(R.color.text_secondary));
        t.setPadding(dp(10), dp(22), dp(10), dp(8));
        return t;
    }

    private int col(int id) {
        return ContextCompat.getColor(this, id);
    }

    private int dp(int v) {
        return Du.dp(this, v);
    }
}
