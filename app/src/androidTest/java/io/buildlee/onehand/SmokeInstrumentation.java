package io.buildlee.onehand;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Set;

/** Device-side integration smoke test. No third-party test dependency or device settings changes. */
public class SmokeInstrumentation extends Instrumentation {
    private int checks;
    private Prefs prefs;
    private String original;
    private int theme;

    @Override public void onCreate(Bundle args) { super.onCreate(args); theme = Integer.parseInt(args.getString("theme", "1")); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_FREEZE_0);
            android.accessibilityservice.AccessibilityServiceInfo automationInfo = getUiAutomation().getServiceInfo();
            automationInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            getUiAutomation().setServiceInfo(automationInfo);
            prefs = new Prefs(getTargetContext()); original = prefs.dumpJson();
            prefs.setInt(Prefs.THEME, theme);
            runOnMainSync(() -> androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(theme == 2
                    ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO));
            prefs.setBool(Prefs.REDUCE_MOTION, true);
            Activity main = launch(MainActivity.class);
            check((main.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == (theme == 2 ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO), "requested theme applied before inflation");
            assertText(main, "我的手势");
            screenshot(main, "home");
            click(main, "面板"); assertText(main, "快速工具");
            click(main, "快速工具"); assertText(main, "按钮 1"); check(find(main.getWindow().getDecorView(), "应用 1") == null, "panel separation");
            runOnMainSync(main::onBackPressed); waitForIdleSync();
            click(main, "设置"); click(main, "使用场景"); assertText(main, "适配键盘");
            runOnMainSync(main::onBackPressed); waitForIdleSync();
            click(main, "系统手势共存"); assertText(main, "距屏幕边缘");
            runOnMainSync(main::onBackPressed); waitForIdleSync();
            screenshot(main, "settings");
            Activity editor = launch(HandleEditActivity.class, "handle_id", "L1");
            assertText(editor, "短滑 · 向右上滑动"); assertText(editor, "短滑 · 向右下滑动");
            screenshot(editor, "mapping");
            click(editor, "长滑"); assertText(editor, "长滑 · 向右上滑动");
            runOnMainSync(editor::finish); waitForIdleSync();
            prefs.setInt(Prefs.FEEDBACK_STYLE, 0);
            Activity appearance = launch(AppearanceActivity.class);
            assertText(appearance, "背景透明度"); assertText(appearance, "轮廓颜色");
            screenshot(appearance, "appearance");
            click(appearance, "背景颜色");
            getUiAutomation().waitForIdle(500, 5000);
            android.view.accessibility.AccessibilityNodeInfo input = colorInput();
            check(input != null, "color editor available");
            Bundle inputArgs = new Bundle();
            inputArgs.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "invalid");
            check(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, inputArgs), "set invalid color");
            nodeClick("应用");
            check(prefs.waveFill() == 0xFF32495D, "invalid color preserves current value");
            inputArgs.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "#456789");
            input = colorInput();
            check(input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, inputArgs), "set custom color");
            nodeClick("应用");
            check(prefs.waveFill() == 0xFF456789 && prefs.waveArrow() == 0xFFFFFFFF, "color dialog saves fill independently");
            View root = appearance.getWindow().getDecorView();
            TextView transparency = find(root, "背景透明度");
            ViewGroup row = (ViewGroup) transparency.getParent().getParent();
            SoftSlider slider = (SoftSlider) row.getChildAt(1);
            dragSlider(slider, true); check(prefs.waveTransparency() == 100, "transparency gesture saves 100");
            dragSlider(slider, false); check(prefs.waveTransparency() == 0, "transparency gesture saves 0");
            String mapping = prefs.actionId("L1-S-H");
            Activity finalAppearance = appearance;
            runOnMainSync(() -> {
                prefs.setInt(Prefs.FEEDBACK_STYLE, 0);
                prefs.setInt(Prefs.WAVE_FILL, 0xFF456789);
                prefs.setInt(Prefs.WAVE_TRANSPARENCY, 100);
                FeedbackView view = new FeedbackView(finalAppearance);
                view.layout(0, 0, 360, 240);
                float d = getTargetContext().getResources().getDisplayMetrics().density;
                view.show(true, 0, 120, 112 * d, 0);
                Bitmap transparent = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(transparent));
                check(transparent.getPixel((int) (7 * d), 120 + (int) (6 * d)) == 0, "fill fully transparent independently");
                prefs.setInt(Prefs.WAVE_TRANSPARENCY, 0);
                Bitmap opaque = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888);
                view.draw(new Canvas(opaque));
                check(opaque.getPixel((int) (7 * d), 120 + (int) (6 * d)) == 0xFF456789, "custom fill rendered opaque");
                Set<Integer> hashes = new HashSet<>();
                for (int style = 0; style < 6; style++) {
                    prefs.setInt(Prefs.FEEDBACK_STYLE, style);
                    Bitmap bitmap = Bitmap.createBitmap(360, 240, Bitmap.Config.ARGB_8888);
                    view.draw(new Canvas(bitmap));
                    int[] pixels = new int[360 * 240]; bitmap.getPixels(pixels, 0, 360, 0, 0, 360, 240);
                    hashes.add(java.util.Arrays.hashCode(pixels)); bitmap.recycle();
                }
                check(hashes.size() == 6, "six distinct native styles");
                transparent.recycle(); opaque.recycle();
            });
            check(mapping.equals(prefs.actionId("L1-S-H")), "appearance preserves mappings");
            String configured = prefs.dumpJson(); prefs.applyJson(configured);
            check(prefs.waveFill() == 0xFF456789, "backup restores custom color");
            check(prefs.inspectJson(configured).contains("项设置"), "backup preview available");
            String bad = "{\"app\":\"onehand\",\"version\":6,\"entries\":{\"wave_fill\":{\"t\":\"s\",\"v\":\"bad\"}}}";
            try { prefs.applyJson(bad); throw new AssertionError("invalid backup accepted"); }
            catch (org.json.JSONException expected) { check(prefs.waveFill() == 0xFF456789, "invalid backup never clears settings"); }
            final android.app.Dialog[] picker = new android.app.Dialog[1];
            runOnMainSync(() -> {
                String selected = "L1-S-H";
                picker[0] = ActionDialog.show(finalAppearance, "动作选择", prefs.actionId(selected), a -> prefs.setActionId(selected, a.id));
            });
            waitForIdleSync();
            screenshot(appearance, "action-picker");
            runOnMainSync(() -> {
                View dialogRoot = picker[0].getWindow().getDecorView();
                android.widget.EditText search = findSearch(dialogRoot);
                check(search != null, "search field available");
                search.setText("音量");
                check(find(dialogRoot, "音量 +") != null, "search matches action");
                check(find(dialogRoot, "返回") == null, "search filters unrelated actions");
                search.setText("不存在的功能xyz");
                check(find(dialogRoot, "没有找到动作，试试其他关键词") != null, "search empty state");
                search.setText("主屏幕");
                View target = find(dialogRoot, "主屏幕");
                while (target != null && !target.isClickable()) target = (View) target.getParent();
                check(target != null, "searched result clickable"); target.performClick();
            });
            check(prefs.actionId("L1-S-H").equals("home"), "searched action saved to correct slot");
            getTargetContext().getSharedPreferences(Prefs.NAME, 0).edit().remove(Prefs.FEEDBACK_STYLE).apply();
            for (int i = 0; i < 3; i++) {
                prefs.setInt(Prefs.ANIM_STYLE, i);
                check(prefs.feedbackStyle() == new int[]{5, 1, 4}[i], "legacy style migration " + i);
            }
            restore();
            result.putString("stream", "PASS: " + checks + " checks; navigation, mapping tabs, transparency touch, native rendering, backup and action picker\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable e) {
            try {
                Bitmap failure = getUiAutomation().takeScreenshot();
                File dir = new File(getTargetContext().getExternalFilesDir(null), "qa"); dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, "failure-" + theme + ".png"))) { failure.compress(Bitmap.CompressFormat.PNG, 100, out); }
            } catch (Exception ignored) {}
            restore();
            result.putString("stream", "FAIL after " + checks + " checks: " + android.util.Log.getStackTraceString(e));
            finish(Activity.RESULT_CANCELED, result);
        } finally {
            restore();
        }
    }
    private Activity launch(Class<?> type) { return launch(type, null, null); }
    private Activity launch(Class<?> type, String key, String value) {
        Intent intent = new Intent(getTargetContext(), type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (key != null) intent.putExtra(key, value);
        Activity activity = startActivitySync(intent); waitForIdleSync(); return activity;
    }
    private void click(Activity a, String value) {
        runOnMainSync(() -> {
            TextView text = find(a.getWindow().getDecorView(), value);
            check(text != null, "find " + value);
            View target = text;
            while (target != null && !target.isClickable()) target = target.getParent() instanceof View ? (View) target.getParent() : null;
            check(target != null, "clickable " + value); target.performClick();
        }); waitForIdleSync();
    }
    private TextView find(View v, String value) {
        if (v instanceof TextView && !(v instanceof android.widget.EditText) && ((TextView) v).getText().toString().equals(value)) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            TextView result = find(((ViewGroup) v).getChildAt(i), value); if (result != null) return result;
        }
        return null;
    }
    private android.widget.EditText findSearch(View v) {
        if (v instanceof android.widget.EditText) return (android.widget.EditText) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
            android.widget.EditText result = findSearch(((ViewGroup) v).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo nodeByClass(android.view.accessibility.AccessibilityNodeInfo node, String name) {
        if (node == null) return null;
        if (name.contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = nodeByClass(node.getChild(i), name);
            if (found != null) return found;
        }
        return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo colorInput() {
        for (android.view.accessibility.AccessibilityWindowInfo window : getUiAutomation().getWindows()) {
            android.view.accessibility.AccessibilityNodeInfo root = window.getRoot();
            if (root != null && "io.buildlee.onehand".contentEquals(root.getPackageName())) {
                android.view.accessibility.AccessibilityNodeInfo found = nodeByClass(root, "android.widget.EditText");
                if (found != null) return found;
            }
        }
        return null;
    }
    private void nodeClick(String name) throws Exception {
        for (android.view.accessibility.AccessibilityNodeInfo node : getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(name)) {
            if (name.contentEquals(node.getText()) && node.isClickable()) {
                check(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK), "click dialog " + name);
                waitForIdleSync(); getUiAutomation().waitForIdle(100, 3000); return;
            }
        }
        throw new AssertionError("missing dialog button: " + name);
    }
    private void assertText(Activity a, String value) { check(find(a.getWindow().getDecorView(), value) != null, value); }
    private void restore() {
        if (prefs != null && original != null) try {
            prefs.applyJson(original);
            // Instrumentation ends its process immediately; flush async preference writes first.
            getTargetContext().getSharedPreferences(Prefs.NAME, 0).edit().commit();
            getUiAutomation().setRotation(android.app.UiAutomation.ROTATION_UNFREEZE);
        } catch (Exception e) { throw new RuntimeException("test settings restore failed", e); }
    }
    private void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
    private void dragSlider(SoftSlider slider, boolean toEnd) {
        runOnMainSync(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
                MotionEvent e = MotionEvent.obtain(now, now, action, toEnd ? slider.getWidth() : 0, slider.getHeight() / 2f, 0);
                slider.dispatchTouchEvent(e); e.recycle();
            }
        }); waitForIdleSync();
    }
    private void screenshot(Activity activity, String name) {
            try {
                waitForIdleSync();
                java.util.concurrent.CountDownLatch frames = new java.util.concurrent.CountDownLatch(1);
                runOnMainSync(() -> activity.getWindow().getDecorView().postOnAnimation(() ->
                        activity.getWindow().getDecorView().postOnAnimation(frames::countDown)));
                check(frames.await(3, java.util.concurrent.TimeUnit.SECONDS), "screenshot frame available");
                getUiAutomation().waitForIdle(500, 5000);
                Bitmap bitmap = getUiAutomation().takeScreenshot();
                if (bitmap == null) throw new IllegalStateException("screenshot unavailable");
                File dir = new File(getTargetContext().getExternalFilesDir(null), "qa"); dir.mkdirs();
                try (FileOutputStream out = new FileOutputStream(new File(dir, name + (theme == 2 ? "-dark" : "-light") + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
                bitmap.recycle();
            } catch (Exception e) { throw new RuntimeException(e); }
    }
}
