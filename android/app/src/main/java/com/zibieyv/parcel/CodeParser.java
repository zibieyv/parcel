package com.zibieyv.parcel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从短信 / 通知文本里抠取件码和快递单号 */
final class CodeParser {

    private static final Pattern[] CODE_PATTERNS = new Pattern[]{
            Pattern.compile("(?:取件[码碼]|提货[码碼]|提貨[码碼]|取货[码碼]|取貨[码碼]|领取[码碼]|領取[码碼])[^0-9]{0,6}([0-9][0-9\\-－]{2,12})"),
            Pattern.compile("(?:[码碼]\\s*[:：]\\s*)([0-9][0-9\\-－]{2,12})"),
            Pattern.compile("([0-9]{2}-[0-9]{1,3}-[0-9]{3,5})"),
    };

    /**
     * 带前缀的单号优先，其次国际单号，最后才是裸数字。
     * 不用 \b：Java 的 \b 对中文的判定容易出意外，这里显式用 ASCII 前后瞻。
     */
    private static final Pattern[] TRACKING_PATTERNS = new Pattern[]{
            Pattern.compile("(?<![A-Za-z0-9])((?:YT|JT|SF|JD|DPK|ZTO)[0-9]{9,16})(?![0-9])", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?<![A-Za-z0-9])([A-Z]{2}[0-9]{9}[A-Z]{2})(?![A-Za-z0-9])"),
            Pattern.compile("(?<![A-Za-z0-9])([0-9]{12,18})(?![0-9])"),
    };

    private CodeParser() {}

    static String find(String text) {
        if (text == null || text.isEmpty()) return null;
        for (Pattern p : CODE_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                String code = m.group(1).replace('－', '-').trim();
                String digits = code.replaceAll("\\D", "");
                // 太短的数字（例如年份、电话）不要
                if (digits.length() >= 4) return code;
            }
        }
        return null;
    }

    static String findTracking(String text) {
        if (text == null || text.isEmpty()) return null;
        for (Pattern p : TRACKING_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1).toUpperCase();
        }
        return null;
    }

    /** 通知/短信是不是跟快递有关，避免外卖、银行短信被误抓 */
    static boolean looksLikeParcel(String text) {
        if (text == null) return false;
        return text.contains("取件") || text.contains("取件碼") || text.contains("驿站")
                || text.contains("驛站") || text.contains("快递柜") || text.contains("快遞櫃")
                || text.contains("丰巢") || text.contains("豐巢") || text.contains("菜鸟")
                || text.contains("菜鳥") || text.contains("包裹") || text.contains("自提");
    }
}
