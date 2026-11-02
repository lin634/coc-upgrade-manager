package com.coc.upgrade;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/** 模型设置:完全自定义服务商(可添加多个)+ 默认模型 + 测试连接 */
public class SettingsActivity extends Activity {

    private static final int GREEN = 0xFF2E7D32;
    private static final int GREEN_DARK = 0xFF1B5E20;
    private static final int TEXT_MAIN = 0xFF1F2937;
    private static final int TEXT_SUB = 0xFF6B7280;

    private ListView provList;
    private TextView provEmpty;
    private Spinner defaultSpinner;
    private ArrayAdapter<String> defaultAdapter;
    private ProvAdapter adapter;
    private EditText syncUrlEt, syncUserEt, syncPassEt;
    private android.widget.CheckBox syncEnabledCb;
    private TextView syncStatus;

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(color);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView text(String s, float size, int color) {
        return text(s, size, color, false);
    }

    private LinearLayout card(int orientation) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(orientation);
        c.setBackgroundResource(R.drawable.bg_card_ripple);
        return c;
    }

    private Button btn(String text, boolean primary, float heightDp) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setBackgroundResource(primary
                ? R.drawable.bg_btn_primary_ripple
                : R.drawable.bg_btn_secondary_ripple);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setGravity(Gravity.CENTER);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(GREEN_DARK);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(12), dp(16), dp(12));
        TextView back = text("‹", 22, Color.WHITE, true);
        back.setPadding(dp(8), 0, dp(12), 0);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        bar.addView(text("模型设置", 17, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(12));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 服务商列表(卡片化两行条目)
        TextView provTitle = text("服务商", 14, TEXT_MAIN, true);
        provTitle.setPadding(dp(4), dp(14), dp(4), dp(6));
        content.addView(provTitle);

        FrameLayout fl = new FrameLayout(this);
        adapter = new ProvAdapter();
        provList = new ListView(this);
        provList.setDivider(null);
        provList.setDividerHeight(0);
        provList.setAdapter(adapter);
        provList.setOnItemClickListener((p, v, pos, id) -> editDialog(pos));
        fl.addView(provList, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        provEmpty = text("还没有服务商\n\n点下方「＋ 添加服务商」开始配置", 14, TEXT_SUB);
        provEmpty.setGravity(Gravity.CENTER);
        fl.addView(provEmpty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(fl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 服务商操作按钮(等高等宽对齐)
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button addP = btn("＋ 添加服务商", true, 46);
        addP.setOnClickListener(v -> editDialog(-1));
        Button test = btn("测试连接", false, 46);
        test.setOnClickListener(v -> test());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        blp.setMargins(dp(3), 0, dp(3), 0);
        row.addView(addP, blp);
        LinearLayout.LayoutParams blp2 = new LinearLayout.LayoutParams(0, dp(46), 1f);
        blp2.setMargins(dp(3), 0, dp(3), 0);
        row.addView(test, blp2);
        LinearLayout.LayoutParams rowlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowlp.setMargins(0, dp(10), 0, 0);
        content.addView(row, rowlp);

        // 默认模型卡片
        LinearLayout defCard = card(LinearLayout.VERTICAL);
        defCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        defCard.addView(text("默认模型(截图识别需选支持图片的模型)", 13, TEXT_SUB));
        defaultSpinner = new Spinner(this);
        defaultAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        defaultAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        defaultSpinner.setAdapter(defaultAdapter);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        splp.setMargins(0, dp(6), 0, 0);
        defCard.addView(defaultSpinner, splp);
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dclp.setMargins(0, dp(10), 0, 0);
        content.addView(defCard, dclp);

        // 多设备同步卡片
        LinearLayout syncCard = card(LinearLayout.VERTICAL);
        syncCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        syncCard.addView(text("多设备同步(WebDAV)", 14, TEXT_MAIN, true));
        TextView syncHint = text("把数据存到网盘,多台手机互通、每台都会到点提醒。"
                + "以坚果云为例,文件地址填:https://dav.jianguoyun.com/dav/coc-upgrade.json"
                + "(账号填邮箱,密码用「添加应用」生成的应用密码)", 12, TEXT_SUB);
        syncHint.setPadding(0, dp(4), 0, dp(2));
        syncCard.addView(syncHint);
        syncUrlEt = field(syncCard, "WebDAV 文件完整地址");
        syncUserEt = field(syncCard, "网盘账号");
        syncPassEt = field(syncCard, "应用密码");
        syncPassEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        syncEnabledCb = new android.widget.CheckBox(this);
        syncEnabledCb.setText("启用自动同步(打开 App 和数据变化时自动同步)");
        syncEnabledCb.setTextSize(13);
        syncEnabledCb.setTextColor(TEXT_MAIN);
        LinearLayout.LayoutParams cblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cblp.setMargins(0, dp(6), 0, 0);
        syncCard.addView(syncEnabledCb, cblp);
        LinearLayout srow = new LinearLayout(this);
        srow.setGravity(Gravity.CENTER_VERTICAL);
        Button syncNow = btn("立即同步", false, 42);
        syncNow.setOnClickListener(v -> {
            saveSyncFields();
            syncStatus.setText("同步中…");
            Sync.syncAsync(msg -> runOnUiThread(() -> {
                syncStatus.setText(msg);
                refresh();
            }));
        });
        LinearLayout.LayoutParams snlp = new LinearLayout.LayoutParams(dp(120), dp(42));
        snlp.setMargins(0, dp(6), dp(10), 0);
        srow.addView(syncNow, snlp);
        syncStatus = text("", 12, TEXT_SUB);
        srow.addView(syncStatus, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        syncCard.addView(srow);
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sclp.setMargins(0, dp(10), 0, 0);
        content.addView(syncCard, sclp);
        prefillSync();

        // 保存
        Button save = btn("保存并返回", true, 48);
        save.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams sblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        sblp.setMargins(0, dp(10), 0, 0);
        content.addView(save, sblp);

        setContentView(root);
        refresh();
    }

    private void refresh() {
        String keep = Store.config.optString("default_model");
        defaultAdapter.clear();
        JSONArray providers = Store.config.optJSONArray("providers");
        int count = providers == null ? 0 : providers.length();
        for (int i = 0; i < count; i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p == null) continue;
            JSONArray models = p.optJSONArray("models");
            int n = models == null ? 0 : models.length();
            for (int j = 0; j < n; j++) {
                String m = models.optString(j);
                if (!m.isEmpty()) defaultAdapter.add(p.optString("name") + "/" + m);
            }
        }
        adapter.notifyDataSetChanged();
        provEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
        int idx = defaultAdapter.getPosition(keep);
        if (idx >= 0) defaultSpinner.setSelection(idx);
    }

    // ---------------------------------------------------------------- 服务商编辑

    private EditText field(LinearLayout form, String label) {
        TextView l = text(label, 12, TEXT_SUB);
        l.setPadding(0, dp(10), 0, dp(3));
        form.addView(l);
        EditText e = new EditText(this);
        e.setTextSize(14);
        e.setSingleLine();
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(dp(10), dp(10), dp(10), dp(10));
        form.addView(e);
        return e;
    }

    private void editDialog(int idx) {
        JSONArray providers = Store.config.optJSONArray("providers");
        final JSONObject p;
        final boolean isNew = idx < 0;
        if (isNew) {
            p = Store.put(new JSONObject(), "name", "");
            Store.put(p, "base_url", "");
            Store.put(p, "api_key", "");
            Store.put(p, "models", new JSONArray());
        } else {
            p = providers.optJSONObject(idx);
            if (p == null) return;
        }

        ScrollView sc = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, dp(8), pad, 0);
        sc.addView(form);

        final EditText nameEt = field(form, "名称(如:智谱 / 中转站A)");
        final EditText urlEt = field(form, "Base URL(OpenAI 兼容,不含 /chat/completions)");
        final EditText keyEt = field(form, "API Key");
        keyEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText modelsEt = field(form, "模型(多个用英文逗号分隔)");
        nameEt.setText(p.optString("name"));
        urlEt.setText(p.optString("base_url"));
        keyEt.setText(p.optString("api_key"));
        JSONArray ms = p.optJSONArray("models");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < (ms == null ? 0 : ms.length()); i++) {
            if (i > 0) sb.append(", ");
            sb.append(ms.optString(i));
        }
        modelsEt.setText(sb.toString());

        AlertDialog.Builder ab = new AlertDialog.Builder(this)
                .setTitle(isNew ? "添加服务商" : "编辑服务商")
                .setView(sc)
                .setPositiveButton("保存", (d, w) -> {
                    // 保存时重新获取最新配置,避免写入过期的内存副本导致丢失
                    JSONArray providersNow = Store.config.optJSONArray("providers");
                    if (providersNow == null) {
                        providersNow = new JSONArray();
                        Store.put(Store.config, "providers", providersNow);
                    }
                    String name = nameEt.getText().toString().trim();
                    if (name.isEmpty()) name = "未命名";
                    JSONArray models = new JSONArray();
                    for (String m : modelsEt.getText().toString().split("[,，]")) {
                        m = m.trim();
                        if (!m.isEmpty()) models.put(m);
                    }
                    // 编辑时在最新列表里定位目标:优先按旧名,其次按索引
                    JSONObject target = null;
                    if (!isNew) {
                        String oldName = p.optString("name");
                        for (int i = 0; i < providersNow.length(); i++) {
                            JSONObject x = providersNow.optJSONObject(i);
                            if (x != null && !oldName.isEmpty() && oldName.equals(x.optString("name"))) {
                                target = x;
                                break;
                            }
                        }
                        if (target == null && idx >= 0 && idx < providersNow.length())
                            target = providersNow.optJSONObject(idx);
                    }
                    if (target == null) {
                        target = new JSONObject();
                        providersNow.put(target);
                    }
                    String prevName = target.optString("name");
                    Store.put(target, "name", name);
                    Store.put(target, "base_url", urlEt.getText().toString().trim());
                    Store.put(target, "api_key", keyEt.getText().toString().trim());
                    Store.put(target, "models", models);
                    String def = Store.config.optString("default_model");
                    if (def.startsWith(prevName + "/") && !prevName.isEmpty()) {
                        Store.put(Store.config, "default_model",
                                name + def.substring(prevName.length()));
                    }
                    Store.saveConfig();
                    refresh();
                    toast("已保存");
                })
                .setNegativeButton("取消", null);
        if (!isNew) {
            ab.setNeutralButton("删除", (d, w) -> {
                JSONArray providersNow = Store.config.optJSONArray("providers");
                JSONArray keep = new JSONArray();
                String name = p.optString("name");
                for (int i = 0; i < providersNow.length(); i++) {
                    JSONObject x = providersNow.optJSONObject(i);
                    if (x != null && !name.equals(x.optString("name"))) keep.put(x);
                }
                Store.put(Store.config, "providers", keep);
                String def = Store.config.optString("default_model");
                if (def.startsWith(name + "/")) Store.put(Store.config, "default_model", "");
                Store.saveConfig();
                refresh();
            });
        }
        ab.show();
    }

    // ---------------------------------------------------------------- 测试连接

    private void test() {
        Object sel = defaultSpinner.getSelectedItem();
        if (sel == null) {
            toast("请先在下方选择默认模型");
            return;
        }
        String s = sel.toString();
        int slash = s.indexOf('/');
        if (slash <= 0) return;
        final String pname = s.substring(0, slash), model = s.substring(slash + 1);
        final JSONObject provider = Store.providerByName(pname);
        if (provider == null) {
            toast("找不到服务商");
            return;
        }
        toast("正在测试 " + s + " …");
        new Thread(() -> {
            String msg;
            try {
                String r = ApiClient.chat(provider, model, "请只回复:OK");
                r = r == null ? "" : r.trim();
                msg = "连接成功,模型返回:" + (r.isEmpty() ? "(空)"
                        : r.substring(0, Math.min(80, r.length())));
            } catch (Exception e) {
                msg = "连接失败:" + e.getMessage();
            }
            final String fMsg = msg;
            runOnUiThread(() ->
                    Toast.makeText(SettingsActivity.this, fMsg, Toast.LENGTH_LONG).show());
        }).start();
    }

    // ---------------------------------------------------------------- 同步配置

    private void prefillSync() {
        JSONObject sc = Store.config.optJSONObject("sync");
        if (sc == null) return;
        syncUrlEt.setText(sc.optString("url"));
        syncUserEt.setText(sc.optString("user"));
        syncPassEt.setText(sc.optString("pass"));
        syncEnabledCb.setChecked(sc.optBoolean("enabled"));
        long ts = sc.optLong("last_ts");
        if (ts > 0) syncStatus.setText("上次同步 " + fmtTime(ts));
    }

    private void saveSyncFields() {
        JSONObject sc = Store.config.optJSONObject("sync");
        if (sc == null) {
            sc = new JSONObject();
            Store.put(Store.config, "sync", sc);
        }
        Store.put(sc, "url", syncUrlEt.getText().toString().trim());
        Store.put(sc, "user", syncUserEt.getText().toString().trim());
        Store.put(sc, "pass", syncPassEt.getText().toString().trim());
        Store.put(sc, "enabled", syncEnabledCb.isChecked());
    }

    private static String fmtTime(long ms) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
                .format(new java.util.Date(ms));
    }

    @Override
    public void finish() {
        saveSyncFields();
        Object sel = defaultSpinner == null ? null : defaultSpinner.getSelectedItem();
        if (sel != null) Store.put(Store.config, "default_model", sel.toString());
        Store.saveConfig();
        super.finish();
    }

    // ---------------------------------------------------------------- 服务商列表适配器

    private class ProvAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            JSONArray ps = Store.config.optJSONArray("providers");
            return ps == null ? 0 : ps.length();
        }

        @Override
        public Object getItem(int position) {
            return Store.config.optJSONArray("providers").optJSONObject(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout v;
            TextView name, url;
            if (convertView instanceof LinearLayout) {
                v = (LinearLayout) convertView;
                LinearLayout left = (LinearLayout) v.getChildAt(0);
                name = (TextView) left.getChildAt(0);
                url = (TextView) left.getChildAt(1);
            } else {
                v = new LinearLayout(SettingsActivity.this);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setBackgroundResource(R.drawable.bg_card_ripple);
                int pad = dp(14);
                v.setPadding(pad, dp(10), pad, dp(10));
                LinearLayout left = new LinearLayout(SettingsActivity.this);
                left.setOrientation(LinearLayout.VERTICAL);
                name = text("", 15, TEXT_MAIN, true);
                url = text("", 12, TEXT_SUB);
                LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                ulp.setMargins(0, dp(3), 0, 0);
                left.addView(name);
                left.addView(url, ulp);
                TextView arrow = text("›", 18, TEXT_SUB, true);
                LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                alp.setMargins(dp(10), 0, 0, 0);
                v.addView(left, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                v.addView(arrow, alp);
            }
            JSONObject p = (JSONObject) getItem(position);
            boolean hasKey = !p.optString("api_key").isEmpty();
            name.setText((hasKey ? "🔑 " : "○ ") + p.optString("name"));
            url.setText(p.optString("base_url"));
            return v;
        }
    }
}
