package com.zibieyv.parcel;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读屏抓单号 + 可展开的悬浮窗。
 *
 * 收起态：只有一个大肥鱼头像，点它展开；
 * 展开态：上面「‹ 返回软件」，下面白卡片带提示文字，点卡片里的鱼才去识屏。
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

    private static final String HINT = "找到待收货包裹，在有快递单号的页面，点击大肥鱼添加包裹";

    private WindowManager wm;
    private LinearLayout root;
    private WindowManager.LayoutParams params;
    private View cardResult;
    private boolean expanded = false;

    private int startX, startY;
    private float downX, downY;
    private long downTime;

    /** 点哪个控件做什么 */
    private final Map<View, Runnable> tapActions = new LinkedHashMap<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable collapseTask = this::collapse;

    /* ---------------- 生命周期 ---------------- */

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildRoot();
        // 首次连上先展开提示，8 秒后自动收起
        ui.postDelayed(this::collapse, 8000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 点悬浮球时再扫屏，省电
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (wm != null && root != null && root.getParent() != null) {
            try { wm.removeView(root); } catch (Exception ignored) {}
        }
        removeCard();
        super.onDestroy();
    }

    /* ---------------- 布局 ---------------- */

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildRoot() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = dp(2);
        params.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.55);

        root.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = params.x;
                        startY = params.y;
                        downX = e.getRawX();
                        downY = e.getRawY();
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = startX + (int) (e.getRawX() - downX);
                        params.y = startY + (int) (e.getRawY() - downY);
                        if (root.getParent() != null) {
                            try { wm.updateViewLayout(root, params); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dx = Math.abs(e.getRawX() - downX);
                        float dy = Math.abs(e.getRawY() - downY);
                        if (dx < dp(8) && dy < dp(8)
                                && System.currentTimeMillis() - downTime < 400) {
                            handleTap(e.getRawX(), e.getRawY());
                        }
                        return true;
                }
                return false;
            }
        });

        try {
            wm.addView(root, params);
        } catch (Exception e) {
            toast("悬浮窗创建失败：" + e.getMessage());
            return;
        }
        expanded = true;
        render();
    }

    private void render() {
        tapActions.clear();
        root.removeAllViews();

        root.addView(buildPill());
        root.addView(expanded ? buildHintCard() : buildBall());

        root.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        if (root.getParent() != null) {
            try { wm.updateViewLayout(root, params); } catch (Exception ignored) {}
        }
    }

    private View buildPill() {
        TextView back = new TextView(this);
        back.setText("‹ 返回软件");
        back.setTextSize(11);
        back.setSingleLine(true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(dp(12), dp(5), dp(12), dp(5));
        back.setTextColor(Color.parseColor("#20305e"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        back.setLayoutParams(lp);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(Color.WHITE);
        pill.setCornerRadius(dp(13));
        pill.setStroke(dp(1), Color.parseColor("#c9d6f0"));
        back.setBackground(pill);
        tapActions.put(back, this::openApp);
        return back;
    }

    private View buildBall() {
        ImageView ball = new ImageView(this);
        ball.setImageDrawable(roundedBall(dp(116)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(58), dp(58));
        bp.topMargin = dp(6);
        ball.setLayoutParams(bp);
        tapActions.put(ball, this::expand);
        return ball;
    }

    private View buildHintCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(8), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#d5dff2"));
        card.setBackground(bg);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                dp(268), LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(6);
        card.setLayoutParams(clp);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(roundedBall(dp(76)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(38), dp(38));
        ip.rightMargin = dp(8);
        card.addView(icon, ip);
        tapActions.put(icon, this::onBallTap);

        TextView tv = new TextView(this);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(2), 1f);
        tv.setText(highlight(HINT));
        card.addView(tv);

        // 空白处点一下也当作"抓单号"，跟点鱼一样
        tapActions.put(card, this::onBallTap);
        tapActions.put(tv, this::onBallTap);
        return card;
    }

    /** 把提示里的关键词染成橙色，跟菜鸟一样 */
    private CharSequence highlight(String text) {
        SpannableString ss = new SpannableString(text);
        int orange = Color.parseColor("#F5822B");
        for (String kw : new String[]{"待收货包裹", "快递单号", "大肥鱼"}) {
            int i = text.indexOf(kw);
            if (i >= 0) {
                ss.setSpan(new ForegroundColorSpan(orange), i, i + kw.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return ss;
    }

    /* ---------------- 点击分发 ---------------- */

    private void handleTap(float rawX, float rawY) {
        View best = null;
        int bestArea = Integer.MAX_VALUE;
        for (View v : tapActions.keySet()) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
            if (r.contains((int) rawX, (int) rawY)) {
                int area = v.getWidth() * v.getHeight();
                if (area < bestArea) {
                    bestArea = area;
                    best = v;
                }
            }
        }
        if (best != null) {
            Runnable action = tapActions.get(best);
            if (action != null) action.run();
        }
    }

    private void expand() {
        expanded = true;
        render();
        ui.removeCallbacks(collapseTask);
        ui.postDelayed(collapseTask, 12000);
    }

    private void collapse() {
        if (root == null) return;
        expanded = false;
        render();
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
        showResultCard(no);
    }

    private String scanScreen() {
        AccessibilityNodeInfo rootNode;
        try {
            rootNode = getRootInActiveWindow();
        } catch (Exception e) {
            return null;
        }
        if (rootNode == null) return null;

        List<String[]> hits = new ArrayList<>();
        collect(rootNode, hits, 0, new int[]{0});

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
                    ctx.append(' ').append(textOf(p));
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

    /* ---------------- 结果卡片 ---------------- */

    private void showResultCard(final String no) {
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
                dp(224),
                LinearLayout.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(12);
        p.y = Math.max(dp(80), (int) (getResources().getDisplayMetrics().heightPixels * 0.42));

        try {
            wm.addView(box, p);
            cardResult = box;
        } catch (Exception e) {
            toast("弹卡片失败：" + e.getMessage());
        }

        ui.postDelayed(this::removeCard, 15000);
    }

    private void removeCard() {
        if (cardResult != null && cardResult.getParent() != null && wm != null) {
            try { wm.removeView(cardResult); } catch (Exception ignored) {}
        }
        cardResult = null;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String sourceApp() {
        AccessibilityNodeInfo r = getRootInActiveWindow();
        if (r == null || r.getPackageName() == null) return "";
        try {
            return getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(r.getPackageName().toString(), 0)).toString();
        } catch (Exception e) {
            return r.getPackageName().toString();
        }
    }

    /* ---------------- 图标处理 ---------------- */

    /** 方图 → 圆角 + 白边 + 深蓝描边 */
    private Drawable roundedBall(int sizePx) {
        Bitmap src = BitmapFactory.decodeResource(getResources(), R.drawable.ball);
        Bitmap out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);

        float radius = sizePx * 0.28f;
        Path path = new Path();
        path.addRoundRect(new RectF(0, 0, sizePx, sizePx), radius, radius, Path.Direction.CW);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setFilterBitmap(true);
        c.save();
        c.clipPath(path);
        c.drawBitmap(src, new Rect(0, 0, src.getWidth(), src.getHeight()),
                new Rect(0, 0, sizePx, sizePx), p);
        c.restore();

        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(sizePx * 0.075f);
        ring.setColor(Color.WHITE);
        c.drawPath(path, ring);
        ring.setStrokeWidth(sizePx * 0.032f);
        ring.setColor(Color.parseColor("#20305e"));
        c.drawPath(path, ring);

        return new BitmapDrawable(getResources(), out);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
