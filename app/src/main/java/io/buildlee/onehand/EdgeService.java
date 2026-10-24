package io.buildlee.onehand;

import android.accessibilityservice.AccessibilityService;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.graphics.PixelFormat;
import android.graphics.Point;
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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 边缘手势服务：在屏幕左右边缘放置透明手柄，识别 6 种手势并执行映射的动作。
 */
public class EdgeService extends AccessibilityService {

    private static final String TAG = "OneHand";

    private Prefs prefs;
    private WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View leftHandle, rightHandle;
    private String currentPkg = "";
    private boolean torchOn = false;
    private String torchId = null;

    private final Runnable restorePkg = () -> {
        currentPkg = "";
        applyVisibility();
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener = (sp, key) -> {
        if (key == null) return;
        if (Prefs.MASTER_ENABLED.equals(key)
                || Prefs.LEFT_ENABLED.equals(key)
                || Prefs.RIGHT_ENABLED.equals(key)
                || Prefs.LANDSCAPE_ENABLED.equals(key)
                || Prefs.HANDLE_WIDTH_DP.equals(key)
                || Prefs.HANDLE_HEIGHT_PCT.equals(key)
                || Prefs.HANDLE_POS_PCT.equals(key)
                || Prefs.HANDLE_ALPHA.equals(key)
                || Prefs.SHOW_INDICATOR.equals(key)
                || Prefs.EXCLUDED_APPS.equals(key)) {
            main.post(this::refreshHandles);
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
    }

    @Override
    public void onDestroy() {
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
        detachHandles();
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (prefs != null) {
            try { prefs.unregisterListener(prefListener); } catch (Exception ignored) {}
        }
        detachHandles();
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (prefs == null) return;
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence p = event.getPackageName();
        if (p == null) return;
        String pkg = p.toString();
        if (pkg.equals(getPackageName())) return;
        main.removeCallbacks(restorePkg);
        if (isTransient(pkg)) {
            main.postDelayed(restorePkg, 800);
        } else {
            currentPkg = pkg;
            applyVisibility();
        }
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
        main.post(this::refreshHandles);
    }

    // ---------------- 手柄管理 ----------------

    private void refreshHandles() {
        if (wm == null || prefs == null) return;
        detachHandles();
        if (prefs.masterEnabled()) {
            attachSide("L");
            attachSide("R");
        }
        applyVisibility();
    }

    private void detachHandles() {
        if (wm == null) return;
        View[] vs = {leftHandle, rightHandle};
        for (View v : vs) {
            if (v != null) {
                try { wm.removeViewImmediate(v); } catch (Exception ignored) {}
            }
        }
        leftHandle = null;
        rightHandle = null;
    }

    private void attachSide(String side) {
        boolean left = side.equals("L");
        Point size = Du.screenSize(this);
        int widthPx = Du.dp(this, prefs.handleWidthDp());
        int heightPx = Math.round(size.y * prefs.handleHeightPct() / 100f);
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
        lp.y = Math.max(0, Math.round((size.y - heightPx) * prefs.handlePosPct() / 100f));

        EdgeHandleView view = new EdgeHandleView(this, left);
        int alphaPct = prefs.handleAlphaPct();
        view.setBaseAlpha(prefs.showIndicator() ? Math.round(alphaPct * 255f / 100f) : 0);
        view.setOnTouchListener(new HandleTouch(left));

        try {
            wm.addView(view, lp);
        } catch (Exception e) {
            Log.w(TAG, "addView failed", e);
            return;
        }
        if (left) leftHandle = view; else rightHandle = view;
        Log.i(TAG, "handle attached side=" + side + " " + widthPx + "x" + heightPx + " y=" + lp.y);
    }

    private void applyVisibility() {
        if (prefs == null) return;
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        boolean gestureOn = prefs.masterEnabled()
                && (!landscape || prefs.landscapeEnabled())
                && !prefs.isExcluded(currentPkg);
        setVis(leftHandle, gestureOn && prefs.sideEnabled("L"));
        setVis(rightHandle, gestureOn && prefs.sideEnabled("R"));
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
        private final boolean left;
        private float downX, downY, lastX, lastY, maxInward;
        private boolean longFed;

        HandleTouch(boolean left) {
            this.left = left;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    downX = lastX = e.getRawX();
                    downY = lastY = e.getRawY();
                    maxInward = 0;
                    longFed = false;
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(true);
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    lastX = e.getRawX();
                    lastY = e.getRawY();
                    float inward = left ? (lastX - downX) : (downX - lastX);
                    if (inward > maxInward) maxInward = inward;
                    if (!longFed && maxInward >= longPx()) {
                        longFed = true;
                        if (prefs.longHint()) hapticOnce();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(false);
                    commit(left, lastX - downX, lastY - downY, maxInward);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    if (v instanceof EdgeHandleView) ((EdgeHandleView) v).setActive(false);
                    return true;
                }
                default:
                    return false;
            }
        }
    }

    private void commit(boolean leftSide, float dxRaw, float dy, float maxInward) {
        float dx = leftSide ? dxRaw : -dxRaw;
        if (dx <= 0 || maxInward < shortPx() * 0.6f) {
            Log.i(TAG, "gesture ignored dx=" + dx + " inward=" + maxInward);
            return;
        }
        double angle = Math.toDegrees(Math.atan2(dy, Math.max(dx, 1f)));
        String dir;
        if (angle <= -28) dir = "U";
        else if (angle >= 28) dir = "D";
        else dir = "H";
        String len = maxInward >= longPx() ? "L" : "S";
        Log.i(TAG, "gesture side=" + (leftSide ? "L" : "R") + " len=" + len
                + " dir=" + dir + " angle=" + Math.round(angle) + " inward=" + Math.round(maxInward));
        runGesture(leftSide ? "L" : "R", len, dir);
    }

    private void runGesture(String side, String len, String dir) {
        String slot = Prefs.slot(side, len, dir);
        GestureAction action = GestureAction.fromId(prefs.actionId(slot));
        if (action == GestureAction.NONE && "L".equals(len)) {
            slot = Prefs.slot(side, "S", dir);
            action = GestureAction.fromId(prefs.actionId(slot));
        }
        if (action == GestureAction.NONE) {
            Log.i(TAG, "gesture slot=" + slot + " action=none");
            return;
        }
        boolean ok = execute(action, prefs.actionPkg(slot));
        Log.i(TAG, "gesture slot=" + slot + " action=" + action.id + " ok=" + ok);
        if (ok && prefs.vibrate()) hapticOnce();
    }

    // ---------------- 动作执行 ----------------

    private boolean execute(GestureAction a, String pkg) {
        switch (a) {
            case NONE:
                return false;
            case BACK:
                return performGlobalAction(GLOBAL_ACTION_BACK);
            case HOME:
                return performGlobalAction(GLOBAL_ACTION_HOME);
            case RECENTS:
                return performGlobalAction(GLOBAL_ACTION_RECENTS);
            case NOTIFICATIONS:
                return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
            case QUICK_SETTINGS:
                return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
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
            case VOLUME_UP:
                return adjustVolume(AudioManager.ADJUST_RAISE);
            case VOLUME_DOWN:
                return adjustVolume(AudioManager.ADJUST_LOWER);
            case MUTE_ON:
                return setMute(true);
            case MUTE_OFF:
                return setMute(false);
            case ROTATE:
                return rotate();
            case LAUNCH_APP:
                return launchApp(pkg);
            default:
                return false;
        }
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

    private boolean rotate() {
        try {
            if (!Settings.System.canWrite(this)) {
                Toast.makeText(this, "旋转屏幕需要「修改系统设置」权限", Toast.LENGTH_SHORT).show();
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
}
