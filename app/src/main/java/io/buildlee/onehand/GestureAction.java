package io.buildlee.onehand;

public enum GestureAction {
    NONE("none", "无", false),
    BACK("back", "返回", false),
    HOME("home", "主屏幕", false),
    RECENTS("recents", "最近任务", false),
    NOTIFICATIONS("notifications", "通知面板", false),
    QUICK_SETTINGS("quick_settings", "快捷设置", false),
    SCREENSHOT("screenshot", "截图", false),
    LOCK_SCREEN("lock_screen", "锁定屏幕", false),
    POWER_MENU("power_menu", "电源菜单", false),
    TORCH("torch", "手电筒", false),
    ASSISTANT("assistant", "语音助手", false),
    LAUNCH_APP("launch_app", "打开应用", true),
    VOLUME_UP("volume_up", "音量 +", false),
    VOLUME_DOWN("volume_down", "音量 -", false),
    MUTE_ON("mute_on", "静音开", false),
    MUTE_OFF("mute_off", "静音关", false),
    ROTATE("rotate", "旋转屏幕", false);

    public final String id;
    public final String label;
    public final boolean needsApp;

    GestureAction(String id, String label, boolean needsApp) {
        this.id = id;
        this.label = label;
        this.needsApp = needsApp;
    }

    public static final GestureAction[] PICKER_ORDER = {
            NONE, BACK, HOME, RECENTS,
            NOTIFICATIONS, QUICK_SETTINGS, SCREENSHOT, LOCK_SCREEN, POWER_MENU,
            TORCH, LAUNCH_APP, ASSISTANT,
            VOLUME_UP, VOLUME_DOWN, MUTE_ON, MUTE_OFF, ROTATE
    };

    public static GestureAction fromId(String id) {
        if (id != null) {
            for (GestureAction a : values()) {
                if (a.id.equals(id)) return a;
            }
        }
        return NONE;
    }
}
