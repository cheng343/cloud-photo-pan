package top.czyxh.pan;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 2001;
    private static final int REQ_PICK_MANUAL_UPLOAD = 2002;
    private static final int REQ_WEBVIEW_FILE_CHOOSER = 2003;
    private static final String BASE_API = "https://pan.czyxh.top";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 CloudPhotoPanApp/2.0";

    // Unified photo/video item model (Local + Cloud merged, like Immich / Ente / Google Photos)
    public static class GalleryItem {
        public String name;
        public long size;
        public long timestampMs;
        public String dateStr;
        public boolean isVideo;
        public String bucket;
        // Local info
        public Uri localUri;
        public long mediaId;
        // Cloud info
        public boolean isOnCloud;
        public String cloudStatus; // "synced", "syncing", or ""
        public String cloudPath;
        public String thumbUrl;
        public String fullUrl;
        public String downloadUrl;

        public String getUniqueKey() {
            return name != null ? name : (cloudPath != null ? cloudPath : String.valueOf(mediaId));
        }
    }

    public static class AlbumFolder {
        public String bucket;
        public int count;
        public Uri coverUri;
        public boolean isVideoCover;
    }

    private final List<GalleryItem> allItems = new ArrayList<>();
    private final List<GalleryItem> filteredItems = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private boolean selectionMode = false;
    private String currentFilter = "all"; // "all", "cloud", "local", "video", or "bucket:xxx"

    private LruCache<String, Bitmap> memoryCache;
    private ExecutorService thumbExecutor;
    private ExecutorService netExecutor;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // Views - Root & Tabs
    private LinearLayout viewPhotosTab;
    private ScrollView viewAlbumsTab;
    private WebView alistWebView;
    private boolean alistLoaded = false;
    private String currentTab = "photos";

    private TextView tabTextPhotos, tabTextAlbums, tabTextFiles;

    // Views - Tab 1 (Native Gallery)
    private TextView tvGallerySubtitle;
    private TextView btnSelectMode, btnTopBackup;
    private final Map<String, TextView> filterChips = new HashMap<>();
    private LinearLayout batchActionBar;
    private TextView tvBatchCount;
    private LinearLayout liveProgressBanner;
    private TextView tvLiveTitle, tvLiveSpeed;
    private ProgressBar pbLiveProgress;
    private GridView photoGridView;
    private TextView tvEmptyGallery;
    private PhotoGridAdapter gridAdapter;

    // Views - Tab 2 (Native Albums & Backup Settings)
    private TextView tvBkStatusBadge, tvBkDetailLine, tvBkLogs;
    private ProgressBar pbBkProgress;
    private TextView btnBkStop;
    private Switch swAutoBackup, swWifiOnly, swIncludeVideo;
    private LinearLayout albumFoldersContainer;

    private String manualUploadTargetFolder = "/GoogleDrive/photos";
    private ValueCallback<Uri[]> webViewFilePathCallback;
    private boolean wasRunningLastPoll = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int maxMem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(8 * 1024, maxMem / 6);
        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bmp) {
                return bmp.getByteCount() / 1024;
            }
        };
        thumbExecutor = Executors.newFixedThreadPool(4);
        netExecutor = Executors.newCachedThreadPool();

        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.setStatusBarColor(Color.parseColor("#0B0F17"));
        window.setNavigationBarColor(Color.parseColor("#111726"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B0F17"));
        root.setFitsSystemWindows(true);

        FrameLayout contentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams frameLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f
        );

        viewPhotosTab = buildNativePhotosTab();
        viewAlbumsTab = buildNativeAlbumsTab();
        viewAlbumsTab.setVisibility(View.GONE);

        alistWebView = new WebView(this);
        configureAlistWebView(alistWebView);
        alistWebView.setVisibility(View.GONE);

        contentFrame.addView(viewPhotosTab, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ));
        contentFrame.addView(viewAlbumsTab, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ));
        contentFrame.addView(alistWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ));

        root.addView(contentFrame, frameLp);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1E293B"));
        root.addView(divider, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // Bottom Navigation Bar
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#111726"));
        bottomBar.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams navLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)
        );

        LinearLayout tabBtnPhotos = createTabItem("🖼️", "照片时间轴", true);
        tabTextPhotos = (TextView) tabBtnPhotos.getChildAt(1);
        tabBtnPhotos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("photos");
            }
        });

        LinearLayout tabBtnAlbums = createTabItem("📂", "相册与备份", false);
        tabTextAlbums = (TextView) tabBtnAlbums.getChildAt(1);
        tabBtnAlbums.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("albums");
            }
        });

        LinearLayout tabBtnFiles = createTabItem("📁", "网盘文件", false);
        tabTextFiles = (TextView) tabBtnFiles.getChildAt(1);
        tabBtnFiles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("files");
            }
        });

        bottomBar.addView(tabBtnPhotos, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        bottomBar.addView(tabBtnAlbums, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));
        bottomBar.addView(tabBtnFiles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f));

        root.addView(bottomBar, navLp);
        setContentView(root);

        requestMediaPermissionsIfNeeded();
        loadUnifiedTimeline(false);

        if (BackupState.get(this).isAutoBackupEnabled()) {
            startBackupService(PhotoBackupService.ACTION_START_WATCHER, null);
        }

        // Periodic status polling
        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollBackupStatus();
                uiHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ==================== TAB 1: NATIVE UNIFIED GALLERY ====================

    private LinearLayout buildNativePhotosTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0B0F17"));

        // Top Header Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#111726"));
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView tvTitle = new TextView(this);
        tvTitle.setText("🖼️ 云相册");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setSingleLine(true);

        tvGallerySubtitle = new TextView(this);
        tvGallerySubtitle.setText("正在加载本地与云端照片...");
        tvGallerySubtitle.setTextColor(Color.parseColor("#94A3B8"));
        tvGallerySubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvGallerySubtitle.setSingleLine(true);

        titleCol.addView(tvTitle);
        titleCol.addView(tvGallerySubtitle);
        row1.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        btnSelectMode = createActionButton("☑️ 多选", "#1E293B", "#334155");
        btnSelectMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSelectionMode();
            }
        });
        row1.addView(btnSelectMode, makeMarginLp(dp(6)));

        btnTopBackup = createActionButton("⚡ 备份新照片", "#059669", "#10B981");
        btnTopBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (BackupState.get(MainActivity.this).isRunning) {
                    stopBackupServiceNow();
                } else {
                    if (!hasPermissions()) {
                        requestMediaPermissionsIfNeeded();
                        return;
                    }
                    startBackupService(PhotoBackupService.ACTION_SCAN_AND_BACKUP, null);
                }
            }
        });
        row1.addView(btnTopBackup, makeMarginLp(dp(6)));

        TextView btnRefresh = createActionButton("🔄", "#1E293B", "#334155");
        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "正在同步云端最新状态...", Toast.LENGTH_SHORT).show();
                loadUnifiedTimeline(true);
            }
        });
        row1.addView(btnRefresh);

        topBar.addView(row1);

        // Row 2: Filter Pills inside HorizontalScrollView
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(0, dp(8), 0, 0);

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);

        addFilterChip(chipRow, "all", "全部照片");
        addFilterChip(chipRow, "cloud", "☁️ 已在云端");
        addFilterChip(chipRow, "local", "📱 仅本地未备份");
        addFilterChip(chipRow, "video", "🎬 视频");

        TextView btnPickUpload = createFilterChipView("➕ 手动选文件上传", false);
        btnPickUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFilesToUpload("/GoogleDrive/photos", false);
            }
        });
        chipRow.addView(btnPickUpload, makeMarginLp(0));

        hsv.addView(chipRow);
        topBar.addView(hsv);
        layout.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Batch Selection Action Bar (Hidden by default)
        batchActionBar = new LinearLayout(this);
        batchActionBar.setOrientation(LinearLayout.HORIZONTAL);
        batchActionBar.setGravity(Gravity.CENTER_VERTICAL);
        batchActionBar.setPadding(dp(12), dp(8), dp(12), dp(8));
        batchActionBar.setBackground(makeRoundRect("#1E293B", "#38BDF8", dp(10)));
        LinearLayout.LayoutParams batchLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        batchLp.setMargins(dp(10), dp(8), dp(10), 0);
        batchActionBar.setVisibility(View.GONE);

        tvBatchCount = new TextView(this);
        tvBatchCount.setText("已选 0 项");
        tvBatchCount.setTextColor(Color.WHITE);
        tvBatchCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvBatchCount.setTypeface(null, Typeface.BOLD);
        batchActionBar.addView(tvBatchCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView btnBatchSelectAll = createActionButton("全选", "#0F172A", "#334155");
        btnBatchSelectAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedKeys.size() == filteredItems.size()) {
                    selectedKeys.clear();
                } else {
                    for (GalleryItem it : filteredItems) {
                        selectedKeys.add(it.getUniqueKey());
                    }
                }
                updateSelectionUi();
            }
        });
        batchActionBar.addView(btnBatchSelectAll, makeMarginLp(dp(6)));

        TextView btnBatchUpload = createActionButton("☁️ 上传至云盘", "#0284C7", "#38BDF8");
        btnBatchUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uploadSelectedLocalItems();
            }
        });
        batchActionBar.addView(btnBatchUpload, makeMarginLp(dp(6)));

        TextView btnBatchDeleteCloud = createActionButton("🗑️ 云端删除", "#7F1D1D", "#EF4444");
        btnBatchDeleteCloud.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelectedCloudItems();
            }
        });
        batchActionBar.addView(btnBatchDeleteCloud);

        layout.addView(batchActionBar, batchLp);

        // Live Backup Progress Banner (Hidden when idle)
        liveProgressBanner = new LinearLayout(this);
        liveProgressBanner.setOrientation(LinearLayout.VERTICAL);
        liveProgressBanner.setPadding(dp(12), dp(8), dp(12), dp(8));
        liveProgressBanner.setBackground(makeRoundRect("#152238", "#0284C7", dp(10)));
        LinearLayout.LayoutParams liveLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        liveLp.setMargins(dp(10), dp(8), dp(10), 0);
        liveProgressBanner.setVisibility(View.GONE);

        LinearLayout liveRow = new LinearLayout(this);
        liveRow.setOrientation(LinearLayout.HORIZONTAL);
        liveRow.setGravity(Gravity.CENTER_VERTICAL);

        tvLiveTitle = new TextView(this);
        tvLiveTitle.setText("⚡ 正在直传...");
        tvLiveTitle.setTextColor(Color.parseColor("#E0F2FE"));
        tvLiveTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvLiveTitle.setTypeface(null, Typeface.BOLD);
        tvLiveTitle.setSingleLine(true);
        tvLiveTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        liveRow.addView(tvLiveTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        tvLiveSpeed = new TextView(this);
        tvLiveSpeed.setText("0 MB/s");
        tvLiveSpeed.setTextColor(Color.parseColor("#38BDF8"));
        tvLiveSpeed.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvLiveSpeed.setTypeface(null, Typeface.BOLD);
        tvLiveSpeed.setPadding(dp(6), 0, dp(8), 0);
        liveRow.addView(tvLiveSpeed);

        TextView btnLiveStop = createActionButton("⏹️ 停止", "#7F1D1D", "#EF4444");
        btnLiveStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopBackupServiceNow();
            }
        });
        liveRow.addView(btnLiveStop);
        liveProgressBanner.addView(liveRow);

        pbLiveProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbLiveProgress.setMax(100);
        pbLiveProgress.setProgress(0);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pbLp.topMargin = dp(6);
        liveProgressBanner.addView(pbLiveProgress, pbLp);

        layout.addView(liveProgressBanner, liveLp);

        // Main Frame: 3-Column Native GridView + Empty State
        FrameLayout gridFrame = new FrameLayout(this);
        photoGridView = new GridView(this);
        photoGridView.setNumColumns(3);
        photoGridView.setHorizontalSpacing(dp(3));
        photoGridView.setVerticalSpacing(dp(3));
        photoGridView.setPadding(dp(4), dp(6), dp(4), dp(16));
        photoGridView.setClipToPadding(false);
        photoGridView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);

        gridAdapter = new PhotoGridAdapter();
        photoGridView.setAdapter(gridAdapter);

        photoGridView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filteredItems.size()) return;
                GalleryItem item = filteredItems.get(position);
                if (selectionMode) {
                    String k = item.getUniqueKey();
                    if (selectedKeys.contains(k)) {
                        selectedKeys.remove(k);
                    } else {
                        selectedKeys.add(k);
                    }
                    updateSelectionUi();
                } else {
                    openNativeLightbox(position);
                }
            }
        });

        photoGridView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= filteredItems.size()) return true;
                GalleryItem item = filteredItems.get(position);
                if (!selectionMode) {
                    selectionMode = true;
                }
                selectedKeys.add(item.getUniqueKey());
                updateSelectionUi();
                return true;
            }
        });

        tvEmptyGallery = new TextView(this);
        tvEmptyGallery.setText("📸 暂无符合筛选条件的照片或视频\n\n点击上方切换筛选，或直接拍摄/上传新照片");
        tvEmptyGallery.setTextColor(Color.parseColor("#94A3B8"));
        tvEmptyGallery.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tvEmptyGallery.setGravity(Gravity.CENTER);
        tvEmptyGallery.setPadding(dp(30), dp(60), dp(30), dp(60));
        tvEmptyGallery.setVisibility(View.GONE);

        gridFrame.addView(photoGridView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        gridFrame.addView(tvEmptyGallery, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        layout.addView(gridFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        return layout;
    }

    private void addFilterChip(LinearLayout parent, final String filterKey, String label) {
        TextView tv = createFilterChipView(label, filterKey.equals(currentFilter));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setGalleryFilter(filterKey);
            }
        });
        filterChips.put(filterKey, tv);
        parent.addView(tv, makeMarginLp(dp(6)));
    }

    private TextView createFilterChipView(String label, boolean active) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setSingleLine(true);
        tv.setPadding(dp(11), dp(5), dp(11), dp(5));
        styleFilterChip(tv, active);
        return tv;
    }

    private void styleFilterChip(TextView tv, boolean active) {
        tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        tv.setTextColor(Color.parseColor(active ? "#38BDF8" : "#94A3B8"));
        tv.setBackground(makeRoundRect(active ? "#162C46" : "#151E32", active ? "#0284C7" : "#1E293B", dp(999)));
    }

    private void setGalleryFilter(String filterKey) {
        currentFilter = filterKey;
        for (Map.Entry<String, TextView> e : filterChips.entrySet()) {
            styleFilterChip(e.getValue(), e.getKey().equals(filterKey));
        }
        applyFilterAndRefreshGrid();
    }

    private void toggleSelectionMode() {
        selectionMode = !selectionMode;
        if (!selectionMode) {
            selectedKeys.clear();
        }
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        btnSelectMode.setText(selectionMode ? "✕ 取消选择" : "☑️ 多选");
        batchActionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        tvBatchCount.setText("已选 " + selectedKeys.size() + " 项");
        gridAdapter.notifyDataSetChanged();
    }

    // ==================== UNIFIED LOCAL + CLOUD DATA ENGINE ====================

    private void loadUnifiedTimeline(final boolean forceCloudRefresh) {
        netExecutor.submit(new Runnable() {
            @Override
            public void run() {
                // Step 1: Load local phone photos/videos immediately (0 latency!)
                final List<GalleryItem> localList = queryLocalMediaItems();
                synchronized (allItems) {
                    if (allItems.isEmpty() && !localList.isEmpty()) {
                        allItems.addAll(localList);
                    }
                }
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyFilterAndRefreshGrid();
                    }
                });

                // Step 2: Fetch cloud photos list from server (memory cached < 10ms) and merge!
                Map<String, GalleryItem> cloudMap = fetchCloudPhotosMap(forceCloudRefresh);
                Map<String, GalleryItem> mergedMap = new LinkedHashMap<>();

                for (GalleryItem loc : localList) {
                    GalleryItem cld = cloudMap.remove(loc.name);
                    if (cld != null) {
                        loc.isOnCloud = true;
                        loc.cloudStatus = cld.cloudStatus;
                        loc.cloudPath = cld.cloudPath;
                        loc.thumbUrl = cld.thumbUrl;
                        loc.fullUrl = cld.fullUrl;
                        loc.downloadUrl = cld.downloadUrl;
                        BackupState.get(MainActivity.this).markBackedUp(loc.name);
                    }
                    mergedMap.put(loc.name, loc);
                }

                // Remaining items in cloudMap are Cloud-Only photos (e.g. deleted locally or uploaded from other devices)
                for (GalleryItem cldOnly : cloudMap.values()) {
                    mergedMap.put(cldOnly.name, cldOnly);
                }

                final List<GalleryItem> finalMerged = new ArrayList<>(mergedMap.values());
                Collections.sort(finalMerged, new Comparator<GalleryItem>() {
                    @Override
                    public int compare(GalleryItem a, GalleryItem b) {
                        return Long.compare(b.timestampMs, a.timestampMs);
                    }
                });

                synchronized (allItems) {
                    allItems.clear();
                    allItems.addAll(finalMerged);
                }

                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyFilterAndRefreshGrid();
                        if ("albums".equals(currentTab)) {
                            refreshNativeAlbumsTab();
                        }
                    }
                });
            }
        });
    }

    private List<GalleryItem> queryLocalMediaItems() {
        List<GalleryItem> result = new ArrayList<>();
        if (!hasPermissions()) return result;

        Set<String> backedUpSet = BackupState.get(this).getBackedUpSet();
        querySingleMediaTable(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, backedUpSet, result);
        querySingleMediaTable(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, backedUpSet, result);

        Collections.sort(result, new Comparator<GalleryItem>() {
            @Override
            public int compare(GalleryItem a, GalleryItem b) {
                return Long.compare(b.timestampMs, a.timestampMs);
            }
        });
        return result;
    }

    private void querySingleMediaTable(Uri contentUri, boolean isVideo, Set<String> backedUpSet, List<GalleryItem> out) {
        String[] proj = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        Cursor c = null;
        SimpleDateFormat sdf = new SimpleDateFormat("MM-dd", Locale.getDefault());
        try {
            c = getContentResolver().query(
                    contentUri,
                    proj,
                    null,
                    null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
            );
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                int nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                int sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                int bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
                int dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);

                int count = 0;
                while (c.moveToNext() && count < 600) {
                    long id = c.getLong(idCol);
                    String name = c.getString(nameCol);
                    long size = c.getLong(sizeCol);
                    String bucket = c.getString(bucketCol);
                    long dateSec = c.getLong(dateCol);
                    if (name == null || name.isEmpty() || size <= 0) continue;
                    if (bucket == null || bucket.isEmpty()) bucket = "Default";

                    GalleryItem it = new GalleryItem();
                    it.mediaId = id;
                    it.localUri = ContentUris.withAppendedId(contentUri, id);
                    it.name = name;
                    it.size = size;
                    it.bucket = bucket;
                    it.isVideo = isVideo;
                    it.timestampMs = dateSec * 1000L;
                    it.dateStr = sdf.format(new Date(it.timestampMs));
                    it.isOnCloud = backedUpSet.contains(name);
                    it.cloudStatus = it.isOnCloud ? "synced" : "";
                    out.add(it);
                    count++;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private Map<String, GalleryItem> fetchCloudPhotosMap(boolean forceRefresh) {
        Map<String, GalleryItem> map = new LinkedHashMap<>();
        HttpURLConnection conn = null;
        try {
            String u = BASE_API + "/api/cloud_transfer/photos" + (forceRefresh ? "?refresh=1" : "");
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject json = new JSONObject(sb.toString());
                JSONArray arr = json.optJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        GalleryItem it = new GalleryItem();
                        it.name = o.optString("name", "");
                        if (it.name.isEmpty()) continue;
                        it.cloudPath = o.optString("path", "/GoogleDrive/photos/" + it.name);
                        it.size = o.optLong("size", 0);
                        it.isVideo = o.optBoolean("is_video", false);
                        it.thumbUrl = o.optString("thumb_url", "");
                        it.fullUrl = o.optString("full_url", "");
                        it.downloadUrl = o.optString("download_url", "");
                        it.cloudStatus = o.optString("status", "synced");
                        it.isOnCloud = true;
                        it.bucket = "Cloud";

                        String mod = o.optString("modified", "");
                        it.dateStr = mod.length() >= 10 ? mod.substring(5, 10) : "云端";
                        it.timestampMs = parseIsoTime(mod);
                        map.put(it.name, it);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return map;
    }

    private long parseIsoTime(String iso) {
        if (iso == null || iso.length() < 19) return System.currentTimeMillis();
        try {
            String clean = iso.substring(0, 19).replace("T", " ");
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            Date d = sdf.parse(clean);
            return d != null ? d.getTime() + 8 * 3600 * 1000L : System.currentTimeMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private void applyFilterAndRefreshGrid() {
        filteredItems.clear();
        int cloudCount = 0;
        int localOnlyCount = 0;

        Set<String> enabledBuckets = BackupState.get(this).getSelectedBuckets();

        synchronized (allItems) {
            for (GalleryItem it : allItems) {
                // In default "all" view, show all cloud photos + local photos from enabled albums (or Camera/Screenshots)
                boolean inRelevantBucket = it.isOnCloud
                        || enabledBuckets.contains(it.bucket)
                        || "Camera".equalsIgnoreCase(it.bucket)
                        || "DCIM".equalsIgnoreCase(it.bucket)
                        || "相机".equals(it.bucket);

                if (it.isOnCloud) cloudCount++;
                else if (inRelevantBucket) localOnlyCount++;

                if ("cloud".equals(currentFilter)) {
                    if (it.isOnCloud) filteredItems.add(it);
                } else if ("local".equals(currentFilter)) {
                    if (!it.isOnCloud && it.localUri != null && inRelevantBucket) filteredItems.add(it);
                } else if ("video".equals(currentFilter)) {
                    if (it.isVideo && inRelevantBucket) filteredItems.add(it);
                } else if (currentFilter.startsWith("bucket:")) {
                    String targetB = currentFilter.substring("bucket:".length());
                    if (targetB.equals(it.bucket)) filteredItems.add(it);
                } else {
                    if (inRelevantBucket) filteredItems.add(it);
                }
            }
        }

        String prefix = currentFilter.startsWith("bucket:") ? ("[" + currentFilter.substring(7) + "] ") : "";
        tvGallerySubtitle.setText(prefix + "显示 " + filteredItems.size() + " 项 · ☁️已上云 " + cloudCount + " · 📱待备份 " + localOnlyCount);
        tvEmptyGallery.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
        gridAdapter.notifyDataSetChanged();
    }

    // ==================== NATIVE 1:1 SQUARE PHOTO GRID ADAPTER ====================

    public static class SquareFrameLayout extends FrameLayout {
        public SquareFrameLayout(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }

    private class PhotoGridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return filteredItems.size();
        }

        @Override
        public Object getItem(int position) {
            return filteredItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder vh;
            if (convertView == null) {
                SquareFrameLayout cell = new SquareFrameLayout(MainActivity.this);
                cell.setBackground(makeRoundRect("#131C2E", "#1E293B", dp(6)));

                ImageView iv = new ImageView(MainActivity.this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                // Top-left status badge (☁️✓ 已上云 / 📱 本地 / ⚡ 同步中)
                TextView tvStatus = new TextView(MainActivity.this);
                tvStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
                tvStatus.setTextColor(Color.WHITE);
                tvStatus.setTypeface(null, Typeface.BOLD);
                tvStatus.setPadding(dp(5), dp(2), dp(5), dp(2));
                FrameLayout.LayoutParams stLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START);
                stLp.setMargins(dp(4), dp(4), 0, 0);
                cell.addView(tvStatus, stLp);

                // Top-right selection check circle
                TextView tvCheck = new TextView(MainActivity.this);
                tvCheck.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                tvCheck.setTextColor(Color.WHITE);
                tvCheck.setTypeface(null, Typeface.BOLD);
                tvCheck.setGravity(Gravity.CENTER);
                FrameLayout.LayoutParams ckLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
                ckLp.setMargins(0, dp(4), dp(4), 0);
                cell.addView(tvCheck, ckLp);

                // Bottom-right size/video badge
                TextView tvMeta = new TextView(MainActivity.this);
                tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 9);
                tvMeta.setTextColor(Color.WHITE);
                tvMeta.setBackground(makeRoundRect("#B3000000", "#00000000", dp(4)));
                tvMeta.setPadding(dp(4), dp(1), dp(4), dp(1));
                FrameLayout.LayoutParams mtLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                mtLp.setMargins(0, 0, dp(4), dp(4));
                cell.addView(tvMeta, mtLp);

                vh = new ViewHolder();
                vh.cell = cell;
                vh.iv = iv;
                vh.tvStatus = tvStatus;
                vh.tvCheck = tvCheck;
                vh.tvMeta = tvMeta;
                convertView = cell;
                convertView.setTag(vh);
            } else {
                vh = (ViewHolder) convertView.getTag();
            }

            GalleryItem item = filteredItems.get(position);
            String key = item.getUniqueKey();
            vh.boundKey = key;

            // Status badge styling
            if ("syncing".equals(item.cloudStatus)) {
                vh.tvStatus.setText("⚡ R2同步中");
                vh.tvStatus.setBackground(makeRoundRect("#D9D97706", "#00000000", dp(4)));
            } else if (item.isOnCloud && item.localUri != null) {
                vh.tvStatus.setText("☁️✓ 已备份");
                vh.tvStatus.setBackground(makeRoundRect("#CC059669", "#00000000", dp(4)));
            } else if (item.isOnCloud) {
                vh.tvStatus.setText("☁️ 云端");
                vh.tvStatus.setBackground(makeRoundRect("#CC0284C7", "#00000000", dp(4)));
            } else {
                vh.tvStatus.setText("📱 待备份");
                vh.tvStatus.setBackground(makeRoundRect("#B3334155", "#00000000", dp(4)));
            }

            // Selection check
            if (selectionMode) {
                vh.tvCheck.setVisibility(View.VISIBLE);
                boolean sel = selectedKeys.contains(key);
                vh.tvCheck.setText(sel ? "✓" : "");
                vh.tvCheck.setBackground(makeRoundRect(sel ? "#0284C7" : "#990F172A", "#FFFFFF", dp(99)));
            } else {
                vh.tvCheck.setVisibility(View.GONE);
            }

            String sizeStr = formatSize(item.size);
            vh.tvMeta.setText(item.isVideo ? ("▶ " + sizeStr) : sizeStr);

            // Load thumbnail natively (Local hardware HEIC/Video decoder first, Cloud JPEG fallback!)
            loadItemThumbnail(item, vh.iv, vh);

            return convertView;
        }
    }

    private static class ViewHolder {
        SquareFrameLayout cell;
        ImageView iv;
        TextView tvStatus;
        TextView tvCheck;
        TextView tvMeta;
        String boundKey;
    }

    private void loadItemThumbnail(final GalleryItem item, final ImageView iv, final ViewHolder vh) {
        final String key = item.getUniqueKey();
        Bitmap cached = memoryCache.get(key);
        if (cached != null && !cached.isRecycled()) {
            iv.setImageBitmap(cached);
            return;
        }

        iv.setImageDrawable(null);
        thumbExecutor.submit(new Runnable() {
            @Override
            public void run() {
                if (vh != null && !key.equals(vh.boundKey)) return;
                Bitmap bmp = null;

                // Priority 1: Load directly from local Android MediaStore (0 network, native HEIC & Video support!)
                if (item.localUri != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            bmp = getContentResolver().loadThumbnail(item.localUri, new Size(320, 320), null);
                        } else {
                            if (item.isVideo) {
                                bmp = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), item.mediaId, MediaStore.Video.Thumbnails.MINI_KIND, null);
                            } else {
                                bmp = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), item.mediaId, MediaStore.Images.Thumbnails.MINI_KIND, null);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                // Priority 2: Load from disk cache or cloud thumbUrl
                if (bmp == null && item.thumbUrl != null && !item.thumbUrl.isEmpty()) {
                    bmp = loadNetworkBitmapWithDiskCache(item.thumbUrl, 360);
                }

                if (bmp != null) {
                    memoryCache.put(key, bmp);
                    final Bitmap finalBmp = bmp;
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (vh == null || key.equals(vh.boundKey)) {
                                iv.setImageBitmap(finalBmp);
                            }
                        }
                    });
                }
            }
        });
    }

    private Bitmap loadNetworkBitmapWithDiskCache(String urlStr, int maxDim) {
        try {
            File cacheDir = new File(getCacheDir(), "cloud_thumbs");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            String hash = md5(urlStr + "_" + maxDim);
            File diskFile = new File(cacheDir, hash + ".jpg");

            if (diskFile.exists() && diskFile.length() > 100) {
                Bitmap b = decodeSampledFile(diskFile, maxDim);
                if (b != null) return b;
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() == 200) {
                InputStream in = conn.getInputStream();
                OutputStream out = new FileOutputStream(diskFile);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                in.close();
                conn.disconnect();
                return decodeSampledFile(diskFile, maxDim);
            }
            conn.disconnect();
        } catch (Exception ignored) {}
        return null;
    }

    private Bitmap decodeSampledFile(File f, int maxDim) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source src = ImageDecoder.createSource(f);
                final int targetMax = maxDim;
                return ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source source) {
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                        int w = info.getSize().getWidth();
                        int h = info.getSize().getHeight();
                        if (w > targetMax || h > targetMax) {
                            float ratio = Math.min((float) targetMax / w, (float) targetMax / h);
                            decoder.setTargetSize(Math.max(1, (int) (w * ratio)), Math.max(1, (int) (h * ratio)));
                        }
                    }
                });
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            int inSample = 1;
            while (opts.outWidth / inSample > maxDim || opts.outHeight / inSample > maxDim) {
                inSample *= 2;
            }
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = inSample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== NATIVE FULLSCREEN LIGHTBOX VIEWER ====================

    private void openNativeLightbox(final int startIdx) {
        if (startIdx < 0 || startIdx >= filteredItems.size()) return;
        final Dialog dlg = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        final int[] curIdx = new int[]{startIdx};
        final boolean[] zoomed = new boolean[]{false};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#05080F"));

        // Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#111726"));
        topBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        final TextView tvName = new TextView(this);
        tvName.setTextColor(Color.WHITE);
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tvName.setTypeface(null, Typeface.BOLD);
        tvName.setSingleLine(true);
        tvName.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        topBar.addView(tvName, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView btnClose = createActionButton("✕ 关闭", "#1E293B", "#334155");
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        topBar.addView(btnClose, makeMarginLp(0));
        root.addView(topBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Center Stage
        final FrameLayout stage = new FrameLayout(this);
        final ImageView ivFull = new ImageView(this);
        ivFull.setScaleType(ImageView.ScaleType.FIT_CENTER);
        stage.addView(ivFull, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final ProgressBar pbLoading = new ProgressBar(this);
        FrameLayout.LayoutParams pbLp = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER);
        stage.addView(pbLoading, pbLp);

        // Prev / Next buttons
        TextView btnPrev = createActionButton("❮", "#991E293B", "#334155");
        FrameLayout.LayoutParams prevLp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.START | Gravity.CENTER_VERTICAL);
        prevLp.leftMargin = dp(10);
        stage.addView(btnPrev, prevLp);

        TextView btnNext = createActionButton("❯", "#991E293B", "#334155");
        FrameLayout.LayoutParams nextLp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END | Gravity.CENTER_VERTICAL);
        nextLp.rightMargin = dp(10);
        stage.addView(btnNext, nextLp);

        root.addView(stage, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Bottom Bar
        LinearLayout botBar = new LinearLayout(this);
        botBar.setOrientation(LinearLayout.HORIZONTAL);
        botBar.setGravity(Gravity.CENTER_VERTICAL);
        botBar.setBackgroundColor(Color.parseColor("#111726"));
        botBar.setPadding(dp(12), dp(10), dp(12), dp(10));

        final TextView tvMeta = new TextView(this);
        tvMeta.setTextColor(Color.parseColor("#94A3B8"));
        tvMeta.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvMeta.setSingleLine(true);
        botBar.addView(tvMeta, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        final TextView btnUploadOne = createActionButton("☁️ 上传至云盘", "#0284C7", "#38BDF8");
        botBar.addView(btnUploadOne, makeMarginLp(dp(6)));

        final TextView btnDownloadOne = createActionButton("⬇️ 下载原图", "#1E293B", "#334155");
        botBar.addView(btnDownloadOne, makeMarginLp(dp(6)));

        final TextView btnDeleteOne = createActionButton("🗑️ 删除云端", "#7F1D1D", "#EF4444");
        botBar.addView(btnDeleteOne);

        root.addView(botBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final Runnable renderCurrent = new Runnable() {
            @Override
            public void run() {
                if (filteredItems.isEmpty()) {
                    dlg.dismiss();
                    return;
                }
                if (curIdx[0] < 0) curIdx[0] = filteredItems.size() - 1;
                if (curIdx[0] >= filteredItems.size()) curIdx[0] = 0;

                final GalleryItem item = filteredItems.get(curIdx[0]);
                zoomed[0] = false;
                ivFull.setScaleX(1.0f);
                ivFull.setScaleY(1.0f);

                tvName.setText("(" + (curIdx[0] + 1) + "/" + filteredItems.size() + ") " + item.name);
                String stText = item.isOnCloud ? "☁️ 已备份在云端" : "📱 仅在手机本地";
                tvMeta.setText(formatSize(item.size) + " · " + stText);

                btnUploadOne.setVisibility(!item.isOnCloud && item.localUri != null ? View.VISIBLE : View.GONE);
                btnDownloadOne.setVisibility(item.isOnCloud ? View.VISIBLE : View.GONE);
                btnDeleteOne.setVisibility(item.isOnCloud ? View.VISIBLE : View.GONE);

                btnUploadOne.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        synchronized (PhotoBackupService.pendingManualUris) {
                            PhotoBackupService.pendingManualUris.add(item.localUri);
                        }
                        Toast.makeText(MainActivity.this, "⚡ 开始直传 " + item.name, Toast.LENGTH_SHORT).show();
                        startBackupService(PhotoBackupService.ACTION_UPLOAD_URIS, "/GoogleDrive/photos");
                        dlg.dismiss();
                    }
                });

                btnDownloadOne.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (item.downloadUrl != null && !item.downloadUrl.isEmpty()) {
                            enqueueSystemDownload(item.downloadUrl, item.name);
                        }
                    }
                });

                btnDeleteOne.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        deleteSingleCloudItem(item);
                        dlg.dismiss();
                    }
                });

                // Show thumbnail immediately while loading full-res
                Bitmap thumb = memoryCache.get(item.getUniqueKey());
                ivFull.setImageBitmap(thumb);
                pbLoading.setVisibility(View.VISIBLE);

                netExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap fullBmp = null;
                        // 1. If local file exists, decode high-res directly from local URI (native Android 9+ ImageDecoder supports 10-bit HEIC!)
                        if (item.localUri != null && !item.isVideo) {
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    ImageDecoder.Source src = ImageDecoder.createSource(getContentResolver(), item.localUri);
                                    fullBmp = ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                                        @Override
                                        public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info, ImageDecoder.Source source) {
                                            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                                            int w = info.getSize().getWidth();
                                            int h = info.getSize().getHeight();
                                            if (w > 2048 || h > 2048) {
                                                float r = Math.min(2048f / w, 2048f / h);
                                                decoder.setTargetSize((int) (w * r), (int) (h * r));
                                            }
                                        }
                                    });
                                } else {
                                    InputStream in = getContentResolver().openInputStream(item.localUri);
                                    if (in != null) {
                                        fullBmp = BitmapFactory.decodeStream(in);
                                        in.close();
                                    }
                                }
                            } catch (Exception ignored) {}
                        }

                        // 2. If cloud photo, load 2048p JPEG stream from cloud
                        if (fullBmp == null && item.fullUrl != null && !item.fullUrl.isEmpty() && !item.isVideo) {
                            fullBmp = loadNetworkBitmapWithDiskCache(item.fullUrl, 1920);
                        }

                        final Bitmap finalBmp = fullBmp;
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                pbLoading.setVisibility(View.GONE);
                                if (finalBmp != null) {
                                    ivFull.setImageBitmap(finalBmp);
                                }
                                if (item.isVideo) {
                                    Toast.makeText(MainActivity.this, "🎬 正在调用播放器打开视频...", Toast.LENGTH_SHORT).show();
                                    try {
                                        Uri playUri = item.localUri != null ? item.localUri : Uri.parse(item.fullUrl);
                                        Intent vi = new Intent(Intent.ACTION_VIEW);
                                        vi.setDataAndType(playUri, "video/*");
                                        vi.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                        startActivity(vi);
                                    } catch (Exception ignored) {}
                                }
                            }
                        });
                    }
                });
            }
        };

        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                curIdx[0]--;
                renderCurrent.run();
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                curIdx[0]++;
                renderCurrent.run();
            }
        });

        // Swipe left/right & tap zoom on image
        final float[] downX = new float[1];
        final long[] downTime = new long[1];
        ivFull.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    downX[0] = event.getX();
                    downTime[0] = System.currentTimeMillis();
                    return true;
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    float dx = event.getX() - downX[0];
                    long dt = System.currentTimeMillis() - downTime[0];
                    if (Math.abs(dx) > dp(50) && !zoomed[0]) {
                        if (dx < 0) curIdx[0]++;
                        else curIdx[0]--;
                        renderCurrent.run();
                    } else if (Math.abs(dx) < dp(12) && dt < 300) {
                        zoomed[0] = !zoomed[0];
                        ivFull.setScaleX(zoomed[0] ? 2.2f : 1.0f);
                        ivFull.setScaleY(zoomed[0] ? 2.2f : 1.0f);
                    }
                    return true;
                }
                return false;
            }
        });

        dlg.setContentView(root);
        dlg.show();
        renderCurrent.run();
    }

    // ==================== BATCH UPLOAD & CLOUD DELETE ====================

    private void uploadSelectedLocalItems() {
        List<Uri> uris = new ArrayList<>();
        for (GalleryItem it : filteredItems) {
            if (selectedKeys.contains(it.getUniqueKey()) && it.localUri != null) {
                uris.add(it.localUri);
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "请勾选手机本地的照片或视频进行上传", Toast.LENGTH_SHORT).show();
            return;
        }
        synchronized (PhotoBackupService.pendingManualUris) {
            PhotoBackupService.pendingManualUris.addAll(uris);
        }
        selectionMode = false;
        selectedKeys.clear();
        updateSelectionUi();
        Toast.makeText(this, "⚡ 开始直传所选的 " + uris.size() + " 张照片至云盘...", Toast.LENGTH_SHORT).show();
        startBackupService(PhotoBackupService.ACTION_UPLOAD_URIS, "/GoogleDrive/photos");
    }

    private void deleteSingleCloudItem(GalleryItem item) {
        selectedKeys.clear();
        selectedKeys.add(item.getUniqueKey());
        deleteSelectedCloudItems();
    }

    private void deleteSelectedCloudItems() {
        final List<String> cloudPaths = new ArrayList<>();
        final Set<String> deletedNames = new HashSet<>();
        synchronized (allItems) {
            for (GalleryItem it : allItems) {
                if (selectedKeys.contains(it.getUniqueKey()) && it.isOnCloud) {
                    String p = (it.cloudPath != null && !it.cloudPath.isEmpty()) ? it.cloudPath : ("/GoogleDrive/photos/" + it.name);
                    cloudPaths.add(p);
                    deletedNames.add(it.name);
                    it.isOnCloud = false;
                    it.cloudStatus = "";
                }
            }
            // Remove cloud-only items completely from list
            for (int i = allItems.size() - 1; i >= 0; i--) {
                GalleryItem it = allItems.get(i);
                if (deletedNames.contains(it.name) && it.localUri == null) {
                    allItems.remove(i);
                }
            }
        }

        if (cloudPaths.isEmpty()) {
            Toast.makeText(this, "所选项目不在云端，无需从云端删除", Toast.LENGTH_SHORT).show();
            return;
        }

        selectionMode = false;
        selectedKeys.clear();
        updateSelectionUi();
        applyFilterAndRefreshGrid();
        Toast.makeText(this, "🗑️ 已从云端相册移除 " + cloudPaths.size() + " 项", Toast.LENGTH_SHORT).show();

        netExecutor.submit(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    JSONObject body = new JSONObject();
                    JSONArray arr = new JSONArray();
                    for (String p : cloudPaths) arr.put(p);
                    body.put("paths", arr);

                    conn = (HttpURLConnection) new URL(BASE_API + "/api/cloud_transfer/delete_photo").openConnection();
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("User-Agent", USER_AGENT);
                    conn.setDoOutput(true);
                    byte[] bytes = body.toString().getBytes("UTF-8");
                    conn.getOutputStream().write(bytes);
                    conn.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    // ==================== TAB 2: NATIVE ALBUM FOLDERS & BACKUP SETTINGS ====================

    private ScrollView buildNativeAlbumsTab() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#0B0F17"));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(12), dp(12), dp(12), dp(30));

        // Card 1: Backup Engine Status
        LinearLayout cardStatus = createCardLayout();
        LinearLayout stHead = new LinearLayout(this);
        stHead.setOrientation(LinearLayout.HORIZONTAL);
        stHead.setGravity(Gravity.CENTER_VERTICAL);

        TextView stTitle = new TextView(this);
        stTitle.setText("☁️ 云相册备份引擎");
        stTitle.setTextColor(Color.WHITE);
        stTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        stTitle.setTypeface(null, Typeface.BOLD);
        stHead.addView(stTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        tvBkStatusBadge = new TextView(this);
        tvBkStatusBadge.setText("待机中");
        tvBkStatusBadge.setTextColor(Color.parseColor("#38BDF8"));
        tvBkStatusBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvBkStatusBadge.setTypeface(null, Typeface.BOLD);
        stHead.addView(tvBkStatusBadge);
        cardStatus.addView(stHead);

        tvBkDetailLine = new TextView(this);
        tvBkDetailLine.setText("已累计备份 0 个文件 · R2 边缘直传 Google Drive");
        tvBkDetailLine.setTextColor(Color.parseColor("#94A3B8"));
        tvBkDetailLine.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvBkDetailLine.setSingleLine(true);
        tvBkDetailLine.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tvBkDetailLine.setPadding(0, dp(6), 0, dp(8));
        cardStatus.addView(tvBkDetailLine);

        pbBkProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbBkProgress.setMax(100);
        pbBkProgress.setProgress(0);
        cardStatus.addView(pbBkProgress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, 0);

        TextView btnScanNow = createActionButton("🚀 立即备份新照片", "#0284C7", "#38BDF8");
        btnScanNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBackupService(PhotoBackupService.ACTION_SCAN_AND_BACKUP, null);
            }
        });
        btnRow.addView(btnScanNow, makeMarginLp(dp(6)));

        btnBkStop = createActionButton("⏹️ 停止传输", "#7F1D1D", "#EF4444");
        btnBkStop.setVisibility(View.GONE);
        btnBkStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopBackupServiceNow();
            }
        });
        btnRow.addView(btnBkStop, makeMarginLp(dp(6)));

        TextView btnResetLedger = createActionButton("🔄 重新比对云端", "#1E293B", "#334155");
        btnResetLedger.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BackupState.get(MainActivity.this).clearBackedUpLedger();
                loadUnifiedTimeline(true);
                Toast.makeText(MainActivity.this, "已重置本地账本并重新与云端比对", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnResetLedger);

        cardStatus.addView(btnRow);
        container.addView(cardStatus, makeBottomMarginLp(dp(12)));

        // Card 2: Auto-Backup Toggles
        LinearLayout cardSwitches = createCardLayout();
        TextView swTitle = new TextView(this);
        swTitle.setText("⚙️ 自动备份策略");
        swTitle.setTextColor(Color.WHITE);
        swTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        swTitle.setTypeface(null, Typeface.BOLD);
        swTitle.setPadding(0, 0, 0, dp(6));
        cardSwitches.addView(swTitle);

        BackupState st = BackupState.get(this);
        swAutoBackup = addSwitchRow(cardSwitches, "开启后台自动备份", "拍照或录像后自动直传云端", st.isAutoBackupEnabled());
        swWifiOnly = addSwitchRow(cardSwitches, "仅在 Wi-Fi 下自动备份", "避免消耗移动数据流量", st.isWifiOnly());
        swIncludeVideo = addSwitchRow(cardSwitches, "同时备份视频文件", "支持 4K 超大视频 4 分片极速同步", st.isIncludeVideos());

        container.addView(cardSwitches, makeBottomMarginLp(dp(12)));

        // Card 3: Phone Album Folders (Like Immich / Google Photos)
        LinearLayout cardAlbums = createCardLayout();
        TextView albTitle = new TextView(this);
        albTitle.setText("📂 手机本地相册文件夹（开启开关即自动备份该相册）");
        albTitle.setTextColor(Color.WHITE);
        albTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        albTitle.setTypeface(null, Typeface.BOLD);
        albTitle.setPadding(0, 0, 0, dp(8));
        cardAlbums.addView(albTitle);

        albumFoldersContainer = new LinearLayout(this);
        albumFoldersContainer.setOrientation(LinearLayout.VERTICAL);
        cardAlbums.addView(albumFoldersContainer);
        container.addView(cardAlbums, makeBottomMarginLp(dp(12)));

        // Card 4: Live Logs
        LinearLayout cardLogs = createCardLayout();
        TextView logTitle = new TextView(this);
        logTitle.setText("📜 传输日志");
        logTitle.setTextColor(Color.WHITE);
        logTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        logTitle.setTypeface(null, Typeface.BOLD);
        logTitle.setPadding(0, 0, 0, dp(6));
        cardLogs.addView(logTitle);

        tvBkLogs = new TextView(this);
        tvBkLogs.setText("等待任务启动...");
        tvBkLogs.setTextColor(Color.parseColor("#94A3B8"));
        tvBkLogs.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvBkLogs.setTypeface(Typeface.MONOSPACE);
        tvBkLogs.setBackground(makeRoundRect("#070A10", "#1E293B", dp(8)));
        tvBkLogs.setPadding(dp(10), dp(8), dp(10), dp(8));
        cardLogs.addView(tvBkLogs);

        container.addView(cardLogs);
        sv.addView(container);
        return sv;
    }

    private Switch addSwitchRow(LinearLayout parent, String title, String sub, boolean checked) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Color.parseColor("#E2E8F0"));
        t1.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        t1.setTypeface(null, Typeface.BOLD);

        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextColor(Color.parseColor("#94A3B8"));
        t2.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);

        textCol.addView(t1);
        textCol.addView(t2);
        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveSwitchSettings();
            }
        });
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    private void saveSwitchSettings() {
        BackupState st = BackupState.get(this);
        boolean autoEn = swAutoBackup.isChecked();
        st.saveConfig(autoEn, swWifiOnly.isChecked(), swIncludeVideo.isChecked(), "/GoogleDrive/photos", st.getSelectedBuckets());
        if (autoEn) {
            startBackupService(PhotoBackupService.ACTION_START_WATCHER, null);
        }
    }

    private void refreshNativeAlbumsTab() {
        if (albumFoldersContainer == null) return;
        albumFoldersContainer.removeAllViews();

        Map<String, AlbumFolder> folderMap = new LinkedHashMap<>();
        synchronized (allItems) {
            for (GalleryItem it : allItems) {
                if (it.localUri == null) continue;
                AlbumFolder f = folderMap.get(it.bucket);
                if (f == null) {
                    f = new AlbumFolder();
                    f.bucket = it.bucket;
                    f.count = 0;
                    f.coverUri = it.localUri;
                    f.isVideoCover = it.isVideo;
                    folderMap.put(it.bucket, f);
                }
                f.count++;
            }
        }

        final Set<String> selectedBuckets = BackupState.get(this).getSelectedBuckets();
        for (final AlbumFolder folder : folderMap.values()) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);

            TextView tvBName = new TextView(this);
            tvBName.setText("📁 " + folder.bucket + " (" + folder.count + " 项)");
            tvBName.setTextColor(Color.WHITE);
            tvBName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            tvBName.setTypeface(null, Typeface.BOLD);
            infoCol.addView(tvBName);

            boolean isBacked = selectedBuckets.contains(folder.bucket);
            TextView tvSub = new TextView(this);
            tvSub.setText(isBacked ? "✅ 已加入云相册展示与自动备份" : "⬜ 未开启自动备份（点击右侧可开启或单独浏览）");
            tvSub.setTextColor(Color.parseColor(isBacked ? "#34D399" : "#94A3B8"));
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            infoCol.addView(tvSub);

            row.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            TextView btnViewFolder = createActionButton("👁️ 浏览", "#1E293B", "#334155");
            btnViewFolder.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab("photos");
                    setGalleryFilter("bucket:" + folder.bucket);
                }
            });
            row.addView(btnViewFolder, makeMarginLp(dp(6)));

            final boolean currentlyOn = isBacked;
            TextView btnToggle = createActionButton(currentlyOn ? "✅ 备份中" : "⬜ 开启备份", currentlyOn ? "#065F46" : "#1E293B", currentlyOn ? "#10B981" : "#334155");
            btnToggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Set<String> cur = BackupState.get(MainActivity.this).getSelectedBuckets();
                    if (currentlyOn) cur.remove(folder.bucket);
                    else cur.add(folder.bucket);
                    BackupState st = BackupState.get(MainActivity.this);
                    st.saveConfig(st.isAutoBackupEnabled(), st.isWifiOnly(), st.isIncludeVideos(), st.getTargetFolder(), cur);
                    refreshNativeAlbumsTab();
                    applyFilterAndRefreshGrid();
                }
            });
            row.addView(btnToggle);

            albumFoldersContainer.addView(row);
        }
    }

    // ==================== STATUS POLLING ====================

    private void pollBackupStatus() {
        BackupState st = BackupState.get(this);
        String speedMb = st.currentSpeedBps > 0 ? String.format(Locale.US, "%.2f MB/s", st.currentSpeedBps / 1048576.0) : (st.currentProgress + "%");

        if (st.isRunning) {
            liveProgressBanner.setVisibility(View.VISIBLE);
            tvLiveTitle.setText("⚡ " + st.statusText + (st.currentFile.isEmpty() ? "" : (" · " + st.currentFile)));
            tvLiveSpeed.setText(speedMb);
            pbLiveProgress.setProgress(Math.max(5, st.currentProgress));
            btnTopBackup.setText("⏹️ 停止备份");
            btnTopBackup.setBackground(makeRoundRect("#7F1D1D", "#EF4444", dp(8)));
            if (btnBkStop != null) btnBkStop.setVisibility(View.VISIBLE);
        } else {
            liveProgressBanner.setVisibility(View.GONE);
            btnTopBackup.setText("⚡ 备份新照片");
            btnTopBackup.setBackground(makeRoundRect("#059669", "#10B981", dp(8)));
            if (btnBkStop != null) btnBkStop.setVisibility(View.GONE);
            if (wasRunningLastPoll) {
                loadUnifiedTimeline(true);
            }
        }
        wasRunningLastPoll = st.isRunning;

        if (tvBkStatusBadge != null) {
            tvBkStatusBadge.setText(st.statusText);
            pbBkProgress.setProgress(st.currentProgress);
            try {
                JSONObject j = new JSONObject(st.toJsonString());
                tvBkDetailLine.setText("已备份 " + j.optInt("totalBackedUpCount", 0) + " 项" + (st.currentFile.isEmpty() ? "" : (" · 当前: " + st.currentFile)));
                JSONArray logs = j.optJSONArray("logs");
                if (logs != null && logs.length() > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < Math.min(15, logs.length()); i++) {
                        sb.append(logs.getString(i)).append("\n");
                    }
                    tvBkLogs.setText(sb.toString().trim());
                }
            } catch (Exception ignored) {}
        }
    }

    // ==================== TAB SWITCHING & ALIST WEBVIEW ====================

    private LinearLayout createTabItem(String emoji, String label, boolean active) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(4), 0, dp(4));

        TextView icon = new TextView(this);
        icon.setText(emoji);
        icon.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        icon.setIncludeFontPadding(false);
        icon.setGravity(Gravity.CENTER);

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        txt.setIncludeFontPadding(false);
        txt.setPadding(0, dp(3), 0, 0);
        txt.setGravity(Gravity.CENTER);
        txt.setSingleLine(true);
        txt.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        txt.setTextColor(Color.parseColor(active ? "#38BDF8" : "#94A3B8"));

        item.addView(icon);
        item.addView(txt);
        return item;
    }

    public void selectTab(final String tab) {
        currentTab = tab;
        boolean isPhotos = "photos".equals(tab);
        boolean isAlbums = "albums".equals(tab);
        boolean isFiles = "files".equals(tab);

        tabTextPhotos.setTextColor(Color.parseColor(isPhotos ? "#38BDF8" : "#94A3B8"));
        tabTextPhotos.setTypeface(null, isPhotos ? Typeface.BOLD : Typeface.NORMAL);

        tabTextAlbums.setTextColor(Color.parseColor(isAlbums ? "#38BDF8" : "#94A3B8"));
        tabTextAlbums.setTypeface(null, isAlbums ? Typeface.BOLD : Typeface.NORMAL);

        tabTextFiles.setTextColor(Color.parseColor(isFiles ? "#38BDF8" : "#94A3B8"));
        tabTextFiles.setTypeface(null, isFiles ? Typeface.BOLD : Typeface.NORMAL);

        viewPhotosTab.setVisibility(isPhotos ? View.VISIBLE : View.GONE);
        viewAlbumsTab.setVisibility(isAlbums ? View.VISIBLE : View.GONE);
        alistWebView.setVisibility(isFiles ? View.VISIBLE : View.GONE);

        if (isAlbums) {
            refreshNativeAlbumsTab();
        } else if (isFiles && !alistLoaded) {
            alistLoaded = true;
            alistWebView.loadUrl("https://pan.czyxh.top/GoogleDrive");
        }
    }

    private void configureAlistWebView(WebView wv) {
        WebSettings ws = wv.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setLoadWithOverviewMode(false);
        ws.setUseWideViewPort(false);
        ws.setTextZoom(100);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setUserAgentString(USER_AGENT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
        wv.setBackgroundColor(Color.parseColor("#0B0F17"));

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String u = request.getUrl().toString();
                if (u.startsWith("http://") || u.startsWith("https://")) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception ignored) {}
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String js = "(function(){var s=document.createElement('style');s.innerHTML='#ct-fab-wrap{display:none!important;}';document.head.appendChild(s);})();";
                view.evaluateJavascript(js, null);
            }
        });

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (webViewFilePathCallback != null) webViewFilePathCallback.onReceiveValue(null);
                webViewFilePathCallback = filePathCallback;
                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(Intent.createChooser(intent, "选择要上传的文件"), REQ_WEBVIEW_FILE_CHOOSER);
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

    // ==================== PERMISSIONS, DOWNLOADS & HELPERS ====================

    private void pickFilesToUpload(String targetFolder, boolean mediaOnly) {
        manualUploadTargetFolder = targetFolder;
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        if (mediaOnly) {
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        }
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "选择要极速直传的照片/视频/文件"), REQ_PICK_MANUAL_UPLOAD);
    }

    private void enqueueSystemDownload(String url, String filename) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(filename);
            req.setDescription("正在通过 Cloudflare 边缘节点极速下载...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.addRequestHeader("User-Agent", USER_AGENT);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(this, "🚀 已加入系统下载队列: " + filename, Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
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
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERMS);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            loadUnifiedTimeline(false);
        }
    }

    private void startBackupService(String action, String targetFolder) {
        Intent i = new Intent(this, PhotoBackupService.class);
        i.setAction(action);
        if (targetFolder != null) i.putExtra(PhotoBackupService.EXTRA_TARGET_FOLDER, targetFolder);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private void stopBackupServiceNow() {
        Intent i = new Intent(this, PhotoBackupService.class);
        i.setAction(PhotoBackupService.ACTION_STOP_BACKUP);
        startService(i);
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
                    for (int i = 0; i < clip.getItemCount(); i++) results[i] = clip.getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            webViewFilePathCallback.onReceiveValue(results);
            webViewFilePathCallback = null;
        } else if (requestCode == REQ_PICK_MANUAL_UPLOAD && resultCode == Activity.RESULT_OK && data != null) {
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                ClipData clip = data.getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) picked.add(clip.getItemAt(i).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (!picked.isEmpty()) {
                synchronized (PhotoBackupService.pendingManualUris) {
                    PhotoBackupService.pendingManualUris.addAll(picked);
                }
                Toast.makeText(this, "⚡ 开始直传 " + picked.size() + " 个文件至云盘...", Toast.LENGTH_SHORT).show();
                startBackupService(PhotoBackupService.ACTION_UPLOAD_URIS, manualUploadTargetFolder);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (selectionMode) {
            selectionMode = false;
            selectedKeys.clear();
            updateSelectionUi();
            return;
        }
        if ("files".equals(currentTab) && alistWebView.canGoBack()) {
            alistWebView.goBack();
            return;
        }
        if (!"photos".equals(currentTab)) {
            selectTab("photos");
            return;
        }
        if (!"all".equals(currentFilter)) {
            setGalleryFilter("all");
            return;
        }
        super.onBackPressed();
    }

    private LinearLayout createCardLayout() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackground(makeRoundRect("#131B2E", "#1E293B", dp(12)));
        return card;
    }

    private TextView createActionButton(String text, String bgHex, String borderHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackground(makeRoundRect(bgHex, borderHex, dp(8)));
        return tv;
    }

    private GradientDrawable makeRoundRect(String fillHex, String strokeHex, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setCornerRadius(radiusPx);
        gd.setStroke(dp(1), Color.parseColor(strokeHex));
        return gd;
    }

    private LinearLayout.LayoutParams makeMarginLp(int rightMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = rightMarginPx;
        return lp;
    }

    private LinearLayout.LayoutParams makeBottomMarginLp(int bottomMarginPx) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = bottomMarginPx;
        return lp;
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return (bytes / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] dig = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : dig) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
