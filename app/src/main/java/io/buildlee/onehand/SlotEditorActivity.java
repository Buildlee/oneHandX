package io.buildlee.onehand;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** v0.7：一个手势槽位的动作序列编辑页（按顺序执行，可增删与排序）。 */
public class SlotEditorActivity extends AppCompatActivity {

    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_NAME = "slot_name";

    private Prefs prefs;
    private String slot;
    private String name;

    @Override public void finish() { super.finish(); Motion.closeActivity(this); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_handle_edit);
        UiKit.applyInsets(findViewById(android.R.id.content));
        slot = getIntent().getStringExtra(EXTRA_SLOT);
        name = getIntent().getStringExtra(EXTRA_NAME);
        if (slot == null) { finish(); return; }
        prefs = new Prefs(this);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.handleTitle)).setText("动作序列");
    }

    @Override
    protected void onResume() {
        super.onResume();
        rebuild();
    }

    private void rebuild() {
        LinearLayout root = findViewById(R.id.contentRoot);
        Motion.reset(root);
        root.removeAllViews();
        build(root);
        Motion.contentIn(root, 0);
    }

    private void build(LinearLayout root) {
        List<Prefs.Step> steps = prefs.steps(slot);

        LinearLayout head = UiKit.card(this);
        head.addView(UiKit.subLabel(this, name == null ? "" : name));
        TextView count = new TextView(this);
        count.setText(steps.isEmpty() ? "当前为空 · 该手势不会触发"
                : "共 " + steps.size() + " 个动作 · 从上到下依次执行");
        count.setTextSize(13);
        count.setTextColor(UiKit.col(this, R.color.text_secondary));
        count.setPadding(UiKit.dp(this, 2), 0, UiKit.dp(this, 2), UiKit.dp(this, 6));
        head.addView(count);
        root.addView(head);

        if (!steps.isEmpty()) {
            LinearLayout list = UiKit.card(this);
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) list.addView(new View(this), new LinearLayout.LayoutParams(-1, 1));
                list.addView(stepRow(i, steps, this::apply));
            }
            root.addView(list);
        }

        LinearLayout add = UiKit.card(this);
        add.addView(UiKit.clickRow(this, "添加动作", steps.isEmpty() ? "选择第一个动作" : "追加到序列末尾",
                v -> ActionDialog.show(this, "添加动作", "",
                        GestureAction.pickerWithoutPanels(),
                        action -> {
                            if (action.needsApp) {
                                Intent i = new Intent(this, AppPickerActivity.class);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT, slot);
                                i.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, "追加应用动作");
                                i.putExtra(AppPickerActivity.EXTRA_APPEND, true);
                                startActivity(i);
                                Motion.openActivity(this);
                            } else {
                                append(action.id);
                            }
                        })));
        LinearLayout clear = UiKit.clickRow(this, "清空动作序列", "该手势将不再触发", v ->
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("清空动作序列")
                        .setMessage("将删除这个手势的全部动作，是否继续？")
                        .setPositiveButton("清空", (d, w) -> {
                            prefs.setSteps(slot, new ArrayList<Prefs.Step>());
                            rebuild();
                        })
                        .setNegativeButton("取消", null)
                        .show());
        TextView clearTitle = (TextView) ((LinearLayout) clear.getChildAt(0)).getChildAt(0);
        clearTitle.setTextColor(UiKit.col(this, R.color.danger));
        add.addView(clear);
        root.addView(add);

        LinearLayout note = UiKit.card(this);
        note.addView(UiKit.subLabel(this,
                "多动作之间间隔约 0.15 秒执行；返回、主屏幕等系统动作受设备限制时，后面的步骤仍会继续。"));
        root.addView(note);
    }

    private View stepRow(final int index, final List<Prefs.Step> steps, final Runnable refresh) {
        final Prefs.Step step = steps.get(index);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(UiKit.dp(this, 56));
        row.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 8), UiKit.dp(this, 2), UiKit.dp(this, 8));

        TextView idx = new TextView(this);
        idx.setText(String.valueOf(index + 1));
        idx.setTextSize(13);
        idx.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        idx.setGravity(Gravity.CENTER);
        idx.setTextColor(UiKit.col(this, R.color.text_secondary));
        row.addView(idx, new LinearLayout.LayoutParams(UiKit.dp(this, 28), -2));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView label = new TextView(this);
        label.setText(prefs.stepText(step));
        label.setTextSize(16.5f);
        label.setLetterSpacing(-0.01f);
        label.setTextColor(UiKit.col(this, R.color.text_primary));
        texts.addView(label);
        TextView note = new TextView(this);
        String trail = step.pkg.isEmpty() ? step.action().category() : step.action().label;
        note.setText(trail);
        note.setTextSize(12);
        note.setTextColor(UiKit.col(this, R.color.text_secondary));
        note.setPadding(0, UiKit.dp(this, 2), 0, 0);
        texts.addView(note);
        texts.setPadding(UiKit.dp(this, 2), UiKit.dp(this, 6), 0, UiKit.dp(this, 6));
        texts.setOnClickListener(v -> pickForStep(index));
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        row.addView(miniButton("\u2191", index == 0, false, v -> move(index, -1)));
        row.addView(miniButton("\u2193", index == steps.size() - 1, false, v -> move(index, 1)));
        row.addView(miniButton("\u2715", false, true, v -> {
            List<Prefs.Step> next = new ArrayList<>(steps);
            next.remove(index);
            prefs.setSteps(slot, next);
            refresh.run();
        }));
        row.setOnClickListener(v -> pickForStep(index));
        texts.setOnClickListener(v -> pickForStep(index));
        return row;
    }

    private TextView miniButton(String text, boolean disabled, boolean danger, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(17);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(UiKit.col(this, danger ? R.color.danger : R.color.text_secondary));
        t.setBackgroundResource(R.drawable.bg_row);
        t.setContentDescription(text.equals("\u2715") ? "删除" : text.equals("\u2191") ? "上移" : "下移");
        t.setOnClickListener(disabled ? null : click);
        t.setEnabled(!disabled);
        if (disabled) t.setAlpha(0.3f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 48));
        lp.leftMargin = UiKit.dp(this, 2);
        t.setLayoutParams(lp);
        return t;
    }

    private void pickForStep(final int index) {
        List<Prefs.Step> steps = prefs.steps(slot);
        if (index < 0 || index >= steps.size()) return;
        final String current = steps.get(index).id;
        ActionDialog.show(this, "第 " + (index + 1) + " 个动作", current,
                GestureAction.pickerWithoutPanels(),
                action -> {
                    if (action.needsApp) {
                        Intent i = new Intent(this, AppPickerActivity.class);
                        i.putExtra(AppPickerActivity.EXTRA_SLOT, slot);
                        i.putExtra(AppPickerActivity.EXTRA_SLOT_NAME, "第 " + (index + 1) + " 个动作");
                        i.putExtra(AppPickerActivity.EXTRA_STEP_INDEX, index);
                        startActivity(i);
                        Motion.openActivity(this);
                    } else {
                        List<Prefs.Step> next = new ArrayList<>(prefs.steps(slot));
                        next.set(index, new Prefs.Step(action.id, null, null));
                        prefs.setSteps(slot, next);
                        rebuild();
                    }
                });
    }

    private void move(int index, int delta) {
        List<Prefs.Step> steps = new ArrayList<>(prefs.steps(slot));
        int to = index + delta;
        if (to < 0 || to >= steps.size()) return;
        Collections.swap(steps, index, to);
        prefs.setSteps(slot, steps);
        rebuild();
    }

    private void append(String id) {
        List<Prefs.Step> steps = new ArrayList<>(prefs.steps(slot));
        steps.add(new Prefs.Step(id, null, null));
        prefs.setSteps(slot, steps);
        rebuild();
    }

    private void apply() { rebuild(); }
}
