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
    public static final String HANDLE_HEIGHT_PCT = "handle_height_pct";
    public static final String HANDLE_POS_PCT = "handle_pos_pct";
    public static final String HANDLE_ALPHA = "handle_alpha";
    public static final String SHOW_INDICATOR = "show_indicator";
    public static final String VIBRATE = "vibrate";
    public static final String LONG_HINT = "long_hint";
    public static final String SHORT_TRIGGER_DP = "short_trigger_dp";
    public static final String LONG_TRIGGER_DP = "long_trigger_dp";
    public static final String LANDSCAPE_ENABLED = "landscape_enabled";
    public static final String EXCLUDED_APPS = "excluded_apps";

    public static final String[] SIDES = {"L", "R"};
    public static final String[] LENGTHS = {"S", "L"};
    public static final String[] DIRS = {"H", "U", "D"};

    public static final int DEF_WIDTH_DP = 24;
    public static final int DEF_HEIGHT_PCT = 70;
    public static final int DEF_POS_PCT = 50;
    public static final int DEF_ALPHA = 45;
    public static final int DEF_SHORT_DP = 28;
    public static final int DEF_LONG_DP = 140;

    private final Context ctx;
    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String slot(String side, String len, String dir) {
        return side + "-" + len + "-" + dir;
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
    public int handleWidthDp() { return getInt(HANDLE_WIDTH_DP, DEF_WIDTH_DP); }
    public int handleHeightPct() { return getInt(HANDLE_HEIGHT_PCT, DEF_HEIGHT_PCT); }
    public int handlePosPct() { return getInt(HANDLE_POS_PCT, DEF_POS_PCT); }
    public int handleAlphaPct() { return getInt(HANDLE_ALPHA, DEF_ALPHA); }
    public boolean showIndicator() { return getBool(SHOW_INDICATOR, true); }
    public boolean vibrate() { return getBool(VIBRATE, true); }
    public boolean longHint() { return getBool(LONG_HINT, true); }
    public int shortTriggerDp() { return getInt(SHORT_TRIGGER_DP, DEF_SHORT_DP); }
    public int longTriggerDp() { return getInt(LONG_TRIGGER_DP, DEF_LONG_DP); }
    public boolean landscapeEnabled() { return getBool(LANDSCAPE_ENABLED, false); }

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
