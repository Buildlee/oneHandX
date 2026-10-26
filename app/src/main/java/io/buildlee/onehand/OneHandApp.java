package io.buildlee.onehand;

import android.app.Application;
import androidx.appcompat.app.AppCompatDelegate;

/** Apply the saved theme before any Activity creates its views. */
public class OneHandApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        int theme = new Prefs(this).getInt(Prefs.THEME, 0);
        AppCompatDelegate.setDefaultNightMode(theme == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : theme == 2 ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
