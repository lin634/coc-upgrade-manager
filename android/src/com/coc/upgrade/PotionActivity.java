package com.coc.upgrade;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 加速药水板块:分建筑/兵种两类,各自倍率,选完手动勾选要加速的项目 */
public class PotionActivity extends Activity {

    private static final int GREEN = 0xFF2E7D32;
    private static final int GREEN_DARK = 0xFF1B5E20;
    private static final int TEXT_MAIN = 0xFF1F2937;
    private static final int TEXT_SUB = 0xFF6B7280;

    private static final int CAT_BUILDING = 10, CAT_TROOP = 11;
    // 建筑升级:2×、10×;兵种升级:2×、24×
    private static final int[][] MULTS = {{2, 10}, {2, 24}};
    private static final String[][] MULT_LABELS = {
            {"2× (1瓶=1小时,完成2小时工作)", "10× (1瓶=1小时,完成10小时工作)"},
            {"2× (1瓶=1小时,完成2小时工作)", "24× (1瓶=1小时,完成24小时工作)"}
    };

    private String account;
    private int cat = CAT_BUILDING;
    private int mult = 2;
    private EditText bottlesEt;
    private EditText hoursEt, minutesEt;
    private LinearLayout bottlesRow, customRow;
    private boolean customMode = false;
    private TextView effectTv;
    private RadioGroup subRg;
    private final List<JSONObject> activeTimers = new ArrayList<>();
    private final List<CheckBox> itemBoxes = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, float sz, int c, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(sz);
        tv.setTextColor(c);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
    }

    private TextView text(String s, float sz, int c) {
        return text(s, sz, c, false);
    }

    private LinearLayout card(int orientation) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(orientation);
        c.setBackgroundResource(R.drawable.bg_card_ripple);
        return c;
    }

    private Button btn(String s, boolean primary, float h) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(primary ? Color.WHITE : GREEN);
        b.setBackgroundResource(primary
                ? R.drawable.bg_btn_primary_ripple
                : R.drawable.bg_btn_secondary_ripple);
        b.setStateListAnimator(null);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setGravity(Gravity.CENTER);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(h)));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Store.init(this);
        account = getIntent().getStringExtra("account");
        if (account == null || Store.account(account) == null) {
            toast("账号无效");
            finish();
            return;
        }
        buildUi();
    }

    private LinearLayout margin(View v, int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(l, t, r, b);
        v.setLayoutParams(lp);
        return (LinearLayout) v;
    }

    private void buildUi() {
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
        bar.addView(text("⚡ 加速药水", 17, Color.WHITE, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar);

        ScrollView sc = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(16));
        sc.addView(content);

        // ① 类型
        LinearLayout c1 = card(LinearLayout.VERTICAL);
        c1.setPadding(dp(14), dp(12), dp(14), dp(12));
        c1.addView(text("① 选择药水类型", 14, TEXT_MAIN, true));
        RadioGroup catRg = new RadioGroup(this);
        RadioButton rb1 = new RadioButton(this);
        rb1.setText("建筑升级加速药水(作用于建筑/英雄升级)");
        rb1.setId(CAT_BUILDING);
        RadioButton rb2 = new RadioButton(this);
        rb2.setText("兵种升级加速药水(作用于兵种/法术升级)");
        rb2.setId(CAT_TROOP);
        catRg.addView(rb1);
        catRg.addView(rb2);
        catRg.check(CAT_BUILDING);
        catRg.setOnCheckedChangeListener((g, id) -> {
            cat = id;
            rebuildSub();
            updateEffect();
        });
        c1.addView(catRg);
        content.addView(margin(c1, 0, dp(12), 0, 0));

        // ② 倍率
        LinearLayout c2 = card(LinearLayout.VERTICAL);
        c2.setPadding(dp(14), dp(12), dp(14), dp(12));
        c2.addView(text("② 选择加速倍率", 14, TEXT_MAIN, true));
        subRg = new RadioGroup(this);
        c2.addView(subRg);
        content.addView(margin(c2, 0, dp(10), 0, 0));
        rebuildSub();

        // ③ 使用时长(按瓶数 或 自定义时长)
        LinearLayout c3 = card(LinearLayout.VERTICAL);
        c3.setPadding(dp(14), dp(12), dp(14), dp(12));
        c3.addView(text("③ 使用时长", 14, TEXT_MAIN, true));
        RadioGroup durRg = new RadioGroup(this);
        durRg.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton drBottles = new RadioButton(this);
        drBottles.setText("按瓶数(每瓶1小时)");
        drBottles.setTextSize(13);
        drBottles.setTextColor(TEXT_SUB);
        drBottles.setId(20);
        RadioButton drCustom = new RadioButton(this);
        drCustom.setText("自定义时长");
        drCustom.setTextSize(13);
        drCustom.setTextColor(TEXT_SUB);
        drCustom.setId(21);
        durRg.addView(drBottles);
        durRg.addView(drCustom);
        durRg.check(20);
        durRg.setOnCheckedChangeListener((g, id) -> {
            customMode = (id == 21);
            bottlesRow.setVisibility(customMode ? View.GONE : View.VISIBLE);
            customRow.setVisibility(customMode ? View.VISIBLE : View.GONE);
            updateEffect();
        });
        c3.addView(durRg);
        // 按瓶数行
        bottlesRow = new LinearLayout(this);
        bottlesRow.setGravity(Gravity.CENTER_VERTICAL);
        bottlesEt = new EditText(this);
        bottlesEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        bottlesEt.setText("1");
        bottlesEt.setBackgroundResource(R.drawable.bg_input);
        bottlesEt.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(80),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, dp(6), dp(8), 0);
        bottlesRow.addView(bottlesEt, blp);
        bottlesRow.addView(text("瓶(可叠加,每瓶持续1小时)", 12, TEXT_SUB));
        c3.addView(bottlesRow);
        // 自定义时长行(支持几分几秒的剩余药水)
        customRow = new LinearLayout(this);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        customRow.setVisibility(View.GONE);
        hoursEt = new EditText(this);
        hoursEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        hoursEt.setHint("0");
        hoursEt.setBackgroundResource(R.drawable.bg_input);
        hoursEt.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.setMargins(0, dp(6), dp(4), 0);
        customRow.addView(hoursEt, hlp);
        customRow.addView(text("时", 12, TEXT_SUB));
        minutesEt = new EditText(this);
        minutesEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesEt.setHint("30");
        minutesEt.setBackgroundResource(R.drawable.bg_input);
        minutesEt.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(64),
                ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.setMargins(dp(8), dp(6), dp(4), 0);
        customRow.addView(minutesEt, mlp);
        customRow.addView(text("分(如 0时5分 = 5分钟)", 12, TEXT_SUB));
        c3.addView(customRow);
        effectTv = text("", 12, TEXT_SUB);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.setMargins(0, dp(6), 0, 0);
        c3.addView(effectTv, elp);
        content.addView(margin(c3, 0, dp(10), 0, 0));
        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { updateEffect(); }
        };
        bottlesEt.addTextChangedListener(tw);
        hoursEt.addTextChangedListener(tw);
        minutesEt.addTextChangedListener(tw);

        // ④ 项目选择
        LinearLayout c4 = card(LinearLayout.VERTICAL);
        c4.setPadding(dp(14), dp(12), dp(14), dp(12));
        c4.addView(text("④ 选择要加速的项目", 14, TEXT_MAIN, true));
        c4.addView(text("只勾选这个药水能加速的项目(建筑药水只加速建筑,兵种药水只加速兵种)",
                12, TEXT_SUB));
        LinearLayout ar = new LinearLayout(this);
        ar.setOrientation(LinearLayout.HORIZONTAL);
        Button all = btn("全选", false, 36);
        all.setOnClickListener(v -> {
            for (CheckBox cb : itemBoxes) cb.setChecked(true);
        });
        Button none = btn("取消全选", false, 36);
        none.setOnClickListener(v -> {
            for (CheckBox cb : itemBoxes) cb.setChecked(false);
        });
        LinearLayout.LayoutParams lpAll = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lpAll.setMargins(dp(3), dp(8), dp(3), 0);
        LinearLayout.LayoutParams lpNone = new LinearLayout.LayoutParams(0, dp(36), 1f);
        lpNone.setMargins(dp(3), dp(8), dp(3), 0);
        ar.addView(all, lpAll);
        ar.addView(none, lpNone);
        c4.addView(ar);

        loadActiveTimers();
        if (activeTimers.isEmpty()) {
            c4.addView(text("当前账号没有进行中的倒计时", 13, TEXT_SUB));
        } else {
            for (JSONObject t : activeTimers) {
                CheckBox cb = new CheckBox(this);
                cb.setTextSize(14);
                cb.setTextColor(TEXT_MAIN);
                long remain = t.optLong("end_ts") - System.currentTimeMillis();
                cb.setText(t.optString("item") + "  (剩余 " + MainActivity.fmtRemaining(remain) + ")");
                final String id = t.optString("id");
                cb.setOnCheckedChangeListener((v, checked) -> {
                    if (checked) selected.add(id);
                    else selected.remove(id);
                    updateEffect();
                });
                c4.addView(cb);
                itemBoxes.add(cb);
            }
        }
        content.addView(margin(c4, 0, dp(10), 0, 0));

        root.addView(sc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 底部使用按钮
        Button apply = btn("使用并加速", true, 48);
        apply.setOnClickListener(v -> applyPotion());
        LinearLayout.LayoutParams alp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        alp2.setMargins(dp(12), dp(8), dp(12), dp(12));
        root.addView(apply, alp2);

        setContentView(root);
        updateEffect();
    }

    private void rebuildSub() {
        subRg.removeAllViews();
        int[] ms = MULTS[cat == CAT_BUILDING ? 0 : 1];
        String[] ls = MULT_LABELS[cat == CAT_BUILDING ? 0 : 1];
        for (int i = 0; i < ms.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(ls[i]);
            rb.setId(1000 + i);
            subRg.addView(rb);
        }
        subRg.check(1000);
        mult = ms[0];
        subRg.setOnCheckedChangeListener((g, id) -> {
            int idx = id - 1000;
            int[] arr = MULTS[cat == CAT_BUILDING ? 0 : 1];
            if (idx >= 0 && idx < arr.length) {
                mult = arr[idx];
                updateEffect();
            }
        });
    }

    private int bottles() {
        try {
            int n = Integer.parseInt(bottlesEt.getText().toString().trim());
            return n < 1 ? 1 : n;
        } catch (Exception e) {
            return 1;
        }
    }

    private double parseD(String s) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return 0; }
    }

    /** 当前药水持续时长(小时),按瓶数或自定义时长 */
    private double potionHours() {
        if (customMode) {
            double h = parseD(hoursEt.getText().toString());
            double m = parseD(minutesEt.getText().toString());
            return h + m / 60.0;
        }
        return bottles();
    }

    private String fmtDur(double h) {
        long totalMin = Math.round(h * 60);
        long hr = totalMin / 60;
        long mn = totalMin % 60;
        if (hr > 0 && mn > 0) return hr + "小时" + mn + "分钟";
        if (hr > 0) return hr + "小时";
        return mn + "分钟";
    }

    private void updateEffect() {
        double ph = potionHours();
        if (ph <= 0) { effectTv.setText("请输入有效的药水时长"); return; }
        effectTv.setText("效果:未来 " + fmtDur(ph) + " 内以 " + mult + " 倍推进,"
                + "等价于 " + String.format(java.util.Locale.CHINA, "%.1f", mult * ph)
                + " 游戏小时工作量\n已选 " + selected.size() + " 个项目");
    }

    private void loadActiveTimers() {
        activeTimers.clear();
        JSONObject acc = Store.account(account);
        if (acc == null) return;
        JSONArray ts = acc.optJSONArray("timers");
        long now = System.currentTimeMillis();
        if (ts != null) {
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t != null && t.optLong("end_ts") > now) activeTimers.add(t);
            }
        }
    }

    private void applyPotion() {
        if (selected.isEmpty()) {
            toast("请先勾选要加速的项目");
            return;
        }
        JSONObject acc = Store.account(account);
        if (acc == null) return;
        long now = System.currentTimeMillis();
        double potionHours = potionHours();
        if (potionHours <= 0) { toast("请输入有效的药水时长"); return; }
        double progressDuring = mult * potionHours;
        JSONArray ts = acc.optJSONArray("timers");
        int affected = 0;
        if (ts != null) {
            for (int i = 0; i < ts.length(); i++) {
                JSONObject t = ts.optJSONObject(i);
                if (t == null) continue;
                String id = t.optString("id");
                if (!selected.contains(id)) continue;
                double remainH = (t.optLong("end_ts") - now) / 3600000.0;
                if (remainH <= 0) continue;
                double newRemainH;
                if (remainH <= progressDuring) {
                    newRemainH = remainH / mult;
                } else {
                    newRemainH = potionHours + (remainH - progressDuring);
                }
                long newEnd = now + (long) (newRemainH * 3600000.0);
                Store.put(t, "end_ts", newEnd);
                Store.put(t, "notified", false);
                Store.put(t, "updated_at", now);
                Alarms.cancel(this, id);
                Alarms.schedule(this, id, newEnd);
                affected++;
            }
        }
        Store.saveData();
        Sync.syncAsync(null);
        toast("已为 " + affected + " 个项目加速");
        finish();
    }
}
