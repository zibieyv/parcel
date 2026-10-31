package com.zibieyv.parcel;

/** 本地跑一下 OCR 纠错：javac 后 java -cp out com.zibieyv.parcel.CodeParserTest */
public class CodeParserTest {
    public static void main(String[] args) {
        String[][] cases = {
                {"EZ001649959TW", "正常国际单号"},
                {"E2001649959TW", "OCR 把 Z 认成 2"},
                {"EZOO1649959TW", "OCR 把 0 认成 O"},
                {"JT3182791168335", "极兔正常"},
                {"JT3I8279II68335", "极兔里 I/1 混了"},
                {"321344613523839", "韵达纯数字"},
                {"3213446l3523839", "韵达里 l/1 混了"},
                {"SF1234567890123", "顺丰"},
                {"单号 9876543210123 到站", "夹在中文里"},
                {"没有单号的页面 12 34", "无单号"},
        };
        for (String[] c : cases) {
            String got = CodeParser.findTracking(c[0]);
            System.out.printf("%-22s -> %-18s (%s)%n", c[0], String.valueOf(got), c[1]);
        }
    }
}
