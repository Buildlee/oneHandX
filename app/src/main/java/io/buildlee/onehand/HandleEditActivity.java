package io.buildlee.onehand;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/** 单个手柄的编辑页：启停、10 个手势槽位（短滑/长滑/滑动保持 × 3 方向 + 长按）、外观、删除 */
public class HandleEditActivity extends AppCompatActivity {

    public static final String EXTRA_HANDLE_ID = "handle_id";

    private Prefs prefs;
    private String handleId;
    private boolean firstBuildAnim = true;
    private boolean advanced;
    private String selectedLength = "S";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_handle_edit);
        UiKit.applyInsets(findViewById(android.R.id.content));
        handleId = getIntent().getStringExtra(EXTRA_HANDLE_ID);
        if (handleId == null || handleId.length() < 2) {
            finish();
            return;
        }
        prefs = new Prefs(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> {
            finish();
            overridePendingTransition(R.anim.fade_in_slight, R.anim.push_out);
        });
        TextView title = findViewById(R.id.handleTitle);
        title.setText(displayName());
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout root = findViewById(R.id.contentRoot);
        root.removeAllViews();
        build(root);
        if (firstBuildAnim) {
            firstBuildAnim = false;
            Motion.enterStagger(root);
        }
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
        c2.addView(UiKit.subLabel(this, "从" + (handleId.startsWith("L") ? "左" : "右") + "手柄向内滑动 · 点按动作修改"));
        LinearLayout lengths = new LinearLayout(this);
        for (String length : new String[]{"S", "L"}) {
            TextView tab = new TextView(this); tab.setText(length.equals("S") ? "短滑" : "长滑");
            tab.setTextSize(15); tab.setGravity(android.view.Gravity.CENTER); tab.setMinHeight(UiKit.dp(this, 48));
            tab.setTextColor(UiKit.col(this, length.equals(selectedLength) ? R.color.accent : R.color.text_secondary));
            tab.setSelected(length.equals(selectedLength));
            tab.setOnClickListener(v -> { selectedLength = length; rebuild(); });
            lengths.addView(tab, new LinearLayout.LayoutParams(0, -2, 1));
        }
        c2.addView(lengths);
        for (String dir : new String[]{"U", "H", "D"}) c2.addView(gestureRow(selectedLength, dir));
        if (selectedLength.equals("L")) c2.addView(UiKit.subLabel(this, "未配置长滑时使用对应短滑动作；长滑总开关在设置 → 手势识别。"));
        c2.addView(UiKit.clickRow(this, advanced ? "收起进阶手势" : "进阶手势", "滑动保持与原地长按", v -> { advanced = !advanced; rebuild(); }));
        if (advanced) {
            for (String dir : new String[]{"U", "H", "D"}) c2.addView(gestureRow("H", dir));
            c2.addView(slotRowFor(Prefs.pressSlot(handleId), "原地长按", 0));
        }

        LinearLayout c3 = UiKit.card(this);
        c3.addView(UiKit.subLabel(this, "尺寸与位置"));
        c3.addView(UiKit.sliderRow(this, "距屏幕边缘", 4, 80, prefs.handleMarginDp(), "dp",
                v -> prefs.setHandleMarginDp(v)));
        c3.addView(UiKit.subLabel(this, "最外侧留给系统返回，26dp 是建议起点；仍有冲突时增加边距并试滑。不同手机的系统热区不同。"));
        c3.addView(UiKit.subLabel(this, "外观"));
        c3.addView(UiKit.sliderRow(this, "长度", 30, 95, prefs.handleLen(handleId), "%",
                this::setLen));
        c3.addView(UiKit.sliderRow(this, "垂直位置", 0, 100, prefs.handlePos(handleId), "%",
                this::setPos));
        c3.addView(UiKit.sliderRow(this, "不透明度", 0, 100, prefs.handleAlpha(handleId), "%",
                v -> prefs.setHandleAlpha(handleId, v)));
        root.addView(c2);
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

    private void rebuild() {
        LinearLayout root = findViewById(R.id.contentRoot); root.removeAllViews(); build(root);
    }

    private View gestureRow(String len, String dir) {
        return slotRowFor(Prefs.slot(handleId, len, dir),
                lenName(len) + " · " + dirName(dir), iconForDir(dir));
    }

    private View slotRowFor(final String slot, final String title, int iconRes) {
        final UiKit.SlotRow[] holder = new UiKit.SlotRow[1];
        UiKit.SlotRow r = UiKit.gestureRow(this, title, prefs.displayFor(slot), handleId.startsWith("L"),
                slot.endsWith("-U") ? -1 : slot.endsWith("-D") ? 1 : 0,
                v -> ActionDialog.show(this, displayName() + " · " + title,
                        prefs.actionId(slot),
                        action -> {
                            prefs.setActionId(slot, action.id);
                            if (action.needsApp) {
                                Intent i = new Intent(this, AppPickerActivity.class);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT, slot);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, displayName() + " · " + title);
                                startActivity(i);
                                overridePendingTransition(R.anim.push_in, R.anim.fade_out_slight);
                            } else {
                                boolean none = GestureAction.NONE.id.equals(prefs.actionId(slot));
                                holder[0].value.setText(prefs.displayFor(slot));
                                holder[0].value.setTextColor(UiKit.col(this,
                                        none ? R.color.text_secondary : R.color.text_primary));
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
                    overridePendingTransition(R.anim.fade_in_slight, R.anim.push_out);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String dirName(String dir) {
        String inward = handleId.startsWith("L") ? "右" : "左";
        if ("U".equals(dir)) return "向" + inward + "上滑动";
        if ("D".equals(dir)) return "向" + inward + "下滑动";
        return "向" + inward + "滑动";
    }

    private void setLen(int v) {
        prefs.setHandleLen(handleId, v);
        if (prefs.syncHandles()) prefs.setHandleLen(mirrorId(), v);
    }

    private void setPos(int v) {
        prefs.setHandlePos(handleId, v);
        if (prefs.syncHandles()) prefs.setHandlePos(mirrorId(), v);
    }

    private String mirrorId() {
        return (handleId.startsWith("L") ? "R" : "L") + handleId.substring(1);
    }

    private String lenName(String len) {
        if ("L".equals(len)) return "长滑";
        if ("H".equals(len)) return "滑动保持";
        return "短滑";
    }

    private int iconForDir(String dir) {
        if ("U".equals(dir)) return R.drawable.ic_swipe_u;
        if ("D".equals(dir)) return R.drawable.ic_swipe_d;
        return R.drawable.ic_swipe_h;
    }
}
