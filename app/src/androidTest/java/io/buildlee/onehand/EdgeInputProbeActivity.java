package io.buildlee.onehand;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.TextView;

/** A neutral foreground target that counts real Back callbacks, including accidental duplicates. */
public final class EdgeInputProbeActivity extends Activity {
    private TextView status;
    private int count;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        status = new TextView(this);
        status.setTextSize(24);
        status.setPadding(48, 160, 48, 48);
        setContentView(status);
        render();
        Log.i("OneHandEdgeProbe", "back count=0");
    }

    private void render() { status.setText("OneHandX 输入验证\n\n返回回调：" + count); }

    @Override public void onBackPressed() {
        count++;
        Log.i("OneHandEdgeProbe", "back count=" + count);
        render();
    }
}
