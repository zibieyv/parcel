package com.zibieyv.parcel;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.telephony.SmsMessage;

/** 读短信里的取件码（丰巢、驿站最常发这个） */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        Object[] pdus = (Object[]) intent.getExtras().get("pdus");
        if (pdus == null) return;

        StringBuilder sb = new StringBuilder();
        for (Object pdu : pdus) {
            SmsMessage msg;
            if (Build.VERSION.SDK_INT >= 23) {
                msg = SmsMessage.createFromPdu((byte[]) pdu, intent.getStringExtra("format"));
            } else {
                msg = SmsMessage.createFromPdu((byte[]) pdu);
            }
            if (msg != null) sb.append(msg.getMessageBody());
        }

        String body = sb.toString();
        if (!CodeParser.looksLikeParcel(body)) return;
        String code = CodeParser.find(body);
        if (code != null) {
            String from = "短信";
            Bridge.deliver(code, from);
        }
    }
}
