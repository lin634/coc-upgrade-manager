package com.coc.upgrade;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 用 AlarmManager 为每个倒计时注册到点闹钟(关掉 App 也能响) */
public class Alarms {

    private static PendingIntent pi(Context ctx, String id) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.putExtra("id", id);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, flags);
    }

    public static void schedule(Context ctx, String id, long atMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms())
                throw new SecurityException();
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi(ctx, id));
        } catch (Exception e) {
            // 无精确闹钟权限时退化为非精确(Doze 下最多延迟十几分钟,可接受)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi(ctx, id));
        }
    }

    public static void cancel(Context ctx, String id) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) am.cancel(pi(ctx, id));
    }
}
