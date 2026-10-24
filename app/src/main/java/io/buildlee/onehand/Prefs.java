package io.buildlee.onehand;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

public class Prefs {
    public static final String NAME = "onehand";

    public static final String MASTER_ENABLED = "master_enabled";
    public static final String LEFT_ENABLED = "left_enabled";
    public static final String RIGHT_ENABLED = "right_enabled";
    public static final String HANDLE_WIDTH_DP = "handle_width_dp";
    public static final String SHOW_INDICATOR = "show_indicator";
    public static final String VIBRATE = "vibrate";
    public static final String LONG_HINT = "long_hint";
    public static final String SHORT_TRIGGER_DP = "short_trigger_dp";
    public static final String LONG_TRIGGER_DP = "long_trigger_dp";
    public static final String LANDSCAPE_ENABLED = "landscape_enabled";
    public static final String EXCLUDED_APPS = "excluded_apps";

    // v1 遗留键（仅用于一次性迁移）
    private static final String OLD_HANDLE_HEIGHT_PCT = "handle_height_pct";
    private static final String OLD_HANDLE_POS_PCT = "handle_pos_pct";
    private static final String OLD_HANDLE_ALPHA = "handle_alpha";
    private static final String MODEL = "model";

    public static final String[] SIDES = {"L", "R"};
    public static final String[] LENGTHS = {"S", "L"};
    public static final String[] DIRS = {"H", "U", "D"};
    public static final int MAX_HANDLES_PER_SIDE = 3;

    public static final int DEF_WIDTH_DP = 24;
    public static final int DEF_ALPHA = 45;
    public static final int DEF_SHORT_DP = 28;
    public static final int DEF_LONG_DP = 140;

