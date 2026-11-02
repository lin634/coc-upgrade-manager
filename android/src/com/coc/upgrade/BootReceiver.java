package com.coc.upgrade;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONArray;
import org.json.JSONObject;

/** 手机重启后:重新注册所有未到点闹钟,并补发错过的提醒 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Store.init(ctx);
        long now = System.currentTimeMillis();
        JSONArray as = Store.accounts();
        boolean changed = false;
        for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.optJSONObject(i);
            if (a == null) continue;
            JSONArray ts = a.optJSONArray("timers");
            if (ts == null) continue;
            for (int j = 0; j < ts.length(); j++) {
                JSONObject t = ts.optJSONObject(j);
                if (t == null) continue;
                long end = t.optLong("end_ts");
                if (end <= now && !t.optBoolean("notified")) {
                    Store.put(t, "notified", true);
                    Store.put(t, "updated_at", now);
                    Notify.post(ctx, a.optString("name"), t.optString("item"));
                    changed = true;
                } else if (end > now) {
                    Alarms.schedule(ctx, t.optString("id"), end);
                }
            }
        }
        if (changed) Store.saveData();
    }
}
