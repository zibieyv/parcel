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
        String direct = strictTracking(text);
        if (direct != null) return direct;

        // OCR 会把 O 认成 0、S 认成 5 之类，按位置纠一遍再匹配
        String[] tokens = text.split("[^A-Za-z0-9]+");
        for (String token : tokens) {
            if (token.length() < 8 || token.length() > 20) continue;
            String fixed = coerceToken(token);
            if (fixed == null || fixed.equalsIgnoreCase(token)) continue;
            String hit = strictTracking(fixed);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String strictTracking(String text) {
        for (Pattern p : TRACKING_PATTERNS) {
            Matcher m = p.matcher(text);
            if (m.find()) return m.group(1).toUpperCase();
        }
        return null;
    }

    private static final String LETTER_TO_DIGIT = "OILSBZGQ";
    private static final String DIGIT_TO_LETTER = "01158269";

    private static char toDigit(char c) {
        if (c >= '0' && c <= '9') return c;
        int i = LETTER_TO_DIGIT.indexOf(c);
        return i >= 0 ? DIGIT_TO_LETTER.charAt(i) : c;
    }

    private static char toLetter(char c) {
        if (c >= 'A' && c <= 'Z') return c;
        switch (c) {
            case '0': return 'O';
            case '1': return 'I';
            case '5': return 'S';
            case '8': return 'B';
            case '2': return 'Z';
            case '6': return 'G';
            case '9': return 'Q';
            default: return c;
        }
    }

    /**
     * 按常见单号形状纠正 OCR 错认：
     *  - 两字母 + 9 位数字 + 两字母（国际邮政，如 EZ001649959TW）
     *  - 字母前缀 + 数字（YT/JT/SF/JD…）
     *  - 纯数字（12–18 位）
     */
    private static String coerceToken(String token) {
        String t = token.toUpperCase();
        int n = t.length();

        // 国际邮政：2 + 9 + 2
        if (n == 13) {
            StringBuilder sb = new StringBuilder(n);
            sb.append(toLetter(t.charAt(0))).append(toLetter(t.charAt(1)));
            for (int i = 2; i < 11; i++) sb.append(toDigit(t.charAt(i)));
            sb.append(toLetter(t.charAt(11))).append(toLetter(t.charAt(12)));
            return sb.toString();
        }

        // 带字母前缀：取前 2–3 个字母，其余按数字纠
        if (n >= 10 && n <= 18) {
            int p = 0;
            while (p < n && p < 3 && (t.charAt(p) >= 'A' && t.charAt(p) <= 'Z')) p++;
            if (p >= 2) {
                StringBuilder sb = new StringBuilder(n);
                sb.append(t, 0, p);
                for (int i = p; i < n; i++) sb.append(toDigit(t.charAt(i)));
                return sb.toString();
            }
        }

        // 纯数字形状（全是数字或数字+易混字母）
        if (n >= 12 && n <= 18) {
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) sb.append(toDigit(t.charAt(i)));
            return sb.toString();
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