    private final Context ctx;
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
        migrateIfNeeded();
    }

    private void migrateIfNeeded() {
        if (sp.getInt(MODEL, 1) >= 2) return;
        SharedPreferences.Editor e = sp.edit();
        for (String side : SIDES) {
            String h1 = side + "1";
            for (String len : LENGTHS) {
                for (String dir : DIRS) {
                    String oldSlot = side + "-" + len + "-" + dir;
                    String newSlot = h1 + "-" + len + "-" + dir;
                    copyIfPresent(e, "act_" + oldSlot, "act_" + newSlot);
                    copyIfPresent(e, "pkg_" + oldSlot, "pkg_" + newSlot);
                    copyIfPresent(e, "label_" + oldSlot, "label_" + newSlot);
                }
            }
        }
        int len = sp.getInt(OLD_HANDLE_HEIGHT_PCT, 70);
        int pos = sp.getInt(OLD_HANDLE_POS_PCT, 50);
        int alpha = sp.getInt(OLD_HANDLE_ALPHA, DEF_ALPHA);
        for (String side : SIDES) {
            String h1 = side + "1";
            if (sp.contains(OLD_HANDLE_HEIGHT_PCT)) e.putInt("h_" + h1 + "_len", len);
            if (sp.contains(OLD_HANDLE_POS_PCT)) e.putInt("h_" + h1 + "_pos", pos);
            if (sp.contains(OLD_HANDLE_ALPHA)) e.putInt("h_" + h1 + "_alpha", alpha);
        }
        e.putInt(MODEL, 2);
        e.apply();
    }

    private void copyIfPresent(SharedPreferences.Editor e, String from, String to) {
        if (sp.contains(from) && !sp.contains(to)) {
            String v = sp.getString(from, null);
            if (v != null) e.putString(to, v);
        }
    }

    public void registerListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp.registerOnSharedPreferenceChangeListener(l);
    }

    public void unregisterListener(SharedPreferences.OnSharedPreferenceChangeListener l) {
        sp.unregisterOnSharedPreferenceChangeListener(l);
    }

    public boolean getBool(String key, boolean def) { return sp.getBoolean(key, def); }
    public void setBool(String key, boolean v) { sp.edit().putBoolean(key, v).apply(); }
    public int getInt(String key, int def) { return sp.getInt(key, def); }
    public void setInt(String key, int v) { sp.edit().putInt(key, v).apply(); }
    public String getStr(String key, String def) { return sp.getString(key, def); }
    public void setStr(String key, String v) { sp.edit().putString(key, v).apply(); }

    public boolean masterEnabled() { return getBool(MASTER_ENABLED, true); }
    public boolean sideEnabled(String side) {
        return getBool(side.equals("L") ? LEFT_ENABLED : RIGHT_ENABLED, true);
    }
    public void setSideEnabled(String side, boolean v) {
        setBool(side.equals("L") ? LEFT_ENABLED : RIGHT_ENABLED, v);
    }

    // ---------------- 多手柄 ----------------

    public static String handleId(String side, int index) { return side + index; }

    public int handleCount(String side) {
        return Math.max(1, Math.min(MAX_HANDLES_PER_SIDE, getInt("handle_count_" + side, 1)));
    }

    public void setHandleCount(String side, int n) {
        setInt("handle_count_" + side, Math.max(1, Math.min(MAX_HANDLES_PER_SIDE, n)));
    }

    public boolean handleEnabled(String id) { return getBool("h_" + id + "_enabled", true); }
    public void setHandleEnabled(String id, boolean v) { setBool("h_" + id + "_enabled", v); }

    private int defaultLen(String id) { return id.endsWith("1") ? 70 : 35; }

    private int defaultPos(String id) {
        if (id.endsWith("2")) return 15;
        if (id.endsWith("3")) return 85;
        return 50;
    }

    public int handleLen(String id) { return getInt("h_" + id + "_len", defaultLen(id)); }
    public int handlePos(String id) { return getInt("h_" + id + "_pos", defaultPos(id)); }
    public int handleAlpha(String id) { return getInt("h_" + id + "_alpha", DEF_ALPHA); }

    public void setHandleLen(String id, int v) { setInt("h_" + id + "_len", v); }
    public void setHandlePos(String id, int v) { setInt("h_" + id + "_pos", v); }
    public void setHandleAlpha(String id, int v) { setInt("h_" + id + "_alpha", v); }

    /** 删除手柄（仅允许删最后一个）：清掉它的属性与全部手势映射 */
    public void removeHandle(String id) {
        SharedPreferences.Editor e = sp.edit();
        e.remove("h_" + id + "_enabled");
        e.remove("h_" + id + "_len");
        e.remove("h_" + id + "_pos");
        e.remove("h_" + id + "_alpha");
        for (String len : LENGTHS) {
            for (String dir : DIRS) {
                String slot = slot(id, len, dir);
                e.remove("act_" + slot);
                e.remove("pkg_" + slot);
                e.remove("label_" + slot);
            }
        }
        e.apply();
    }

    // ---------------- 全局参数 ----------------

    public int handleWidthDp() { return getInt(HANDLE_WIDTH_DP, DEF_WIDTH_DP); }
    public boolean showIndicator() { return getBool(SHOW_INDICATOR, true); }
    public boolean vibrate() { return getBool(VIBRATE, true); }
    public boolean longHint() { return getBool(LONG_HINT, true); }
    public int shortTriggerDp() { return getInt(SHORT_TRIGGER_DP, DEF_SHORT_DP); }
    public int longTriggerDp() { return getInt(LONG_TRIGGER_DP, DEF_LONG_DP); }
    public boolean landscapeEnabled() { return getBool(LANDSCAPE_ENABLED, false); }

    // ---------------- 手势映射 ----------------

    public static String slot(String handleId, String len, String dir) {
        return handleId + "-" + len + "-" + dir;
    }

    private String defaultAction(String slot) {
        if (slot.endsWith("-S-H")) return GestureAction.BACK.id;
        if (slot.endsWith("-S-U")) return GestureAction.HOME.id;
        if (slot.endsWith("-S-D")) return GestureAction.RECENTS.id;
        return GestureAction.NONE.id;
    }

    public String actionId(String slot) { return getStr("act_" + slot, defaultAction(slot)); }
    public void setActionId(String slot, String id) { setStr("act_" + slot, id); }
    public String actionPkg(String slot) { return getStr("pkg_" + slot, ""); }
    public void setActionPkg(String slot, String pkg) { setStr("pkg_" + slot, pkg); }
    public String actionLabel(String slot) { return getStr("label_" + slot, ""); }
    public void setActionLabel(String slot, String label) { setStr("label_" + slot, label); }

    public String displayFor(String slot) {
        GestureAction a = GestureAction.fromId(actionId(slot));
        if (a == GestureAction.LAUNCH_APP) {
            String label = actionLabel(slot);
            if (label != null && !label.isEmpty()) return label;
            String pkg = actionPkg(slot);
            if (pkg != null && !pkg.isEmpty()) {
                try {
                    return ctx.getPackageManager()
                            .getApplicationLabel(ctx.getPackageManager().getApplicationInfo(pkg, 0))
                            .toString();
                } catch (Exception e) {
                    return pkg;
                }
            }
            return "打开应用…";
        }
        return a.label;
    }

    // ---------------- 快速面板 ----------------

    public String quickToolSlot(int i) { return "QT" + i; }
    public String quickAppSlot(int i) { return "QS" + i; }

    // ---------------- 排除应用 ----------------

    public String excludedApps() { return getStr(EXCLUDED_APPS, ""); }

    public boolean isExcluded(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        return ("\n" + excludedApps() + "\n").contains("\n" + pkg + "\n");
    }

    public Set<String> excludedSet() {
        Set<String> out = new HashSet<>();
        String s = excludedApps();
        if (!s.isEmpty()) {
            for (String p : s.split("\n")) {
                if (!p.isEmpty()) out.add(p);
            }
        }
        return out;
    }

    public void setExcludedApps(Set<String> pkgs) {
        StringBuilder sb = new StringBuilder();
        for (String p : pkgs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        setStr(EXCLUDED_APPS, sb.toString());
    }

    public void resetAll() { sp.edit().clear().apply(); }
}
