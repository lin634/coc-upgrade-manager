package com.coc.upgrade;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/** 分段选择器:浅灰胶囊容器,选中项白底胶囊 + 强调色加粗(替代系统原生单选钮) */
public class Segmented extends LinearLayout {

    public interface OnSelect {
        void onPick(int index);
    }

    private static final int TEXT_MAIN = 0xFF263B31;
    private static final int TEXT_SUB = 0xFF7B857E;

    private final ArrayList<TextView> items = new ArrayList<>();
    private OnSelect cb;
    private int selected;

    public Segmented(Context ctx, String[] labels, int selected, OnSelect cb) {
        super(ctx);
        this.selected = selected;
        this.cb = cb;
        setOrientation(HORIZONTAL);
        setBackgroundResource(R.drawable.bg_pill_container);
        int pad = dp(3);
        setPadding(pad, pad, pad, pad);
        for (int i = 0; i < labels.length; i++) {
            TextView tv = new TextView(ctx);
            tv.setText(labels[i]);
            tv.setTextSize(13);
            tv.setSingleLine(true);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(8), dp(9), dp(8), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(2));
            lp.setMarginEnd(dp(2));
            addView(tv, lp);
            final int idx = i;
            tv.setOnClickListener(v -> {
                if (this.selected != idx) pick(idx, true);
            });
            items.add(tv);
        }
        restyle();
    }

    /** 设置/替换选择回调 */
    public void setOnSelect(OnSelect cb) {
        this.cb = cb;
    }

    private void pick(int idx, boolean notify) {
        selected = idx;
        restyle();
        if (notify && cb != null) cb.onPick(idx);
    }

    /** 编程式选中(不触发回调) */
    public void select(int idx) {
        pick(idx, false);
    }

    public int getSelected() {
        return selected;
    }

    private void restyle() {
        for (int i = 0; i < items.size(); i++) {
            TextView tv = items.get(i);
            if (i == selected) {
                tv.setBackgroundResource(R.drawable.bg_pill_selected);
                tv.setTextColor(TEXT_MAIN);
                tv.setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                tv.setBackground(null);
                tv.setTextColor(TEXT_SUB);
                tv.setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
