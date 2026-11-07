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

/** 加速药水板块:选倍率与时长,手动勾选要加速的项目 */
public class PotionActivity extends Activity {

    private static final int GREEN = 0xFF2E7D32;
    private static final int GREEN_DARK = 0xFF1B5E20;
    private static final int TEXT_MAIN = 0xFF1F2937;
    private static final int TEXT_SUB = 0xFF6B7280;

    // 预设倍率:2×、10×、24×,另支持自定义任意倍率
    private static final int[] MULTS = {2, 10, 24};
    private static final String[] MULT_LABELS = {
            "2× (1瓶=1小时,完成2小时工作)", "10× (1瓶=1小时,完成10小时工作)",
            "24× (1瓶=1小时,完成24小时工作)"
    };

    private String account;
    private double mult = 2;
    private EditText bottlesEt;
    private EditText hoursEt, minutesEt;
    private EditText customMultEt;
    private LinearLayout bottlesRow, customRow, customMultRow;
    private boolean customMode = false;
    private TextView effectTv;
    private EditText calcEtD, calcEtH, calcEtM;
    private TextView calcTv;
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

        // ① 倍率
        LinearLayout c2 = card(LinearLayout.VERTICAL);
        c2.setPadding(dp(14), dp(12), dp(14), dp(12));
        c2.addView(text("① 选择加速倍率", 14, TEXT_MAIN, true));
        subRg = new RadioGroup(this);
        c2.addView(subRg);
        // 自定义倍率输入行(选中「自定义倍率」时显示)
        customMultRow = new LinearLayout(this);
        customMultRow.setGravity(Gravity.CENTER_VERTICAL);
        customMultRow.setVisibility(View.GONE);
        customMultEt = new EditText(this);
        customMultEt.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        customMultEt.setHint("如 1.5");
        customMultEt.setTextSize(14);
        customMultEt.setGravity(Gravity.CENTER);
        customMultEt.setBackgroundResource(R.drawable.bg_input);
        customMultEt.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(72), dp(48));
        clp.setMargins(dp(30), dp(4), dp(6), 0);
        customMultRow.addView(customMultEt, clp);
        customMultRow.addView(text("倍(支持小数)", 13, TEXT_MAIN));
        c2.addView(customMultRow);
        // 🧮 减免试算:输入项目剩余时间,按当前倍率与时长实时算可减免的小时数
        c2.addView(text("🧮 减免试算(输入剩余时间)", 12, TEXT_SUB));
        LinearLayout calcRow = new LinearLayout(this);
        calcRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams clpD = new LinearLayout.LayoutParams(dp(44), dp(48));
        clpD.setMargins(0, dp(6), dp(2), 0);
        calcEtD = calcEdit();
        calcRow.addView(calcEtD, clpD);
        calcRow.addView(text("天", 12, TEXT_SUB));
        calcEtH = calcEdit();
        LinearLayout.LayoutParams clpH2 = new LinearLayout.LayoutParams(dp(44), dp(48));
        clpH2.setMargins(dp(6), dp(6), dp(2), 0);
        calcRow.addView(calcEtH, clpH2);
        calcRow.addView(text("时", 12, TEXT_SUB));
        calcEtM = calcEdit();
        LinearLayout.LayoutParams clpM2 = new LinearLayout.LayoutParams(dp(44), dp(48));
        clpM2.setMargins(dp(6), dp(6), dp(2), 0);
        calcRow.addView(calcEtM, clpM2);
        calcRow.addView(text("分", 12, TEXT_SUB));
        c2.addView(calcRow);
        calcTv = text("", 13, GREEN, true);
        LinearLayout.LayoutParams calcLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        calcLp.setMargins(0, dp(6), 0, 0);
        c2.addView(calcTv, calcLp);
        content.addView(margin(c2, 0, dp(12), 0, 0));
        rebuildSub();

        // ② 使用时长(按瓶数 或 自定义时长)
        LinearLayout c3 = card(LinearLayout.VERTICAL);
        c3.setPadding(dp(14), dp(12), dp(14), dp(12));
        c3.addView(text("② 使用时长", 14, TEXT_MAIN, true));
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
        bottlesEt.setTextSize(14);
        bottlesEt.setGravity(Gravity.CENTER);
        bottlesEt.setBackgroundResource(R.drawable.bg_input);
        bottlesEt.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(80), dp(48));
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
        hoursEt.setTextSize(14);
        hoursEt.setGravity(Gravity.CENTER);
        hoursEt.setBackgroundResource(R.drawable.bg_input);
        hoursEt.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(64), dp(48));
        hlp.setMargins(0, dp(6), dp(4), 0);
        customRow.addView(hoursEt, hlp);
        customRow.addView(text("时", 12, TEXT_SUB));
        minutesEt = new EditText(this);
        minutesEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        minutesEt.setHint("30");
        minutesEt.setTextSize(14);
        minutesEt.setGravity(Gravity.CENTER);
        minutesEt.setBackgroundResource(R.drawable.bg_input);
        minutesEt.setPadding(dp(8), dp(4), dp(8), dp(4));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(64), dp(48));
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
        calcEtD.addTextChangedListener(tw);
        calcEtH.addTextChangedListener(tw);
        calcEtM.addTextChangedListener(tw);
        customMultEt.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                double v = parseMultInput();
                if (v > 0) {
                    RadioButton crb = (RadioButton) subRg.findViewById(2000);
                    if (crb != null) crb.setText("自定义倍率(" + fmtMult(v) + "×,可修改)");
                    if (subRg.getCheckedRadioButtonId() == 2000) {
                        mult = v;
                        updateEffect();
                    }
                }
            }
        });

        // ③ 项目选择
        LinearLayout c4 = card(LinearLayout.VERTICAL);
        c4.setPadding(dp(14), dp(12), dp(14), dp(12));
        c4.addView(text("③ 选择要加速的项目", 14, TEXT_MAIN, true));
        c4.addView(text("勾选这次药水要加速的项目(可多选)", 12, TEXT_SUB));
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
        for (int i = 0; i < MULTS.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(MULT_LABELS[i]);
            rb.setId(1000 + i);
            subRg.addView(rb);
        }
        RadioButton customRb = new RadioButton(this);
        customRb.setText("自定义倍率");
        customRb.setId(2000);
        subRg.addView(customRb);
        subRg.check(1000);
        mult = MULTS[0];
        customMultRow.setVisibility(View.GONE);
        subRg.setOnCheckedChangeListener((g, id) -> {
            if (id == 2000) {
                customMultRow.setVisibility(View.VISIBLE);
                mult = parseMultInput(); // 输入为空时为 0,效果栏会提示输入有效倍率
                updateEffect();
            } else {
                customMultRow.setVisibility(View.GONE);
                int idx = id - 1000;
                if (idx >= 0 && idx < MULTS.length) {
                    mult = MULTS[idx];
                    updateEffect();
                }
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

    /** 解析自定义倍率输入,非法或 ≤0 返 0 */
    private double parseMultInput() {
        double v = parseD(customMultEt.getText().toString());
        return v > 0 ? v : 0;
    }

    /** 倍率显示:整数不带小数点,小数保留 1 位 */
    private String fmtMult(double m) {
        if (m == Math.floor(m) && m < 1e6) return String.valueOf((long) m);
        return String.format(java.util.Locale.CHINA, "%.1f", m);
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
        if (mult <= 0) {
            effectTv.setText("请输入有效的自定义倍率(大于 0)");
            calcTv.setText("");
            return;
        }
        double ph = potionHours();
        if (ph <= 0) {
            effectTv.setText("请输入有效的药水时长");
            calcTv.setText("");
            return;
        }
        effectTv.setText("效果:未来 " + fmtDur(ph) + " 内以 " + fmtMult(mult)
                + " 倍推进,等价于 " + fmtDur(mult * ph) + " 游戏工作量\n已选 "
                + selected.size() + " 个项目");
        updateCalc();
    }

    /** 减免试算:按当前倍率与药水时长,算输入的剩余时间能减免多少小时 */
    private void updateCalc() {
        double ph = potionHours();
        if (mult <= 0 || ph <= 0) { calcTv.setText(""); return; }
        double t = parseD(calcEtD.getText().toString()) * 86400
                + parseD(calcEtH.getText().toString()) * 3600
                + parseD(calcEtM.getText().toString()) * 60;
        if (t <= 0) { calcTv.setText(""); return; }
        double saved;
        String note;
        if (t <= mult * ph * 3600) {
            // 加速期内就能完成:实际耗时 = 剩余 ÷ 倍率
            saved = t - t / mult;
            note = "加速期内即可完成,";
        } else {
            saved = (mult - 1) * ph * 3600;
            note = "";
        }
        calcTv.setText(note + "可减免约 " + fmtDur(saved / 3600.0) + "的工作量");
    }

    private EditText calcEdit() {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setTextSize(14);
        e.setGravity(Gravity.CENTER);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setPadding(dp(8), dp(4), dp(8), dp(4));
        return e;
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
        if (mult <= 0) { toast("请输入有效的自定义倍率"); return; }
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
