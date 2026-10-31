package io.buildlee.onehand;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import java.io.File;
import java.io.FileOutputStream;

/** Exercises actual UI callbacks and dry-run touch events, preserving settings in the caller. */
final class ProductFlowChecks {
    private final Instrumentation test;
    private final Prefs prefs;
    private final int theme;
    private int checks;

    ProductFlowChecks(Instrumentation test, Prefs prefs, int theme) {
        this.test = test; this.prefs = prefs; this.theme = theme;
    }

    int run() throws Exception {
        prefs.setBool(Prefs.REDUCE_MOTION, true);
        prefs.setBool(Prefs.LONG_ENABLED, false);
        prefs.setActionId("L1-S-H", "back");
        prefs.setActionId("L1-S-U", "home");
        prefs.setActionId("L1-S-D", "recents");
        MainActivity home = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync(); waitForFocus(home);
        screenshot("product-home");
        HandleEditActivity editor = (HandleEditActivity) next(home, "左手柄 1", HandleEditActivity.class);
        assertText(editor, "短滑练习 · 不执行动作");
        assertText(editor, "短滑 · 向右上滑动");
        assertText(editor, "短滑 · 向右下滑动");
        screenshot("product-editor");
        String beforePractice = prefs.dumpJson();
        swipe(editor, true, -65, MotionEvent.ACTION_UP);
        assertText(editor, "已识别 · 主屏幕\n未执行系统动作");
        swipe(editor, true, 0, MotionEvent.ACTION_UP);
        assertText(editor, "已识别 · 返回\n未执行系统动作");
        swipe(editor, true, 65, MotionEvent.ACTION_UP);
        assertText(editor, "已识别 · 最近任务\n未执行系统动作");
        swipe(editor, true, 0, MotionEvent.ACTION_CANCEL);
        check(practice(editor).lastSlot() == null, "cancel never retains a recognized action");
        check(beforePractice.equals(prefs.dumpJson()), "practice never changes configuration");
        check(!editor.isFinishing(), "practice never executes Back or Home");

        click(editor, "短滑 · 向右滑动");
        chooseAction("音量", "音量 +");
        check("volume_up".equals(prefs.actionId("L1-S-H")), "actual action picker saves selected slot");
        swipe(editor, true, 0, MotionEvent.ACTION_UP);
        assertText(editor, "已识别 · 音量 +\n未执行系统动作");
        screenshot("product-practice");

        click(editor, "短滑 · 向右滑动");
        Instrumentation.ActivityMonitor appMonitor = test.addMonitor(AppPickerActivity.class.getName(), null, false);
        chooseAction("打开应用", "打开应用");
        AppPickerActivity picker = (AppPickerActivity) test.waitForMonitorWithTimeout(appMonitor, 5000);
        test.removeMonitor(appMonitor);
        check(picker != null, "application selection opens from actual gesture row");
        waitForFocus(picker);
        ui(() -> picker.findViewById(R.id.btnBack).performClick()); test.waitForIdleSync();
        waitForFocus(editor);
        check("volume_up".equals(prefs.actionId("L1-S-H")), "cancelled application selection preserves old action");

        click(editor, "短滑 · 向右滑动");
        appMonitor = test.addMonitor(AppPickerActivity.class.getName(), null, false);
        chooseAction("打开应用", "打开应用");
        AppPickerActivity selection = (AppPickerActivity) test.waitForMonitorWithTimeout(appMonitor, 5000);
        test.removeMonitor(appMonitor);
        check(selection != null, "application selection can be reopened");
        waitForFocus(selection);
        ListView list = selection.findViewById(R.id.appList);
        long deadline = SystemClock.uptimeMillis() + 8000;
        final boolean[] loaded = new boolean[1];
        do {
            ui(() -> loaded[0] = list.getAdapter().getCount() > 0 && list.getChildCount() > 0);
            if (loaded[0]) break;
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        check(loaded[0], "installed applications load");
        ui(() -> list.performItemClick(list.getChildAt(0), 0, list.getAdapter().getItemId(0)));
        test.waitForIdleSync();
        waitForFocus(editor);
        check("launch_app".equals(prefs.actionId("L1-S-H")) && !prefs.actionPkg("L1-S-H").isEmpty(), "selecting application commits complete configuration");
        swipe(editor, true, 0, MotionEvent.ACTION_UP);
        assertText(editor, "已识别 · " + prefs.displayFor("L1-S-H") + "\n未执行系统动作");

        click(editor, "长滑"); assertText(editor, "启用长滑");
        check(!prefs.longEnabled(), "opening long-swipe configuration does not silently enable it");
        click(editor, "启用长滑"); check(prefs.longEnabled(), "long-swipe switch is reachable in context");
        click(editor, "位置与外观"); assertText(editor, "垂直位置");
        int originalPosition = prefs.handlePos("L1");
        ui(() -> {
            View label = TestViews.text(editor.getWindow().getDecorView(), "垂直位置");
            View parent = (View) label.getParent();
            SoftSlider slider = null;
            while (parent != null && slider == null) {
                slider = TestViews.type(parent, SoftSlider.class);
                if (slider == null) parent = parent.getParent() instanceof View ? (View) parent.getParent() : null;
            }
            if (slider == null) throw new AssertionError("position slider missing");
            slider.performAccessibilityAction(originalPosition >= 95
                    ? android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);
        });
        check(prefs.handlePos("L1") != originalPosition, "position control saves changes");
        int savedPosition = prefs.handlePos("L1");
        ui(editor::finish); test.waitForIdleSync();
        HandleEditActivity reopened = (HandleEditActivity) next(home, "左手柄 1", HandleEditActivity.class);
        check(prefs.handlePos("L1") == savedPosition, "configuration survives closing and reopening editor");
        assertText(reopened, "动作配置");
        click(reopened, "进阶手势"); assertText(reopened, "原地长按");
        ui(reopened::finish); test.waitForIdleSync();
        prefs.setActionId("R1-S-H", "volume_down");
        HandleEditActivity right = (HandleEditActivity) next(home, "右手柄 1", HandleEditActivity.class);
        swipe(right, false, 0, MotionEvent.ACTION_UP);
        assertText(right, "已识别 · 音量 -\n未执行系统动作");
        check("R1-S-H".equals(practice(right).lastSlot()), "right practice uses mirrored origin and right mapping");
        screenshot("product-right");
        ui(right::finish); test.waitForIdleSync();
        return checks;
    }

    private Activity next(Activity from, String label, Class<?> type) throws Exception {
        Instrumentation.ActivityMonitor monitor = test.addMonitor(type.getName(), null, false);
        click(from, label);
        Activity activity = test.waitForMonitorWithTimeout(monitor, 5000); test.removeMonitor(monitor);
        check(activity != null, "navigation opens " + type.getSimpleName());
        test.waitForIdleSync(); waitForFocus(activity);
        return activity;
    }
    private void click(Activity activity, String label) throws Exception {
        waitForFocus(activity);
        final View[] target = new View[1];
        ui(() -> {
            target[0] = TestViews.text(activity.getWindow().getDecorView(), label);
            if (target[0] == null) throw new AssertionError("Missing click target " + label);
            target[0].requestRectangleOnScreen(new android.graphics.Rect(0, 0, target[0].getWidth(), target[0].getHeight()), true);
        });
        test.waitForIdleSync();
        android.graphics.Rect visible = new android.graphics.Rect();
        ui(() -> { if (!target[0].getGlobalVisibleRect(visible)) throw new AssertionError("Hidden click target " + label); });
        inject(visible.exactCenterX(), visible.exactCenterY(), MotionEvent.ACTION_DOWN, SystemClock.uptimeMillis());
        inject(visible.exactCenterX(), visible.exactCenterY(), MotionEvent.ACTION_UP, SystemClock.uptimeMillis());
        test.waitForIdleSync();
    }
    private void chooseAction(String query, String action) throws Exception {
        test.waitForIdleSync();
        final View[] popup = new View[1];
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            ui(() -> popup[0] = TestViews.editRoot());
            if (popup[0] != null) break;
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        check(popup[0] != null, "action popup is attached");
        ui(() -> {
            View root = popup[0];
            EditText search = TestViews.type(root, EditText.class);
            if (search == null) throw new AssertionError("action search missing");
            search.setText(query); TestViews.click(root, action);
        });
        test.waitForIdleSync();
    }
    private GesturePracticeView practice(Activity activity) { return TestViews.type(activity.getWindow().getDecorView(), GesturePracticeView.class); }
    private void swipe(Activity activity, boolean left, int dyDp, int endAction) {
        final GesturePracticeView stage = practice(activity);
        ui(() -> {
            if (stage == null || stage.getWidth() == 0) throw new AssertionError("practice not laid out");
            stage.requestRectangleOnScreen(new android.graphics.Rect(0, 0, stage.getWidth(), stage.getHeight()), true);
        });
        test.waitForIdleSync();
        int[] location = new int[2]; ui(() -> stage.getLocationOnScreen(location));
        float x = location[0] + (left ? UiKit.dp(activity, 22) : stage.getWidth() - UiKit.dp(activity, 22));
        float y = location[1] + stage.getHeight() / 2f;
        long now = SystemClock.uptimeMillis();
        inject(x, y, MotionEvent.ACTION_DOWN, now);
        inject(x + UiKit.dp(activity, left ? 80 : -80), y + UiKit.dp(activity, dyDp), MotionEvent.ACTION_MOVE, now);
        inject(x + UiKit.dp(activity, left ? 80 : -80), y + UiKit.dp(activity, dyDp), endAction, now);
        test.waitForIdleSync();
    }
    private void inject(float x, float y, int action, long down) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try { test.sendPointerSync(event); } finally { event.recycle(); }
    }
    private void assertText(Activity activity, String label) {
        ui(() -> check(TestViews.text(activity.getWindow().getDecorView(), label) != null, "visible text: " + label));
    }
    private void ui(Runnable action) {
        final Throwable[] failure = new Throwable[1];
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError("UI callback failed", failure[0]);
    }
    private void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); checks++; }
    private void waitForFocus(Activity activity) throws Exception {
        final boolean[] focused = new boolean[1];
        long deadline = SystemClock.uptimeMillis() + 8000;
        do {
            ui(() -> focused[0] = activity.getWindow().getDecorView().hasWindowFocus());
            if (focused[0]) return;
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Activity never received focus: " + activity.getClass().getSimpleName());
    }
    private void screenshot(String name) throws Exception {
        test.waitForIdleSync();
        java.util.concurrent.CountDownLatch frames = new java.util.concurrent.CountDownLatch(1);
        ui(() -> {
            View root = TestViews.focusedRoot();
            if (root != null) root.postOnAnimation(() -> root.postOnAnimation(frames::countDown));
        });
        check(frames.await(4, java.util.concurrent.TimeUnit.SECONDS), "rendered frames " + name);
        Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        check(bitmap != null, "screenshot " + name);
        File dir = new File(test.getTargetContext().getExternalFilesDir(null), "qa"); dir.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(new File(dir, name + "-" + theme + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        }
        bitmap.recycle();
    }
}
