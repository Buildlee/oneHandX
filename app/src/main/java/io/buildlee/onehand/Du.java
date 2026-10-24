package io.buildlee.onehand;

import android.content.Context;
import android.graphics.Point;
import android.util.TypedValue;
import android.view.Display;
import android.view.WindowManager;

public class Du {

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("deprecation")
    public static Point screenSize(Context c) {
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Point p = new Point();
        Display d = wm.getDefaultDisplay();
        d.getRealSize(p);
        return p;
    }
}
