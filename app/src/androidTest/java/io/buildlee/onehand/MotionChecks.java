package io.buildlee.onehand;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Animated-mode integration: interruption, reversal, direct drag, reduced motion and real window timings. */
final class MotionChecks {
    private final Instrumentation host;
    private final Prefs prefs;
    private int checks;
    MotionChecks(Instrumentation host, Prefs prefs) { this.host = host; this.prefs = prefs; }

    int run() throws Exception {
        prefs.setBool(Prefs.REDUCE_MOTION, false);
        check(Motion.animationsEnabled(host.getTargetContext()), "device animator setting allows motion");
        prefs.setInt(Prefs.FEEDBACK_STYLE, 0);
        Activity main = host.startActivitySync(new Intent(host.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        host.waitForIdleSync();
        HandlerThread metricsThread = new HandlerThread("motion-metrics"); metricsThread.start();
        List<Long> durations = Collections.synchronizedList(new ArrayList<>());
        Window.OnFrameMetricsAvailableListener listener = (window, metrics, lost) -> {
            long value = metrics.getMetric(FrameMetrics.TOTAL_DURATION);
            if (value > 0 && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0) durations.add(value);
        };
        main(() -> main.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(metricsThread.getLooper())));
        try {
            for (int i = 0; i < 12; i++) {
                click(main.getWindow().getDecorView(), new String[]{"面板", "手势", "设置"}[i % 3]);
                SystemClock.sleep(55);
            }
            View root = main.findViewById(R.id.contentRoot);
            check(find(root, "外观与反馈") != null, "rapid navigation commits latest destination");
            SystemClock.sleep(300);
            main(() -> check(resting(root), "navigation settles without stale transforms"));
            click(root, "手势识别");
            check(find(root, "触发距离") != null, "drill-down content interactive immediately");
            main(main::onBackPressed);
            SystemClock.sleep(300);
            main(() -> check(resting(root), "back interrupts forward transition safely"));
        } finally {
            main(() -> main.getWindow().removeOnFrameMetricsAvailableListener(listener));
            metricsThread.quitSafely(); metricsThread.join(1500);
        }
        writeMetrics(durations);

        FrameLayout fixture = new FrameLayout(main);
        FeedbackView feedback = new FeedbackView(main);
        main(() -> {
            fixture.setBackgroundColor(UiKit.col(main, R.color.card));
            fixture.addView(feedback, new FrameLayout.LayoutParams(-1, dp(main, 208)));
            main.addContentView(fixture, new ViewGroup.LayoutParams(-1, dp(main, 208)));
        });
        host.waitForIdleSync(); SystemClock.sleep(80);
        main(() -> feedback.show(true, dp(main, 12), dp(main, 104), dp(main, 112), 0));
        int originalWidth = snapshot(feedback, "curve-held")[0];
        final Bitmap[] midRelease = new Bitmap[1];
        java.util.concurrent.CountDownLatch returningFrame = new java.util.concurrent.CountDownLatch(1);
        main(() -> {
            feedback.dismiss();
            try {
                java.lang.reflect.Field field = FeedbackView.class.getDeclaredField("releaseAnimator"); field.setAccessible(true);
                android.animation.ValueAnimator animation = (android.animation.ValueAnimator) field.get(feedback);
                check(animation != null, "release starts an animation");
                animation.addUpdateListener(a -> {
                    float amount = (float) a.getAnimatedValue();
                    if (amount > .1f && amount < .9f && midRelease[0] == null) {
                        midRelease[0] = Bitmap.createBitmap(feedback.getWidth(), feedback.getHeight(), Bitmap.Config.ARGB_8888);
                        feedback.draw(new Canvas(midRelease[0])); returningFrame.countDown();
                    }
                });
            } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        });
        check(returningFrame.await(2, java.util.concurrent.TimeUnit.SECONDS), "intermediate release frame delivered");
        main(() -> feedback.show(false, feedback.getWidth() - dp(main, 12), dp(main, 104), dp(main, 112), -dp(main, 40)));
        int retractingWidth = saveSnapshot(midRelease[0], "curve-returning")[0];
        check(retractingWidth < originalWidth && retractingWidth > 0, "curve retracts geometrically before disappearing: " + originalWidth + " -> " + retractingWidth);
        SystemClock.sleep(300);
        check(snapshot(feedback, "curve-interrupted")[1] > 0, "new drag survives old release completion");
        main(() -> check(feedback.getAlpha() == 1f, "new drag is fully visible"));
        main(() -> feedback.demonstrate(true, dp(main, 12), dp(main, 104), dp(main, 112), dp(main, 40)));
        SystemClock.sleep(70);
        main(() -> feedback.show(true, dp(main, 12), dp(main, 104), dp(main, 100), 0));
        SystemClock.sleep(500);
        check(snapshot(feedback, "curve-demo-interrupted")[1] > 0, "demo completion cannot clear a real drag");
        main(feedback::clear);
        check(snapshot(feedback, "curve-cleared")[1] == 0, "clear leaves no lingering pixels");

        AtomicInteger completions = new AtomicInteger();
        main(() -> Motion.popIn(fixture, true));
        SystemClock.sleep(40);
        main(() -> Motion.fadeOut(fixture, completions::incrementAndGet));
        SystemClock.sleep(35);
        main(() -> Motion.popIn(fixture, false));
        awaitResting(fixture);
        main(() -> check(resting(fixture), "popup reverses to visible resting state: " + fixture.getAlpha()
                + ", " + fixture.getScaleX() + ", " + fixture.getScaleY() + ", " + fixture.getTranslationX() + ", " + fixture.getTranslationY()));
        check(completions.get() == 0, "interrupted exit does not remove reopened popup");
        main(() -> Motion.fadeOut(fixture, completions::incrementAndGet));
        long exitDeadline = SystemClock.uptimeMillis() + 1500;
        while (completions.get() == 0 && SystemClock.uptimeMillis() < exitDeadline) SystemClock.sleep(20);
        check(completions.get() == 1, "completed exit invokes cleanup exactly once: " + completions.get());

        prefs.setBool(Prefs.REDUCE_MOTION, true);
        main(() -> {
            feedback.show(true, 0, dp(main, 104), dp(main, 100), 0); feedback.dismiss();
            Motion.contentIn(fixture, 1); check(resting(fixture), "reduced motion has no page translation or fade");
            Motion.popIn(fixture, false); check(resting(fixture), "reduced popup has no scaling");
            Motion.fadeOut(fixture, completions::incrementAndGet);
            check(completions.get() == 2, "reduced exit cleanup is immediate");
        });
        check(snapshot(feedback, "curve-reduced")[1] == 0, "reduced gesture returns immediately");
        main(() -> ((ViewGroup) fixture.getParent()).removeView(fixture));

        prefs.setBool(Prefs.REDUCE_MOTION, false);
        Activity editor = host.startActivitySync(new Intent(host.getTargetContext(), HandleEditActivity.class)
                .putExtra(HandleEditActivity.EXTRA_HANDLE_ID, "L1").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        host.waitForIdleSync(); SystemClock.sleep(80);
        View editorRoot = editor.getWindow().getDecorView();
        TextView press = find(editorRoot, "原地长按");
        ViewGroup disclosure = (ViewGroup) press.getParent().getParent().getParent();
        String mapping = prefs.actionId("L1-S-H");
        check(disclosure.getVisibility() == View.GONE, "advanced group starts collapsed");
        java.util.concurrent.CountDownLatch expandedLayout = new java.util.concurrent.CountDownLatch(1);
        main(() -> disclosure.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (b > t) { view.removeOnLayoutChangeListener(this); expandedLayout.countDown(); }
            }
        }));
        click(editorRoot, "进阶手势");
        main(() -> check(disclosure.getTag() instanceof android.animation.ValueAnimator,
                "first disclosure opening animates from gone state"));
        check(expandedLayout.await(2, java.util.concurrent.TimeUnit.SECONDS), "disclosure produces visible layout");
        click(editorRoot, "收起进阶手势"); SystemClock.sleep(35);
        click(editorRoot, "进阶手势"); SystemClock.sleep(35);
        click(editorRoot, "收起进阶手势"); SystemClock.sleep(250);
        main(() -> {
            check(disclosure.getVisibility() == View.GONE && disclosure.getAlpha() == 1f, "disclosure handles repeated reversal");
            check(disclosure.getLayoutParams().height == ViewGroup.LayoutParams.WRAP_CONTENT, "disclosure restores natural sizing");
        });
        check(mapping.equals(prefs.actionId("L1-S-H")), "motion never changes action mapping");
        click(editorRoot, "长滑"); click(editorRoot, "短滑"); SystemClock.sleep(260);
        main(() -> check(resting(editor.findViewById(R.id.contentRoot)), "mapping tab interruption settles"));
        main(editor::finish);
        return checks;
    }

    private int[] snapshot(FeedbackView view, String name) throws Exception {
        final Bitmap[] image = new Bitmap[1];
        org.json.JSONObject state = new org.json.JSONObject();
        main(() -> {
            try {
                java.lang.reflect.Field release = FeedbackView.class.getDeclaredField("release"); release.setAccessible(true);
                state.put("release", release.getFloat(view)); state.put("alpha", view.getAlpha()); state.put("uptime_ms", SystemClock.uptimeMillis());
            } catch (Exception e) { throw new RuntimeException(e); }
            image[0] = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
            view.draw(new Canvas(image[0]));
        });
        File dir = new File(host.getTargetContext().getExternalFilesDir(null), "qa-motion"); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".json"))) { out.write(state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        return saveSnapshot(image[0], name);
    }

    private int[] saveSnapshot(Bitmap image, String name) throws Exception {
        int min = image.getWidth(), max = -1, count = 0;
        int[] pixels = new int[image.getWidth() * image.getHeight()];
        image.getPixels(pixels, 0, image.getWidth(), 0, 0, image.getWidth(), image.getHeight());
        for (int i = 0; i < pixels.length; i++) if ((pixels[i] >>> 24) != 0) {
            int x = i % image.getWidth(); min = Math.min(min, x); max = Math.max(max, x); count++;
        }
        File dir = new File(host.getTargetContext().getExternalFilesDir(null), "qa-motion"); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        image.recycle();
        return new int[]{Math.max(0, max - min + 1), count};
    }


    private void writeMetrics(List<Long> metrics) throws Exception {
        List<Long> values;
        synchronized (metrics) { values = new ArrayList<>(metrics); }
        Collections.sort(values);
        check(!values.isEmpty(), "native window frame timings captured");
        org.json.JSONObject json = new org.json.JSONObject();
        json.put("frames", values.size());
        json.put("total_duration_p50_ms", values.get(values.size() / 2) / 1_000_000.0);
        json.put("total_duration_p95_ms", values.get(Math.min(values.size() - 1, (int) (values.size() * .95))) / 1_000_000.0);
        json.put("display_hz", mainRefreshRate());
        json.put("scope", "API 32 emulator; rapid navigation only; excludes first draw and bitmap test captures");
        File dir = new File(host.getTargetContext().getExternalFilesDir(null), "qa-motion"); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "frame-metrics.json"))) { out.write(json.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }
    private float mainRefreshRate() { return Motion.maxRefreshRate(host.getTargetContext()); }
    private void click(View root, String name) {
        main(() -> {
            View node = find(root, name);
            if (node == null) throw new AssertionError("missing " + name);
            while (!node.isClickable()) node = (View) node.getParent();
            node.performClick();
        }); host.waitForIdleSync();
    }
    private TextView find(View node, String value) {
        if (node instanceof TextView && value.contentEquals(((TextView) node).getText())) return (TextView) node;
        if (node instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) node).getChildCount(); i++) {
            TextView child = find(((ViewGroup) node).getChildAt(i), value); if (child != null) return child;
        }
        return null;
    }
    private int dp(Activity activity, int value) { return UiKit.dp(activity, value); }
    private boolean resting(View view) { return Math.abs(view.getAlpha() - 1f) < .001f && Math.abs(view.getTranslationX()) < .001f
            && Math.abs(view.getTranslationY()) < .001f && Math.abs(view.getScaleX() - 1f) < .001f && Math.abs(view.getScaleY() - 1f) < .001f; }
    private void main(Runnable action) {
        Throwable[] failure = new Throwable[1];
        host.runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { failure[0] = e; } });
        if (failure[0] != null) throw new AssertionError("main-thread check failed", failure[0]);
    }
    private void awaitResting(View view) {
        long deadline = SystemClock.uptimeMillis() + 1500;
        boolean[] settled = new boolean[1];
        while (SystemClock.uptimeMillis() < deadline) {
            main(() -> settled[0] = resting(view));
            if (settled[0]) return;
            SystemClock.sleep(20);
        }
        throw new AssertionError("animation did not settle");
    }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); checks++; }
}
