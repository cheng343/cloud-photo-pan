package top.czyxh.pan;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 2001;
    private static final int REQ_PICK_MANUAL_UPLOAD = 2002;
    private static final int REQ_WEBVIEW_FILE_CHOOSER = 2003;

    private WebView galleryWebView;
    private WebView alistWebView;
    private boolean alistLoaded = false;

    private LinearLayout tabBtnPhotos;
    private LinearLayout tabBtnFiles;
    private LinearLayout tabBtnBackup;
    private TextView tabIconPhotos, tabTextPhotos;
    private TextView tabIconFiles, tabTextFiles;
    private TextView tabIconBackup, tabTextBackup;

    private String currentTab = "photos";
    private String manualUploadTargetFolder = "/GoogleDrive/photos";
    private ValueCallback<Uri[]> webViewFilePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#0B0F17"));
        window.setNavigationBarColor(Color.parseColor("#111726"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F17"));

        FrameLayout contentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
        );

        galleryWebView = new WebView(this);
        configureWebView(galleryWebView, false);
        galleryWebView.addJavascriptInterface(new NativeBridge(), "NativeApp");
        galleryWebView.loadUrl("file:///android_asset/index.html");

        alistWebView = new WebView(this);
        configureWebView(alistWebView, true);
        alistWebView.setVisibility(View.GONE);

        contentFrame.addView(galleryWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ));
        contentFrame.addView(alistWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ));

        root.addView(contentFrame, frameLp);

        // Divider line
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1E293B"));
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // Native Bottom Navigation Bar
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#111726"));
        bottomBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)
        );

        tabBtnPhotos = createTabItem("🖼️", "云相册", true);
        tabIconPhotos = (TextView) tabBtnPhotos.getChildAt(0);
        tabTextPhotos = (TextView) tabBtnPhotos.getChildAt(1);
        tabBtnPhotos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("photos");
            }
        });

        tabBtnFiles = createTabItem("📁", "全部文件", false);
        tabIconFiles = (TextView) tabBtnFiles.getChildAt(0);
        tabTextFiles = (TextView) tabBtnFiles.getChildAt(1);
        tabBtnFiles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("files");
            }
        });

        tabBtnBackup = createTabItem("☁️", "自动备份", false);
        tabIconBackup = (TextView) tabBtnBackup.getChildAt(0);
        tabTextBackup = (TextView) tabBtnBackup.getChildAt(1);
        tabBtnBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("backup");
            }
        });

        bottomBar.addView(tabBtnPhotos, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        bottomBar.addView(tabBtnFiles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        bottomBar.addView(tabBtnBackup, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        root.addView(bottomBar, navLp);
        setContentView(root);

        requestMediaPermissionsIfNeeded();
        if (BackupState.get(this).isAutoBackupEnabled()) {
            startBackupService(PhotoBackupService.ACTION_START_WATCHER, null);
        }
    }

    private LinearLayout createTabItem(String emoji, String label, boolean active) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(6), 0, dp(6));

        TextView icon = new TextView(this);
        icon.setText(emoji);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        icon.setGravity(Gravity.CENTER);

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        txt.setGravity(Gravity.CENTER);
        txt.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        txt.setTextColor(Color.parseColor(active ? "#38BDF8" : "#94A3B8"));

        item.addView(icon);
        item.addView(txt);
        return item;
    }

    public void selectTab(final String tab) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                currentTab = tab;
                boolean isPhotos = "photos".equals(tab);
                boolean isFiles = "files".equals(tab);
                boolean isBackup = "backup".equals(tab);

                tabTextPhotos.setTextColor(Color.parseColor(isPhotos ? "#38BDF8" : "#94A3B8"));
                tabTextPhotos.setTypeface(null, isPhotos ? Typeface.BOLD : Typeface.NORMAL);

                tabTextFiles.setTextColor(Color.parseColor(isFiles ? "#38BDF8" : "#94A3B8"));
                tabTextFiles.setTypeface(null, isFiles ? Typeface.BOLD : Typeface.NORMAL);

                tabTextBackup.setTextColor(Color.parseColor(isBackup ? "#38BDF8" : "#94A3B8"));
                tabTextBackup.setTypeface(null, isBackup ? Typeface.BOLD : Typeface.NORMAL);

                if (isFiles) {
                    if (!alistLoaded) {
                        alistLoaded = true;
                        alistWebView.loadUrl("https://pan.czyxh.top/GoogleDrive");
                    }
                    galleryWebView.setVisibility(View.GONE);
                    alistWebView.setVisibility(View.VISIBLE);
                } else {
                    alistWebView.setVisibility(View.GONE);
                    galleryWebView.setVisibility(View.VISIBLE);
                    galleryWebView.evaluateJavascript("window.onNativeTabChanged && window.onNativeTabChanged('" + tab + "');", null);
                }
            }
        });
    }

    private void configureWebView(WebView wv, final boolean isAlist) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setLoadWithOverviewMode(true);
        ws.setUseWideViewPort(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 CloudPhotoPanApp/1.0");

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);

        wv.setBackgroundColor(Color.parseColor("#0B0F17"));

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://") || u.startsWith("file://")) {
                    return false;
                }
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, request.getUrl());
                    startActivity(intent);
                } catch (Exception ignored) {}
                return true;
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (webViewFilePathCallback != null) {
                    webViewFilePathCallback.onReceiveValue(null);
                }
                webViewFilePathCallback = filePathCallback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(intent, "选择要上传的文件或照片"), REQ_WEBVIEW_FILE_CHOOSER);
                } catch (Exception e) {
                    webViewFilePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        wv.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimeType, long contentLength) {
                String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                enqueueSystemDownload(url, filename);
            }
        });
    }

    private void enqueueSystemDownload(String url, String filename) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(filename);
            req.setDescription("正在通过 Cloudflare 边缘节点极速下载...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.addRequestHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0.0.0 Mobile Safari/537.36");
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(this, "🚀 已加入系统下载队列: " + filename, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(i);
            } catch (Exception ignored) {}
        }
    }

    private boolean hasPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= 23) {
            return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private void requestMediaPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            List<String> perms = new ArrayList<>();
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            }
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!perms.isEmpty()) {
                requestPermissions(perms.toArray(new String[0]), REQ_PERMS);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQ_PERMS);
            }
        }
    }

    private void startBackupService(String action, String targetFolder) {
        Intent i = new Intent(this, PhotoBackupService.class);
        i.setAction(action);
        if (targetFolder != null) {
            i.putExtra(PhotoBackupService.EXTRA_TARGET_FOLDER, targetFolder);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_WEBVIEW_FILE_CHOOSER) {
            if (webViewFilePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    results = new Uri[clip.getItemCount()];
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        results[i] = clip.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            webViewFilePathCallback.onReceiveValue(results);
            webViewFilePathCallback = null;
        } else if (requestCode == REQ_PICK_MANUAL_UPLOAD) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                List<Uri> picked = new ArrayList<>();
                if (data.getClipData() != null) {
                    ClipData clip = data.getClipData();
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        picked.add(clip.getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    picked.add(data.getData());
                }
                if (!picked.isEmpty()) {
                    synchronized (PhotoBackupService.pendingManualUris) {
                        PhotoBackupService.pendingManualUris.addAll(picked);
                    }
                    Toast.makeText(this, "⚡ 开始极速直传 " + picked.size() + " 个文件至 R2...", Toast.LENGTH_SHORT).show();
                    startBackupService(PhotoBackupService.ACTION_UPLOAD_URIS, manualUploadTargetFolder);
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        if ("files".equals(currentTab) && alistWebView.canGoBack()) {
            alistWebView.goBack();
            return;
        }
        if (!"photos".equals(currentTab)) {
            selectTab("photos");
            return;
        }
        super.onBackPressed();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public class NativeBridge {
        @JavascriptInterface
        public boolean hasMediaPermissions() {
            return hasPermissions();
        }

        @JavascriptInterface
        public void requestPermissionsFromJs() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestMediaPermissionsIfNeeded();
                }
            });
        }

        @JavascriptInterface
        public String getBackupStateJson() {
            return BackupState.get(MainActivity.this).toJsonString();
        }

        @JavascriptInterface
        public String getPhoneAlbumsJson() {
            Map<String, Integer> bucketCounts = new LinkedHashMap<>();
            bucketCounts.put("Camera", 0);
            bucketCounts.put("Screenshots", 0);
            countBuckets(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, bucketCounts);
            countBuckets(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, bucketCounts);

            Set<String> selected = BackupState.get(MainActivity.this).getSelectedBuckets();
            JSONArray arr = new JSONArray();
            try {
                for (Map.Entry<String, Integer> entry : bucketCounts.entrySet()) {
                    JSONObject o = new JSONObject();
                    o.put("bucket", entry.getKey());
                    o.put("count", entry.getValue());
                    o.put("selected", selected.contains(entry.getKey()) || selected.contains("ALL"));
                    arr.put(o);
                }
            } catch (Exception ignored) {}
            return arr.toString();
        }

        private void countBuckets(Uri uri, Map<String, Integer> map) {
            if (!hasPermissions()) return;
            Cursor c = null;
            try {
                c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.BUCKET_DISPLAY_NAME}, null, null, null);
                if (c != null) {
                    int idx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
                    while (c.moveToNext()) {
                        String b = c.getString(idx);
                        if (b == null || b.isEmpty()) b = "Default";
                        Integer cur = map.get(b);
                        map.put(b, cur == null ? 1 : cur + 1);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.close();
            }
        }

        @JavascriptInterface
        public void saveBackupConfig(boolean autoEnabled, boolean wifiOnly, boolean includeVideos, String targetFolder, String bucketsJson) {
            Set<String> buckets = new HashSet<>();
            try {
                JSONArray arr = new JSONArray(bucketsJson);
                for (int i = 0; i < arr.length(); i++) {
                    buckets.add(arr.getString(i));
                }
            } catch (Exception ignored) {}
            BackupState.get(MainActivity.this).saveConfig(autoEnabled, wifiOnly, includeVideos, targetFolder, buckets);
            if (autoEnabled) {
                startBackupService(PhotoBackupService.ACTION_START_WATCHER, null);
            }
        }

        @JavascriptInterface
        public void startBackupNow() {
            if (!hasPermissions()) {
                requestPermissionsFromJs();
                return;
            }
            startBackupService(PhotoBackupService.ACTION_SCAN_AND_BACKUP, null);
        }

        @JavascriptInterface
        public void clearBackedUpLedger() {
            BackupState.get(MainActivity.this).clearBackedUpLedger();
        }

        @JavascriptInterface
        public void pickAndUploadMedia(final String targetFolder, final boolean mediaOnly) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    manualUploadTargetFolder = (targetFolder == null || targetFolder.isEmpty()) ? "/GoogleDrive/photos" : targetFolder;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    if (mediaOnly) {
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                    } else {
                        intent.setType("*/*");
                    }
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(intent, "选择要极速直传的照片/视频/文件"), REQ_PICK_MANUAL_UPLOAD);
                }
            });
        }

        @JavascriptInterface
        public void switchTab(String tab) {
            selectTab(tab);
        }

        @JavascriptInterface
        public void downloadFile(final String url, final String filename) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    enqueueSystemDownload(url, filename);
                }
            });
        }
    }
}
