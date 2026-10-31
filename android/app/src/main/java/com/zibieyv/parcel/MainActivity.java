package com.zibieyv.parcel;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class MainActivity extends Activity implements Bridge.Sink {

    private static final String HOME = "https://parcel.zibieyv.com";

    /** 读屏抓到的单号，等网页来取 */
    public static volatile String PENDING_ADD = "";

    private WebView web;
    private volatile boolean pageReady = false;
    private long downloadingId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " ParcelApp/" + BuildConfig.VERSION_NAME);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                Bridge.attach(MainActivity.this);
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new NativeApi(), "Native");
        web.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(web);
        web.loadUrl(HOME);

        askSmsPermission();
    }

    private void askSmsPermission() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECEIVE_SMS}, 1001);
        }
    }

    /* ---------------- 原生 → 网页 ---------------- */

    @Override
    public void onPickupCode(String code, String tracking, String source) {
        final String js = "window.__onPickupCode && window.__onPickupCode("
                + jsStr(code) + "," + jsStr(tracking) + "," + jsStr(source) + ")";
        runOnUiThread(() -> {
            if (pageReady) web.evaluateJavascript(js, null);
        });
    }

    private static String jsStr(String v) {
        if (v == null) return "''";
        return "'" + v.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ") + "'";
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // 从读屏悬浮球跳回来时，通知网页去取单号
        runOnUiThread(() -> {
            if (pageReady && web != null) {
                web.evaluateJavascript(
                        "window.__checkPendingAdd && window.__checkPendingAdd()", null);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Bridge.attach(this);
    }

    @Override
    protected void onPause() {
        Bridge.detach(this);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && web != null) {
            web.setVerticalScrollBarEnabled(false);
            web.setHorizontalScrollBarEnabled(false);
        }
    }

    /* ---------------- 下载更新 ---------------- */

    private void pollDownload(DownloadManager dm, long id, int tries) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (tries > 240) return; // 最多等 4 分钟
            Cursor c = null;
            try {
                c = dm.query(new DownloadManager.Query().setFilterById(id));
                if (c != null && c.moveToFirst()) {
                    int status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        Uri file = dm.getUriForDownloadedFile(id);
                        if (file != null) launchInstaller(file);
                        return;
                    }
                    if (status == DownloadManager.STATUS_FAILED) {
                        toast("下载失败，请检查网络");
                        return;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
            pollDownload(dm, id, tries + 1);
        }, 1000);
    }

    private void launchInstaller(Uri apkUri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(apkUri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            // 没开「安装未知应用」权限，引导去开
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                toast("请允许「包裹」安装应用，回来再点一次更新");
            } catch (Exception ignored) {
                toast("无法启动安装器");
            }
        }
    }

    private void toast(String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    /* ---------------- 网页可调用的能力 ---------------- */

    public class NativeApi {

        @JavascriptInterface
        public String version() {
            return BuildConfig.VERSION_NAME;
        }

        @JavascriptInterface
        public int versionCode() {
            return BuildConfig.VERSION_CODE;
        }

        /** 下载新版 APK 并拉起系统安装器 */
        @JavascriptInterface
        public void installUpdate(String url) {
            if (url == null || url.isEmpty()) return;
            runOnUiThread(() -> {
                try {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm == null) return;

                    Uri uri = Uri.parse(url.startsWith("http") ? url : HOME + url);
                    DownloadManager.Request req = new DownloadManager.Request(uri);
                    req.setTitle("包裹 App 更新");
                    req.setDescription("正在下载新版本…");
                    req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    req.setDestinationInExternalFilesDir(MainActivity.this,
                            Environment.DIRECTORY_DOWNLOADS, "parcel-update.apk");
                    req.setMimeType("application/vnd.android.package-archive");
                    downloadingId = dm.enqueue(req);
                    toast("开始下载，完成后会自动弹出安装");
                    pollDownload(dm, downloadingId, 0);
                } catch (Exception e) {
                    toast("下载失败：" + e.getMessage());
                }
            });
        }

        /** 打开系统的「通知使用权」设置页，用于开启取件码自动抓取 */
        @JavascriptInterface
        public void openNotificationSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        Intent i = new Intent(Settings.ACTION_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ignored) {}
                }
            });
        }

        /** 通知监听有没有开 */
        @JavascriptInterface
        public boolean notificationAccessEnabled() {
            String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return flat != null && flat.contains(getPackageName());
        }

        /** 网页来取读屏抓到的单号，取一次就清空 */
        @JavascriptInterface
        public String consumePendingAdd() {
            String s = PENDING_ADD;
            PENDING_ADD = "";
            return s;
        }

        /** 读屏（无障碍）有没有开 */
        @JavascriptInterface
        public boolean accessibilityEnabled() {
            try {
                String flat = Settings.Secure.getString(getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (flat == null) return false;
                return flat.contains(getPackageName());
            } catch (Exception e) {
                return false;
            }
        }

        /** 打开无障碍设置页，让用户给「包裹」开读屏 */
        @JavascriptInterface
        public void openAccessibilitySettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }

        /** 打开应用详情页，方便改短信权限 */
        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName()));
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                } catch (Exception ignored) {}
            });
        }
    }
}
