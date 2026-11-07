package io.buildlee.onehand;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import rikka.shizuku.Shizuku;

/** Explicit entry for the optional shell-backed replacement session. */
public final class ReplacementActivity extends AppCompatActivity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private ExclusionSession session;
    private TextView status, confirm, start;
    private android.view.View retry;
    private CheckBox recovery;
    private final Runnable tick=new Runnable() { public void run() {
        status.setText(session.status());
        start.setVisibility(ExclusionSession.active()?android.view.View.GONE:android.view.View.VISIBLE);
        start.setEnabled(!session.busy());
        confirm.setVisibility(ExclusionSession.active()?android.view.View.VISIBLE:android.view.View.GONE);
        confirm.setText(session.confirmed()?"已确认替换":"确认替换");
        confirm.setEnabled(session.canConfirm()); confirm.setAlpha(session.canConfirm()?1f:.5f);
        retry.setVisibility(ExclusionSession.pending(ReplacementActivity.this) && !ExclusionSession.active()?android.view.View.VISIBLE:android.view.View.GONE);
        main.postDelayed(this,500);
    }};
    private final Shizuku.OnRequestPermissionResultListener permission=(code,result) -> {
        if(code==812) main.post(() -> { if(result==android.content.pm.PackageManager.PERMISSION_GRANTED) session.start(); });
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); session=ExclusionSession.get(this);
        Shizuku.addRequestPermissionResultListener(permission);
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);
        scroll.setBackgroundColor(UiKit.col(this,R.color.bg));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(UiKit.dp(this,20),UiKit.dp(this,24),UiKit.dp(this,20),UiKit.dp(this,32));
        scroll.addView(root);
        TextView back=new TextView(this); back.setText("‹ 返回"); back.setTextSize(16);
        back.setTextColor(UiKit.col(this,R.color.accent)); back.setGravity(android.view.Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> finish()); root.addView(back,new LinearLayout.LayoutParams(UiKit.dp(this,88),UiKit.dp(this,48)));
        TextView title=new TextView(this); title.setText("替换侧边手势"); title.setTextSize(30);
        title.setTextColor(UiKit.col(this,R.color.text_primary)); title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        title.setPadding(0,UiKit.dp(this,12),0,UiKit.dp(this,24)); root.addView(title);
        LinearLayout details=UiKit.card(this);
        details.addView(UiKit.subLabel(this,"实验功能 · Shizuku\n临时扩大系统允许的手柄范围，左右手柄贴边。上下系统强制区域仍保留，底部主页手势不变。"));
        details.addView(UiKit.subLabel(this,"先试用 60 秒。从左右边缘分别向内滑动，再回此页确认。试用期间只执行返回；超时会自动暂停并尝试恢复原额度。"));
        recovery=new CheckBox(this); recovery.setText("我能从桌面打开「恢复系统手势」入口");
        recovery.setTextColor(UiKit.col(this,R.color.text_primary)); details.addView(recovery);
        root.addView(details);
        LinearLayout actions=UiKit.card(this);
        status=UiKit.subLabel(this,""); actions.addView(status);
        start=UiKit.pillButton(this,"开始 60 秒试用",v -> startTrial()); actions.addView(start);
        confirm=UiKit.pillButton(this,"确认替换",v -> session.confirm()); actions.addView(confirm);
        actions.addView(UiKit.clickRow(this,"恢复系统手势","停止手柄并尝试恢复临时额度",v -> startActivity(new Intent(this,RecoveryActivity.class))));
        retry=UiKit.clickRow(this,"重试额度恢复","处理上次未完成的还原",v -> session.recover()); actions.addView(retry);
        root.addView(actions);
        LinearLayout range=UiKit.card(this);
        range.addView(UiKit.subLabel(this,"启用后默认主手柄覆盖可用侧边。在主界面点按左右手柄，进入「位置与外观」可调高度和位置。此模式内设为 0 即停用，不会转成内移手柄。"));
        range.addView(UiKit.subLabel(this,"手机系统可能拒绝扩大范围；只有额度和真实滑动验证通过后才确认。若守护与 Shizuku 同时停止，窗口撤下后仍可使用系统返回；额度记录保留，待重新授权后恢复。"));
        root.addView(range); setContentView(scroll); UiKit.applyInsets(scroll);
    }
    private void startTrial() {
        if (!recovery.isChecked()) { android.widget.Toast.makeText(this,"请先确认可以打开恢复入口",android.widget.Toast.LENGTH_LONG).show(); return; }
        try {
            if (!Shizuku.pingBinder() || Shizuku.getUid()!=2000) { android.widget.Toast.makeText(this,"请先启动 Shizuku shell 服务",android.widget.Toast.LENGTH_LONG).show(); return; }
            if (Shizuku.checkSelfPermission()!=android.content.pm.PackageManager.PERMISSION_GRANTED) { Shizuku.requestPermission(812); return; }
            session.start();
        } catch(RuntimeException e) { android.widget.Toast.makeText(this,"Shizuku 暂不可用",android.widget.Toast.LENGTH_LONG).show(); }
    }
    @Override protected void onResume() { super.onResume(); main.post(tick); }
    @Override public void finish() { super.finish(); Motion.closeActivity(this); }
    @Override protected void onPause() { main.removeCallbacks(tick); super.onPause(); }
    @Override protected void onDestroy() { Shizuku.removeRequestPermissionResultListener(permission); super.onDestroy(); }
}
