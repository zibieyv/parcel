package com.zibieyv.parcel;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 原生 → 网页 的单向通道。
 * 短信 / 通知抓到的取件码都从这里丢给 WebView。
 */
public final class Bridge {

    public interface Sink {
        void onPickupCode(String code, String source);
    }

    private static final Deque<String[]> QUEUE = new ArrayDeque<>();
    private static volatile Sink sink;

    private Bridge() {}

    static void attach(Sink s) {
        sink = s;
        flush();
    }

    static void detach(Sink s) {
        if (sink == s) sink = null;
    }

    /** 任何线程都能调用 */
    static void deliver(String code, String source) {
        if (code == null) return;
        String c = code.trim();
        if (c.isEmpty()) return;
        synchronized (QUEUE) {
            if (QUEUE.size() > 20) QUEUE.pollFirst();
            QUEUE.addLast(new String[]{c, source == null ? "" : source});
        }
        flush();
    }

    private static void flush() {
        Sink s = sink;
        if (s == null) return;
        String[] item;
        while (true) {
            synchronized (QUEUE) {
                item = QUEUE.pollFirst();
            }
            if (item == null) break;
            s.onPickupCode(item[0], item[1]);
        }
    }
}
