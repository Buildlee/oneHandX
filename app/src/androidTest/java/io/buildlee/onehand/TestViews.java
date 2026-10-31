package io.buildlee.onehand;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Native view lookup for in-process UI checks. Invoke on the main thread. */
final class TestViews {
    static View text(View root, String label) {
        if (root instanceof TextView && !(root instanceof android.widget.EditText)
                && label.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = text(((ViewGroup) root).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    static <T extends View> T type(View root, Class<T> cls) {
        if (cls.isInstance(root)) return cls.cast(root);
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            T found = type(((ViewGroup) root).getChildAt(i), cls);
            if (found != null) return found;
        }
        return null;
    }

    static View focusedRoot() {
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IllegalStateException("Native window checks require API 29+");
        View lastVisible = null;
        for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
            if (root.isShown()) lastVisible = root;
            if (root.isShown() && root.hasWindowFocus()) return root;
        }
        return lastVisible;
    }

    static View editRoot() {
        View found = null;
        for (View root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
            if (root.isShown() && type(root, android.widget.EditText.class) != null) found = root;
        }
        return found;
    }

    static void click(View root, String label) {
        View target = text(root, label);
        while (target != null && !target.isClickable() && target.getParent() instanceof View) target = (View) target.getParent();
        if (target == null || !target.isClickable() || !target.performClick()) throw new AssertionError("Cannot click " + label);
    }
}
