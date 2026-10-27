package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentValues;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
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

import io.buildlee.onehand.takeover.TakeoverManager;

/**
 * 边缘手势服务：多手柄管理 + 6 种手势 × 每手柄映射 + 动作执行 + 快速面板。
 */
public class EdgeService extends AccessibilityService implements TakeoverManager.Host {

    private static final String TAG = "OneHand";
    private static final long HIDE_DURATION_MS = 30000;
    private static final int RECENT_MAX = 8;
    private static final long PRESS_HOLD_MS = 500;

    private static final int PANEL_QUICK_TOOLS = 0;
    private static final int PANEL_QUICK_LAUNCHER = 1;
    private static final int PANEL_TASK_SWITCHER = 2;

    private Prefs prefs;
    private WindowManager wm;
    private FeedbackView feedback;
    private final int[] feedbackLocation = new int[2];
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
    private TouchpadOverlay touchpad;
    private FloatNavOverlay floatNav;
    private String homePkg = "";
    private boolean isHome = false;
    private int imeTop = 0;
    private boolean quickVibed = false;
    private static volatile EdgeService connectedService;
    private TakeoverManager takeover;
    private boolean takeoverWindows;
    private volatile boolean takeoverWindowsReady;
    private String calibratedGeometry = "";
    private View leftRouter, rightRouter;
    private boolean screenReceiverRegistered;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (takeover == null) return;
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && takeover.status() != TakeoverManager.Status.IDLE)
                takeover.pauseForScene();
            else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || Intent.ACTION_USER_PRESENT.equals(intent.getAction()))
                takeover.sceneMayHaveChanged();
        }
    };

    public static EdgeService connectedService() { return connectedService; }

    private final Runnable restorePkg = () -> {
        currentPkg = "";
        applyVisibility();
    };

    private final Runnable unhide = () -> {
        tempHidden = false;
        applyVisibility();
    };

    private final Runnable dismissPanelRunnable = this::dismissPanel;

    private final Runnable refreshForIme = this::refreshHandles;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (key == null) {
            main.post(() -> { refreshHandles(); dismissPanel(); hideOverlays(); syncNavbar(); });
            return;
        }
        if (Prefs.THEME.equals(key)) {
            main.post(() -> { refreshHandles(); dismissPanel(); hideOverlays(); });
        }
        if (key.startsWith("h_")
                || key.startsWith("handle_count_")
                || Prefs.MASTER_ENABLED.equals(key)
                || Prefs.LEFT_ENABLED.equals(key)
                || Prefs.RIGHT_ENABLED.equals(key)
                || Prefs.LANDSCAPE_ENABLED.equals(key)
                || Prefs.HANDLE_WIDTH_DP.equals(key)
                || Prefs.SHOW_INDICATOR.equals(key)
                || Prefs.ANIM_STYLE.equals(key)
                || Prefs.ANIM_COLOR.equals(key)
                || Prefs.HANDLE_MARGIN_DP.equals(key)
                || Prefs.FEEDBACK_STYLE.equals(key)
                || key.startsWith("wave_")
                || Prefs.HANDLE_COLOR.equals(key)
                || Prefs.EXCLUDED_APPS.equals(key)) {
            main.post(() -> {
                if (takeover != null && takeover.isPausingScene()) takeover.stop();
                refreshHandles();
            });
        }
        if (Prefs.PAUSE_KEYBOARD.equals(key) || Prefs.KEYBOARD_MODE.equals(key)) {
            main.post(() -> {
                if (takeover != null && takeover.isPausingScene()) takeover.stop();
                updateImeState();
                refreshHandles();
            });
        }
        if (Prefs.HIDE_ON_HOME.equals(key)) {
            main.post(this::applyVisibility);
        }
        if (Prefs.HIDE_NAVBAR.equals(key)) {
            main.post(() -> {
                if (takeover != null && (takeoverWindows || takeover.isPausingScene())) takeover.stop();
                else syncNavbar();
            });
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        prefs = new Prefs(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        takeover = TakeoverManager.get(this);
        connectedService = this;
        IntentFilter screenFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        screenFilter.addAction(Intent.ACTION_SCREEN_ON);
        screenFilter.addAction(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, screenFilter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screenReceiver, screenFilter);
        screenReceiverRegistered = true;
        prefs.registerListener(prefListener);
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(home, 0);
            if (ri != null && ri.activityInfo != null) homePkg = ri.activityInfo.packageName;
        } catch (Exception ignored) {}
        Log.i(TAG, "service connected homePkg=" + homePkg);
        refreshHandles();
        updateImeState();
        syncNavbar();
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "service destroy");
        if (connectedService == this) connectedService = null;
        if (takeover != null && takeover.status() != TakeoverManager.Status.IDLE) takeover.stop();
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
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
        hideOverlays();
        restoreNavbarIfOurs();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "service unbind");
        if (connectedService == this) connectedService = null;
        if (takeover != null && takeover.status() != TakeoverManager.Status.IDLE) takeover.stop();
        if (screenReceiverRegistered) { unregisterReceiver(screenReceiver); screenReceiverRegistered = false; }
        if (prefs != null) {
            try { prefs.unregisterListener(prefListener); } catch (Exception ignored) {}
        }
        dismissPanel();
        detachAll();
        hideOverlays();
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
        if (pkg.equals(getPackageName())) {
            isHome = false;
            currentPkg = "";
            main.removeCallbacks(restorePkg);
            main.post(this::applyVisibility);
            return;
        }
        if (!homePkg.isEmpty() && homePkg.equals(pkg)) {
            if (!isHome) {
                isHome = true;
                main.post(this::applyVisibility);
            }
            main.removeCallbacks(restorePkg);
            return;
        }
        isHome = false;
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
            if (takeoverWindows && takeover != null && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                takeover.pauseForScene();
                return;
            }
            dismissPanel();
            maybeUpdateIme();
            refreshHandles();
            if (takeover != null) takeover.sceneMayHaveChanged();
        });
    }

    // ---------------- 手柄管理 ----------------

    private void refreshHandles() {
        if (wm == null || prefs == null) return;
        if (takeoverWindows && takeover != null && takeover.status() != TakeoverManager.Status.IDLE) {
            if (!takeover.isPausingScene()) takeover.stop();
            return;
        }
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
        takeoverWindowsReady = false;
        for (View router : new View[]{leftRouter, rightRouter}) {
            if (router != null) {
                if (router.getTag() instanceof RouterTouch) ((RouterTouch) router.getTag()).cancel();
                try { wm.removeViewImmediate(router); } catch (Exception ignored) { }
            }
        }
        leftRouter = rightRouter = null;
        if (feedback != null) {
            feedback.clear();
            try { wm.removeViewImmediate(feedback); } catch (Exception ignored) {}
            feedback = null;
        }
        for (View v : handleViews.values()) {
            if (v.getTag() instanceof HandleTouch) ((HandleTouch) v.getTag()).cancel();
            try { wm.removeViewImmediate(v); } catch (Exception ignored) {}
        }
        handleViews.clear();
    }

    private void showFeedback(boolean left, float y, float inward, float dy) {
        if (feedback == null) {
            FeedbackView candidate = new FeedbackView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.preferredRefreshRate = Motion.maxRefreshRate(this);
            try { wm.addView(candidate, lp); feedback = candidate; }
            catch (Exception e) { Log.w(TAG, "feedback overlay unavailable", e); return; }
        }
        feedback.getLocationOnScreen(feedbackLocation);
        int margin = takeoverWindows ? 0 : prefs.handleMarginDp();
        float edge = left ? Du.dp(this, margin)
                : Du.screenSize(this).x - Du.dp(this, margin);
        feedback.show(left, edge - feedbackLocation[0], y - feedbackLocation[1], inward, dy);
    }

    private void attachHandle(String id) {
        boolean left = id.startsWith("L");
        Point size = Du.screenSize(this);
        int widthPx = Du.dp(this, prefs.handleWidthDp());
        int heightPx = Math.round(size.y * prefs.handleLen(id) / 100f);
        int margin = takeoverWindows ? 0 : Du.dp(this, prefs.handleMarginDp());
        int usableBottom = size.y;
        if (imeVisible && Prefs.KB_ABOVE.equals(prefs.keyboardMode()) && imeTop > 0) {
            usableBottom = Math.max(Du.dp(this, 240), imeTop);
        }
        int maxH = Math.max(Du.dp(this, 120), usableBottom - margin * 2);
        if (heightPx > maxH) heightPx = maxH;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                widthPx, heightPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (left ? Gravity.LEFT : Gravity.RIGHT);
        if (takeoverWindows) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.x = margin;
        lp.y = Math.max(0, Math.round((usableBottom - heightPx) * prefs.handlePos(id) / 100f));
        float rr = Motion.maxRefreshRate(this);
        if (rr > 0) lp.preferredRefreshRate = rr;

        EdgeHandleView view = new EdgeHandleView(this, left);
        view.setBaseAlpha(prefs.showIndicator() ? Math.round(prefs.handleAlpha(id) * 255f / 100f) : 0);
        view.setColor(prefs.handleColor());
        view.setAnimColor(prefs.animColor());
        HandleTouch touch = new HandleTouch(id, left);
        view.setTag(touch);
        view.setOnTouchListener(touch);

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            Log.w(TAG, "addView failed", e);
            return;
        }
        handleViews.put(id, view);
        Log.i(TAG, "handle attached id=" + id + " " + widthPx + "x" + heightPx + " y=" + lp.y);
    }

    @Override public boolean prepareTakeoverWindows() {
        if (connectedService != this || prefs == null || wm == null || !prefs.masterEnabled()
                || !prefs.sideEnabled("L") || !prefs.sideEnabled("R") || tempHidden
                || homeBlocked() || prefs.isExcluded(currentPkg)
                || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) return false;
        String geometry = takeoverGeometry();
        if (takeover != null && takeover.reusingCalibration()) {
            if (!geometry.equals(calibratedGeometry)) return false;
        } else calibratedGeometry = geometry;
        takeoverWindows = true;
        updateImeState();
        if (imeVisible) { takeoverWindows = false; return false; }
        detachAll();
        for (String side : Prefs.SIDES) {
            for (int i = 1; i <= prefs.handleCount(side); i++) {
                String id = Prefs.handleId(side, i);
                if (prefs.handleEnabled(id)) attachHandle(id);
            }
        }
        boolean hasLeft = false, hasRight = false;
        for (String id : handleViews.keySet()) {
            if (id.startsWith("L")) hasLeft = true;
            if (id.startsWith("R")) hasRight = true;
        }
        if (hasLeft && hasRight) {
            leftRouter = attachRouter(true);
            rightRouter = attachRouter(false);
        }
        takeoverWindowsReady = leftRouter != null && rightRouter != null;
        if (!takeoverWindowsReady) finishTakeoverWindows();
        return takeoverWindowsReady;
    }

    @Override public boolean takeoverWindowsHealthy() {
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        int navigation = navigationMode();
        return connectedService == this && takeoverWindowsReady && (navigation < 0 || navigation == 2)
                && power != null && power.isInteractive()
                && keyguard != null && !keyguard.isKeyguardLocked();
    }

    @Override public boolean sceneTemporarilyBlocked() {
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()
                || imeVisible || tempHidden || homeBlocked() || (prefs != null && prefs.isExcluded(currentPkg))
                || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override public boolean canResumeTakeover() {
        if (connectedService != this || prefs == null || wm == null || !prefs.masterEnabled()
                || !prefs.sideEnabled("L") || !prefs.sideEnabled("R") || imeVisible || tempHidden
                || homeBlocked() || prefs.isExcluded(currentPkg) || prefs.hideNavbar() || NavbarHelper.isHidden(this)
                || getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) return false;
        if (!takeoverGeometry().equals(calibratedGeometry)) return false;
        int navigation = navigationMode();
        if (navigation >= 0 && navigation != 2) return false;
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        android.app.KeyguardManager keyguard = (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) return false;
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            android.graphics.Insets insets = wm.getCurrentWindowMetrics().getWindowInsets()
                    .getInsets(android.view.WindowInsets.Type.systemGestures());
            return insets.left > 0 && insets.right > 0;
        } catch (RuntimeException e) { return false; }
    }

    private String takeoverGeometry() {
        Point size = Du.screenSize(this);
        return size.x + "x" + size.y + ":" + getResources().getDisplayMetrics().densityDpi
                + ":" + wm.getDefaultDisplay().getRotation() + ":" + navigationMode();
    }

    private int navigationMode() {
        try {
            int id = getResources().getIdentifier("config_navBarInteractionMode", "integer", "android");
            return id == 0 ? -1 : getResources().getInteger(id);
        } catch (RuntimeException e) { return -1; }
    }

    @Override public void finishTakeoverWindows() {
        takeoverWindows = false;
        detachAll();
        if (connectedService == this) { refreshHandles(); syncNavbar(); }
    }

    private View attachRouter(boolean left) {
        Point size = Du.screenSize(this);
        int top = Du.dp(this, 24), bottom = Du.dp(this, 24);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.view.WindowInsets insets = wm.getCurrentWindowMetrics().getWindowInsets();
                top = Math.max(top, insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top);
                bottom = Math.max(bottom, insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom);
            } catch (RuntimeException e) { Log.w(TAG, "gesture insets unavailable", e); return null; }
        }
        int height = size.y - top - bottom;
        if (height <= Du.dp(this, 120)) return null;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Du.dp(this, prefs.handleWidthDp()), height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (left ? Gravity.LEFT : Gravity.RIGHT);
        lp.y = top;
        View view = new View(this);
        RouterTouch touch = new RouterTouch(left);
        view.setTag(touch);
        view.setOnTouchListener(touch);
        try { wm.addView(view, lp); return view; }
        catch (RuntimeException e) { Log.w(TAG, "takeover input window unavailable", e); return null; }
    }

    private final class RouterTouch implements View.OnTouchListener {
        private final boolean left;
        private HandleTouch delegate;
        private View handle;
        private float startX, startY, lastX, lastY, maxInward;
        RouterTouch(boolean left) { this.left = left; }
        void cancel() { if (delegate != null) delegate.cancel(); delegate = null; handle = null; }
        @Override public boolean onTouch(View view, MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                cancel();
                startX = lastX = event.getRawX(); startY = lastY = event.getRawY(); maxInward = 0;
                String side = left ? "L" : "R";
                for (int i = 1; i <= prefs.handleCount(side); i++) {
                    View candidate = handleViews.get(Prefs.handleId(side, i));
                    if (candidate == null || candidate.getVisibility() != View.VISIBLE) continue;
                    int[] location = new int[2]; candidate.getLocationOnScreen(location);
                    if (startY >= location[1] && startY < location[1] + candidate.getHeight()) {
                        handle = candidate; delegate = (HandleTouch) candidate.getTag(); break;
                    }
                }
            }
            if (delegate != null) return delegate.onTouch(handle, event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: return true;
                case MotionEvent.ACTION_MOVE:
                    lastX = event.getRawX(); lastY = event.getRawY();
                    float inward = left ? lastX - startX : startX - lastX;
                    maxInward = Math.max(maxInward, inward);
                    showFeedback(left, startY, inward, lastY - startY);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (feedback != null) feedback.dismiss();
                    float finalInward = left ? lastX - startX : startX - lastX;
                    if (maxInward >= shortPx() && finalInward >= shortPx() * .6f) dispatchTakeoverBack(left);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (feedback != null) feedback.dismiss();
                    return true;
                default: return true;
            }
        }
    }

    private void dispatchTakeoverBack(boolean left) {
        if (takeover == null) return;
        boolean trial = takeover.status() == TakeoverManager.Status.TRIAL;
        if (trial) takeover.expectCalibrationBack(left);
        boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
        Log.i(TAG, "takeover fallback side=" + (left ? "L" : "R") + " ok=" + ok);
        if (!ok) { takeover.cancelCalibrationBack(); takeover.stop(); }
    }

    private void applyVisibility() {
        if (prefs == null) return;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean env = prefs.masterEnabled()
                && (!landscape || prefs.landscapeEnabled())
                && !tempHidden
                && !imeBlocked()
                && !homeBlocked()
                && !prefs.isExcluded(currentPkg);
        if (takeoverWindows && !env && takeover != null) {
            if (!prefs.masterEnabled() || !prefs.sideEnabled("L") || !prefs.sideEnabled("R")) takeover.stop();
            else takeover.pauseForScene();
        }
        if (env && takeover != null && takeover.isPausedForScene()) takeover.sceneMayHaveChanged();
        for (View v : handleViews.values()) {
            setVis(v, env);
        }
        if (!env && feedback != null) feedback.clear();
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
        private boolean instantFired;
        private long lastMoveTime;
        private long crossTime;

        HandleTouch(String handleId, boolean left) {
            this.handleId = handleId;
            this.left = left;
        }

        void cancel() {
            down = false;
            main.removeCallbacks(pressHoldTask);
            main.removeCallbacks(holdCheckTask);
        }

        /** 长按手柄（原地按住不动）→ -P 槽位动作 */
        private final Runnable pressHoldTask = () -> {
            if (!down || pressFired || holdFired || instantFired) return;
            if (maxInward > slop || Math.hypot(lastX - downX, lastY - downY) > slop * 2f) return;
            String slot = Prefs.pressSlot(handleId);
            GestureAction a = GestureAction.fromId(prefs.actionId(slot));
            if (a == GestureAction.NONE) return;
            pressFired = true;
            boolean ok = execute(a, prefs.actionPkg(slot), left);
            Log.i(TAG, "press-hold slot=" + slot + " action=" + a.id + " ok=" + ok);
            if (ok && prefs.vibrate() && immediateFeedback(a)) hapticOnce();
        };

        /** 滑动保持（滑入后停住不动，超过设定时长）→ -H-<dir> 槽位动作；未配置则不触发，松手走普通判定 */
        private final Runnable holdCheckTask = new Runnable() {
            @Override
            public void run() {
                if (!down || holdFired || pressFired || instantFired) return;
                if (maxInward < shortPx()) return;
                // 快捷操作（长滑）：达到长滑距离并按住足够时长后立即执行
                if (!instantFired && prefs.instantLong() && prefs.longEnabled()
                        && maxInward >= longPx() && prefs.longHoldMs() > 0
                        && crossTime > 0 && System.currentTimeMillis() - crossTime >= prefs.longHoldMs()) {
                    float idx = left ? (lastX - downX) : (downX - lastX);
                    if (idx > 0) {
                        instantFired = true;
                        String d = dirFor(idx, lastY - downY);
                        runGesture(handleId, "L", d, left);
                        return;
                    }
                }
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
                if (ok && prefs.vibrate() && immediateFeedback(a)) hapticOnce();
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    down = true;
                    pressFired = false;
                    holdFired = false;
                    instantFired = false;
                    crossTime = 0;
                    quickVibed = false;
                    downX = lastX = e.getRawX();
                    downY = lastY = e.getRawY();
                    maxInward = 0;
                    longFed = false;
                    lastMoveTime = System.currentTimeMillis();
                    main.removeCallbacks(pressHoldTask);
                    main.removeCallbacks(holdCheckTask);
                    if (takeover == null || takeover.status() != TakeoverManager.Status.TRIAL)
                        main.postDelayed(pressHoldTask, PRESS_HOLD_MS);
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    lastMoveTime = System.currentTimeMillis();
                    float inward = left ? (lastX - downX) : (downX - lastX);
                    showFeedback(left, downY, inward, lastY - downY);
                    if (inward > maxInward) maxInward = inward;
                    if (maxInward > slop || Math.hypot(lastX - downX, lastY - downY) > slop * 2f) {
                        main.removeCallbacks(pressHoldTask);
                    }
                    if (takeover != null && takeover.status() == TakeoverManager.Status.TRIAL) return true;
                    // 快速振动：越过触发距离立即振一次（对齐 OHO+「快速振动」）
                    if (!quickVibed && maxInward >= shortPx() && prefs.vibrate() && prefs.quickVibrate()) {
                        quickVibed = true;
                        hapticOnce();
                    }
                    if (!longFed && prefs.longEnabled() && maxInward >= longPx()) {
                        longFed = true;
                        if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setHot(true);
                        if (crossTime == 0) crossTime = System.currentTimeMillis();
                        if (prefs.longHint()) hapticOnce();
                    }
                    // 快捷操作：越过阈值立即执行（不等松手）
                    if (!instantFired && !pressFired && !holdFired && maxInward >= shortPx()) {
                        float idir = left ? (lastX - downX) : (downX - lastX);
                        if (idir > 0) {
                            String d = dirFor(idir, lastY - downY);
                            if (prefs.instantShort()) {
                                instantFired = true;
                                runGesture(handleId, "S", d, left);
                            } else if (prefs.instantLong() && prefs.longEnabled()
                                    && maxInward >= longPx() && prefs.longHoldMs() <= 0) {
                                instantFired = true;
                                runGesture(handleId, "L", d, left);
                            }
                        }
                    }
                    if (!holdFired && !pressFired && maxInward >= shortPx()) {
                        main.removeCallbacks(holdCheckTask);
                        main.postDelayed(holdCheckTask, 80);
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (feedback != null) feedback.dismiss();
                    down = false;
                    main.removeCallbacks(pressHoldTask);
                    main.removeCallbacks(holdCheckTask);
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(false);
                    if (pressFired || holdFired || instantFired) return true;
                    long held = crossTime > 0 ? System.currentTimeMillis() - crossTime : 0;
                    commit(handleId, left, lastX - downX, lastY - downY, maxInward, held);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (feedback != null) feedback.clear();
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
        int lim = prefs != null ? prefs.angleDeg() : 28;
        double angle = Math.toDegrees(Math.atan2(dy, Math.max(dx, 1f)));
        if (angle <= -lim) return "U";
        if (angle >= lim) return "D";
        return "H";
    }

    private void commit(String handleId, boolean leftSide, float dxRaw, float dy, float maxInward, long longHeldMs) {
        float dx = leftSide ? dxRaw : -dxRaw;
        if (dx <= 0 || maxInward < shortPx() * 0.6f || (takeoverWindows && dx < shortPx() * 0.6f)) {
            Log.i(TAG, "gesture ignored id=" + handleId + " dx=" + dx + " inward=" + maxInward);
            return;
        }
        String dir = dirFor(dx, dy);
        boolean isLong = prefs.longEnabled() && maxInward >= longPx()
                && (prefs.longHoldMs() <= 0 || longHeldMs >= prefs.longHoldMs());
        String len = isLong ? "L" : "S";
        Log.i(TAG, "gesture id=" + handleId + " len=" + len + " dir=" + dir
                + " inward=" + Math.round(maxInward));
        runGesture(handleId, len, dir, leftSide);
    }

    private void runGesture(String handleId, String len, String dir, boolean leftSide) {
        if (takeover != null && takeover.status() == TakeoverManager.Status.TRIAL) {
            dispatchTakeoverBack(leftSide);
            return;
        }
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
        if (!ok && action == GestureAction.BACK && takeoverWindows && takeover != null) takeover.stop();
        boolean pendingShot = action == GestureAction.SCREENSHOT && Build.VERSION.SDK_INT >= 30 && ok;
        Log.i(TAG, "gesture slot=" + slot + " action=" + action.id + (pendingShot ? " submitted=true" : " ok=" + ok));
        if (ok && !pendingShot && prefs.vibrate() && !quickVibed) hapticOnce();
    }

    // ---------------- 动作执行 ----------------

    private boolean immediateFeedback(GestureAction action) {
        return action != GestureAction.SCREENSHOT || Build.VERSION.SDK_INT < 30;
    }

    private boolean execute(GestureAction a, String pkg, boolean leftSide) {
        boolean ok;
        try { ok = executeInternal(a, pkg, leftSide); }
        catch (RuntimeException e) { Log.w(TAG, "action failed: " + a.id, e); ok = false; }
        if (!ok && a != GestureAction.NONE) Toast.makeText(this,
                a.label + "未执行，请检查系统支持、应用和相关权限", Toast.LENGTH_SHORT).show();
        return ok;
    }

    private boolean executeInternal(GestureAction a, String pkg, boolean leftSide) {
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
            case SEARCH:
                return search();
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
            case TOUCHPAD:
                return toggleTouchpad();
            case FLOAT_NAV:
                return toggleFloatNav();
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
            case COLOR_INVERT:
                return colorInvert();
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

    private boolean search() {
        try {
            Intent i = new Intent(Intent.ACTION_WEB_SEARCH);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean toggleTouchpad() {
        if (touchpad == null) touchpad = new TouchpadOverlay(this, () -> {});
        touchpad.toggle();
        return true;
    }

    private boolean toggleFloatNav() {
        if (floatNav == null) floatNav = new FloatNavOverlay(this);
        floatNav.toggle();
        return true;
    }

    private boolean colorInvert() {
        if (!NavbarHelper.granted(this)) {
            Toast.makeText(this, "颜色反转需先通过 ADB 授权（见应用设置）", Toast.LENGTH_SHORT).show();
            return false;
        }
        try {
            int cur = Settings.Secure.getInt(getContentResolver(), "accessibility_display_inversion_enabled", 0);
            Settings.Secure.putInt(getContentResolver(), "accessibility_display_inversion_enabled", cur == 1 ? 0 : 1);
            Log.i(TAG, "color invert -> " + (cur == 1 ? 0 : 1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void hideOverlays() {
        if (touchpad != null) touchpad.hide();
        if (floatNav != null) floatNav.hide();
    }

    private void restoreNavbarIfOurs() {
        Log.i(TAG, "restore navbar check ours=" + navbarHiddenByUs);
        if (navbarHiddenByUs) {
            NavbarHelper.setHidden(this, false);
            navbarHiddenByUs = false;
        }
    }

    private void maybeUpdateIme() {
        if (prefs != null && (takeoverWindows || (takeover != null && takeover.isPausedForScene())
                || !Prefs.KB_OFF.equals(prefs.keyboardMode()))) {
            updateImeState();
        } else if (imeVisible) {
            imeVisible = false;
            main.post(this::applyVisibility);
        }
    }

    private void updateImeState() {
        boolean v = false;
        int top = 0;
        String mode = prefs != null ? prefs.keyboardMode() : Prefs.KB_OFF;
        if (prefs != null && (takeoverWindows || (takeover != null && takeover.isPausedForScene())
                || !Prefs.KB_OFF.equals(mode))) {
            try {
                List<AccessibilityWindowInfo> ws = getWindows();
                if (ws != null) {
                    for (AccessibilityWindowInfo w : ws) {
                        if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                            android.graphics.Rect b = new android.graphics.Rect();
                            w.getBoundsInScreen(b);
                            if (b.height() < Du.dp(this, 80)) continue;
                            v = true;
                            top = b.top;
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        boolean changed = v != imeVisible || (v && top != imeTop);
        imeVisible = v;
        if (v && top > 0) imeTop = top;
        if (changed) {
            Log.i(TAG, "ime visible=" + v + " top=" + imeTop + " mode=" + mode);
            if (v && takeoverWindows && takeover != null) {
                if (takeover.status() != TakeoverManager.Status.STARTING) takeover.pauseForScene();
                return;
            }
            if (!v && takeover != null && takeover.isPausedForScene()) takeover.sceneMayHaveChanged();
            if (Prefs.KB_ABOVE.equals(mode)) {
                main.removeCallbacks(refreshForIme);
                main.postDelayed(refreshForIme, 250);
            } else {
                main.post(this::applyVisibility);
            }
        }
    }

    private boolean imeBlocked() {
        return prefs != null && Prefs.KB_HIDE.equals(prefs.keyboardMode()) && imeVisible;
    }

    private boolean homeBlocked() {
        return prefs != null && prefs.hideOnHome() && isHome;
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
        final boolean alreadyVibrated = quickVibed;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        boolean saved = saveShot(result.getHardwareBuffer(), result.getColorSpace());
                        Toast.makeText(EdgeService.this, saved ? "截图已保存到 Pictures/OneHand" : "截图保存失败", Toast.LENGTH_SHORT).show();
                        if (saved && prefs.vibrate() && !alreadyVibrated) hapticOnce();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.w(TAG, "takeScreenshot failed: " + errorCode);
                        Toast.makeText(EdgeService.this, "截图失败，当前窗口可能受保护或系统暂不可用", Toast.LENGTH_SHORT).show();
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

    @androidx.annotation.RequiresApi(30)
    private boolean saveShot(HardwareBuffer buffer, ColorSpace colorSpace) {
        Bitmap bmp = null;
        Uri pending = null;
        try {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, colorSpace);
            if (hardware == null) return false;
            try { bmp = hardware.copy(Bitmap.Config.ARGB_8888, false); }
            finally { hardware.recycle(); }
            if (bmp == null) return false;
            String name = "OneHand_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".png";
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OneHand");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            pending = uri;
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null || !bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) return false;
            }
            ContentValues published = new ContentValues(); published.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (getContentResolver().update(uri, published, null, null) <= 0) return false;
            pending = null;
            Log.i(TAG, "screenshot saved: " + name);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "saveShot", t);
            return false;
        } finally {
            if (pending != null) try { getContentResolver().delete(pending, null, null); } catch (Exception ignored) {}
            if (bmp != null) bmp.recycle();
            try { buffer.close(); } catch (Exception ignored) {}
        }
    }

    private void hapticOnce() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                int amp = prefs != null ? prefs.vibrateAmp() : 200;
                v.vibrate(VibrationEffect.createOneShot(15, amp));
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
        bg.setColor(UiKit.col(this, R.color.card));
        bg.setCornerRadius(Du.dp(this, 16));
        panel.setBackground(bg);
        int p = Du.dp(this, 10);
        panel.setPadding(p, p, p, p);
        TextView close = panelButton("关闭面板", v -> dismissPanel());
        close.setContentDescription("关闭面板");
        close.setLayoutParams(new LinearLayout.LayoutParams(Du.dp(this, 190), Du.dp(this, 48)));
        panel.addView(close);

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

        android.widget.ScrollView host = new android.widget.ScrollView(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int max = Math.max(Du.dp(EdgeService.this, 96), Du.screenSize(EdgeService.this).y - Du.dp(EdgeService.this, 48));
                if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) max = Math.min(max, MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        host.setVerticalScrollBarEnabled(false);
        host.addView(panel);
        host.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) dismissPanel();
            return false;
        });

        try {
            wm.addView(host, lp);
        } catch (Exception e) {
            Log.w(TAG, "panel addView failed", e);
            return;
        }
        panelView = host;
        Motion.popIn(host, leftSide);
        main.removeCallbacks(dismissPanelRunnable);
        main.postDelayed(dismissPanelRunnable, 15000);
        Log.i(TAG, "panel shown mode=" + mode + " side=" + (leftSide ? "L" : "R"));
    }

    private void dismissPanel() {
        final View pv = panelView;
        panelView = null;
        main.removeCallbacks(dismissPanelRunnable);
        if (pv != null && wm != null) {
            Motion.fadeOut(pv, () -> {
                try { wm.removeViewImmediate(pv); } catch (Exception ignored) {}
            });
        }
    }

    private void buildQuickTools(LinearLayout panel) {
        boolean canWrite = Settings.System.canWrite(this);
        int cur = 128;
        try {
            cur = Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        } catch (Exception ignored) {}
        SeekBar sb = new SeekBar(this);
        sb.setMax(254);
        sb.setContentDescription("屏幕亮度");
        sb.setMinimumHeight(Du.dp(this, 48));
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
            hint.setTextColor(UiKit.col(this, R.color.text_secondary));
            hint.setTextSize(11);
            hint.setPadding(Du.dp(this, 4), 0, Du.dp(this, 4), Du.dp(this, 6));
            hint.setSingleLine(false);
            panel.addView(hint, new LinearLayout.LayoutParams(Du.dp(this, 190), LinearLayout.LayoutParams.WRAP_CONTENT));
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
                boolean ok = execute(a, prefs.actionPkg(slot), true);
                if (ok && prefs.vibrate() && immediateFeedback(a)) hapticOnce();
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
        t.setTextColor(UiKit.col(this, R.color.text_primary));
        t.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(UiKit.col(this, R.color.fill));
        bg.setCornerRadius(Du.dp(this, 12));
        t.setBackground(bg);
        t.setMinHeight(Du.dp(this, 48));
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
        bg.setColor(UiKit.col(this, R.color.fill));
        bg.setCornerRadius(Du.dp(this, 12));
        row.setBackground(bg);
        row.setMinimumHeight(Du.dp(this, 48));
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
        label.setTextColor(UiKit.col(this, R.color.text_primary));
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
        t.setTextColor(UiKit.col(this, R.color.text_secondary));
        t.setTextSize(12);
        t.setPadding(Du.dp(this, 6), Du.dp(this, 4), Du.dp(this, 6), Du.dp(this, 4));
        return t;
    }
}
