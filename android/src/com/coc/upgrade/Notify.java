package com.coc.upgrade;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 升级完成系统通知(高优先级横幅 + 声音 + 震动) */
public class Notify {
    public static final String CHANNEL = "upgrade_done";

    public static void post(Context ctx, String account, String item) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "升级完成提醒",
                    NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("部落冲突升级倒计时到点提醒");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(ctx, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("⏰ 升级完成")
                .setContentText(account + " · " + item)
                .setStyle(new Notification.BigTextStyle().bigText(
                        "账号:" + account + "\n项目:" + item + "\n已升级完成,记得上线操作!"))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL);
        nm.notify((int) (System.currentTimeMillis() & 0x7fffffffL), b.build());
    }
}
