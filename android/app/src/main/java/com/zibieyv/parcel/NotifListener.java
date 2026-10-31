package com.zibieyv.parcel;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * 读通知里的取件码。
 * 覆盖：丰巢、菜鸟驿站、淘宝、京东、拼多多、抖音等 App 的取件推送。
 * 只在文本像快递通知、且真的能抠出码时才发出去，别的通知一律忽略。
 */
public class NotifListener extends NotificationListenerService {

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return;
        Bundle extras = sbn.getNotification().extras;
        if (extras == null) return;

        String title = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        String big = str(extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String all = (title + "\n" + text + "\n" + big).trim();
        if (all.isEmpty()) return;

        if (!CodeParser.looksLikeParcel(all)) return;
        String code = CodeParser.find(all);
        if (code == null) return;

        String tracking = CodeParser.findTracking(all);
        String src = appLabel(sbn.getPackageName());
        Bridge.deliver(code, tracking, src);
    }

    private String appLabel(String pkg) {
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    private static String str(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }
}
