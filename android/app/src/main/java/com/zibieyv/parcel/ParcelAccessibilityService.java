package com.zibieyv.parcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读屏抓单号 + 悬浮球。
 *
 * 悬浮球常驻在购物软件上层：点一下就把当前页面的单号抠出来，
 * 确认后跳到「包裹」并预填添加表单。
 */
public class ParcelAccessibilityService extends AccessibilityService {

    private static final Pattern TRACK_STRONG = Pattern.compile(
            "(?<![A-Za-z0-9])((?:YT|JT|SF|JD|DPK|ZTO|YD)[0-9]{9,16})(?![0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRACK_INTL = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Z]{2}[0-9]{9}[A-Z]{2})(?![A-Za-z0-9])");
    private static final Pattern TRACK_WEAK = Pattern.compile(
            "(?<![A-Za-z0-9])([0-9]{12,18})(?![0-9])");
    private static final Pattern KEYWORD = Pattern.compile("快递|快件|运单|物流|包裹|已签收|运输中|派送|揽收");

    private WindowManager wm;
    private LinearLayout ballBox;
    private WindowManager.LayoutParams ballParams;
    private View card;
    private int startX, startY;
    private float downX, downY;
    private long downTime;

    private final Handler ui = new Handler(Looper.getMainLooper());

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        showBall();
        maybeShowHint();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 目前不需要实时监听，点悬浮球时再扫，省电
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        removeBall();
        super.onDestroy();
    }

    /* ---------------- 悬浮球 ---------------- */

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void showBall() {
        if (ballBox != null) return;

        ballBox = new LinearLayout(this);
        ballBox.setOrientation(LinearLayout.VERTICAL);
        ballBox.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView back = new TextView(this);
        back.setText("‹ 返回软件");
        back.setTextSize(11);
        back.setSingleLine(true);
        back.setMaxLines(1);
        back.setTextColor(Color.parseColor("#20305e"));
        back.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(12));
        pill.setStroke(dp(1), Color.parseColor("#c9d6f0"));
        back.setBackground(pill);

        ImageView ball = new ImageView(this);
        ball.setImageDrawable(roundedBall(dp(116)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(58), dp(58));
        bp.topMargin = dp(6);
        ball.setLayoutParams(bp);

        ballBox.addView(back);
        ballBox.addView(ball);
        ballBox.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        final int pillH = Math.max(dp(20), back.getMeasuredHeight());

        ballParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = dp(2);
        ballParams.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.62);

        ballBox.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = ballParams.x;
                    startY = ballParams.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    downTime = System.currentTimeMillis();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    ballParams.x = startX + (int) (e.getRawX() - downX);
                    ballParams.y = startY + (int) (e.getRawY() - downY);
                    if (wm != null && ballBox.getParent() != null) {
                        wm.updateViewLayout(ballBox, ballParams);
                    }
                    return true;
                case MotionEvent.ACTION_UP: {
                    float dx = Math.abs(e.getRawX() - downX);
                    float dy = Math.abs(e.getRawY() - downY);
                    boolean tap = dx < dp(8) && dy < dp(8)
                            && System.currentTimeMillis() - downTime < 400;
                    if (tap) {
                        // 点在气泡条上 → 回 App；点在鱼上 → 抓单号
                        float localY = e.getRawY() - ballParams.y;
                        if (localY <= pillH + dp(2)) openApp();
                        else onBallTap();
                    }
                    return true;
                }
            }
            return false;
        });

        try {
            wm.addView(ballBox, ballParams);
        } catch (Exception e) {
            toast("悬浮球创建失败：" + e.getMessage());
        }
    }

    /** 把方图裁成圆角并描一圈边 */
    private android.graphics.drawable.Drawable roundedBall(int sizePx) {
        android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeResource(
                getResources(), R.drawable.ball);
        android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);

        float radius = sizePx * 0.28f;
        android.graphics.Path path = new android.graphics.Path();
        path.addRoundRect(new android.graphics.RectF(0, 0, sizePx, sizePx),
                radius, radius, android.graphics.Path.Direction.CW);

        android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        p.setFilterBitmap(true);
        c.save();
        c.clipPath(path);
        c.drawBitmap(src,
                new android.graphics.Rect(0, 0, src.getWidth(), src.getHeight()),
                new android.graphics.Rect(0, 0, sizePx, sizePx), p);
        c.restore();

        // 先描一圈白边（让图在深色背景上也分得开），再描深蓝细边
        android.graphics.Paint ring = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(android.graphics.Paint.Style.STROKE);
        ring.setStrokeWidth(sizePx * 0.075f);
        ring.setColor(Color.WHITE);
        c.drawPath(path, ring);

        ring.setStrokeWidth(sizePx * 0.032f);
        ring.setColor(Color.parseColor("#20305e"));
        c.drawPath(path, ring);

        return new android.graphics.drawable.BitmapDrawable(getResources(), out);
    }

    private void removeBall() {
        if (wm != null && ballBox != null && ballBox.getParent() != null) {
            try { wm.removeView(ballBox); } catch (Exception ignored) {}
        }
        ballBox = null;
        removeCard();
    }

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    /* ---------------- 抓单号 ---------------- */

    private void onBallTap() {
        String no = scanScreen();
        if (no == null) {
            toast("没找到单号，把物流详情往上滚一下再点");
            return;
        }
        showCard(no);
    }

    private String scanScreen() {
        AccessibilityNodeInfo root;
        try {
            root = getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
        if (root == null) return null;

        List<String[]> hits = new ArrayList<>(); // [单号, 上下文]
        collect(root, hits, 0, new int[]{0});

        String weak = null;
        for (String[] hit : hits) {
            String no = hit[0];
            String ctx = hit[1] == null ? "" : hit[1];
            if (TRACK_STRONG.matcher(no).matches() || TRACK_INTL.matcher(no).matches()) {
                return no;
            }
            if (weak == null && KEYWORD.matcher(ctx).find()) weak = no;
        }
        return weak;
    }

    private void collect(AccessibilityNodeInfo node, List<String[]> out, int depth, int[] budget) {
        if (node == null || depth > 60 || budget[0] > 2000) return;
        budget[0]++;

        String text = textOf(node);
        if (text != null && !text.isEmpty()) {
            Set<String> found = new LinkedHashSet<>();
            Matcher m = TRACK_STRONG.matcher(text);
            while (m.find()) found.add(m.group(1).toUpperCase());
            m = TRACK_INTL.matcher(text);
            while (m.find()) found.add(m.group(1).toUpperCase());
            m = TRACK_WEAK.matcher(text);
            while (m.find()) found.add(m.group(1));

            if (!found.isEmpty()) {
                StringBuilder ctx = new StringBuilder(text);
                AccessibilityNodeInfo p = node.getParent();
                for (int i = 0; p != null && i < 2; i++) {
                    ctx.append(' ').append(textOf(p) == null ? "" : textOf(p));
                    p = p.getParent();
                }
                for (String no : found) out.add(new String[]{no, ctx.toString()});
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child;
            try {
                child = node.getChild(i);
            } catch (Exception e) {
                continue;
            }
            if (child != null) collect(child, out, depth + 1, budget);
        }
    }

    private static String textOf(AccessibilityNodeInfo n) {
        if (n == null) return "";
        CharSequence t = n.getText();
        if (t == null) t = n.getContentDescription();
        return t == null ? "" : t.toString().trim();
    }

    /* ---------------- 确认卡片 ---------------- */

    private void showCard(final String no) {
        removeCard();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#d5dff2"));
        box.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("抓到单号");
        title.setTextSize(12);
        title.setTextColor(Color.parseColor("#7c879b"));

        TextView num = new TextView(this);
        num.setText(no);
        num.setTextSize(18);
        num.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        num.setTextColor(Color.parseColor("#1b2a6b"));
        num.setPadding(0, dp(4), 0, dp(12));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        TextView add = new TextView(this);
        add.setText("添加到包裹");
        add.setTextSize(14);
        add.setTextColor(Color.WHITE);
        add.setGravity(Gravity.CENTER);
        add.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable addBg = new GradientDrawable();
        addBg.setColor(Color.parseColor("#3d7eff"));
        addBg.setCornerRadius(dp(10));
        add.setBackground(addBg);
        add.setOnClickListener(v -> {
            removeCard();
            MainActivity.PENDING_ADD = "{\"no\":\"" + no + "\",\"source\":\"" + esc(sourceApp()) + "\"}";
            openApp();
        });

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(Color.parseColor("#7c879b"));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(18), dp(10), dp(18), dp(10));
        cancel.setOnClickListener(v -> removeCard());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        row.addView(add, lp);
        row.addView(cancel);

        box.addView(title);
        box.addView(num);
        box.addView(row);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(220),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(12);
        p.y = Math.max(dp(80), (int) (getResources().getDisplayMetrics().heightPixels * 0.42));

        try {
            wm.addView(box, p);
            card = box;
        } catch (Exception e) {
            toast("弹卡片失败：" + e.getMessage());
        }

        ui.postDelayed(this::removeCard, 15000);
    }

    private void removeCard() {
        if (card != null && card.getParent() != null && wm != null) {
            try { wm.removeView(card); } catch (Exception ignored) {}
        }
        card = null;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String sourceApp() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        CharSequence pkg = root.getPackageName();
        if (pkg == null) return "";
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(pkg.toString(), 0)).toString();
        } catch (Exception e) {
            return pkg.toString();
        }
    }

    /* ---------------- 首次提示 ---------------- */

    private void maybeShowHint() {
        if (getSharedPreferences("parcel", MODE_PRIVATE).getBoolean("hint_shown", false)) return;
        getSharedPreferences("parcel", MODE_PRIVATE).edit().putBoolean("hint_shown", true).apply();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(14), dp(12), dp(14), dp(12));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#d5dff2"));
        box.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ball);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(34), dp(34));
        ip.rightMargin = dp(10);
        box.addView(icon, ip);

        TextView tv = new TextView(this);
        tv.setText("找到待收货包裹，在有快递单号的页面，点击大肥鱼添加包裹");
        tv.setTextSize(12);
        tv.setTextColor(Color.parseColor("#3b4658"));
        box.addView(tv);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(280),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = dp(90);

        try {
            wm.addView(box, p);
            ui.postDelayed(() -> {
                if (box.getParent() != null) {
                    try { wm.removeView(box); } catch (Exception ignored) {}
                }
            }, 8000);
        } catch (Exception ignored) {}
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
