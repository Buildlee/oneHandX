package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 边缘手势服务：多手柄管理 + 6 种手势 × 每手柄映射 + 动作执行 + 快速面板。
 */
public class EdgeService extends AccessibilityService {

    private static final String TAG = "OneHand";
    private static final long HIDE_DURATION_MS = 30000;
    private static final int RECENT_MAX = 8;
    private static final long PRESS_HOLD_MS = 500;

    private static final int PANEL_QUICK_TOOLS = 0;
    private static final int PANEL_QUICK_LAUNCHER = 1;
    private static final int PANEL_TASK_SWITCHER = 2;

    private Prefs prefs;
    private WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, View> handleViews = new HashMap<>();
    private String currentPkg = "";
    private boolean torchOn = false;
    private String torchId = null;
    private boolean tempHidden = false;
    private View panelView = null;
    private final LinkedList<String> recentPkgs = new LinkedList<>();
    private boolean imeVisible = false;
    private boolean navbarHiddenByUs = false;

    private final Runnable restorePkg = () -> {
        currentPkg = "";
        applyVisibility();
    };

    private final Runnable unhide = () -> {
        tempHidden = false;
        applyVisibility();
    };

    private final Runnable dismissPanelRunnable = this::dismissPanel;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (key == null) return;
        if (key.startsWith("h_")
                || key.startsWith("handle_count_")
                || Prefs.MASTER_ENABLED.equals(key)
                || Prefs.LEFT_ENABLED.equals(key)
                || Prefs.RIGHT_ENABLED.equals(key)
                || Prefs.LANDSCAPE_ENABLED.equals(key)
                || Prefs.HANDLE_WIDTH_DP.equals(key)
                || Prefs.SHOW_INDICATOR.equals(key)
                || Prefs.EXCLUDED_APPS.equals(key)) {
            main.post(this::refreshHandles);
        }
        if (Prefs.PAUSE_KEYBOARD.equals(key)) {
            main.post(() -> {
                updateImeState();
                applyVisibility();
            });
        }
        if (Prefs.HIDE_NAVBAR.equals(key)) {
            main.post(this::syncNavbar);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        prefs.registerListener(prefListener);
        Log.i(TAG, "service connected");
        refreshHandles();
        updateImeState();
        syncNavbar();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroy");
        if (torchOn && torchId != null) {
            try {
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                if (cm != null) cm.setTorchMode(torchId, false);
            } catch (Exception ignored) {}
            torchOn = false;
        }
        if (prefs != null) {
            try { prefs.unregisterListener(prefListener); } catch (Exception ignored) {}
        }
        dismissPanel();
        detachAll();
        restoreNavbarIfOurs();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "service unbind");
        if (prefs != null) {
            try { prefs.unregisterListener(prefListener); } catch (Exception ignored) {}
        }
        dismissPanel();
        detachAll();
        restoreNavbarIfOurs();
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null) return;
        maybeUpdateIme();
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals(getPackageName())) return;
        if (isTransient(pkg)) {
            main.removeCallbacks(restorePkg);
            main.postDelayed(restorePkg, 800);
            return;
        }
        if (!pkg.equals(currentPkg)) {
            recentPkgs.remove(pkg);
            recentPkgs.addFirst(pkg);
            while (recentPkgs.size() > RECENT_MAX) recentPkgs.removeLast();
        }
        main.removeCallbacks(restorePkg);
        currentPkg = pkg;
        applyVisibility();
    }

    private boolean isTransient(String pkg) {
        return pkg.contains("systemui")
                || pkg.contains("launcher")
                || pkg.contains("permissioncontroller")
                || pkg.contains("keyguard");
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        main.post(() -> {
            dismissPanel();
            maybeUpdateIme();
            refreshHandles();
        });
    }

    // ---------------- 手柄管理 ----------------

    private void refreshHandles() {
        if (wm == null || prefs == null) return;
        detachAll();
        if (prefs.masterEnabled()) {
            for (String side : Prefs.SIDES) {
                if (!prefs.sideEnabled(side)) continue;
                int count = prefs.handleCount(side);
                for (int i = 1; i <= count; i++) {
                    String id = Prefs.handleId(side, i);
                    if (prefs.handleEnabled(id)) attachHandle(id);
                }
            }
        }
        applyVisibility();
    }

    private void detachAll() {
        if (wm == null) return;
        for (View v : handleViews.values()) {
            try { wm.removeViewImmediate(v); } catch (Exception ignored) {}
        }
        handleViews.clear();
    }

    private void attachHandle(String id) {
        boolean left = id.startsWith("L");
        Point size = Du.screenSize(this);
        int widthPx = Du.dp(this, prefs.handleWidthDp());
        int heightPx = Math.round(size.y * prefs.handleLen(id) / 100f);
        int margin = Du.dp(this, 6);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                widthPx, heightPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (left ? Gravity.LEFT : Gravity.RIGHT);
        lp.x = margin;
        lp.y = Math.max(0, Math.round((size.y - heightPx) * prefs.handlePos(id) / 100f));

        EdgeHandleView view = new EdgeHandleView(this, left);
        view.setBaseAlpha(prefs.showIndicator() ? Math.round(prefs.handleAlpha(id) * 255f / 100f) : 0);
        view.setOnTouchListener(new HandleTouch(id, left));

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            Log.w(TAG, "addView failed", e);
            return;
        }
        handleViews.put(id, view);
        Log.i(TAG, "handle attached id=" + id + " " + widthPx + "x" + heightPx + " y=" + lp.y);
    }

    private void applyVisibility() {
        if (prefs == null) return;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean env = prefs.masterEnabled()
                && (!landscape || prefs.landscapeEnabled())
                && !tempHidden
                && !imeBlocked()
                && !prefs.isExcluded(currentPkg);
        for (View v : handleViews.values()) {
            setVis(v, env);
        }
    }

    private void setVis(View v, boolean visible) {
        if (v == null) return;
        int want = visible ? View.VISIBLE : View.GONE;
        if (v.getVisibility() != want) v.setVisibility(want);
    }

    // ---------------- 手势识别 ----------------

    private int shortPx() { return Du.dp(this, prefs.shortTriggerDp()); }
    private int longPx() { return Du.dp(this, prefs.longTriggerDp()); }

    private class HandleTouch implements View.OnTouchListener {
        private String handleId;
        private boolean left;
        private final int slop = ViewConfiguration.get(EdgeService.this).getScaledTouchSlop();
        private float downX, downY, lastX, lastY, maxInward;
        private boolean longFed;
        private boolean down;
        private boolean pressFired;
        private boolean holdFired;
        private long lastMoveTime;

        HandleTouch(String handleId, boolean left) {
            this.handleId = handleId;
            this.left = left;
        }

        /** 长按手柄（原地按住不动）→ -P 槽位动作 */
        private final Runnable pressHoldTask = () -> {
            if (!down || pressFired || holdFired) return;
            if (maxInward > slop || Math.hypot(lastX - downX, lastY - downY) > slop * 2f) return;
            String slot = Prefs.pressSlot(handleId);
            GestureAction a = GestureAction.fromId(prefs.actionId(slot));
            if (a == GestureAction.NONE) return;
            pressFired = true;
            boolean ok = execute(a, prefs.actionPkg(slot), left);
            Log.i(TAG, "press-hold slot=" + slot + " action=" + a.id + " ok=" + ok);
            if (ok && prefs.vibrate()) hapticOnce();
        };

        /** 滑动保持（滑入后停住不动，超过设定时长）→ -H-<dir> 槽位动作；未配置则不触发，松手走普通判定 */
        private final Runnable holdCheckTask = new Runnable() {
            @Override
            public void run() {
                if (!down || holdFired || pressFired) return;
                if (maxInward < shortPx()) return;
                long now = System.currentTimeMillis();
                if (now - lastMoveTime < prefs.holdMs()) {
                    main.postDelayed(this, 60);
                    return;
                }
                float dx = left ? (lastX - downX) : (downX - lastX);
                if (dx <= 0) return;
                String dir = dirFor(dx, lastY - downY);
                String slot = Prefs.slot(handleId, Prefs.LEN_HOLD, dir);
                GestureAction a = GestureAction.fromId(prefs.actionId(slot));
                if (a == GestureAction.NONE) return;
                holdFired = true;
                main.removeCallbacks(pressHoldTask);
                boolean ok = execute(a, prefs.actionPkg(slot), left);
                Log.i(TAG, "hold slot=" + slot + " action=" + a.id + " ok=" + ok);
                if (ok && prefs.vibrate()) hapticOnce();
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    down = true;
                    pressFired = false;
                    holdFired = false;
                    downX = lastX = e.getRawX();
                    downY = lastY = e.getRawY();
                    maxInward = 0;
                    longFed = false;
                    lastMoveTime = System.currentTimeMillis();
                    main.removeCallbacks(pressHoldTask);
                    main.removeCallbacks(holdCheckTask);
                    main.postDelayed(pressHoldTask, PRESS_HOLD_MS);
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    lastMoveTime = System.currentTimeMillis();
                    float inward = left ? (lastX - downX) : (downX - lastX);
                    if (inward > maxInward) maxInward = inward;
                    if (maxInward > slop || Math.hypot(lastX - downX, lastY - downY) > slop * 2f) {
                        main.removeCallbacks(pressHoldTask);
                    }
                    if (!longFed && maxInward >= longPx()) {
                        longFed = true;
                        if (prefs.longHint()) hapticOnce();
                    }
                    if (!holdFired && !pressFired && maxInward >= shortPx()) {
                        main.removeCallbacks(holdCheckTask);
                        main.postDelayed(holdCheckTask, 80);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    down = false;
                    main.removeCallbacks(pressHoldTask);
                    main.removeCallbacks(holdCheckTask);
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(false);
                    if (pressFired || holdFired) return true;
                    commit(handleId, left, lastX - downX, lastY - downY, maxInward);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    down = false;
                    main.removeCallbacks(pressHoldTask);
                    main.removeCallbacks(holdCheckTask);
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(false);
                    return true;
                }
                default:
                    return false;
            }
        }
    }

    private String dirFor(float dx, float dy) {
        double angle = Math.toDegrees(Math.atan2(dy, Math.max(dx, 1f)));
        if (angle <= -28) return "U";
        if (angle >= 28) return "D";
        return "H";
    }

    private void commit(String handleId, boolean leftSide, float dxRaw, float dy, float maxInward) {
        float dx = leftSide ? dxRaw : -dxRaw;
        if (dx <= 0 || maxInward < shortPx() * 0.6f) {
            Log.i(TAG, "gesture ignored id=" + handleId + " dx=" + dx + " inward=" + maxInward);
            return;
        }
        String dir = dirFor(dx, dy);
        String len = maxInward >= longPx() ? "L" : "S";
        Log.i(TAG, "gesture id=" + handleId + " len=" + len + " dir=" + dir
                + " inward=" + Math.round(maxInward));
        runGesture(handleId, len, dir, leftSide);
    }

    private void runGesture(String handleId, String len, String dir, boolean leftSide) {
        String slot = Prefs.slot(handleId, len, dir);
        GestureAction action = GestureAction.fromId(prefs.actionId(slot));
        if (action == GestureAction.NONE && "L".equals(len)) {
            slot = Prefs.slot(handleId, "S", dir);
            action = GestureAction.fromId(prefs.actionId(slot));
        }
        if (action == GestureAction.NONE) {
            Log.i(TAG, "gesture slot=" + slot + " action=none");
            return;
        }
        boolean ok = execute(action, prefs.actionPkg(slot), leftSide);
        Log.i(TAG, "gesture slot=" + slot + " action=" + action.id + " ok=" + ok);
        if (ok && prefs.vibrate()) hapticOnce();
    }

    // ---------------- 动作执行 ----------------

    private boolean execute(GestureAction a, String pkg, boolean leftSide) {
        switch (a) {
            case NONE:
                return false;
            case BACK:
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case HOME:
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case RECENTS:
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case PREVIOUS_APP:
                return previousApp();
            case SPLIT_SCREEN:
                return splitScreen();
            case NOTIFICATIONS:
                return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case QUICK_SETTINGS:
                return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
            case KEYBOARD_PICKER:
                return keyboardPicker();
            case POWER_MENU:
                return performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
            case LOCK_SCREEN:
                return Build.VERSION.SDK_INT >= 28 && performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
            case SCREENSHOT:
                return screenshot();
            case TORCH:
                return toggleTorch();
            case ASSISTANT:
                return assistant();
            case LAUNCH_APP:
                return launchApp(pkg);
            case QUICK_TOOLS:
                showPanel(PANEL_QUICK_TOOLS, leftSide);
                return true;
            case QUICK_LAUNCHER:
                showPanel(PANEL_QUICK_LAUNCHER, leftSide);
                return true;
            case TASK_SWITCHER:
                showPanel(PANEL_TASK_SWITCHER, leftSide);
                return true;
            case VOLUME_UP:
                return adjustVolume(AudioManager.ADJUST_RAISE);
            case VOLUME_DOWN:
                return adjustVolume(AudioManager.ADJUST_LOWER);
            case VOLUME_PANEL:
                return volumePanel();
            case MUTE_ON:
                return setMute(true);
            case MUTE_OFF:
                return setMute(false);
            case PLAY_PAUSE:
                return mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
            case PREV_TRACK:
                return mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            case NEXT_TRACK:
                return mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT);
            case SCREEN_TIMEOUT:
                return cycleScreenTimeout();
            case ROTATE:
                return rotate();
            case ROTATE_RIGHT:
                return rotateTo(1);
            case ROTATE_LEFT:
                return rotateTo(3);
            case NAVBAR_TOGGLE:
                return navbarToggle();
            case HIDE_HANDLES:
                return hideHandlesTemp();
            default:
                return false;
        }
    }

    private boolean previousApp() {
        for (String p : recentPkgs) {
            if (!p.equals(currentPkg)) return launchApp(p);
        }
        return false;
    }

    private boolean splitScreen() {
        try {
            if (performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)) return true;
            Log.i(TAG, "split global action returned false");
        } catch (Throwable t) {
            Log.w(TAG, "split global action failed", t);
        }
        // Android 12+ 已逐步移除该接口（实测 Android 16 返回 false，系统日志 Unknown windowing mode）；
        // 旧版系统/部分厂商 ROM 仍可用，此处保持“尽力而为”。
        return false;
    }

    private boolean keyboardPicker() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showInputMethodPicker();
                Log.i(TAG, "kbd picker direct");
                return true;
            }
        } catch (Throwable t) {
            Log.w(TAG, "kbd picker direct failed", t);
        }
        try {
            Intent i = new Intent(this, InputMethodPickerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(i);
            Log.i(TAG, "kbd picker via activity");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean volumePanel() {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Intent i = new Intent(Settings.Panel.ACTION_VOLUME);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                Log.i(TAG, "volume panel via Settings.Panel");
                return true;
            } catch (Throwable t) {
                Log.w(TAG, "volume panel intent failed", t);
            }
        }
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean navbarToggle() {
        if (!NavbarHelper.granted(this)) {
            Toast.makeText(this, "隐藏导航栏需先通过 ADB 授权（见应用设置）", Toast.LENGTH_SHORT).show();
            return false;
        }
        boolean hide = !NavbarHelper.isHidden(this);
        boolean ok = NavbarHelper.setHidden(this, hide);
        if (ok) {
            prefs.setBool(Prefs.HIDE_NAVBAR, hide);
            navbarHiddenByUs = hide;
        }
        return ok;
    }

    private void syncNavbar() {
        if (prefs == null) return;
        if (prefs.hideNavbar()) {
            if (NavbarHelper.granted(this)) {
                if (NavbarHelper.isHidden(this) || NavbarHelper.setHidden(this, true)) {
                    navbarHiddenByUs = true;
                }
            }
        } else if (navbarHiddenByUs) {
            NavbarHelper.setHidden(this, false);
            navbarHiddenByUs = false;
        }
    }

    private void restoreNavbarIfOurs() {
        Log.i(TAG, "restore navbar check ours=" + navbarHiddenByUs);
        if (navbarHiddenByUs) {
            NavbarHelper.setHidden(this, false);
            navbarHiddenByUs = false;
        }
    }

    private void maybeUpdateIme() {
        if (prefs != null && prefs.pauseKeyboard()) {
            updateImeState();
        } else if (imeVisible) {
            imeVisible = false;
            main.post(this::applyVisibility);
        }
    }

    private void updateImeState() {
        boolean v = false;
        if (prefs != null && prefs.pauseKeyboard()) {
            try {
                List<AccessibilityWindowInfo> ws = getWindows();
                if (ws != null) {
                    for (AccessibilityWindowInfo w : ws) {
                        if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                            v = true;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        if (v != imeVisible) {
            imeVisible = v;
            Log.i(TAG, "ime visible=" + v);
            main.post(this::applyVisibility);
        }
    }

    private boolean imeBlocked() {
        return prefs != null && prefs.pauseKeyboard() && imeVisible;
    }

    private boolean mediaKey(int code) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final int[] TIMEOUTS = {15000, 30000, 60000, 120000, 300000, 600000};

    private boolean cycleScreenTimeout() {
        try {
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, "需要「修改系统设置」权限", Toast.LENGTH_SHORT).show();
                return false;
            }
            int cur = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 60000);
            int next = TIMEOUTS[0];
            for (int t : TIMEOUTS) {
                if (t > cur) {
                    next = t;
                    break;
                }
            }
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, next);
            Toast.makeText(this, "屏幕超时：" + timeoutLabel(next), Toast.LENGTH_SHORT).show();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String timeoutLabel(int ms) {
        int s = ms / 1000;
        if (s < 60) return s + " 秒";
        return (s / 60) + " 分钟";
    }

    private boolean rotate() {
        try {
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, "需要「修改系统设置」权限", Toast.LENGTH_SHORT).show();
                return false;
            }
            int cur = Settings.System.getInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, cur == 1 ? 0 : 1);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean rotateTo(int rotation) {
        try {
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, "需要「修改系统设置」权限", Toast.LENGTH_SHORT).show();
                return false;
            }
            Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
            Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, rotation);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean hideHandlesTemp() {
        tempHidden = true;
        applyVisibility();
        main.removeCallbacks(unhide);
        main.postDelayed(unhide, HIDE_DURATION_MS);
        Toast.makeText(this, "手柄已隐藏 30 秒", Toast.LENGTH_SHORT).show();
        return true;
    }

    private boolean adjustVolume(int dir) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean setMute(boolean mute) {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return false;
            am.adjustStreamVolume(AudioManager.STREAM_RING,
                    mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean assistant() {
        try {
            Intent i = new Intent(Intent.ACTION_ASSIST);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            try {
                Intent i2 = new Intent(Intent.ACTION_VOICE_COMMAND);
                i2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i2);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private boolean launchApp(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean toggleTorch() {
        CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return false;
        try {
            if (torchId == null) {
                for (String id : cm.getCameraIdList()) {
                    CameraCharacteristics c = cm.getCameraCharacteristics(id);
                    Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                    Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                    if (flash != null && flash && facing != null
                            && facing == CameraCharacteristics.LENS_FACING_BACK) {
                        torchId = id;
                        break;
                    }
                }
            }
            if (torchId == null) return false;
            torchOn = !torchOn;
            cm.setTorchMode(torchId, torchOn);
            return true;
        } catch (CameraAccessException | IllegalArgumentException e) {
            return false;
        }
    }

    private boolean screenshot() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        saveShot(result.getHardwareBuffer(), result.getColorSpace());
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "takeScreenshot failed: " + errorCode);
                    }
                });
                return true;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT);
            }
        } catch (Throwable t) {
            Log.w(TAG, "screenshot", t);
        }
        return false;
    }

    private void saveShot(HardwareBuffer buffer, ColorSpace colorSpace) {
        Bitmap bmp = null;
        try {
            bmp = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (bmp == null) return;
            bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
            if (bmp == null) return;
            String name = "OneHand_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OneHand");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os != null) bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            }
            Log.i(TAG, "screenshot saved: " + name);
        } catch (Throwable t) {
            Log.w(TAG, "saveShot", t);
        } finally {
            if (bmp != null) bmp.recycle();
            try { buffer.close(); } catch (Exception ignored) {}
        }
    }

    private void hapticOnce() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {}
    }

    // ---------------- 快速面板 ----------------

    private void showPanel(int mode, boolean leftSide) {
        dismissPanel();
        if (wm == null || prefs == null) return;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF0222226);
        bg.setCornerRadius(Du.dp(this, 18));
        panel.setBackground(bg);
        int p = Du.dp(this, 10);
        panel.setPadding(p, p, p, p);

        if (mode == PANEL_QUICK_TOOLS) buildQuickTools(panel);
        else if (mode == PANEL_QUICK_LAUNCHER) buildQuickLauncher(panel);
        else buildTaskSwitcher(panel);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = (leftSide ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL;
        lp.x = Du.dp(this, 20);
        lp.setTitle("OneHandPanel");

        panel.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) dismissPanel();
            return false;
        });

        try {
            wm.addView(panel, lp);
        } catch (Exception e) {
            Log.w(TAG, "panel addView failed", e);
            return;
        }
        panelView = panel;
        main.removeCallbacks(dismissPanelRunnable);
        main.postDelayed(dismissPanelRunnable, 15000);
        Log.i(TAG, "panel shown mode=" + mode + " side=" + (leftSide ? "L" : "R"));
    }

    private void dismissPanel() {
        if (panelView != null && wm != null) {
            try { wm.removeViewImmediate(panelView); } catch (Exception ignored) {}
        }
        panelView = null;
        main.removeCallbacks(dismissPanelRunnable);
    }

    private void buildQuickTools(LinearLayout panel) {
        boolean canWrite = Settings.System.canWrite(this);
        int cur = 128;
        try {
            cur = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        } catch (Exception ignored) {}
        SeekBar sb = new SeekBar(this);
        sb.setMax(254);
        sb.setProgress(Math.max(1, Math.min(255, cur)) - 1);
        sb.setEnabled(canWrite);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && canWrite) {
                    try {
                        Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS, progress + 1);
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                Du.dp(this, 190), LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.bottomMargin = Du.dp(this, 6);
        panel.addView(sb, slp);
        if (!canWrite) {
            TextView hint = new TextView(this);
            hint.setText("亮度不可调（需「修改系统设置」权限）");
            hint.setTextColor(0x99FFFFFF);
            hint.setTextSize(11);
            hint.setPadding(Du.dp(this, 4), 0, Du.dp(this, 4), Du.dp(this, 6));
            panel.addView(hint);
        }

        LinearLayout row = null;
        int shown = 0;
        for (int i = 1; i <= 6; i++) {
            final String slot = prefs.quickToolSlot(i);
            final GestureAction a = GestureAction.fromId(prefs.actionId(slot));
            if (a == GestureAction.NONE) continue;
            if (shown % 2 == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        Du.dp(this, 190), LinearLayout.LayoutParams.WRAP_CONTENT);
                row.setLayoutParams(rlp);
                panel.addView(row);
            }
            row.addView(panelButton(a.label, v -> {
                execute(a, prefs.actionPkg(slot), true);
                if (prefs.vibrate()) hapticOnce();
                dismissPanel();
            }));
            shown++;
        }
        if (shown == 0) {
            panel.addView(panelHint("未配置按钮 · 在应用设置中配置"));
        }
    }

    private void buildQuickLauncher(LinearLayout panel) {
        int shown = 0;
        for (int i = 1; i <= 6; i++) {
            final String slot = prefs.quickAppSlot(i);
            String pkg = prefs.actionPkg(slot);
            if (GestureAction.fromId(prefs.actionId(slot)) != GestureAction.LAUNCH_APP || pkg.isEmpty()) continue;
            final String fpkg = pkg;
            panel.addView(appRow(fpkg, () -> {
                launchApp(fpkg);
                if (prefs.vibrate()) hapticOnce();
                dismissPanel();
            }));
            shown++;
        }
        if (shown == 0) panel.addView(panelHint("未配置应用 · 在应用设置中配置"));
    }

    private void buildTaskSwitcher(LinearLayout panel) {
        int shown = 0;
        for (String pkg : recentPkgs) {
            if (pkg.equals(currentPkg)) continue;
            if (shown >= 6) break;
            final String fpkg = pkg;
            panel.addView(appRow(fpkg, () -> {
                launchApp(fpkg);
                if (prefs.vibrate()) hapticOnce();
                dismissPanel();
            }));
            shown++;
        }
        if (shown == 0) panel.addView(panelHint("暂无最近应用"));
    }

    private TextView panelButton(String label, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x26FFFFFF);
        bg.setCornerRadius(Du.dp(this, 12));
        t.setBackground(bg);
        t.setPadding(Du.dp(this, 10), Du.dp(this, 10), Du.dp(this, 10), Du.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(Du.dp(this, 3), Du.dp(this, 3), Du.dp(this, 3), Du.dp(this, 3));
        t.setLayoutParams(lp);
        t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setOnClickListener(click);
        return t;
    }

    private View appRow(String pkg, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x1AFFFFFF);
        bg.setCornerRadius(Du.dp(this, 12));
        row.setBackground(bg);
        row.setPadding(Du.dp(this, 10), Du.dp(this, 8), Du.dp(this, 14), Du.dp(this, 8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                Du.dp(this, 190), LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = Du.dp(this, 4);
        row.setLayoutParams(rlp);

        ImageView icon = new ImageView(this);
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            icon.setImageDrawable(d);
        } catch (Exception ignored) {}
        icon.setLayoutParams(new LinearLayout.LayoutParams(Du.dp(this, 24), Du.dp(this, 24)));
        row.addView(icon);

        TextView label = new TextView(this);
        label.setText(appLabel(pkg));
        label.setTextSize(14);
        label.setTextColor(0xFFFFFFFF);
        label.setMaxLines(1);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = Du.dp(this, 10);
        label.setLayoutParams(tlp);
        row.addView(label);

        row.setOnClickListener(v -> onClick.run());
        return row;
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private TextView panelHint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0x99FFFFFF);
        t.setTextSize(12);
        t.setPadding(Du.dp(this, 6), Du.dp(this, 4), Du.dp(this, 6), Du.dp(this, 4));
        return t;
    }
}
