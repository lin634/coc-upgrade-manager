package com.coc.upgrade;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    /** App 是否在前台(前台时到点提醒统一由界面发出,避免重复) */
    public static volatile boolean foreground = false;

    private static final int REQ_PICK = 1;
    private static final int REQ_PERM = 2;

    private static final int GREEN = 0xFF356B51;
    private static final int TEXT_MAIN = 0xFF263B31;
    private static final int TEXT_SUB = 0xFF7B857E;
    private static final int DONE_GREEN = 0xFF356B51;
    private static final int DANGER = 0xFFA5744E;

    /** 账号下拉里的"全部账号"总览项(哨兵值,不是真实账号名) */
    private static final String ALL = "全部账号";

    private Spinner accountSpinner;
    private ArrayAdapter<String> accountAdapter;
    private ListView listView;
    private TimerAdapter timerAdapter;
    private TextView statusView;
    private TextView emptyView;
    private LinearLayout selBar;
    private TextView selCount;
    private String currentAccount;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        buildUi();
        requestPerms();
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        handler.postDelayed(tick, 300);
    }

    @Override
    protected void onStop() {
        super.onStop();
        foreground = false;
        handler.removeCallbacks(tick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Store.init(this);
        refreshAccounts();
        startMonitor();
        syncSoon();
    }

    /** 数据变化后触发一次云端同步(未启用时静默跳过) */
    private void syncSoon() {
        if (!Sync.enabled()) return;
        Sync.syncAsync(msg -> runOnUiThread(() -> {
            statusView.setText("☁ " + msg);
            refreshAccounts();
        }));
    }

    /** 删除倒计时时记录墓碑,避免同步后从其他设备"复活" */
    private void tombstone(String id) {
        JSONArray d = Store.data.optJSONArray("deleted_ids");
        if (d == null) {
            d = new JSONArray();
            Store.put(Store.data, "deleted_ids", d);
        }
        if (d.length() >= 300) d.remove(0);
        d.put(id);
    }

    // ---------------------------------------------------------------- 通用控件

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
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
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        return b;
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

    private EditText numEdit() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setGravity(Gravity.CENTER);
        e.setTextSize(14);
        e.setBackgroundResource(R.drawable.bg_input);
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 界面构建

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // 顶栏(纸感:纸色背景 + 深色标题,状态栏同为纸色)
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(16), dp(14), dp(10));
        bar.addView(text("部落冲突升级管理", 18, TEXT_MAIN, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView gear = text("⚙ 设置", 13, GREEN);
        gear.setBackgroundResource(R.drawable.bg_btn_secondary);
        gear.setPadding(dp(12), dp(7), dp(12), dp(7));
        gear.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        bar.addView(gear);
        root.addView(bar);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 账号卡片
        LinearLayout accountCard = card(LinearLayout.HORIZONTAL);
        accountCard.setGravity(Gravity.CENTER_VERTICAL);
        accountCard.setPadding(dp(14), dp(6), dp(8), dp(6));
        accountCard.addView(text("账号", 14, TEXT_MAIN, true));
        accountSpinner = new Spinner(this);
        accountAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item);
        accountAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        accountSpinner.setAdapter(accountAdapter);
        accountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                currentAccount = accountAdapter.getItem(pos);
                if (timerAdapter != null && timerAdapter.selMode) exitSelMode();
                refreshList();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        splp.setMargins(dp(8), 0, dp(8), 0);
        accountCard.addView(accountSpinner, splp);
        Button addBtn = btn("＋", false, 38);
        addBtn.setTextSize(16);
        addBtn.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams abp = (LinearLayout.LayoutParams) addBtn.getLayoutParams();
        abp.width = dp(52);
        abp.setMargins(0, 0, dp(6), 0);
        addBtn.setLayoutParams(abp);
        addBtn.setOnClickListener(v -> addAccount());
        accountCard.addView(addBtn);
        Button delBtn = btn("－", false, 38);
        delBtn.setBackgroundResource(R.drawable.bg_btn_danger_ripple);
        delBtn.setTextColor(DANGER);
        delBtn.setTextSize(16);
        delBtn.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams dbp = (LinearLayout.LayoutParams) delBtn.getLayoutParams();
        dbp.width = dp(52);
        delBtn.setLayoutParams(dbp);
        delBtn.setOnClickListener(v -> removeAccount());
        accountCard.addView(delBtn);
        content.addView(margin(accountCard, 0, dp(12), 0, 0));

        // 操作按钮(两行,每行两个等宽按钮)
        LinearLayout rowA = new LinearLayout(this);
        rowA.setOrientation(LinearLayout.HORIZONTAL);
        Button shot = btn("📷 截图识别", true, 46);
        shot.setOnClickListener(v -> pickImage());
        Button manual = btn("✍ 手动添加", false, 46);
        manual.setOnClickListener(v -> manualAdd());
        rowA.addView(shot, newLinearLayoutParamsWeight());
        rowA.addView(manual, newLinearLayoutParamsWeight());
        content.addView(margin(rowA, 0, dp(10), 0, 0));

        // 导入村庄 JSON(游戏内「设置 → 更多设置 → 导出村庄数据」)
        LinearLayout rowC = new LinearLayout(this);
        rowC.setOrientation(LinearLayout.HORIZONTAL);
        Button impJson = btn("📋 导入村庄JSON", false, 46);
        impJson.setOnClickListener(v -> importJsonDialog());
        rowC.addView(impJson, newLinearLayoutParamsWeight());
        content.addView(margin(rowC, 0, dp(6), 0, 0));

        LinearLayout rowB = new LinearLayout(this);
        rowB.setOrientation(LinearLayout.HORIZONTAL);
        Button potion = btn("⚡ 加速药水", false, 46);
        potion.setOnClickListener(v -> {
            if (ALL.equals(currentAccount)) {
                toast("请先在上方选择一个具体账号");
                return;
            }
            if (Store.account(currentAccount) == null) {
                toast("请先添加账号");
                return;
            }
            Intent it = new Intent(this, PotionActivity.class);
            it.putExtra("account", currentAccount);
            startActivity(it);
        });
        Button clear = btn("清除已完成", false, 46);
        clear.setBackgroundResource(R.drawable.bg_btn_danger_ripple);
        clear.setTextColor(DANGER);
        clear.setOnClickListener(v -> clearDone());
        rowB.addView(potion, newLinearLayoutParamsWeight());
        rowB.addView(clear, newLinearLayoutParamsWeight());
        content.addView(margin(rowB, 0, dp(6), 0, 0));

        // 多选操作栏(默认隐藏)
        selBar = new LinearLayout(this);
        selBar.setOrientation(LinearLayout.HORIZONTAL);
        selBar.setGravity(Gravity.CENTER_VERTICAL);
        selBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        selBar.setVisibility(View.GONE);
        selCount = text("已选 0", 13, TEXT_MAIN, true);
        LinearLayout.LayoutParams sclp0 = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        selBar.addView(selCount, sclp0);
        Button selAll = btn("全选", false, 36);
        selAll.setOnClickListener(v -> { timerAdapter.selectAll(); updateSelBar(); });
        LinearLayout.LayoutParams salp = new LinearLayout.LayoutParams(dp(70), dp(36));
        salp.setMargins(dp(4), 0, dp(4), 0);
        selBar.addView(selAll, salp);
        Button selDel = btn("删除", true, 36);
        selDel.setBackgroundResource(R.drawable.bg_btn_danger_ripple);
        selDel.setTextColor(DANGER);
        selDel.setOnClickListener(v -> deleteSelected());
        LinearLayout.LayoutParams sdlp = new LinearLayout.LayoutParams(dp(70), dp(36));
        sdlp.setMargins(dp(4), 0, dp(4), 0);
        selBar.addView(selDel, sdlp);
        Button selCancel = btn("取消", false, 36);
        selCancel.setOnClickListener(v -> exitSelMode());
        LinearLayout.LayoutParams sclp = new LinearLayout.LayoutParams(dp(70), dp(36));
        selBar.addView(selCancel, sclp);
        content.addView(margin(selBar, 0, dp(8), 0, 0));

        // 列表 + 空状态
        FrameLayout fl = new FrameLayout(this);
        timerAdapter = new TimerAdapter();
        listView = new ListView(this);
        listView.setDivider(null);
        listView.setDividerHeight(0);
        listView.setAdapter(timerAdapter);
        listView.setCacheColorHint(Color.TRANSPARENT);
        listView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        listView.setOnItemClickListener((p, v, pos, id2) -> {
            if (timerAdapter.selMode) {
                timerAdapter.toggle(timerAdapter.items.get(pos).optString("id"));
                updateSelBar();
                if (timerAdapter.selected.isEmpty()) exitSelMode();
            } else {
                editTimerDialog(timerAdapter.items.get(pos));
            }
        });
        listView.setOnItemLongClickListener((p, v, pos, id2) -> {
            if (!timerAdapter.selMode) {
                timerAdapter.enterSelMode(timerAdapter.items.get(pos).optString("id"));
                selBar.setVisibility(View.VISIBLE);
                updateSelBar();
            } else {
                timerAdapter.toggle(timerAdapter.items.get(pos).optString("id"));
                updateSelBar();
            }
            return true;
        });
        fl.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        emptyView = text("还没有倒计时\n\n点上方「📷 截图识别」或「✍ 手动添加」", 14, TEXT_SUB);
        emptyView.setGravity(Gravity.CENTER);
        fl.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.addView(margin(fl, 0, dp(10), 0, 0), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部状态
        statusView = text("", 12, TEXT_SUB);
        statusView.setPadding(dp(6), dp(8), dp(6), dp(2));
        root.addView(statusView);

        setContentView(root);
    }

    private LinearLayout.LayoutParams newLinearLayoutParamsWeight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private <V extends View> V margin(V v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, r, b);
        v.setLayoutParams(lp);
        return v;
    }

    // ---------------------------------------------------------------- 后台监控服务

    private boolean hasPendingTimers() {
        long now = System.currentTimeMillis();
        JSONArray as = Store.accounts();
        for (int a = 0; a < as.length(); a++) {
            JSONObject acc = as.optJSONObject(a);
            if (acc == null) continue;
            JSONArray ts = acc.optJSONArray("timers");
            if (ts == null) continue;
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null && t.optLong("end_ts") > now && !t.optBoolean("notified"))
                    return true;
            }
        }
        return false;
    }

    /** 有未完成倒计时时启动前台服务,保证 App 退出后到点仍准时通知 */
    private void startMonitor() {
        if (!hasPendingTimers()) return;
        Intent i = new Intent(this, MonitorService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
        } catch (Exception ignored) {
            // 个别 ROM 限制后台启动服务时,仍有系统闹钟兜底
        }
    }

    private void requestPerms() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_PERM);
        }
        SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 31 && !sp.getBoolean("asked_exact", false)) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) {
                }
                sp.edit().putBoolean("asked_exact", true).apply();
            }
        }
    }

    // ---------------------------------------------------------------- 账号

    private void refreshAccounts() {
        accountAdapter.clear();
        accountAdapter.add(ALL);
        int sel = 0;
        JSONArray as = Store.accounts();
        for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a == null) continue;
            String n = a.optString("name");
            if (ALL.equals(n)) continue; // 账号名不能占用总览项
            accountAdapter.add(n);
            if (n.equals(currentAccount)) sel = accountAdapter.getCount() - 1;
        }
        if (currentAccount == null) currentAccount = ALL;
        if (ALL.equals(currentAccount)) sel = 0;
        accountSpinner.setSelection(sel);
        refreshList();
    }

    private void addAccount() {
        final EditText et = new EditText(this);
        et.setHint("账号名称");
        et.setTextSize(14);
        et.setBackgroundResource(R.drawable.bg_input);
        int p = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, dp(10), p, 0);
        box.addView(et);
        new AlertDialog.Builder(this).setTitle("添加账号").setView(box)
                .setPositiveButton("添加", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("名称不能为空");
                        return;
                    }
                    if (Store.account(name) != null) {
                        toast("账号「" + name + "」已存在");
                        return;
                    }
                    JSONObject a = Store.put(new JSONObject(), "name", name);
                    Store.put(a, "timers", new JSONArray());
                    Store.accounts().put(a);
                    Store.saveData();
                    currentAccount = name;
                    refreshAccounts();
                    syncSoon();
                })
                .setNegativeButton("取消", null).show();
    }

    private void removeAccount() {
        if (ALL.equals(currentAccount)) {
            toast("请先选择要删除的账号");
            return;
        }
        final JSONObject acc = Store.account(currentAccount);
        if (acc == null) {
            toast("还没有账号");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除账号")
                .setMessage("确定删除账号「" + acc.optString("name") + "」及其全部倒计时吗?")
                .setPositiveButton("删除", (d, w) -> {
                    JSONArray as = Store.accounts();
                    JSONArray keep = new JSONArray();
                    for (int i = 0; i < as.length(); i++) {
                        JSONObject a = as.optJSONObject(i);
                        if (a == null) continue;
                        if (a.optString("name").equals(acc.optString("name"))) {
                            JSONArray ts = a.optJSONArray("timers");
                            for (int j = 0; j < (ts == null ? 0 : ts.length()); j++) {
                                JSONObject t = ts.optJSONObject(j);
                                if (t != null) tombstone(t.optString("id"));
                            }
                            continue;
                        }
                        keep.put(a);
                    }
                    Store.put(Store.data, "accounts", keep);
                    Store.saveData();
                    currentAccount = null;
                    refreshAccounts();
                    syncSoon();
                })
                .setNegativeButton("取消", null).show();
    }

    // ---------------------------------------------------------------- 倒计时

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            checkExpired();
            refreshList();
            handler.postDelayed(this, 500);
        }
    };

    private void checkExpired() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        JSONArray as = Store.accounts();
        for (int a = 0; a < as.length(); a++) {
            JSONObject acc = as.optJSONObject(a);
            if (acc == null) continue;
            JSONArray ts = acc.optJSONArray("timers");
            if (ts == null) continue;
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                if (t.optLong("end_ts") <= now && !t.optBoolean("notified")) {
                    Store.put(t, "notified", true);
                    Store.put(t, "updated_at", now);
                    Notify.post(this, acc.optString("name"), t.optString("item"));
                    Alarms.cancel(this, t.optString("id"));
                    changed = true;
                }
            }
        }
        if (changed) Store.saveData();
    }

    private void refreshList() {
        List<JSONObject> list = new ArrayList<>();
        if (ALL.equals(currentAccount)) {
            JSONArray as = Store.accounts();
            for (int a = 0; a < as.length(); a++) {
                JSONObject acc = as.optJSONObject(a);
                if (acc == null) continue;
                JSONArray ts = acc.optJSONArray("timers");
                if (ts == null) continue;
                for (int i = 0; i < ts.length(); i++) {
                    JSONObject t = ts.optJSONObject(i);
                    if (t != null) list.add(t);
                }
            }
        } else {
            JSONObject acc = Store.account(currentAccount);
            if (acc != null) {
                JSONArray ts = acc.optJSONArray("timers");
                if (ts != null) {
                    for (int i = 0; i < ts.length(); i++) {
                        JSONObject t = ts.optJSONObject(i);
                        if (t != null) list.add(t);
                    }
                }
            }
        }
        Collections.sort(list, (a, b) -> Long.compare(a.optLong("end_ts"), b.optLong("end_ts")));
        timerAdapter.setData(list);
        emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        long now = System.currentTimeMillis();
        int done = 0;
        for (JSONObject t : list) if (t.optLong("end_ts") <= now) done++;
        statusView.setText("账号:" + (currentAccount == null ? "无" : currentAccount)
                + " · 倒计时 " + (list.size() - done) + " 个 · 已完成 " + done + " 个");
    }

    /** 批量添加倒计时;replace=true 时先清空本账号原有倒计时(用于"覆盖导入") */
    private void addTimersBatch(String accountName, List<String[]> items, boolean replace) {
        JSONObject acc = Store.account(accountName);
        if (acc == null) return;
        long now = System.currentTimeMillis();
        JSONArray ts = acc.optJSONArray("timers");
        if (replace) {
            if (ts != null) {
                for (int i = 0; i < ts.length(); i++) {
                    JSONObject t = ts.optJSONObject(i);
                    if (t != null) {
                        tombstone(t.optString("id"));
                        Alarms.cancel(this, t.optString("id"));
                    }
                }
            }
            ts = new JSONArray();
            Store.put(acc, "timers", ts);
        } else if (ts == null) {
            ts = new JSONArray();
            Store.put(acc, "timers", ts);
        }
        for (String[] it : items) {
            String id = java.util.UUID.randomUUID().toString();
            JSONObject t = Store.put(new JSONObject(), "id", id);
            Store.put(t, "item", it[0]);
            Store.put(t, "end_ts", now + Long.parseLong(it[1]) * 1000L);
            Store.put(t, "notified", false);
            Store.put(t, "updated_at", now);
            ts.put(t);
            Alarms.schedule(this, id, t.optLong("end_ts"));
        }
        Store.saveData();
        startMonitor();
        refreshList();
        syncSoon();
    }

    private void addTimer(String accountName, String item, long durMs) {
        JSONObject acc = Store.account(accountName);
        if (acc == null) return;
        String id = java.util.UUID.randomUUID().toString();
        JSONObject t = Store.put(new JSONObject(), "id", id);
        Store.put(t, "item", item);
        Store.put(t, "end_ts", System.currentTimeMillis() + durMs);
        Store.put(t, "notified", false);
        Store.put(t, "updated_at", System.currentTimeMillis());
        JSONArray ts = acc.optJSONArray("timers");
        if (ts == null) {
            ts = new JSONArray();
            Store.put(acc, "timers", ts);
        }
        ts.put(t);
        Store.saveData();
        Alarms.schedule(this, id, t.optLong("end_ts"));
        startMonitor();
        refreshList();
        syncSoon();
    }

    /** 按 id 删除倒计时,自动定位所属账号(总览视图下同样可用) */
    private void deleteTimerIds(java.util.Set<String> ids) {
        boolean changed = false;
        for (String id : ids) {
            JSONObject acc = Store.accountOfTimer(id);
            if (acc == null) continue;
            JSONArray ts = acc.optJSONArray("timers");
            if (ts == null) continue;
            JSONArray keep = new JSONArray();
            for (int i = 0; i < ts.length(); i++) {
                JSONObject x = ts.optJSONObject(i);
                if (x != null && !id.equals(x.optString("id"))) keep.put(x);
            }
            Store.put(acc, "timers", keep);
            Alarms.cancel(this, id);
            tombstone(id);
            changed = true;
        }
        if (changed) {
            Store.saveData();
            if (timerAdapter != null && timerAdapter.selMode) exitSelMode();
            refreshList();
            syncSoon();
        }
    }

    /** 单击列表项:编辑名称与剩余时间(从现在起重新计时) */
    private void editTimerDialog(final JSONObject t) {
        if (t == null) return;
        final String id = t.optString("id");
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        box.setPadding(p, dp(12), p, dp(4));
        sc.addView(box);
        box.addView(text("项目名称", 13, TEXT_SUB));
        final EditText name = new EditText(this);
        name.setTextSize(14);
        name.setBackgroundResource(R.drawable.bg_input);
        name.setPadding(dp(10), dp(10), dp(10), dp(10));
        name.setText(t.optString("item"));
        box.addView(margin(name, 0, dp(4), 0, dp(12)));
        box.addView(text("剩余时间(全 0 表示标记为已完成)", 13, TEXT_SUB));
        long remain = Math.max(t.optLong("end_ts") - System.currentTimeMillis(), 0) / 1000;
        long d = remain / 86400;
        remain %= 86400;
        long h = remain / 3600;
        remain %= 3600;
        long m = remain / 60;
        long s = remain % 60;
        final EditText de = numEdit(), he = numEdit(), me = numEdit(), se = numEdit();
        if (d > 0) de.setText(String.valueOf(d));
        if (h > 0) he.setText(String.valueOf(h));
        if (m > 0) me.setText(String.valueOf(m));
        if (s > 0) se.setText(String.valueOf(s));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(de, cellDp(52));
        row.addView(lblSmall("天"));
        row.addView(he, cellDp(52));
        row.addView(lblSmall("时"));
        row.addView(me, cellDp(52));
        row.addView(lblSmall("分"));
        row.addView(se, cellDp(52));
        row.addView(lblSmall("秒"));
        box.addView(margin(row, 0, dp(6), 0, 0));
        new AlertDialog.Builder(this)
                .setTitle("编辑倒计时")
                .setView(sc)
                .setPositiveButton("保存", (dlg, w) -> {
                    JSONObject tt = Store.timerById(id);
                    if (tt == null) return;
                    String nm = name.getText().toString().trim();
                    if (nm.isEmpty()) nm = "升级项目";
                    long secs = parse(de.getText().toString()) * 86400L
                            + parse(he.getText().toString()) * 3600L
                            + parse(me.getText().toString()) * 60L
                            + parse(se.getText().toString());
                    long now = System.currentTimeMillis();
                    Store.put(tt, "item", nm);
                    Store.put(tt, "end_ts", now + secs * 1000L);
                    Store.put(tt, "notified", secs <= 0);
                    Store.put(tt, "updated_at", now);
                    Store.saveData();
                    Alarms.cancel(this, id);
                    if (secs > 0) Alarms.schedule(this, id, tt.optLong("end_ts"));
                    refreshList();
                    startMonitor();
                    syncSoon();
                    toast("已保存");
                })
                .setNeutralButton("删除", (dlg, w) -> {
                    deleteTimerIds(Collections.singleton(id));
                    toast("已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearDone() {
        long now = System.currentTimeMillis();
        List<JSONObject> accs = new ArrayList<>();
        if (ALL.equals(currentAccount)) {
            JSONArray as = Store.accounts();
            for (int i = 0; i < as.length(); i++) {
                JSONObject a = as.optJSONObject(i);
                if (a != null) accs.add(a);
            }
        } else {
            JSONObject a = Store.account(currentAccount);
            if (a != null) accs.add(a);
        }
        int cleared = 0;
        for (JSONObject acc : accs) {
            JSONArray ts = acc.optJSONArray("timers");
            if (ts == null) continue;
            JSONArray keep = new JSONArray();
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                if (t.optLong("end_ts") > now) keep.put(t);
                else {
                    Alarms.cancel(this, t.optString("id"));
                    tombstone(t.optString("id"));
                    cleared++;
                }
            }
            Store.put(acc, "timers", keep);
        }
        if (cleared == 0) {
            toast("没有已完成的倒计时");
            return;
        }
        Store.saveData();
        refreshList();
        syncSoon();
        toast("已清除 " + cleared + " 个");
    }

    // ---------------------------------------------------------------- 多选删除

    private void updateSelBar() {
        selCount.setText("已选 " + timerAdapter.selected.size());
    }

    private void exitSelMode() {
        timerAdapter.exitSelMode();
        selBar.setVisibility(View.GONE);
    }

    private void deleteSelected() {
        if (timerAdapter.selected.isEmpty()) {
            toast("未选中任何项");
            return;
        }
        final java.util.Set<String> ids = new java.util.HashSet<>(timerAdapter.selected);
        new AlertDialog.Builder(this).setTitle("删除倒计时")
                .setMessage("删除选中的 " + ids.size() + " 个倒计时?")
                .setPositiveButton("删除", (d, w) -> {
                    deleteTimerIds(ids);
                    toast("已删除 " + ids.size() + " 个");
                })
                .setNegativeButton("取消", null).show();
    }

    @Override
    public void onBackPressed() {
        if (timerAdapter != null && timerAdapter.selMode) {
            exitSelMode();
        } else {
            super.onBackPressed();
        }
    }

    // ---------------------------------------------------------------- 添加入口

    private String[] pickModel() {
        String sel = Store.config.optString("default_model");
        int slash = sel.indexOf('/');
        if (slash <= 0) return null;
        String pname = sel.substring(0, slash), model = sel.substring(slash + 1);
        JSONObject p = Store.providerByName(pname);
        if (p == null || TextUtils.isEmpty(p.optString("api_key"))) return null;
        return new String[]{pname, model};
    }

    private void pickImage() {
        if (ALL.equals(currentAccount)) {
            toast("请先在上方选择一个具体账号");
            return;
        }
        if (Store.account(currentAccount) == null) {
            toast("请先点「＋」添加一个账号");
            return;
        }
        if (pickModel() == null) {
            new AlertDialog.Builder(this).setTitle("未配置模型")
                    .setMessage("请先在「模型设置」中添加服务商、填入 API Key,\n"
                            + "并选择一个支持图片识别的默认模型\n(如 glm-4.5v / qwen-vl-max / gpt-4o)")
                    .setPositiveButton("去设置", (d, w) ->
                            startActivity(new Intent(this, SettingsActivity.class)))
                    .setNegativeButton("取消", null).show();
            return;
        }
        Intent it;
        if (Build.VERSION.SDK_INT >= 33) {
            it = new Intent(MediaStore.ACTION_PICK_IMAGES);
            it.setType("image/*");
        } else {
            it = new Intent(Intent.ACTION_GET_CONTENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("image/*");
        }
        try {
            startActivityForResult(Intent.createChooser(it, "选择升级剩余时间截图"), REQ_PICK);
        } catch (Exception e) {
            toast("无法打开图片选择器:" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        final String[] pm = pickModel();
        if (pm == null) {
            toast("模型配置已失效,请重新设置");
            return;
        }
        byte[] img;
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            img = bos.toByteArray();
        } catch (Exception e) {
            toast("读取图片失败:" + e.getMessage());
            return;
        }
        String mime = getContentResolver().getType(uri);
        if (mime == null || !mime.startsWith("image/")) mime = "image/png";
        final String ext = mime.substring(6);
        final byte[] fimg = img;
        statusView.setText("正在调用 AI 识别(" + pm[0] + " / " + pm[1] + ")…");
        final String fModel = pm[1];
        final String fProvider = pm[0];
        new Thread(() -> {
            String err = null, raw = null;
            JSONArray items = null;
            try {
                JSONObject provider = Store.providerByName(fProvider);
                raw = ApiClient.vision(provider, fModel, fimg, ext);
                items = ApiClient.extractItems(raw);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final JSONArray fItems = items;
            final String fErr = err, fRaw = raw;
            runOnUiThread(() -> {
                statusView.setText("");
                if (fErr != null) {
                    errDialog("AI 识别失败", fErr);
                    return;
                }
                if (fItems == null || fItems.length() == 0) {
                    new AlertDialog.Builder(this).setTitle("未识别到升级项目")
                            .setMessage("建议:\n· 确认截图里能看到剩余时间(如 1d 2h 或 3天12小时)\n"
                                    + "· 检查所选模型是否支持图片识别\n\n模型返回:\n"
                                    + (fRaw == null ? "" : (fRaw.length() > 400 ? fRaw.substring(0, 400) : fRaw)))
                            .setPositiveButton("确定", null).show();
                } else {
                    editDialog("确认识别结果 — " + currentAccount, fItems);
                }
            });
        }).start();
    }

    private void manualAdd() {
        if (ALL.equals(currentAccount)) {
            toast("请先在上方选择一个具体账号");
            return;
        }
        if (Store.account(currentAccount) == null) {
            toast("请先点「＋」添加一个账号");
            return;
        }
        JSONArray preset = new JSONArray();
        JSONObject one = new JSONObject();
        Store.put(one, "item", "");
        Store.put(one, "seconds", 0L);
        preset.put(one);
        editDialog("手动添加倒计时 — " + currentAccount, preset);
    }

    /** 导入游戏内「导出村庄数据」JSON:解析带 timer 的项目生成倒计时 */
    private void importJsonDialog() {
        if (ALL.equals(currentAccount)) {
            toast("请先在上方选择一个具体账号");
            return;
        }
        if (Store.account(currentAccount) == null) {
            toast("请先点「＋」添加一个账号");
            return;
        }
        ScrollView sc = new ScrollView(this);
        EditText et = new EditText(this);
        et.setTextSize(12);
        et.setGravity(Gravity.TOP);
        et.setTypeface(Typeface.MONOSPACE);
        et.setBackgroundResource(R.drawable.bg_input);
        et.setPadding(dp(10), dp(10), dp(10), dp(10));
        int p = dp(14);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(p, dp(8), p, 0);
        box.addView(text("把游戏内「设置 → 更多设置 → 导出村庄数据」复制的内容粘贴到这里,"
                + "自动识别正在升级的项目:", 12, TEXT_SUB));
        LinearLayout.LayoutParams etlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        etlp.setMargins(0, dp(8), 0, 0);
        box.addView(et, etlp);
        et.setText(clipboardVillageJson());
        sc.addView(box);
        new AlertDialog.Builder(this)
                .setTitle("导入村庄 JSON — " + currentAccount)
                .setView(sc)
                .setPositiveButton("解析并添加", (d, w) -> {
                    JSONArray preset;
                    try {
                        preset = parseVillageJson(et.getText().toString());
                    } catch (Exception e) {
                        errDialog("解析失败", "内容不是有效的村庄数据 JSON:\n" + e.getMessage());
                        return;
                    }
                    if (preset.length() == 0) {
                        toast("没有发现正在升级的项目(数据里没有 timer)");
                        return;
                    }
                    editDialog("导入村庄 JSON — " + currentAccount, preset);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 剪贴板里若已有村庄导出数据(含 buildings 字段)则直接带出 */
    private String clipboardVillageJson() {
        try {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            CharSequence cs = cm.getPrimaryClip().getItemAt(0).getText();
            String s = cs == null ? "" : cs.toString();
            return s.contains("\"buildings\"") ? s : "";
        } catch (Exception e) {
            return "";
        }
    }

    /** 解析导出 JSON:收集各区块里带 timer 的条目,名字带当前→目标等级 */
    private JSONArray parseVillageJson(String text) throws Exception {
        int a = text.indexOf('{'), b = text.lastIndexOf('}');
        if (a < 0 || b <= a) throw new Exception("未找到 JSON 内容");
        JSONObject root = new JSONObject(text.substring(a, b + 1));
        JSONArray preset = new JSONArray();
        String[][] sections = {
                {"buildings", "建筑"}, {"heroes", "英雄"}, {"units", "兵种"},
                {"spells", "法术"}, {"siege_machines", "攻城机械"}, {"traps", "陷阱"},
                {"pets", "宠物"}
        };
        for (String[] sec : sections) {
            JSONArray arr = root.optJSONArray(sec[0]);
            if (arr == null) continue;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                long timer = o.optLong("timer");
                if (timer <= 0) continue;
                int id = o.optInt("data"), lvl = o.optInt("lvl");
                JSONObject one = new JSONObject();
                Store.put(one, "item", villageName(sec[1], id) + " " + lvl + "→" + (lvl + 1) + "级");
                Store.put(one, "seconds", timer);
                preset.put(one);
            }
        }
        return preset;
    }

    /** dataId → 中文名(参考社区整理的官方数据 ID 表);未知 ID 显示为类别+编号,可在确认前改名 */
    private static final String VILLAGE_NAMES =
            "1000000:兵营,1000001:大本营,1000002:圣水收集器,1000003:圣水罐,"
            + "1000004:金矿,1000005:金罐,1000006:训练营,1000007:实验室,"
            + "1000008:加农炮,1000009:箭塔,1000010:城墙,1000011:法师塔,"
            + "1000012:防空火箭,1000013:迫击炮,1000014:部落城堡,1000015:建筑工人小屋,"
            + "1000019:电磁塔,1000020:法术工厂,1000021:X连弩,1000023:暗黑重油钻井,"
            + "1000024:暗黑重油罐,1000026:暗黑训练营,1000027:地狱塔,1000028:空气炮,"
            + "1000029:暗黑法术工厂,1000031:天鹰火炮,1000032:炸弹塔,1000034:建筑大师大本营,"
            + "1000046:星光实验室,1000059:工坊,1000067:投石炮,1000068:宠物小屋,"
            + "1000070:铁匠铺,1000071:英雄大厅,"
            + "28000000:蛮王,28000001:弓箭女皇,28000002:大守护者,28000003:战争机器,"
            + "28000004:飞盾战神,28000005:战斗直升机,28000006:亡灵王子,"
            + "4000000:野蛮人,4000001:弓箭手,4000002:哥布林,4000003:巨人,"
            + "4000004:炸弹人,4000005:气球兵,4000006:法师,4000007:天使,"
            + "4000008:飞龙,4000009:皮卡超人,4000010:亡灵,4000011:野猪骑士,"
            + "4000012:瓦基丽武神,4000013:戈仑石人,4000015:女巫,4000017:熔岩猎犬,"
            + "4000022:巨石投手,4000023:飞龙宝宝,4000024:掘地矿工,"
            + "4000051:攻城战车,4000052:战斗飞艇,4000053:雪怪,4000058:冰戈仑,"
            + "4000059:电龙,4000062:石砸攻城兽,4000065:骑龙者,4000075:攻城兵营,"
            + "4000082:猎头者,4000087:原木战车,4000091:烈焰投石车,4000092:战斗钻机,"
            + "4000095:雷电泰坦,4000097:见习守护者,4000110:树根骑士,4000123:德鲁伊,"
            + "4000132:投掷手,4000135:兵种发射车,4000150:熔炉,"
            + "26000000:闪电法术,26000001:治疗法术,26000002:狂暴法术,26000003:弹跳法术,"
            + "26000005:冰冻法术,26000009:毒药法术,26000010:地震法术,26000011:急速法术,"
            + "26000016:镜像法术,26000017:骷髅法术,26000028:蝙蝠法术,26000035:隐形法术,"
            + "26000053:召回法术,26000070:丛生法术,26000098:复活法术,"
            + "73000000:莱西,73000001:电鸮,73000002:牦牛,73000003:独角兽,"
            + "73000011:愤怒果冻,73000016:喷嚏怪";

    private static java.util.HashMap<String, String> VILLAGE_MAP;

    private static String villageName(String cat, int id) {
        if (VILLAGE_MAP == null) {
            VILLAGE_MAP = new java.util.HashMap<>();
            for (String pair : VILLAGE_NAMES.split(",")) {
                int c = pair.indexOf(':');
                if (c > 0) VILLAGE_MAP.put(pair.substring(0, c).trim(), pair.substring(c + 1).trim());
            }
        }
        String n = VILLAGE_MAP.get(String.valueOf(id));
        return n == null ? cat + " " + id : n;
    }

    /** 编辑确认对话框:preset 中每项生成一行(名称 + 天/时/分/秒 + 删除),可选加速倍率与持续时长(换算逻辑与加速药水一致),确认后写入倒计时 */
    private void editDialog(String title, JSONArray preset) {
        ScrollView sc = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(18);
        box.setPadding(p, dp(8), p, 0);
        sc.addView(box);
        box.addView(text("请核对名称与剩余时间(可直接修改):", 13, TEXT_SUB));
        final android.widget.CheckBox replaceCb = new android.widget.CheckBox(this);
        replaceCb.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
        replaceCb.setText("覆盖本账号当前所有倒计时(用识别结果替换原有记录)");
        replaceCb.setTextSize(13);
        replaceCb.setTextColor(TEXT_MAIN);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(6), 0, dp(2));
        box.addView(replaceCb, rlp);
        // ---- ⚡ 加速(可选):倍率与持续时长,换算逻辑与「加速药水」一致 ----
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(8), 0, dp(2));
        box.addView(text("⚡ 加速(可选):", 13, TEXT_MAIN, true), hlp);
        box.addView(text("选中倍率后,本批所有行按加速药水同款规则折算剩余时间", 11, TEXT_SUB));

        final int[] multIdx = {0}; // 0=不加速 1=2× 2=10× 3=24× 4=自定义
        final Segmented multSeg = new Segmented(this,
                new String[]{"不加速", "2×", "10×", "24×", "自定义"}, 0, null);
        LinearLayout.LayoutParams mslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mslp.setMargins(0, dp(6), 0, 0);
        box.addView(multSeg, mslp);

        // 自定义倍率输入行(选中「自定义倍率」后显示),样式与药水页一致
        final LinearLayout customMultRow = new LinearLayout(this);
        customMultRow.setGravity(Gravity.CENTER_VERTICAL);
        customMultRow.setClipChildren(false);
        customMultRow.setVisibility(View.GONE);
        final EditText customMultEt = new EditText(this);
        customMultEt.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        customMultEt.setTextSize(14);
        customMultEt.setGravity(Gravity.CENTER);
        customMultEt.setBackgroundResource(R.drawable.bg_input);
        customMultEt.setPadding(dp(8), dp(2), dp(8), dp(2));
        LinearLayout.LayoutParams cmlp = new LinearLayout.LayoutParams(dp(48), dp(32));
        cmlp.setMargins(dp(24), 0, dp(6), 0);
        customMultRow.addView(customMultEt, cmlp);
        customMultRow.addView(text("倍(支持小数,如 1.5)", 12, TEXT_SUB));
        LinearLayout.LayoutParams cmlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cmlp2.setMargins(0, dp(2), 0, 0);
        box.addView(customMultRow, cmlp2);

        // 持续时长:默认 1 小时,可选 1 天(24 小时)或自定义
        final LinearLayout durCard = new LinearLayout(this);
        durCard.setOrientation(LinearLayout.VERTICAL);
        durCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams dclp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dclp.setMargins(dp(24), dp(2), 0, 0);
        box.addView(durCard, dclp);
        LinearLayout durHead = new LinearLayout(this);
        durHead.setGravity(Gravity.CENTER_VERTICAL);
        durHead.addView(text("持续:", 13, TEXT_MAIN));
        final int[] durIdx = {0}; // 0=1小时 1=1天 2=自定义
        final Segmented durSeg = new Segmented(this, new String[]{"1小时", "1天", "自定义"}, 0, null);
        durHead.addView(durSeg, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        durCard.addView(durHead);
        final LinearLayout durCustomRow = new LinearLayout(this);
        durCustomRow.setGravity(Gravity.CENTER_VERTICAL);
        durCustomRow.setClipChildren(false);
        durCustomRow.setVisibility(View.GONE);
        final EditText durHEt = numEdit(), durMEt = numEdit();
        durHEt.setHint("0");
        durMEt.setHint("30");
        durHEt.setPadding(dp(6), dp(2), dp(6), dp(2));
        durMEt.setPadding(dp(6), dp(2), dp(6), dp(2));
        LinearLayout.LayoutParams dhlp = new LinearLayout.LayoutParams(dp(42), dp(32));
        dhlp.setMargins(0, 0, 0, 0);
        durCustomRow.addView(durHEt, dhlp);
        durCustomRow.addView(lblSmall("时"));
        LinearLayout.LayoutParams dmlp = new LinearLayout.LayoutParams(dp(42), dp(32));
        dmlp.setMargins(dp(8), 0, 0, 0);
        durCustomRow.addView(durMEt, dmlp);
        durCustomRow.addView(lblSmall("分"));
        durCustomRow.addView(text("(如 0时30分 = 30分钟)", 11, TEXT_SUB));
        LinearLayout.LayoutParams drlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        drlp.setMargins(0, dp(2), 0, 0);
        durCard.addView(durCustomRow, drlp);
        final TextView effectTv = text("", 12, TEXT_SUB);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(dp(24), dp(4), 0, 0);
        box.addView(effectTv, elp);

        final Runnable updateBoostUi = () -> {
            boolean boosted = multIdx[0] != 0;
            durCard.setVisibility(boosted ? View.VISIBLE : View.GONE);
            customMultRow.setVisibility(multIdx[0] == 4 ? View.VISIBLE : View.GONE);
            durCustomRow.setVisibility(durIdx[0] == 2 ? View.VISIBLE : View.GONE);
            if (!boosted) { effectTv.setText(""); return; }
            double m = 0;
            if (multIdx[0] == 1) m = 2;
            else if (multIdx[0] == 2) m = 10;
            else if (multIdx[0] == 3) m = 24;
            else if (multIdx[0] == 4) m = parseD(customMultEt.getText().toString());
            long ds = 3600;
            if (durIdx[0] == 1) ds = 86400;
            else if (durIdx[0] == 2)
                ds = parse(durHEt.getText().toString()) * 3600
                        + parse(durMEt.getText().toString()) * 60;
            if (m <= 0) {
                effectTv.setText("请输入有效的自定义倍率");
            } else if (ds <= 0) {
                effectTv.setText("请输入有效的加速时长");
            } else {
                effectTv.setText("效果:未来 " + fmtBoostDur(ds) + " 内以 " + fmtMult(m)
                        + " 倍推进,等价于约 "
                        + fmtBoostDur(Math.round(m * ds)) + " 游戏工作量");
            }
        };
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { updateBoostUi.run(); }
        };
        customMultEt.addTextChangedListener(tw);
        durHEt.addTextChangedListener(tw);
        durMEt.addTextChangedListener(tw);
        multSeg.setOnSelect(idx -> {
            multIdx[0] = idx;
            updateBoostUi.run();
        });
        durSeg.setOnSelect(idx -> {
            durIdx[0] = idx;
            updateBoostUi.run();
        });
        updateBoostUi.run();

        final List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < preset.length(); i++) {
            JSONObject it = preset.optJSONObject(i);
            if (it != null) makeRow(box, rows, it.optString("item"), it.optLong("seconds") * 1000);
        }
        if (rows.isEmpty()) makeRow(box, rows, "", 0);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.setMargins(0, dp(8), 0, dp(8));
        final Button addRowBtn = btn("＋ 再加一行", false, 38);
        addRowBtn.setOnClickListener(v -> {
            makeRow(box, rows, "", 0);
            // 新增行插到按钮上方后,把按钮移回最底部
            box.removeView(addRowBtn);
            box.addView(addRowBtn, alp);
        });
        box.addView(addRowBtn, alp);

        final LinearLayout fBox = box;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(sc)
                .setPositiveButton("确认添加", (d, w) -> {
                    // ⚡ 加速倍率与持续时长(bm=0 表示不加速)
                    double bm = 0;
                    if (multIdx[0] == 1) bm = 2;
                    else if (multIdx[0] == 2) bm = 10;
                    else if (multIdx[0] == 3) bm = 24;
                    else if (multIdx[0] == 4) {
                        double cv = parseD(customMultEt.getText().toString());
                        if (cv <= 0) {
                            toast("请输入有效的自定义倍率");
                            return;
                        }
                        bm = cv;
                    }
                    long boostSec = 0;
                    if (bm > 0) {
                        if (durIdx[0] == 1) boostSec = 86400L;
                        else if (durIdx[0] == 2)
                            boostSec = parse(durHEt.getText().toString()) * 3600L
                                    + parse(durMEt.getText().toString()) * 60L;
                        else boostSec = 3600L; // 默认 1 小时
                        if (boostSec <= 0) {
                            toast("请输入有效的加速时长");
                            return;
                        }
                    }
                    List<Object[]> toAdd = new ArrayList<>();
                    for (Object[] r : rows)
                        if (r[0] instanceof View && ((View) r[0]).getParent() == fBox)
                            toAdd.add(r);
                    List<String[]> picked = new ArrayList<>();
                    for (Object[] r : toAdd) {
                        String nm = ((EditText) r[1]).getText().toString().trim();
                        if (nm.isEmpty()) nm = "升级项目";
                        long secs = parse(((EditText) r[2]).getText().toString()) * 86400L
                                + parse(((EditText) r[3]).getText().toString()) * 3600L
                                + parse(((EditText) r[4]).getText().toString()) * 60L
                                + parse(((EditText) r[5]).getText().toString());
                        if (secs > 0) {
                            if (bm > 0) {
                                // 与「加速药水」一致:加速期内以 M 倍推进
                                double progress = bm * boostSec;
                                secs = secs <= progress
                                        ? Math.max(Math.round(secs / bm), 1)
                                        : boostSec + Math.round(secs - progress);
                            }
                            picked.add(new String[]{nm, String.valueOf(secs)});
                        }
                    }
                    if (picked.isEmpty()) {
                        toast("没有可添加的项目(时间需大于 0)");
                        return;
                    }
                    addTimersBatch(currentAccount, picked, replaceCb.isChecked());
                    toast((replaceCb.isChecked() ? "已覆盖,共 " : "已添加 ")
                            + picked.size() + " 个倒计时");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private double parseD(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 倍率显示:整数不带小数点,小数保留 1 位(与加速药水页一致) */
    private String fmtMult(double m) {
        if (m == Math.floor(m) && m < 1e6) return String.valueOf((long) m);
        return String.format(Locale.CHINA, "%.1f", m);
    }

    private String fmtBoostDur(long sec) {
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        if (h > 0 && m > 0) return h + "小时" + m + "分钟";
        if (h > 0) return h + "小时";
        return m + "分钟";
    }

    private LinearLayout makeRow(LinearLayout box, List<Object[]> rows, String item, long ms) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        EditText name = new EditText(this);
        name.setTextSize(14);
        name.setHint("升级项目");
        name.setBackgroundResource(R.drawable.bg_input);
        name.setPadding(dp(10), dp(10), dp(10), dp(10));
        if (!item.isEmpty()) name.setText(item);
        long s = Math.max(ms, 0) / 1000;
        long d = s / 86400;
        s %= 86400;
        long h = s / 3600;
        s %= 3600;
        long m = s / 60;
        long sec = s % 60;
        EditText de = numEdit(), he = numEdit(), me = numEdit(), se = numEdit();
        if (d > 0) de.setText(String.valueOf(d));
        if (h > 0) he.setText(String.valueOf(h));
        if (m > 0) me.setText(String.valueOf(m));
        if (sec > 0) se.setText(String.valueOf(sec));
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(de, cellDp(44));
        row.addView(lblSmall("天"));
        row.addView(he, cellDp(44));
        row.addView(lblSmall("时"));
        row.addView(me, cellDp(44));
        row.addView(lblSmall("分"));
        row.addView(se, cellDp(44));
        row.addView(lblSmall("秒"));
        final LinearLayout fRow = row;
        Button del = btn("✕", false, 38);
        del.setBackgroundResource(R.drawable.bg_btn_danger_ripple);
        del.setTextColor(DANGER);
        del.setTextSize(12);
        del.setOnClickListener(v -> {
            box.removeView(fRow);
            rows.removeIf(r -> r[0] == fRow);
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(38), dp(38));
        dlp.setMargins(dp(6), 0, 0, 0);
        row.addView(del, dlp);
        box.addView(row);
        rows.add(new Object[]{row, name, de, he, me, se});
        return row;
    }

    private LinearLayout.LayoutParams cellDp(int w) {
        return new LinearLayout.LayoutParams(dp(w), dp(38));
    }

    private TextView lblSmall(String s) {
        return text(s, 13, TEXT_SUB);
    }

    private void errDialog(String title, String msg) {
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(msg == null ? "" : (msg.length() > 800 ? msg.substring(0, 800) : msg))
                .setPositiveButton("确定", null).show();
    }

    // ---------------------------------------------------------------- 格式化

    static String fmtRemaining(long ms) {
        if (ms <= 0) return "已完成";
        long s = ms / 1000;
        long d = s / 86400;
        s %= 86400;
        long h = s / 3600;
        s %= 3600;
        long m = s / 60;
        long sec = s % 60;
        if (d > 0) return String.format(Locale.CHINA, "%d天%02d:%02d:%02d", d, h, m, sec);
        return String.format(Locale.CHINA, "%02d:%02d:%02d", h, m, sec);
    }

    static String fmtEnd(long ms) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(ms));
    }

    // ---------------------------------------------------------------- 列表适配器

    private class TimerAdapter extends BaseAdapter {
        final List<JSONObject> items = new ArrayList<>();
        final java.util.Set<String> selected = new java.util.HashSet<>();
        boolean selMode = false;

        void setData(List<JSONObject> list) {
            items.clear();
            items.addAll(list);
            // 清掉已不存在的选中项
            java.util.Iterator<String> it = selected.iterator();
            while (it.hasNext()) {
                String id = it.next();
                boolean exists = false;
                for (JSONObject t : items)
                    if (id.equals(t.optString("id"))) { exists = true; break; }
                if (!exists) it.remove();
            }
            notifyDataSetChanged();
        }

        void enterSelMode(String firstId) {
            selMode = true;
            selected.clear();
            if (firstId != null) selected.add(firstId);
            notifyDataSetChanged();
        }

        void exitSelMode() {
            selMode = false;
            selected.clear();
            notifyDataSetChanged();
        }

        void toggle(String id) {
            if (selected.contains(id)) selected.remove(id);
            else selected.add(id);
            notifyDataSetChanged();
        }

        void selectAll() {
            for (JSONObject t : items) selected.add(t.optString("id"));
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout v;
            android.widget.CheckBox cb;
            TextView name, sub, chip;
            if (convertView instanceof LinearLayout) {
                v = (LinearLayout) convertView;
                cb = (android.widget.CheckBox) v.getChildAt(0);
                LinearLayout left = (LinearLayout) v.getChildAt(1);
                name = (TextView) left.getChildAt(0);
                sub = (TextView) left.getChildAt(1);
                chip = (TextView) v.getChildAt(2);
            } else {
                v = new LinearLayout(MainActivity.this);
                v.setOrientation(LinearLayout.HORIZONTAL);
                v.setGravity(Gravity.CENTER_VERTICAL);
                v.setBackgroundResource(R.drawable.bg_card_ripple);
                int pad = dp(14);
                v.setPadding(pad, dp(10), pad, dp(10));
                cb = new android.widget.CheckBox(MainActivity.this);
                cb.setButtonTintList(android.content.res.ColorStateList.valueOf(GREEN));
                cb.setClickable(false);
                cb.setFocusable(false);
                v.addView(cb, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                LinearLayout left = new LinearLayout(MainActivity.this);
                left.setOrientation(LinearLayout.VERTICAL);
                name = text("", 15, TEXT_MAIN, true);
                sub = text("", 12, TEXT_SUB);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.setMargins(0, dp(3), 0, 0);
                left.addView(name);
                left.addView(sub, slp);
                chip = text("", 12, DONE_GREEN, true);
                chip.setBackgroundResource(R.drawable.bg_chip);
                chip.setPadding(dp(10), dp(4), dp(10), dp(4));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(dp(10), 0, 0, 0);
                v.addView(left, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                v.addView(chip, clp);
            }
            JSONObject t = items.get(position);
            String id = t.optString("id");
            cb.setVisibility(selMode ? View.VISIBLE : View.GONE);
            cb.setChecked(selected.contains(id));
            long remain = t.optLong("end_ts") - System.currentTimeMillis();
            String label = t.optString("item");
            if (ALL.equals(currentAccount)) {
                JSONObject owner = Store.accountOfTimer(id);
                if (owner != null) label = owner.optString("name") + " · " + label;
            }
            name.setText(label);
            if (remain <= 0) {
                name.setTextColor(DONE_GREEN);
                sub.setText("完成于 " + fmtEnd(t.optLong("end_ts")));
                chip.setText("已完成");
                chip.setTextColor(DONE_GREEN);
            } else {
                name.setTextColor(TEXT_MAIN);
                sub.setText("剩余 " + fmtRemaining(remain) + " · 完成 " + fmtEnd(t.optLong("end_ts")));
                chip.setText("进行中");
                chip.setTextColor(GREEN);
            }
            return v;
        }
    }
}
