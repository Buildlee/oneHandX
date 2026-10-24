package io.buildlee.onehand;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/** 单个手柄的编辑页：启停、6 个手势映射、外观、删除 */
public class HandleEditActivity extends AppCompatActivity {

    public static final String EXTRA_HANDLE_ID = "handle_id";

    private Prefs prefs;
    private String handleId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_handle_edit);
        handleId = getIntent().getStringExtra(EXTRA_HANDLE_ID);
        if (handleId == null || handleId.length() < 2) {
            finish();
            return;
        }
        prefs = new Prefs(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        TextView title = findViewById(R.id.handleTitle);
        title.setText(displayName());
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout root = findViewById(R.id.contentRoot);
        root.removeAllViews();
        build(root);
    }

    private String displayName() {
        return (handleId.startsWith("L") ? "左手柄 " : "右手柄 ") + handleId.substring(1);
    }

    private void build(LinearLayout root) {
        LinearLayout c1 = UiKit.card(this);
        c1.addView(UiKit.switchRow(this, "启用此手柄", null, prefs.handleEnabled(handleId),
                v -> prefs.setHandleEnabled(handleId, v)));
        root.addView(c1);

        LinearLayout c2 = UiKit.card(this);
        c2.addView(UiKit.subLabel(this, "手势映射（点击修改）"));
        c2.addView(UiKit.subLabel(this, "短滑"));
        for (String dir : Prefs.DIRS) c2.addView(gestureRow("S", dir));
        c2.addView(UiKit.subLabel(this, "长滑（从边缘拖得更远；未配置时回退短滑动作）"));
        for (String dir : Prefs.DIRS) c2.addView(gestureRow("L", dir));
        root.addView(c2);

        LinearLayout c3 = UiKit.card(this);
        c3.addView(UiKit.subLabel(this, "手柄外观"));
        c3.addView(UiKit.sliderRow(this, "长度", 30, 95, prefs.handleLen(handleId), "%",
                v -> prefs.setHandleLen(handleId, v)));
        c3.addView(UiKit.sliderRow(this, "垂直位置", 0, 100, prefs.handlePos(handleId), "%",
                v -> prefs.setHandlePos(handleId, v)));
        c3.addView(UiKit.sliderRow(this, "不透明度", 0, 100, prefs.handleAlpha(handleId), "%",
                v -> prefs.setHandleAlpha(handleId, v)));
        root.addView(c3);

        int index = Integer.parseInt(handleId.substring(1));
        String side = handleId.substring(0, 1);
        if (index > 1 && index == prefs.handleCount(side)) {
            LinearLayout c4 = UiKit.card(this);
            LinearLayout del = UiKit.clickRow(this, "删除此手柄", "该手柄的手势映射将一并清除",
                    v -> confirmDelete(side));
            LinearLayout texts = (LinearLayout) del.getChildAt(0);
            ((TextView) texts.getChildAt(0)).setTextColor(UiKit.col(this, R.color.danger));
            c4.addView(del);
            root.addView(c4);
        }
    }

    private View gestureRow(String len, String dir) {
        final String slot = Prefs.slot(handleId, len, dir);
        final String title = ("S".equals(len) ? "短滑 · " : "长滑 · ") + dirName(dir);
        final UiKit.SlotRow[] holder = new UiKit.SlotRow[1];
        UiKit.SlotRow r = UiKit.slotRow(this, title, iconForDir(dir), prefs.displayFor(slot),
                GestureAction.NONE.id.equals(prefs.actionId(slot)),
                v -> ActionDialog.show(this, displayName() + " · " + title,
                        prefs.actionId(slot),
                        action -> {
                            prefs.setActionId(slot, action.id);
                            if (action.needsApp) {
                                Intent i = new Intent(this, AppPickerActivity.class);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT, slot);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, displayName() + " · " + title);
                                startActivity(i);
                            } else {
                                boolean none = GestureAction.NONE.id.equals(prefs.actionId(slot));
                                holder[0].value.setText(prefs.displayFor(slot));
                                holder[0].value.setTextColor(UiKit.col(this, none ? R.color.text_secondary : R.color.text_primary));
                            }
                        }));
        holder[0] = r;
        return r.row;
    }

    private void confirmDelete(String side) {
        new AlertDialog.Builder(this)
                .setTitle("删除此手柄")
                .setMessage("将删除该手柄及其全部手势映射，是否继续？")
                .setPositiveButton("删除", (d, w) -> {
                    prefs.removeHandle(handleId);
                    prefs.setHandleCount(side, prefs.handleCount(side) - 1);
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String dirName(String dir) {
        if ("U".equals(dir)) return "上滑";
        if ("D".equals(dir)) return "下滑";
        return "直滑";
    }

    private int iconForDir(String dir) {
        if ("U".equals(dir)) return R.drawable.ic_swipe_u;
        if ("D".equals(dir)) return R.drawable.ic_swipe_d;
        return R.drawable.ic_swipe_h;
    }
}
