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
    private int groupIndex;
    private boolean advanced;
    private String selectedLength = "S";
    private boolean placement;
    private GesturePracticeView practice;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_handle_edit);
        UiKit.applyInsets(findViewById(android.R.id.content));
        handleId = getIntent().getStringExtra(EXTRA_HANDLE_ID);
        if (handleId == null || !handleId.matches("[LR][1-3]")) {
            finish();
            return;
        }
        prefs = new Prefs(this);
        groupIndex = Integer.parseInt(handleId.substring(1));
        if (savedInstanceState != null) {
            advanced = savedInstanceState.getBoolean("advanced"); selectedLength = savedInstanceState.getString("length", "S");
            placement = savedInstanceState.getBoolean("placement");
        }
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

    @Override public void finish() { super.finish(); Motion.closeActivity(this); }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBoolean("advanced", advanced); out.putString("length", selectedLength);
        out.putBoolean("placement", placement); super.onSaveInstanceState(out);
    }

    private String displayName() {
        return (handleId.startsWith("L") ? "左手柄 " : "右手柄 ") + handleId.substring(1);
    }

    private void build(LinearLayout root) {
        LinearLayout c0 = UiKit.card(this);
        c0.addView(UiKit.segmentedTabs(this, new String[]{"左手柄", "右手柄"}, handleId.startsWith("L") ? 0 : 1, sel -> {
            handleId = (sel == 0 ? "L" : "R") + groupIndex;
            ((TextView) findViewById(R.id.handleTitle)).setText(displayName());
            rebuild();
        }));
        c0.addView(UiKit.clickRow(this, "复制到对侧", "把这一侧的尺寸与全部映射复制过去", v -> showCopyDialog()));
        root.addView(c0);

        LinearLayout c1 = UiKit.card(this);
        c1.addView(UiKit.switchRow(this, "启用此手柄", null, prefs.handleEnabled(handleId),
                v -> { prefs.setHandleEnabled(handleId, v); rebuild(); }));
        c1.addView(UiKit.subLabel(this, "短滑练习 · 不执行动作"));
        practice = new GesturePracticeView(this, handleId);
        c1.addView(practice, new LinearLayout.LayoutParams(-1, UiKit.dp(this, 156)));
        root.addView(c1);
        root.addView(UiKit.segmentedTabs(this, new String[]{"动作配置", "位置与外观"}, placement ? 1 : 0, selected -> {
            placement = selected == 1; rebuild();
        }));

        LinearLayout c2 = UiKit.card(this);
        c2.addView(UiKit.subLabel(this, "从" + (handleId.startsWith("L") ? "左" : "右") + "手柄向内滑动 · 点按动作修改"));
        c2.addView(UiKit.segmentedTabs(this, new String[]{"短滑", "长滑"}, selectedLength.equals("L") ? 1 : 0, selected -> {
            selectedLength = selected == 1 ? "L" : "S"; rebuild();
        }));
        if (selectedLength.equals("L")) c2.addView(UiKit.switchRow(this, "启用长滑", "同时对所有手柄生效", prefs.longEnabled(),
                enabled -> prefs.setBool(Prefs.LONG_ENABLED, enabled)));
        for (String dir : new String[]{"U", "H", "D"}) c2.addView(gestureRow(selectedLength, dir));
        if (selectedLength.equals("L")) c2.addView(UiKit.subLabel(this, "未配置长滑时使用对应短滑动作；长滑总开关在设置 → 手势识别。"));
        LinearLayout advancedRows = new LinearLayout(this); advancedRows.setOrientation(LinearLayout.VERTICAL);
        for (String dir : new String[]{"U", "H", "D"}) advancedRows.addView(gestureRow("H", dir));
        advancedRows.addView(slotRowFor(Prefs.pressSlot(handleId), "原地长按", 0));
        advancedRows.setVisibility(advanced ? View.VISIBLE : View.GONE);
        advancedRows.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) {}
            public void onViewDetachedFromWindow(View view) {
                if (view.getTag() instanceof android.animation.ValueAnimator) {
                    android.animation.ValueAnimator animation = (android.animation.ValueAnimator) view.getTag();
                    animation.removeAllListeners(); animation.cancel(); view.setTag(null);
                }
            }
        });
        LinearLayout toggle = UiKit.clickRow(this, advanced ? "收起进阶手势" : "进阶手势", "滑动保持与原地长按", null);
        toggle.setOnClickListener(v -> {
            advanced = !advanced;
            ((TextView) ((LinearLayout) toggle.getChildAt(0)).getChildAt(0)).setText(advanced ? "收起进阶手势" : "进阶手势");
            Motion.expand(advancedRows, advanced);
        });
        c2.addView(toggle); c2.addView(advancedRows);

        LinearLayout c3 = UiKit.card(this);
        c3.addView(UiKit.subLabel(this, "尺寸与位置"));
        LinearLayout coexist = new LinearLayout(this);
        coexist.setOrientation(LinearLayout.VERTICAL);
        if (prefs.handlePriorityEnabled()) {
            TextView allocation = UiKit.subLabel(this, prioritySummary());
            c3.addView(UiKit.sliderRow(this, "接管高度", 0, 200, prefs.priorityRequestedDp(handleId), "dp", value -> {
                prefs.setPriorityRequestedDp(handleId, value);
                if (prefs.syncHandles()) prefs.setPriorityRequestedDp(mirrorId(), value);
                allocation.setText(prioritySummary());
                coexist.setVisibility(prefs.priorityHeightDp(handleId) > 0 ? View.GONE : View.VISIBLE);
            }));
            c3.addView(allocation);
        }
        coexist.addView(UiKit.sliderRow(this, "距屏幕边缘", 4, 80, prefs.handleMarginDp(), "dp", prefs::setHandleMarginDp));
        coexist.addView(UiKit.subLabel(this, "最外侧留给系统返回；短指示条只标记起点，触摸范围仍由长度决定。"));
        coexist.addView(UiKit.sliderRow(this, "长度", 30, 95, prefs.handleLen(handleId), "%", this::setLen));
        coexist.setVisibility(prefs.handlePriorityEnabled() && prefs.priorityHeightDp(handleId) > 0 ? View.GONE : View.VISIBLE);
        c3.addView(coexist);
        c3.addView(UiKit.subLabel(this, "外观"));
        c3.addView(UiKit.sliderRow(this, "垂直位置", 0, 100, prefs.handlePos(handleId), "%",
                this::setPos));
        c3.addView(UiKit.sliderRow(this, "不透明度", 0, 100, prefs.handleAlpha(handleId), "%",
                v -> prefs.setHandleAlpha(handleId, v)));
        root.addView(placement ? c3 : c2);
        LinearLayout footer = UiKit.card(this);
        footer.addView(UiKit.clickRow(this, "外观与反馈", "曲面样式、颜色与透明度", v -> {
            startActivity(new Intent(this, AppearanceActivity.class)); Motion.openActivity(this);
        }));
        footer.addView(UiKit.subLabel(this, "修改自动保存。上方仅练习短滑；实际边缘手势需开启服务，并按设备调整系统返回边距。"));
        root.addView(footer);

        LinearLayout c4 = UiKit.card(this);
        final boolean canDelete = prefs.groupCount() > 1;
        String delSub = !canDelete ? "至少保留 1 个手柄组"
                : prefs.bothSidesEnabled(groupIndex) ? "将同时删除左手柄和右手柄" : "将删除该侧手柄";
        LinearLayout del = UiKit.clickRow(this, "删除手柄组 " + groupIndex, delSub,
                v -> { if (canDelete) confirmDelete(); });
        LinearLayout texts = (LinearLayout) del.getChildAt(0);
        ((TextView) texts.getChildAt(0)).setTextColor(UiKit.col(this, canDelete ? R.color.danger : R.color.text_secondary));
        c4.addView(del);
        root.addView(c4);
    }

    private void rebuild() {
        LinearLayout root = findViewById(R.id.contentRoot); Motion.reset(root); root.removeAllViews(); build(root); Motion.contentIn(root, 0);
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
                            if (action.needsApp) {
                                Intent i = new Intent(this, AppPickerActivity.class);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT, slot);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, displayName() + " · " + title);
                                startActivity(i);
                                Motion.openActivity(this);
                            } else {
                                prefs.setActionId(slot, action.id);
                                boolean none = GestureAction.NONE.id.equals(prefs.actionId(slot));
                                holder[0].value.setText(prefs.displayFor(slot));
                                holder[0].value.setTextColor(UiKit.col(this,
                                        none ? R.color.text_secondary : R.color.text_primary));
                                practice.refreshMapping();
                            }
                        }));
        holder[0] = r;
        return r.row;
    }

    private void confirmDelete() {
        boolean both = prefs.bothSidesEnabled(groupIndex);
        new AlertDialog.Builder(this)
                .setTitle("删除手柄组 " + groupIndex)
                .setMessage(both ? "将同时删除左手柄和右手柄，是否继续？" : "将删除该手柄，是否继续？")
                .setPositiveButton("删除", (d, w) -> {
                    prefs.beginUndo("手柄组 " + groupIndex);
                    prefs.deleteGroup(groupIndex);
                    Toast.makeText(this, "已删除，可在首页撤销", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showCopyDialog() {
        final String side = handleId.startsWith("L") ? "L" : "R";
        final String other = side.equals("L") ? "R" : "L";
        new AlertDialog.Builder(this).setTitle("复制映射")
                .setItems(new String[]{"复制此侧到对侧", "从对侧复制到此侧"}, (d, which) -> {
                    prefs.copySideToOther(groupIndex, which == 0 ? side : other);
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
                    rebuild();
                }).show();
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

    private String prioritySummary() {
        return "当前分配约 " + Math.round(prefs.priorityHeightDp(handleId))
                + "dp。每侧总计最多 200dp，多手柄按设定比例共享；设为 0 时，此手柄使用内移共存。";
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
