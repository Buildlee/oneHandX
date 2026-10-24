package io.buildlee.onehand;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * 通过系统策略（Settings.Global "policy_control"）隐藏/恢复导航栏。
 * 需要一次性 ADB 授权：adb shell pm grant <包名> android.permission.WRITE_SECURE_SETTINGS
 * 注意：该机制为历史方案，部分新版 Android（13+）已忽略，见应用内说明。
 */
public class NavbarHelper {

    private static final String TAG = "OneHand";
    private static final String KEY = "policy_control";
    private static final String HIDE_VALUE = "immersive.navigation=*";

    /** 是否已获 WRITE_SECURE_SETTINGS（由 ADB 一次性授予） */
    public static boolean granted(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 当前是否处于「我们设置的」隐藏状态 */
    public static boolean isHidden(Context c) {
        try {
            return HIDE_VALUE.equals(Settings.Global.getString(c.getContentResolver(), KEY));
        } catch (Exception e) {
            return false;
        }
    }

    /** 隐藏或恢复导航栏；写入成功返回 true（是否真正生效取决于系统实现） */
    public static boolean setHidden(Context c, boolean hide) {
        if (!granted(c)) return false;
        try {
            Settings.Global.putString(c.getContentResolver(), KEY, hide ? HIDE_VALUE : null);
            Log.i(TAG, "navbar policy set hide=" + hide
                    + " now=" + Settings.Global.getString(c.getContentResolver(), KEY));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "navbar policy failed", e);
            return false;
        }
    }

    /** 供界面展示的 ADB 授权命令 */
    public static String grantCommand(Context c) {
        return "adb shell pm grant " + c.getPackageName()
                + " android.permission.WRITE_SECURE_SETTINGS";
    }
}
