package com.zibieyv.parcel;

import android.content.Context;

/** 悬浮球开关的存储（Service 和 Activity 共用） */
final class BallPrefs {

    static final String ACTION_CHANGED = "com.zibieyv.parcel.BALL_CHANGED";
    private static final String PREF = "parcel";
    private static final String KEY = "ball_enabled";

    private BallPrefs() {}

    static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY, true);
    }

    static void setEnabled(Context c, boolean on) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply();
    }
}
