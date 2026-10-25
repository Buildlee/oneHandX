package io.buildlee.onehand;

public enum GestureAction {
    NONE("none", "无", false),
    BACK("back", "返回", false),
    HOME("home", "主屏幕", false),
    RECENTS("recents", "最近任务", false),
    PREVIOUS_APP("previous_app", "上一应用", false),
    SPLIT_SCREEN("split_screen", "分屏", false),
    NOTIFICATIONS("notifications", "通知面板", false),
    QUICK_SETTINGS("quick_settings", "快捷设置", false),
    KEYBOARD_PICKER("keyboard_picker", "切换输入法", false),
    SCREENSHOT("screenshot", "截图", false),
    LOCK_SCREEN("lock_screen", "锁定屏幕", false),
    POWER_MENU("power_menu", "电源菜单", false),
    TORCH("torch", "手电筒", false),
    ASSISTANT("assistant", "语音助手", false),
    SEARCH("search", "搜索查找", false),
    LAUNCH_APP("launch_app", "打开应用", true),
    QUICK_TOOLS("quick_tools", "快速工具", false),
    QUICK_LAUNCHER("quick_launcher", "快速启动器", false),
    TASK_SWITCHER("task_switcher", "任务切换器", false),
    TOUCHPAD("touchpad", "虚拟触控板", false),
    FLOAT_NAV("float_nav", "浮动导航按钮", false),
    VOLUME_UP("volume_up", "音量 +", false),
    VOLUME_DOWN("volume_down", "音量 -", false),
    VOLUME_PANEL("volume_panel", "音量面板", false),
    MUTE_ON("mute_on", "静音开", false),
    MUTE_OFF("mute_off", "静音关", false),
    PLAY_PAUSE("media_play_pause", "播放/暂停", false),
    PREV_TRACK("media_prev", "上一曲", false),
    NEXT_TRACK("media_next", "下一曲", false),
    SCREEN_TIMEOUT("screen_timeout", "屏幕超时", false),
    ROTATE("rotate", "自动旋转开关", false),
    ROTATE_RIGHT("rotate_right", "向右旋转", false),
    ROTATE_LEFT("rotate_left", "向左旋转", false),
    NAVBAR_TOGGLE("navbar_toggle", "切换导航栏", false),
    COLOR_INVERT("color_invert", "颜色反转", false),
    HIDE_HANDLES("hide_handles", "隐藏手柄", false);

    public final String id;
    public final String label;
    public final boolean needsApp;

    GestureAction(String id, String label, boolean needsApp) {
        this.id = id;
        this.label = label;
        this.needsApp = needsApp;
    }

    public static final GestureAction[] PICKER_ORDER = {
            NONE, BACK, HOME, RECENTS, PREVIOUS_APP, SPLIT_SCREEN,
            NOTIFICATIONS, QUICK_SETTINGS, KEYBOARD_PICKER, SCREENSHOT, LOCK_SCREEN, POWER_MENU,
            TORCH, ASSISTANT, SEARCH, LAUNCH_APP,
            QUICK_TOOLS, QUICK_LAUNCHER, TASK_SWITCHER, TOUCHPAD, FLOAT_NAV,
            VOLUME_UP, VOLUME_DOWN, VOLUME_PANEL, MUTE_ON, MUTE_OFF,
            PLAY_PAUSE, PREV_TRACK, NEXT_TRACK,
            SCREEN_TIMEOUT, ROTATE, ROTATE_RIGHT, ROTATE_LEFT, NAVBAR_TOGGLE, COLOR_INVERT,
            HIDE_HANDLES
    };

    public static boolean isPanelAction(GestureAction a) {
        return a == QUICK_TOOLS || a == QUICK_LAUNCHER || a == TASK_SWITCHER;
    }

    /** 配置快速工具按钮时可选的列表（排除面板类动作，避免递归） */
    public static GestureAction[] pickerWithoutPanels() {
        java.util.ArrayList<GestureAction> list = new java.util.ArrayList<>();
        for (GestureAction a : PICKER_ORDER) {
            if (!isPanelAction(a)) list.add(a);
        }
        return list.toArray(new GestureAction[0]);
    }

    public static GestureAction fromId(String id) {
        if (id != null) {
            for (GestureAction a : values()) {
                if (a.id.equals(id)) return a;
            }
        }
        return NONE;
    }
}
