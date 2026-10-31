package com.zibieyv.parcel;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.IBinder;

/**
 * 常驻前台服务。
 *
 * 目的不是"后台跑业务"，而是把进程钉住：安卓和国产 ROM 对前台服务的清理优先级
 * 远低于普通进程，这样无障碍服务（悬浮球）才不会被随手杀掉。
 *
 * 通知栏上带一个开关：显示 / 隐藏悬浮球。
 */
public class KeepAliveService extends Service {

    public static final String ACTION_TOGGLE_BALL = "com.zibieyv.parcel.TOGGLE_BALL";
    private static final String CHANNEL_ID = "parcel_keepalive";
    private static final int NOTIFY_ID = 1001;

    private BroadcastReceiver toggleReceiver;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();

        toggleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean now = !BallPrefs.isEnabled(KeepAliveService.this);
                BallPrefs.setEnabled(KeepAliveService.this, now);
                updateNotification();
                sendBroadcast(new Intent(BallPrefs.ACTION_CHANGED).setPackage(getPackageName()));
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_TOGGLE_BALL);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(toggleReceiver, filter);
        }

        startForeground(NOTIFY_ID, buildNotification());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (toggleReceiver != null) {
            try { unregisterReceiver(toggleReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "包裹常驻", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保证悬浮球随时可用");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        boolean ballOn = BallPrefs.isEnabled(this);

        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(
                this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent toggle = new Intent(ACTION_TOGGLE_BALL).setPackage(getPackageName());
        PendingIntent togglePi = PendingIntent.getBroadcast(
                this, 2, toggle,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("包裹")
                .setContentText(ballOn ? "悬浮球已显示，点通知栏按钮可隐藏" : "悬浮球已隐藏")
                .setSmallIcon(R.drawable.ball)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null,
                        ballOn ? "隐藏悬浮球" : "显示悬浮球", togglePi).build());
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFY_ID, buildNotification());
        } catch (Exception ignored) {}
    }
}
