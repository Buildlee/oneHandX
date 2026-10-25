package io.buildlee.onehand;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
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
    public static final String HOLD_MS = "hold_ms";
    public static final String PAUSE_KEYBOARD = "pause_keyboard";   // v0.3 遗留，仅迁移用
    public static final String HIDE_NAVBAR = "hide_navbar";

    // v0.4：对齐 One Hand Operation+ 的参数
    public static final String ANGLE_DEG = "angle_deg";
    public static final String QUICK_VIBRATE = "quick_vibrate";
    public static final String VIBRATE_AMP = "vibrate_amp";
    public static final String INSTANT_SHORT = "instant_short";
    public static final String INSTANT_LONG = "instant_long";
    public static final String LONG_ENABLED = "long_enabled";
    public static final String LONG_HOLD_MS = "long_hold_ms";
    public static final String KEYBOARD_MODE = "keyboard_mode";
    public static final String HIDE_ON_HOME = "hide_on_home";
    public static final String SYNC_HANDLES = "sync_handles";
    public static final String ANIM_STYLE = "anim_style";
    public static final String ANIM_COLOR = "anim_color";
    public static final String HANDLE_COLOR = "handle_color";

    public static final String KB_ABOVE = "above";
    public static final String KB_HIDE = "hide";
    public static final String KB_OFF = "off";

    public static final int[] COLORS = {
            0xFFFFFFFF, 0xFF4A9EFF, 0xFF39D98A, 0xFFFF9F43, 0xFFFF6B9A, 0xFFBF7BFF
    };
    public static final String[] COLOR_NAMES = {"白", "蓝", "绿", "橙", "粉", "紫"};

    // v1 遗留键（仅用于一次性迁移）
    private static final String OLD_HANDLE_HEIGHT_PCT = "handle_height_pct";
    private static final String OLD_HANDLE_POS_PCT = "handle_pos_pct";
    private static final String OLD_HANDLE_ALPHA = "handle_alpha";
    private static final String MODEL = "model";

    public static final String[] SIDES = {"L", "R"};
    public static final String[] LENGTHS = {"S", "L"};
    /** 手势槽位的长度维度：短滑 / 长滑 / 滑动保持 */
    public static final String[] SLOT_LENS = {"S", "L", "H"};
    public static final String[] DIRS = {"H", "U", "D"};
    public static final String LEN_HOLD = "H";
    public static final String PRESS = "P";
    public static final int MAX_HANDLES_PER_SIDE = 3;

    public static final int DEF_WIDTH_DP = 24;
    public static final int DEF_ALPHA = 45;
    public static final int DEF_SHORT_DP = 28;
    public static final int DEF_LONG_DP = 140;
    public static final int DEF_HOLD_MS = 400;

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
        for (String len : SLOT_LENS) {
            for (String dir : DIRS) {
                String slot = slot(id, len, dir);
                e.remove("act_" + slot);
                e.remove("pkg_" + slot);
                e.remove("label_" + slot);
            }
        }
        String pslot = pressSlot(id);
        e.remove("act_" + pslot);
        e.remove("pkg_" + pslot);
        e.remove("label_" + pslot);
        e.apply();
    }

    // ---------------- 全局参数 ----------------

    public int handleWidthDp() { return getInt(HANDLE_WIDTH_DP, DEF_WIDTH_DP); }
    public boolean showIndicator() { return getBool(SHOW_INDICATOR, true); }
    public boolean vibrate() { return getBool(VIBRATE, true); }
    public boolean longHint() { return getBool(LONG_HINT, true); }
    public int shortTriggerDp() { return getInt(SHORT_TRIGGER_DP, DEF_SHORT_DP); }
    public int longTriggerDp() { return getInt(LONG_TRIGGER_DP, DEF_LONG_DP); }
    public int holdMs() { return Math.max(150, Math.min(1200, getInt(HOLD_MS, DEF_HOLD_MS))); }
    public boolean landscapeEnabled() { return getBool(LANDSCAPE_ENABLED, false); }

    // v0.4
    public int angleDeg() { return Math.max(15, Math.min(60, getInt(ANGLE_DEG, 28))); }
    public boolean quickVibrate() { return getBool(QUICK_VIBRATE, true); }
    public int vibrateAmp() { return Math.max(30, Math.min(255, getInt(VIBRATE_AMP, 200))); }
    public boolean instantShort() { return getBool(INSTANT_SHORT, false); }
    public boolean instantLong() { return getBool(INSTANT_LONG, true); }
    public boolean longEnabled() { return getBool(LONG_ENABLED, true); }
    public int longHoldMs() { return Math.max(0, Math.min(1200, getInt(LONG_HOLD_MS, 0))); }

    /** 键盘策略：above（移到键盘上方）/ hide（隐藏）/ off；默认按 OHO+ 移到上方 */
    public String keyboardMode() {
        String def = getBool(PAUSE_KEYBOARD, false) ? KB_HIDE : KB_ABOVE;
        String mode = getStr(KEYBOARD_MODE, def);
        if (!KB_ABOVE.equals(mode) && !KB_HIDE.equals(mode) && !KB_OFF.equals(mode)) mode = KB_ABOVE;
        return mode;
    }

    public boolean hideOnHome() { return getBool(HIDE_ON_HOME, false); }

    public boolean hideNavbar() { return getBool(HIDE_NAVBAR, false); }
    public boolean syncHandles() { return getBool(SYNC_HANDLES, false); }
    public int animStyle() { return Math.max(0, Math.min(2, getInt(ANIM_STYLE, 0))); }
    public int animColorIndex() { return Math.max(0, Math.min(COLORS.length - 1, getInt(ANIM_COLOR, 0))); }
    public int handleColorIndex() { return Math.max(0, Math.min(COLORS.length - 1, getInt(HANDLE_COLOR, 0))); }
    public int animColor() { return COLORS[animColorIndex()]; }
    public int handleColor() { return COLORS[handleColorIndex()]; }

    // ---------------- 手势映射 ----------------

    public static String slot(String handleId, String len, String dir) {
        return handleId + "-" + len + "-" + dir;
    }

    /** 长按（按住手柄不动）的槽位 */
    public static String pressSlot(String handleId) {
        return handleId + "-" + PRESS;
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

    // ---------------- 备份 / 恢复 ----------------

    public int countEntries() { return sp.getAll().size(); }

    public String dumpJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("app", "onehand");
        root.put("version", 4);
        JSONObject entries = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            JSONObject o = new JSONObject();
            Object v = e.getValue();
            if (v instanceof Boolean) { o.put("t", "b"); o.put("v", v); }
            else if (v instanceof Integer) { o.put("t", "i"); o.put("v", v); }
            else if (v instanceof Long) { o.put("t", "l"); o.put("v", v); }
            else if (v instanceof Float) { o.put("t", "f"); o.put("v", v); }
            else { o.put("t", "s"); o.put("v", String.valueOf(v)); }
            entries.put(e.getKey(), o);
        }
        root.put("entries", entries);
        return root.toString(2);
    }

    public int applyJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONObject entries = root.optJSONObject("entries");
        if (entries == null || !"onehand".equals(root.optString("app", "onehand"))) {
            throw new JSONException("不是 OneHand+ 备份文件");
        }
        SharedPreferences.Editor editor = sp.edit().clear();
        int n = 0;
        Iterator<String> it = entries.keys();
        while (it.hasNext()) {
            String key = it.next();
            JSONObject o = entries.optJSONObject(key);
            if (o == null) continue;
            switch (o.optString("t", "s")) {
                case "b": editor.putBoolean(key, o.optBoolean("v")); break;
                case "i": editor.putInt(key, o.optInt("v")); break;
                case "l": editor.putLong(key, o.optLong("v")); break;
                case "f": editor.putFloat(key, (float) o.optDouble("v")); break;
                default: editor.putString(key, o.optString("v", "")); break;
            }
            n++;
        }
        editor.apply();
        return n;
    }
}
