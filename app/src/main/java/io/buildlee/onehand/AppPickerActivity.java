package io.buildlee.onehand;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AppPickerActivity extends AppCompatActivity {

    public static final String EXTRA_SLOT = "slot";
    public static final String EXTRA_SLOT_NAME = "slot_name";

    public static class Entry {
        public String label;
        public String pkg;
        public Drawable icon;
    }

    private List<Entry> all = new ArrayList<>();
    private List<Entry> shown = new ArrayList<>();
    private AppAdapter adapter;
    private String slot;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_picker);

        slot = getIntent().getStringExtra(EXTRA_SLOT);
        if (slot == null) {
            finish();
            return;
        }
        String slotName = getIntent().getStringExtra(EXTRA_SLOT_NAME);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        TextView sub = findViewById(R.id.pickerSub);
        sub.setText(slotName == null ? "" : "指定给：" + slotName);

        EditText search = findViewById(R.id.searchInput);
        ListView list = findViewById(R.id.appList);
        adapter = new AppAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Entry e = shown.get(position);
            Prefs prefs = new Prefs(this);
            prefs.setActionId(slot, GestureAction.LAUNCH_APP.id);
            prefs.setActionPkg(slot, e.pkg);
            prefs.setActionLabel(slot, e.label);
            Toast.makeText(this, "已设置：" + e.label, Toast.LENGTH_SHORT).show();
            finish();
        });
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { filter(s.toString()); }
            @Override public void afterTextChanged(Editable s) {}
        });

        load();
    }

    private void load() {
        new Thread(() -> {
            List<Entry> list = loadEntries(this);
            runOnUiThread(() -> {
                all = list;
                shown = list;
                adapter.setData(shown);
                if (list.isEmpty()) {
                    Toast.makeText(this, "没有找到可启动的应用", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    public static List<Entry> loadEntries(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        Map<String, Entry> uniq = new HashMap<>();
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName())) continue;
            Entry e = new Entry();
            e.pkg = pkg;
            e.label = ri.loadLabel(pm).toString();
            e.icon = ri.loadIcon(pm);
            uniq.put(pkg, e);
        }
        List<Entry> out = new ArrayList<>(uniq.values());
        Collections.sort(out, (a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void filter(String q) {
        String query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            shown = all;
        } else {
            List<Entry> out = new ArrayList<>();
            for (Entry e : all) {
                if (e.label.toLowerCase(Locale.ROOT).contains(query)
                        || e.pkg.toLowerCase(Locale.ROOT).contains(query)) {
                    out.add(e);
                }
            }
            shown = out;
        }
        adapter.setData(shown);
    }

    private class AppAdapter extends BaseAdapter {
        private List<Entry> data = new ArrayList<>();

        void setData(List<Entry> d) {
            data = d == null ? new ArrayList<>() : d;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return data.size(); }
        @Override public Entry getItem(int position) { return data.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : LayoutInflater.from(AppPickerActivity.this)
                    .inflate(R.layout.item_app, parent, false);
            Entry e = data.get(position);
            ((ImageView) v.findViewById(R.id.appIcon)).setImageDrawable(e.icon);
            ((TextView) v.findViewById(R.id.appName)).setText(e.label);
            ((TextView) v.findViewById(R.id.appPkg)).setText(e.pkg);
            return v;
        }
    }
}
