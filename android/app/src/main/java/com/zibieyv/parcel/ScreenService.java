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
import android.graphics.Bitmap;
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
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

/**
 * 悬浮球 + 截屏 OCR。
 *
 * 完全不依赖无障碍：截屏走 MediaProjection（App 启动时授权一次），
 * 文字识别走本地 ML Kit，图片只在内存里过一遍，不落盘。
 */
public class ScreenService extends Service {

    private static final String CHANNEL_ID = "parcel_screen";
    private static final int NOTIFY_ID = 2001;
    private static final String EXTRA_CODE = "code";
    private static final String EXTRA_DATA = "data";

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private int screenW, screenH, screenDpi;

    private WindowManager wm;
    private LinearLayout ballBox;
    private ImageView ballView;
    private ImageView cardIcon;
    private LinearLayout cardView;
    private boolean expanded = true;
    private int cardWidthPx = 0;
    private WindowManager.LayoutParams ballParams;
    private View resultCard;
    private boolean ballVisible = false;

    private int startX, startY;
    private float downX, downY;
    private long downTime;
    private boolean dragging;

    private TextRecognizer recognizer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private BroadcastReceiver toggleReceiver;

    /* ---------------- 生命周期 ---------------- */

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        createChannel();

        toggleReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                syncBallVisibility();
            }
        };
        IntentFilter f = new IntentFilter(BallPrefs.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(toggleReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(toggleReceiver, f);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFY_ID, buildNotification());

        if (intent != null && intent.hasExtra(EXTRA_CODE) && projection == null) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (code == android.app.Activity.RESULT_OK && data != null) {
                try {
                    android.media.projection.MediaProjectionManager mgr =
                            (android.media.projection.MediaProjectionManager)
                                    getSystemService(MEDIA_PROJECTION_SERVICE);
                    projection = mgr.getMediaProjection(code, data);
                    setupCapture();
                } catch (Exception e) {
                    toast("截屏授权失败：" + e.getMessage());
                }
            }
        }

        syncBallVisibility();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        hideBall();
        removeResultCard();
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
        }
        if (imageReader != null) {
            try { imageReader.close(); } catch (Exception ignored) {}
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
        }
        if (toggleReceiver != null) {
            try { unregisterReceiver(toggleReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    /* ---------------- 截屏通道 ---------------- */

    private void setupCapture() {
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        screenDpi = dm.densityDpi;

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                toast("截屏授权已结束，重新打开 App 授权一次");
                stopSelf();
            }
        }, ui);

        virtualDisplay = projection.createVirtualDisplay(
                "parcel-capture", screenW, screenH, screenDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, ui);
    }

    private void captureAndRecognize() {
        if (imageReader == null) {
            toast("还没拿到截屏授权，打开 App 授权一次");
            openApp();
            return;
        }
        Image image = null;
        try {
            image = imageReader.acquireLatestImage();
        } catch (Exception ignored) {}
        if (image == null) {
            toast("画面还没准备好，再点一次");
            return;
        }

        Bitmap bmp = null;
        try {
            Image.Plane plane = image.getPlanes()[0];
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int width = screenW + (rowStride - pixelStride * screenW) / pixelStride;

            Bitmap raw = Bitmap.createBitmap(width, screenH, Bitmap.Config.ARGB_8888);
            raw.copyPixelsFromBuffer(plane.getBuffer());
            bmp = Bitmap.createBitmap(raw, 0, 0, screenW, screenH);
        } catch (Exception e) {
            toast("取图失败：" + e.getMessage());
        } finally {
            try { image.close(); } catch (Exception ignored) {}
        }
        if (bmp == null) return;

        toast("识别中…");
        final Bitmap target = bmp;
        recognizer.process(InputImage.fromBitmap(target, 0))
                .addOnSuccessListener(result -> {
                    String no = CodeParser.findTracking(result.getText());
                    if (no == null) no = CodeParser.find(result.getText());
                    if (no == null) {
                        toast("这张图里没找到单号");
                        recycle(target);
                        return;
                    }
                    showResultCard(no);
                    recycle(target);
                })
                .addOnFailureListener(e -> {
                    toast("识别失败：" + e.getMessage());
                    recycle(target);
                });
    }

    private void recycle(Bitmap b) {
        try { if (b != null && !b.isRecycled()) b.recycle(); } catch (Exception ignored) {}
    }

    /* ---------------- 悬浮球 ---------------- */

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void syncBallVisibility() {
        if (BallPrefs.isEnabled(this)) showBall();
        else hideBall();
    }

    private void showBall() {
        if (ballVisible || ballBox != null) return;

        ballBox = new LinearLayout(this);
        ballBox.setOrientation(LinearLayout.VERTICAL);
        ballBox.setGravity(Gravity.START);

        TextView pill = new TextView(this);
        pill.setText("‹ 返回软件");
        pill.setTextSize(9.5f);
        pill.setSingleLine(true);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(2), dp(4), dp(2), dp(4));
        pill.setTextColor(Color.parseColor("#20305e"));
        pill.setLayoutParams(new LinearLayout.LayoutParams(dp(58),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.WHITE);
        pillBg.setCornerRadius(dp(13));
        pillBg.setStroke(dp(1), Color.parseColor("#c9d6f0"));
        pill.setBackground(pillBg);

        ballView = new ImageView(this);
        ballView.setImageDrawable(roundedBall(dp(116)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(58), dp(58));
        bp.topMargin = dp(6);
        ballView.setLayoutParams(bp);

        cardView = buildHintCard();

        ballBox.addView(pill);
        ballBox.addView(ballView);
        ballBox.addView(cardView);

        ballParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        ballParams.gravity = Gravity.TOP | Gravity.START;
        ballParams.x = dp(2);
        ballParams.y = (int) (getResources().getDisplayMetrics().heightPixels * 0.55);

        ballBox.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    startX = ballParams.x;
                    startY = ballParams.y;
                    downX = e.getRawX();
                    downY = e.getRawY();
                    downTime = System.currentTimeMillis();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        dragging = Math.abs(e.getRawX() - downX) > dp(8)
                                || Math.abs(e.getRawY() - downY) > dp(8);
                    }
                    // 展开时锁住，先点一下收起成球才能拖
                    if (expanded) return true;
                    ballParams.x = startX + (int) (e.getRawX() - downX);
                    ballParams.y = startY + (int) (e.getRawY() - downY);
                    clamp();
                    try { wm.updateViewLayout(ballBox, ballParams); } catch (Exception ignored) {}
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!dragging && System.currentTimeMillis() - downTime < 400) {
                        handleTap(e.getRawX(), e.getRawY());
                    }
                    dragging = false;
                    return true;
            }
            return false;
        });

        try {
            wm.addView(ballBox, ballParams);
            ballVisible = true;
        } catch (Exception e) {
            toast("悬浮球创建失败：" + e.getMessage());
            ballBox = null;
            return;
        }
        setExpanded(true);
    }

    private LinearLayout buildHintCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), Color.parseColor("#d5dff2"));
        card.setBackground(bg);

        int screenW = getResources().getDisplayMetrics().widthPixels;
        cardWidthPx = Math.min(dp(360), screenW - dp(8));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                cardWidthPx, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(6);
        card.setLayoutParams(clp);

        cardIcon = new ImageView(this);
        cardIcon.setImageDrawable(roundedBall(dp(76)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(38), dp(38));
        ip.rightMargin = dp(8);
        card.addView(cardIcon, ip);

        TextView tv = new TextView(this);
        tv.setTextSize(10.5f);
        tv.setLineSpacing(dp(3), 1f);
        tv.setText(highlight(
                "找到待收货包裹，在有快递单号的页面，点击大肥鱼添加包裹，点击此处收起提示以拖动肥鱼"));
        card.addView(tv);
        return card;
    }

    private CharSequence highlight(String text) {
        android.text.SpannableString ss = new android.text.SpannableString(text);
        int orange = Color.parseColor("#F5822B");
        for (String kw : new String[]{"待收货包裹", "快递单号", "大肥鱼", "收起提示", "拖动肥鱼"}) {
            int from = 0;
            while (true) {
                int i = text.indexOf(kw, from);
                if (i < 0) break;
                ss.setSpan(new android.text.style.ForegroundColorSpan(orange), i, i + kw.length(),
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                from = i + kw.length();
            }
        }
        return ss;
    }

    private void setExpanded(boolean value) {
        expanded = value;
        if (ballView != null) ballView.setVisibility(value ? View.GONE : View.VISIBLE);
        if (cardView != null) cardView.setVisibility(value ? View.VISIBLE : View.GONE);
        clamp();
        if (ballBox != null && ballBox.getParent() != null) {
            try { wm.updateViewLayout(ballBox, ballParams); } catch (Exception ignored) {}
        }
        ui.removeCallbacks(collapseTask);
        if (value) ui.postDelayed(collapseTask, 12000);
    }

    private final Runnable collapseTask = () -> setExpanded(false);

    private void handleTap(float rawX, float rawY) {
        View[] targets = new View[]{ballView, cardIcon, cardView};
        for (View v : targets) {
            if (v == null || !v.isShown()) continue;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            Rect r = new Rect(loc[0], loc[1], loc[0] + v.getWidth(), loc[1] + v.getHeight());
            if (!r.contains((int) rawX, (int) rawY)) continue;

            if (v == ballView) {
                setExpanded(true);
            } else if (v == cardIcon) {
                captureAndRecognize();
            } else {
                setExpanded(false);
            }
            return;
        }
        // 没命中球/卡片，落在顶部那条「返回软件」上
        float localY = rawY - ballParams.y;
        if (localY <= dp(28)) openApp();
    }

    private void hideBall() {
        if (ballBox != null && ballBox.getParent() != null) {
            try { wm.removeView(ballBox); } catch (Exception ignored) {}
        }
        ballBox = null;
        ballView = null;
        cardView = null;
        cardIcon = null;
        ballVisible = false;
    }

    private void clamp() {
        int w = expanded ? (cardWidthPx > 0 ? cardWidthPx : dp(360)) : dp(58);
        int h = expanded ? dp(140) : dp(86);
        int maxX = Math.max(0, getResources().getDisplayMetrics().widthPixels - w);
        int maxY = Math.max(0, getResources().getDisplayMetrics().heightPixels - h);
        ballParams.x = Math.max(0, Math.min(ballParams.x, maxX));
        ballParams.y = Math.max(0, Math.min(ballParams.y, maxY));
    }

    /* ---------------- 结果卡片 ---------------- */

    private void showResultCard(final String no) {
        removeResultCard();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.parseColor("#d5dff2"));
        box.setBackground(bg);

        TextView title = new TextView(this);
        title.setText("截图里找到单号");
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
            removeResultCard();
            MainActivity.PENDING_ADD = "{\"no\":\"" + no + "\",\"source\":\"截屏识别\"}";
            openApp();
        });

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(Color.parseColor("#7c879b"));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(18), dp(10), dp(18), dp(10));
        cancel.setOnClickListener(v -> removeResultCard());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        row.addView(add, lp);
        row.addView(cancel);

        box.addView(title);
        box.addView(num);
        box.addView(row);

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                dp(224), LinearLayout.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(12);
        p.y = Math.max(dp(80), (int) (getResources().getDisplayMetrics().heightPixels * 0.42));

        try {
            wm.addView(box, p);
            resultCard = box;
        } catch (Exception e) {
            toast("卡片显示失败：" + e.getMessage());
        }
        ui.postDelayed(this::removeResultCard, 15000);
    }

    private void removeResultCard() {
        if (resultCard != null && resultCard.getParent() != null) {
            try { wm.removeView(resultCard); } catch (Exception ignored) {}
        }
        resultCard = null;
    }

    /* ---------------- 杂项 ---------------- */

    private void openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
    }

    private Drawable roundedBall(int sizePx) {
        Bitmap src = android.graphics.BitmapFactory.decodeResource(getResources(), R.drawable.ball);
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

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "包裹截屏", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("包裹")
                .setContentText("悬浮球待命中 · 点球截图识单号")
                .setSmallIcon(R.drawable.ball)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi);
        return b.build();
    }

    private void toast(String msg) {
        ui.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }
}
