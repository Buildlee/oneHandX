package io.buildlee.onehand;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

/** 透明中转 Activity（兜底）：待窗口获得焦点后弹出系统输入法选择器 */
public class InputMethodPickerActivity extends Activity {

    private boolean shown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || shown) return;
        shown = true;
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            new Handler(getMainLooper()).postDelayed(this::finish, 1200);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开输入法选择器", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
