package com.coc.upgrade;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

/** 后台监控前台服务:App 退出后进程常驻,每秒检查倒计时,到点立刻发通知 */
public class MonitorService extends Service {

    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 42;

    private final Handler handler = new Handler();

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 只在服务启动时读取一次数据,循环中不再重读,避免覆盖界面正在编辑的内存配置
        Store.init(this);
        startForegroundCompat();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 300);
        return START_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "后台监控",
                    NotificationManager.IMPORTANCE_MIN);
            ch.setDescription("保持后台运行以便准时提醒");
            ch.setShowBadge(false);
            ch.setSound(null, null);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("升级监控运行中")
                .setContentText("倒计时到点会准时提醒你")
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, 0);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void updateOngoing(int pending, String next) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("升级监控运行中")
                .setContentText("还有 " + pending + " 个倒计时" + (next.isEmpty() ? "" : " · 最近:" + next))
                .setOngoing(true)
                .setOnlyAlertOnce(true);
        nm.notify(NOTIF_ID, b.build());
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            int pending = 0;
            long nearest = Long.MAX_VALUE;
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
                    long end = t.optLong("end_ts");
                    if (end <= now && !t.optBoolean("notified")) {
                        if (!MainActivity.foreground) {
                            // App 在后台:由服务负责发提醒
                            Store.put(t, "notified", true);
                            Store.put(t, "updated_at", now);
                            Notify.post(MonitorService.this,
                                    acc.optString("name"), t.optString("item"));
                            Alarms.cancel(MonitorService.this, t.optString("id"));
                            changed = true;
                        }
                    } else if (end > now) {
                        pending++;
                        if (end < nearest) nearest = end;
                    }
                }
            }
            if (changed) {
                Store.saveData();
                Sync.syncAsync(null); // 把"已提醒"状态同步到云端,其他设备不会重复处理
            }
            if (pending == 0) {
                stopSelf();
                return;
            }
            String next = nearest == Long.MAX_VALUE ? "" : fmtShort(nearest - now);
            updateOngoing(pending, next);
            handler.postDelayed(this, 1000);
        }
    };

    private static String fmtShort(long ms) {
        long s = ms / 1000;
        long d = s / 86400;
        s %= 86400;
        long h = s / 3600;
        s %= 3600;
        long m = s / 60;
        long sec = s % 60;
        if (d > 0) return d + "天" + h + "时" + m + "分";
        if (h > 0) return h + "时" + m + "分";
        if (m > 0) return m + "分" + sec + "秒";
        return sec + "秒";
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
