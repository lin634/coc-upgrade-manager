package com.coc.upgrade;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** 单个倒计时到点的闹钟回调 */
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String id = intent.getStringExtra("id");
        if (id == null) return;
        Store.init(ctx);
        JSONObject t = Store.timerById(id);
        if (t == null) return;
        if (t.optLong("end_ts") <= System.currentTimeMillis() && !t.optBoolean("notified")) {
            if (MainActivity.foreground) return; // 前台时由界面统一检查并提醒
            Store.put(t, "notified", true);
            Store.put(t, "updated_at", System.currentTimeMillis());
            Store.saveData();
            JSONObject acc = Store.accountOfTimer(id);
            Notify.post(ctx, acc == null ? "" : acc.optString("name"), t.optString("item"));
        }
    }
}
