package top.czyxh.pan;

import android.Manifest;
import android.app.Activity;
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
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Calendar;
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
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 CloudPhotoPanApp/3.0";

    public static class GalleryItem {
        public String name;
        public long size;
        public long timestampMs;
        public String dayGroupTitle;
        public boolean isVideo;
        public long durationMs;
        public String bucket;
        public Uri localUri;
        public long mediaId;
        public boolean isOnCloud;
        public String cloudStatus;
        public String cloudPath;
        public String thumbUrl;
        public String fullUrl;
        public String downloadUrl;

        public String getUniqueKey() {
            return name != null ? name : (cloudPath != null ? cloudPath : String.valueOf(mediaId));
        }
    }

    public static class AlbumCardItem {
        public String id;
        public String title;
        public String subtitle;
        public int count;
        public GalleryItem coverItem;
        public boolean isPhoneBucket;
        public boolean autoBackupEnabled;
    }

    // Timeline Row Model for Date-Grouped ListView (Type 0 = Date Header, Type 1 = Photo Row of N columns)
    private static class TimelineRow {
        boolean isHeader;
        String dateTitle;
        List<GalleryItem> dayItems; // all items in this date group (for header select-all)
        List<GalleryItem> rowItems; // 3 or 4 items in this horizontal row
        List<Integer> rowGlobalIndices;
    }

    private final List<GalleryItem> allItems = new ArrayList<>();
    private final List<GalleryItem> filteredItems = new ArrayList<>();
    private final List<TimelineRow> timelineRows = new ArrayList<>();
    private final List<AlbumCardItem> albumCards = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();

    private boolean selectionMode = false;
    private String currentFilter = "all";
    private int gridColumns = 3;

    private LruCache<String, Bitmap> memoryCache;
    private ExecutorService thumbExecutor;
    private ExecutorService netExecutor;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // Root Tab Views
    private LinearLayout viewPhotosTab;
    private LinearLayout viewAlbumsTab;
    private ScrollView viewBackupTab;
    private WebView alistWebView;
    private boolean alistLoaded = false;
    private String currentTab = "photos";

    private ImageView navIconPhotos, navIconAlbums, navIconBackup, navIconFiles;
    private TextView navTextPhotos, navTextAlbums, navTextBackup, navTextFiles;

    // Tab 1: Date-Grouped Timeline Views
    private TextView tvTopTitle, tvCloudPill;
    private final Map<String, TextView> filterChips = new HashMap<>();
    private LinearLayout selectionBar;
    private TextView tvSelectionCount;
    private LinearLayout liveSyncBanner;
    private TextView tvLiveSyncTitle, tvLiveSyncSpeed;
    private ProgressBar pbLiveSync;
    private ListView timelineListView;
    private TextView tvEmptyState;
    private TimelineAdapter timelineAdapter;

    // Tab 2: Visual Album Cover Grid
    private GridView albumGridView;
    private AlbumGridAdapter albumGridAdapter;

    // Tab 3: Backup & Settings Views
    private TextView tvBkBadge, tvBkSummary, tvBkLogs;
    private ProgressBar pbBkBar;
    private TextView btnBkStop;
    private Switch swAutoBackup, swWifiOnly, swIncludeVideo;
    private LinearLayout bkFolderListLayout;

    private String manualUploadTargetFolder = "/GoogleDrive/photos";
    private ValueCallback<Uri[]> webViewFilePathCallback;
    private boolean wasRunningLastPoll = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        gridColumns = BackupState.get(this).getGridColumns();
        int maxMem = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(10 * 1024, maxMem / 5);
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

        viewPhotosTab = buildPhotosTimelineTab();
        viewAlbumsTab = buildAlbumsGridTab();
        viewAlbumsTab.setVisibility(View.GONE);
        viewBackupTab = buildBackupSettingsTab();
        viewBackupTab.setVisibility(View.GONE);

        alistWebView = new WebView(this);
        configureAlistWebView(alistWebView);
        alistWebView.setVisibility(View.GONE);

        contentFrame.addView(viewPhotosTab, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(viewAlbumsTab, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(viewBackupTab, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        contentFrame.addView(alistWebView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(contentFrame, frameLp);

        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#1E293B"));
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        // Clean 4-Tab Bottom Navigation Bar with Vector Icons
        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackgroundColor(Color.parseColor("#111726"));
        bottomBar.setGravity(Gravity.CENTER);

        LinearLayout navPhotos = createVectorTabItem(VectorIcons.ICON_PHOTOS, "照片", true);
        navIconPhotos = (ImageView) navPhotos.getChildAt(0);
        navTextPhotos = (TextView) navPhotos.getChildAt(1);
        navPhotos.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("photos".equals(currentTab) && !"all".equals(currentFilter)) {
                    setGalleryFilter("all");
                } else {
                    selectTab("photos");
                }
            }
        });

        LinearLayout navAlbums = createVectorTabItem(VectorIcons.ICON_ALBUMS, "相册", false);
        navIconAlbums = (ImageView) navAlbums.getChildAt(0);
        navTextAlbums = (TextView) navAlbums.getChildAt(1);
        navAlbums.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("albums");
            }
        });

        LinearLayout navBackup = createVectorTabItem(VectorIcons.ICON_CLOUD_SYNC, "云同步", false);
        navIconBackup = (ImageView) navBackup.getChildAt(0);
        navTextBackup = (TextView) navBackup.getChildAt(1);
        navBackup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("backup");
            }
        });

        LinearLayout navFiles = createVectorTabItem(VectorIcons.ICON_DRIVE, "网盘", false);
        navIconFiles = (ImageView) navFiles.getChildAt(0);
        navTextFiles = (TextView) navFiles.getChildAt(1);
        navFiles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectTab("files");
            }
        });

        bottomBar.addView(navPhotos, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomBar.addView(navAlbums, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomBar.addView(navBackup, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        bottomBar.addView(navFiles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));

        root.addView(bottomBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));
        setContentView(root);

        requestMediaPermissionsIfNeeded();
        loadUnifiedTimeline(false);

        if (BackupState.get(this).isAutoBackupEnabled()) {
            startBackupService(PhotoBackupService.ACTION_START_WATCHER, null);
        }

        uiHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollBackupStatus();
                uiHandler.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ==================== TAB 1: CLEAN DATE-GROUPED PHOTO TIMELINE ====================

    private LinearLayout buildPhotosTimelineTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0B0F17"));

        // Minimalist Flagship Top Bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#0B0F17"));
        topBar.setPadding(dp(16), dp(12), dp(12), dp(8));

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);

        tvTopTitle = new TextView(this);
        tvTopTitle.setText("照片");
        tvTopTitle.setTextColor(Color.WHITE);
        tvTopTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        tvTopTitle.setTypeface(null, Typeface.BOLD);
        tvTopTitle.setSingleLine(true);
        row1.addView(tvTopTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // Cloud Sync Status Pill
        tvCloudPill = new TextView(this);
        tvCloudPill.setText("云端已就绪");
        tvCloudPill.setTextColor(Color.parseColor("#38BDF8"));
        tvCloudPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvCloudPill.setTypeface(null, Typeface.BOLD);
        tvCloudPill.setPadding(dp(10), dp(5), dp(10), dp(5));
        tvCloudPill.setBackground(makeRoundRect("#152238", "#1E3A5F", dp(999)));
        tvCloudPill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (BackupState.get(MainActivity.this).isRunning) {
                    selectTab("backup");
                } else {
                    startBackupService(PhotoBackupService.ACTION_SCAN_AND_BACKUP, null);
                    Toast.makeText(MainActivity.this, "已触发相册增量备份扫描", Toast.LENGTH_SHORT).show();
                }
            }
        });
        row1.addView(tvCloudPill, makeMarginLp(dp(8)));

        // Column Layout Switch Button (3 cols <-> 4 cols)
        ImageView btnCols = new ImageView(this);
        btnCols.setImageDrawable(VectorIcons.create(VectorIcons.ICON_GRID, Color.parseColor("#CBD5E1"), dp(20)));
        btnCols.setPadding(dp(7), dp(7), dp(7), dp(7));
        btnCols.setBackground(makeRoundRect("#151E32", "#1E293B", dp(8)));
        btnCols.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gridColumns = (gridColumns == 3) ? 4 : 3;
                BackupState.get(MainActivity.this).setGridColumns(gridColumns);
                rebuildTimelineRows();
                timelineAdapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this, "已切换为每行 " + gridColumns + " 列视图", Toast.LENGTH_SHORT).show();
            }
        });
        row1.addView(btnCols, new LinearLayout.LayoutParams(dp(34), dp(34)));

        View spacer = new View(this);
        row1.addView(spacer, new LinearLayout.LayoutParams(dp(6), dp(1)));

        // Upload Plus Button
        ImageView btnUpload = new ImageView(this);
        btnUpload.setImageDrawable(VectorIcons.create(VectorIcons.ICON_PLUS, Color.WHITE, dp(20)));
        btnUpload.setPadding(dp(7), dp(7), dp(7), dp(7));
        btnUpload.setBackground(makeRoundRect("#0284C7", "#38BDF8", dp(8)));
        btnUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFilesToUpload("/GoogleDrive/photos", false);
            }
        });
        row1.addView(btnUpload, new LinearLayout.LayoutParams(dp(34), dp(34)));

        topBar.addView(row1);

        // Subtle Filter Bar
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.setPadding(0, dp(10), 0, dp(2));

        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setGravity(Gravity.CENTER_VERTICAL);

        addFilterChip(chipRow, "all", "全部");
        addFilterChip(chipRow, "cloud", "已备份");
        addFilterChip(chipRow, "local", "未备份");
        addFilterChip(chipRow, "video", "视频");
        addFilterChip(chipRow, "fav", "收藏");

        hsv.addView(chipRow);
        topBar.addView(hsv);
        layout.addView(topBar);

        // Selection Contextual Action Bar (Only visible when items are selected)
        selectionBar = new LinearLayout(this);
        selectionBar.setOrientation(LinearLayout.HORIZONTAL);
        selectionBar.setGravity(Gravity.CENTER_VERTICAL);
        selectionBar.setPadding(dp(14), dp(8), dp(12), dp(8));
        selectionBar.setBackground(makeRoundRect("#162338", "#0284C7", dp(10)));
        LinearLayout.LayoutParams selLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        selLp.setMargins(dp(12), dp(4), dp(12), dp(4));
        selectionBar.setVisibility(View.GONE);

        tvSelectionCount = new TextView(this);
        tvSelectionCount.setText("已选 0 项");
        tvSelectionCount.setTextColor(Color.WHITE);
        tvSelectionCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tvSelectionCount.setTypeface(null, Typeface.BOLD);
        selectionBar.addView(tvSelectionCount, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnSelAll = createPillButton("全选", "#1E293B", "#334155");
        btnSelAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selectedKeys.size() == filteredItems.size()) {
                    selectedKeys.clear();
                } else {
                    for (GalleryItem it : filteredItems) selectedKeys.add(it.getUniqueKey());
                }
                updateSelectionUi();
            }
        });
        selectionBar.addView(btnSelAll, makeMarginLp(dp(6)));

        TextView btnSelUpload = createPillButton("备份上云", "#0284C7", "#38BDF8");
        btnSelUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                uploadSelectedLocalItems();
            }
        });
        selectionBar.addView(btnSelUpload, makeMarginLp(dp(6)));

        TextView btnSelDelete = createPillButton("删除云端", "#7F1D1D", "#EF4444");
        btnSelDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                deleteSelectedCloudItems();
            }
        });
        selectionBar.addView(btnSelDelete, makeMarginLp(dp(6)));

        TextView btnSelCancel = createPillButton("完成", "#1E293B", "#334155");
        btnSelCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                selectionMode = false;
                selectedKeys.clear();
                updateSelectionUi();
            }
        });
        selectionBar.addView(btnSelCancel);
        layout.addView(selectionBar, selLp);

        // Live Sync Progress Banner
        liveSyncBanner = new LinearLayout(this);
        liveSyncBanner.setOrientation(LinearLayout.VERTICAL);
        liveSyncBanner.setPadding(dp(12), dp(8), dp(12), dp(8));
        liveSyncBanner.setBackground(makeRoundRect("#132036", "#0284C7", dp(10)));
        LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        syncLp.setMargins(dp(12), dp(4), dp(12), dp(4));
        liveSyncBanner.setVisibility(View.GONE);

        LinearLayout syncRow = new LinearLayout(this);
        syncRow.setOrientation(LinearLayout.HORIZONTAL);
        syncRow.setGravity(Gravity.CENTER_VERTICAL);

        tvLiveSyncTitle = new TextView(this);
        tvLiveSyncTitle.setTextColor(Color.parseColor("#E0F2FE"));
        tvLiveSyncTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvLiveSyncTitle.setSingleLine(true);
        tvLiveSyncTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        syncRow.addView(tvLiveSyncTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvLiveSyncSpeed = new TextView(this);
        tvLiveSyncSpeed.setTextColor(Color.parseColor("#38BDF8"));
        tvLiveSyncSpeed.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvLiveSyncSpeed.setTypeface(null, Typeface.BOLD);
        tvLiveSyncSpeed.setPadding(dp(8), 0, dp(8), 0);
        syncRow.addView(tvLiveSyncSpeed);

        TextView btnStopInline = createPillButton("停止", "#7F1D1D", "#EF4444");
        btnStopInline.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopBackupServiceNow();
            }
        });
        syncRow.addView(btnStopInline);
        liveSyncBanner.addView(syncRow);

        pbLiveSync = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbLiveSync.setMax(100);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        pbLp.topMargin = dp(6);
        liveSyncBanner.addView(pbLiveSync, pbLp);
        layout.addView(liveSyncBanner, syncLp);

        // Date-Grouped Timeline ListView
        FrameLayout listFrame = new FrameLayout(this);
        timelineListView = new ListView(this);
        timelineListView.setDivider(null);
        timelineListView.setDividerHeight(0);
        timelineListView.setSelector(android.R.color.transparent);
        timelineListView.setPadding(0, 0, 0, dp(24));
        timelineListView.setClipToPadding(false);

        timelineAdapter = new TimelineAdapter();
        timelineListView.setAdapter(timelineAdapter);

        tvEmptyState = new TextView(this);
        tvEmptyState.setText("暂无照片或视频\n点击右上角 + 上传，或在「相册」页开启相册目录");
        tvEmptyState.setTextColor(Color.parseColor("#64748B"));
        tvEmptyState.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tvEmptyState.setGravity(Gravity.CENTER);
        tvEmptyState.setVisibility(View.GONE);

        listFrame.addView(timelineListView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        listFrame.addView(tvEmptyState, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        layout.addView(listFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return layout;
    }

    private void addFilterChip(LinearLayout parent, final String key, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setSingleLine(true);
        tv.setPadding(dp(13), dp(5), dp(13), dp(5));
        styleFilterChip(tv, key.equals(currentFilter));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setGalleryFilter(key);
            }
        });
        filterChips.put(key, tv);
        parent.addView(tv, makeMarginLp(dp(6)));
    }

    private void styleFilterChip(TextView tv, boolean active) {
        tv.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        tv.setTextColor(Color.parseColor(active ? "#FFFFFF" : "#94A3B8"));
        tv.setBackground(makeRoundRect(active ? "#0284C7" : "#151E32", active ? "#38BDF8" : "#1E293B", dp(999)));
    }

    private void setGalleryFilter(String filterKey) {
        currentFilter = filterKey;
        for (Map.Entry<String, TextView> e : filterChips.entrySet()) {
            styleFilterChip(e.getValue(), e.getKey().equals(filterKey));
        }
        if (filterKey.startsWith("bucket:")) {
            tvTopTitle.setText(filterKey.substring(7));
        } else {
            tvTopTitle.setText("照片");
        }
        applyFilterAndRefreshTimeline();
    }

    private void updateSelectionUi() {
        if (selectedKeys.isEmpty()) {
            selectionMode = false;
        }
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        tvSelectionCount.setText("已选择 " + selectedKeys.size() + " 项");
        timelineAdapter.notifyDataSetChanged();
    }

    // ==================== DATE-GROUPED TIMELINE ADAPTER ====================

    private void rebuildTimelineRows() {
        timelineRows.clear();
        if (filteredItems.isEmpty()) return;

        Map<String, List<GalleryItem>> grouped = new LinkedHashMap<>();
        Map<String, List<Integer>> groupedIndices = new LinkedHashMap<>();

        for (int i = 0; i < filteredItems.size(); i++) {
            GalleryItem it = filteredItems.get(i);
            String g = it.dayGroupTitle != null ? it.dayGroupTitle : "更早";
            if (!grouped.containsKey(g)) {
                grouped.put(g, new ArrayList<GalleryItem>());
                groupedIndices.put(g, new ArrayList<Integer>());
            }
            grouped.get(g).add(it);
            groupedIndices.get(g).add(i);
        }

        int cols = Math.max(2, Math.min(5, gridColumns));
        for (Map.Entry<String, List<GalleryItem>> entry : grouped.entrySet()) {
            String dateTitle = entry.getKey();
            List<GalleryItem> dayList = entry.getValue();
            List<Integer> dayIdxList = groupedIndices.get(dateTitle);

            TimelineRow header = new TimelineRow();
            header.isHeader = true;
            header.dateTitle = dateTitle;
            header.dayItems = dayList;
            timelineRows.add(header);

            for (int i = 0; i < dayList.size(); i += cols) {
                TimelineRow row = new TimelineRow();
                row.isHeader = false;
                int end = Math.min(i + cols, dayList.size());
                row.rowItems = dayList.subList(i, end);
                row.rowGlobalIndices = dayIdxList.subList(i, end);
                timelineRows.add(row);
            }
        }
    }

    private class TimelineAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return timelineRows.size();
        }

        @Override
        public Object getItem(int position) {
            return timelineRows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return timelineRows.get(position).isHeader ? 0 : 1;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final TimelineRow row = timelineRows.get(position);
            if (row.isHeader) {
                LinearLayout headerView = (LinearLayout) convertView;
                if (headerView == null) {
                    headerView = new LinearLayout(MainActivity.this);
                    headerView.setOrientation(LinearLayout.HORIZONTAL);
                    headerView.setGravity(Gravity.CENTER_VERTICAL);
                    headerView.setPadding(dp(14), dp(14), dp(14), dp(8));

                    TextView tvDate = new TextView(MainActivity.this);
                    tvDate.setTextColor(Color.parseColor("#E2E8F0"));
                    tvDate.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                    tvDate.setTypeface(null, Typeface.BOLD);
                    headerView.addView(tvDate, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                    ImageView ivDaySelect = new ImageView(MainActivity.this);
                    headerView.addView(ivDaySelect, new LinearLayout.LayoutParams(dp(20), dp(20)));
                }

                TextView tvDate = (TextView) headerView.getChildAt(0);
                ImageView ivDaySelect = (ImageView) headerView.getChildAt(1);
                tvDate.setText(row.dateTitle);

                boolean allDaySelected = true;
                for (GalleryItem it : row.dayItems) {
                    if (!selectedKeys.contains(it.getUniqueKey())) {
                        allDaySelected = false;
                        break;
                    }
                }
                ivDaySelect.setImageDrawable(VectorIcons.create(
                        allDaySelected ? VectorIcons.ICON_CHECK_CIRCLE : VectorIcons.ICON_UNCHECK_CIRCLE,
                        allDaySelected ? Color.parseColor("#0284C7") : Color.parseColor("#64748B"),
                        dp(20)
                ));
                final boolean finalAllSelected = allDaySelected;
                ivDaySelect.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        selectionMode = true;
                        for (GalleryItem it : row.dayItems) {
                            if (finalAllSelected) selectedKeys.remove(it.getUniqueKey());
                            else selectedKeys.add(it.getUniqueKey());
                        }
                        updateSelectionUi();
                    }
                });
                return headerView;
            } else {
                // Photo Row with N square cells
                int cols = Math.max(2, Math.min(5, gridColumns));
                LinearLayout rowLayout = (LinearLayout) convertView;
                if (rowLayout == null || rowLayout.getChildCount() != cols) {
                    rowLayout = new LinearLayout(MainActivity.this);
                    rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                    rowLayout.setPadding(dp(2), 0, dp(2), dp(3));

                    for (int c = 0; c < cols; c++) {
                        SquareFrameLayout cell = new SquareFrameLayout(MainActivity.this);
                        LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        cellLp.setMargins(dp(1), 0, dp(1), 0);

                        ImageView ivPhoto = new ImageView(MainActivity.this);
                        ivPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
                        ivPhoto.setBackgroundColor(Color.parseColor("#131C2E"));
                        cell.addView(ivPhoto, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                        // Subtle bottom-left vector cloud icon (NO ugly text labels!)
                        ImageView ivCloudBadge = new ImageView(MainActivity.this);
                        FrameLayout.LayoutParams cbLp = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.BOTTOM | Gravity.START);
                        cbLp.setMargins(dp(5), 0, 0, dp(5));
                        ivCloudBadge.setPadding(dp(2), dp(2), dp(2), dp(2));
                        ivCloudBadge.setBackground(makeRoundRect("#88000000", "#00000000", dp(99)));
                        cell.addView(ivCloudBadge, cbLp);

                        // Subtle bottom-right video duration pill (only visible for videos!)
                        TextView tvVideoPill = new TextView(MainActivity.this);
                        tvVideoPill.setTextColor(Color.WHITE);
                        tvVideoPill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                        tvVideoPill.setTypeface(null, Typeface.BOLD);
                        tvVideoPill.setPadding(dp(5), dp(1), dp(5), dp(1));
                        tvVideoPill.setBackground(makeRoundRect("#99000000", "#00000000", dp(4)));
                        FrameLayout.LayoutParams vpLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
                        vpLp.setMargins(0, 0, dp(5), dp(5));
                        cell.addView(tvVideoPill, vpLp);

                        // Top-right selection check circle
                        ImageView ivCheck = new ImageView(MainActivity.this);
                        FrameLayout.LayoutParams ckLp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
                        ckLp.setMargins(0, dp(5), dp(5), 0);
                        cell.addView(ivCheck, ckLp);

                        CellHolder ch = new CellHolder();
                        ch.cell = cell;
                        ch.ivPhoto = ivPhoto;
                        ch.ivCloudBadge = ivCloudBadge;
                        ch.tvVideoPill = tvVideoPill;
                        ch.ivCheck = ivCheck;
                        cell.setTag(ch);
                        rowLayout.addView(cell, cellLp);
                    }
                }

                for (int c = 0; c < cols; c++) {
                    SquareFrameLayout cell = (SquareFrameLayout) rowLayout.getChildAt(c);
                    final CellHolder ch = (CellHolder) cell.getTag();
                    if (c >= row.rowItems.size()) {
                        cell.setVisibility(View.INVISIBLE);
                        ch.boundKey = "";
                        continue;
                    }
                    cell.setVisibility(View.VISIBLE);
                    final GalleryItem item = row.rowItems.get(c);
                    final int globalIdx = row.rowGlobalIndices.get(c);
                    final String key = item.getUniqueKey();
                    ch.boundKey = key;

                    // Cloud icon: only show subtle cloud-done icon when backed up
                    if (item.isOnCloud) {
                        ch.ivCloudBadge.setVisibility(View.VISIBLE);
                        ch.ivCloudBadge.setImageDrawable(VectorIcons.create(VectorIcons.ICON_CLOUD_DONE, Color.WHITE, dp(14)));
                    } else {
                        ch.ivCloudBadge.setVisibility(View.GONE);
                    }

                    // Video duration badge: only for videos! Photos remain 100% clean!
                    if (item.isVideo) {
                        ch.tvVideoPill.setVisibility(View.VISIBLE);
                        ch.tvVideoPill.setText(item.durationMs > 0 ? ("▶ " + formatDuration(item.durationMs)) : "▶ 视频");
                    } else {
                        ch.tvVideoPill.setVisibility(View.GONE);
                    }

                    // Selection overlay
                    if (selectionMode) {
                        ch.ivCheck.setVisibility(View.VISIBLE);
                        boolean sel = selectedKeys.contains(key);
                        ch.ivCheck.setImageDrawable(VectorIcons.create(
                                sel ? VectorIcons.ICON_CHECK_CIRCLE : VectorIcons.ICON_UNCHECK_CIRCLE,
                                sel ? Color.parseColor("#0284C7") : Color.WHITE,
                                dp(22)
                        ));
                        ch.ivPhoto.setAlpha(sel ? 0.78f : 1.0f);
                    } else {
                        ch.ivCheck.setVisibility(View.GONE);
                        ch.ivPhoto.setAlpha(1.0f);
                    }

                    loadItemThumbnail(item, ch.ivPhoto, ch);

                    cell.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (selectionMode) {
                                if (selectedKeys.contains(key)) selectedKeys.remove(key);
                                else selectedKeys.add(key);
                                updateSelectionUi();
                            } else {
                                openViewerDialog(globalIdx);
                            }
                        }
                    });

                    cell.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            selectionMode = true;
                            selectedKeys.add(key);
                            updateSelectionUi();
                            return true;
                        }
                    });
                }
                return rowLayout;
            }
        }
    }

    private static class CellHolder {
        SquareFrameLayout cell;
        ImageView ivPhoto;
        ImageView ivCloudBadge;
        TextView tvVideoPill;
        ImageView ivCheck;
        String boundKey;
    }

    public static class SquareFrameLayout extends FrameLayout {
        public SquareFrameLayout(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, widthMeasureSpec);
        }
    }

    // ==================== TAB 2: VISUAL 2-COLUMN ALBUM COVER CARDS ====================

    private LinearLayout buildAlbumsGridTab() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#0B0F17"));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(14), dp(16), dp(10));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("相册");
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        tvTitle.setTypeface(null, Typeface.BOLD);
        topBar.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvHint = new TextView(this);
        tvHint.setText("点击右上角云标可开关该相册自动备份");
        tvHint.setTextColor(Color.parseColor("#64748B"));
        tvHint.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        topBar.addView(tvHint);
        layout.addView(topBar);

        albumGridView = new GridView(this);
        albumGridView.setNumColumns(2);
        albumGridView.setHorizontalSpacing(dp(12));
        albumGridView.setVerticalSpacing(dp(14));
        albumGridView.setPadding(dp(14), dp(6), dp(14), dp(24));
        albumGridView.setClipToPadding(false);
        albumGridView.setSelector(android.R.color.transparent);

        albumGridAdapter = new AlbumGridAdapter();
        albumGridView.setAdapter(albumGridAdapter);

        layout.addView(albumGridView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return layout;
    }

    private void rebuildAlbumCards() {
        albumCards.clear();
        Set<String> enabledBuckets = BackupState.get(this).getSelectedBuckets();
        Set<String> favKeys = BackupState.get(this).getFavoritesSet();

        GalleryItem firstCloud = null;
        GalleryItem firstVideo = null;
        GalleryItem firstFav = null;
        int cloudCount = 0, videoCount = 0, favCount = 0;

        Map<String, AlbumCardItem> bucketMap = new LinkedHashMap<>();

        synchronized (allItems) {
            for (GalleryItem it : allItems) {
                if (it.isOnCloud) {
                    cloudCount++;
                    if (firstCloud == null) firstCloud = it;
                }
                if (it.isVideo) {
                    videoCount++;
                    if (firstVideo == null) firstVideo = it;
                }
                if (favKeys.contains(it.getUniqueKey())) {
                    favCount++;
                    if (firstFav == null) firstFav = it;
                }
                if (it.localUri != null && it.bucket != null) {
                    AlbumCardItem card = bucketMap.get(it.bucket);
                    if (card == null) {
                        card = new AlbumCardItem();
                        card.id = "bucket:" + it.bucket;
                        card.title = translateBucketName(it.bucket);
                        card.subtitle = it.bucket;
                        card.count = 0;
                        card.coverItem = it;
                        card.isPhoneBucket = true;
                        card.autoBackupEnabled = enabledBuckets.contains(it.bucket);
                        bucketMap.put(it.bucket, card);
                    }
                    card.count++;
                }
            }
        }

        // Smart Cloud Album Card
        AlbumCardItem cloudCard = new AlbumCardItem();
        cloudCard.id = "cloud";
        cloudCard.title = "云端已备份";
        cloudCard.subtitle = "Google Drive";
        cloudCard.count = cloudCount;
        cloudCard.coverItem = firstCloud;
        albumCards.add(cloudCard);

        // Smart Favorites Album Card
        if (favCount > 0) {
            AlbumCardItem favCard = new AlbumCardItem();
            favCard.id = "fav";
            favCard.title = "我的收藏";
            favCard.subtitle = "精选照片";
            favCard.count = favCount;
            favCard.coverItem = firstFav;
            albumCards.add(favCard);
        }

        // Smart Videos Album Card
        if (videoCount > 0) {
            AlbumCardItem vidCard = new AlbumCardItem();
            vidCard.id = "video";
            vidCard.title = "视频";
            vidCard.subtitle = "全部视频";
            vidCard.count = videoCount;
            vidCard.coverItem = firstVideo;
            albumCards.add(vidCard);
        }

        albumCards.addAll(bucketMap.values());
        if (albumGridAdapter != null) {
            albumGridAdapter.notifyDataSetChanged();
        }
    }

    private String translateBucketName(String b) {
        if ("Camera".equalsIgnoreCase(b)) return "相机";
        if ("Screenshots".equalsIgnoreCase(b) || "Screenshot".equalsIgnoreCase(b)) return "截屏";
        if ("WeiXin".equalsIgnoreCase(b) || "WeChat".equalsIgnoreCase(b)) return "微信";
        if ("Movies".equalsIgnoreCase(b)) return "电影与视频";
        if ("Download".equalsIgnoreCase(b)) return "下载";
        return b;
    }

    private class AlbumGridAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return albumCards.size();
        }

        @Override
        public Object getItem(int position) {
            return albumCards.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final AlbumCardItem card = albumCards.get(position);

            LinearLayout cardWrap = new LinearLayout(MainActivity.this);
            cardWrap.setOrientation(LinearLayout.VERTICAL);

            SquareFrameLayout coverBox = new SquareFrameLayout(MainActivity.this);
            coverBox.setBackground(makeRoundRect("#151E32", "#1E293B", dp(14)));

            ImageView ivCover = new ImageView(MainActivity.this);
            ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            coverBox.addView(ivCover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (card.coverItem != null) {
                CellHolder ch = new CellHolder();
                ch.boundKey = card.coverItem.getUniqueKey();
                loadItemThumbnail(card.coverItem, ivCover, ch);
            }

            // If phone folder, show auto-backup toggle badge on top-right of cover
            if (card.isPhoneBucket) {
                TextView tvBackupTag = new TextView(MainActivity.this);
                tvBackupTag.setText(card.autoBackupEnabled ? "自动备份中" : "点击开启备份");
                tvBackupTag.setTextColor(Color.WHITE);
                tvBackupTag.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                tvBackupTag.setTypeface(null, Typeface.BOLD);
                tvBackupTag.setPadding(dp(8), dp(4), dp(8), dp(4));
                tvBackupTag.setBackground(makeRoundRect(card.autoBackupEnabled ? "#CC0284C7" : "#990F172A", "#33FFFFFF", dp(999)));
                FrameLayout.LayoutParams tagLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.END);
                tagLp.setMargins(0, dp(8), dp(8), 0);
                tvBackupTag.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Set<String> cur = BackupState.get(MainActivity.this).getSelectedBuckets();
                        if (card.autoBackupEnabled) cur.remove(card.subtitle);
                        else cur.add(card.subtitle);
                        BackupState st = BackupState.get(MainActivity.this);
                        st.saveConfig(st.isAutoBackupEnabled(), st.isWifiOnly(), st.isIncludeVideos(), st.getTargetFolder(), cur);
                        rebuildAlbumCards();
                        applyFilterAndRefreshTimeline();
                    }
                });
                coverBox.addView(tvBackupTag, tagLp);
            }

            cardWrap.addView(coverBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView tvName = new TextView(MainActivity.this);
            tvName.setText(card.title);
            tvName.setTextColor(Color.WHITE);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setSingleLine(true);
            tvName.setPadding(dp(4), dp(6), dp(4), 0);
            cardWrap.addView(tvName);

            TextView tvCount = new TextView(MainActivity.this);
            tvCount.setText(card.count + " 项");
            tvCount.setTextColor(Color.parseColor("#94A3B8"));
            tvCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            tvCount.setPadding(dp(4), dp(1), dp(4), 0);
            cardWrap.addView(tvCount);

            cardWrap.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab("photos");
                    setGalleryFilter(card.id);
                }
            });

            return cardWrap;
        }
    }

    // ==================== TAB 3: CLOUD BACKUP & SETTINGS ====================

    private ScrollView buildBackupSettingsTab() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#0B0F17"));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(14), dp(14), dp(14), dp(30));

        TextView pageTitle = new TextView(this);
        pageTitle.setText("云同步与备份设置");
        pageTitle.setTextColor(Color.WHITE);
        pageTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        pageTitle.setTypeface(null, Typeface.BOLD);
        pageTitle.setPadding(dp(2), 0, 0, dp(12));
        container.addView(pageTitle);

        // Status Card
        LinearLayout cardStatus = createCardLayout();
        LinearLayout headRow = new LinearLayout(this);
        headRow.setOrientation(LinearLayout.HORIZONTAL);
        headRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvCardTitle = new TextView(this);
        tvCardTitle.setText("备份引擎状态");
        tvCardTitle.setTextColor(Color.WHITE);
        tvCardTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tvCardTitle.setTypeface(null, Typeface.BOLD);
        headRow.addView(tvCardTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        tvBkBadge = new TextView(this);
        tvBkBadge.setText("已就绪");
        tvBkBadge.setTextColor(Color.parseColor("#38BDF8"));
        tvBkBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvBkBadge.setTypeface(null, Typeface.BOLD);
        headRow.addView(tvBkBadge);
        cardStatus.addView(headRow);

        tvBkSummary = new TextView(this);
        tvBkSummary.setText("已备份 0 项 · 手机直传 Cloudflare R2 自动归档 Google Drive");
        tvBkSummary.setTextColor(Color.parseColor("#94A3B8"));
        tvBkSummary.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvBkSummary.setPadding(0, dp(6), 0, dp(8));
        cardStatus.addView(tvBkSummary);

        pbBkBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbBkBar.setMax(100);
        cardStatus.addView(pbBkBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(12), 0, 0);

        TextView btnStart = createPillButton("立即扫描备份", "#0284C7", "#38BDF8");
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startBackupService(PhotoBackupService.ACTION_SCAN_AND_BACKUP, null);
            }
        });
        btnRow.addView(btnStart, makeMarginLp(dp(8)));

        btnBkStop = createPillButton("停止传输", "#7F1D1D", "#EF4444");
        btnBkStop.setVisibility(View.GONE);
        btnBkStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopBackupServiceNow();
            }
        });
        btnRow.addView(btnBkStop, makeMarginLp(dp(8)));

        TextView btnRecompare = createPillButton("重新比对云端", "#1E293B", "#334155");
        btnRecompare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BackupState.get(MainActivity.this).clearBackedUpLedger();
                loadUnifiedTimeline(true);
                Toast.makeText(MainActivity.this, "已重新同步云端列表", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnRecompare);
        cardStatus.addView(btnRow);
        container.addView(cardStatus, makeBottomMarginLp(dp(12)));

        // Strategy Switches Card
        LinearLayout cardSwitches = createCardLayout();
        BackupState st = BackupState.get(this);
        swAutoBackup = addSwitchRow(cardSwitches, "开启后台自动备份", "拍照或录像后自动直传云端", st.isAutoBackupEnabled());
        swWifiOnly = addSwitchRow(cardSwitches, "仅在 Wi-Fi 下自动备份", "防止消耗移动蜂窝流量", st.isWifiOnly());
        swIncludeVideo = addSwitchRow(cardSwitches, "同时备份视频文件", "支持 4K 视频分片极速同步", st.isIncludeVideos());
        container.addView(cardSwitches, makeBottomMarginLp(dp(12)));

        // Logs Card
        LinearLayout cardLogs = createCardLayout();
        TextView logHead = new TextView(this);
        logHead.setText("传输记录");
        logHead.setTextColor(Color.WHITE);
        logHead.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        logHead.setTypeface(null, Typeface.BOLD);
        logHead.setPadding(0, 0, 0, dp(6));
        cardLogs.addView(logHead);

        tvBkLogs = new TextView(this);
        tvBkLogs.setText("暂无传输记录");
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

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t1 = new TextView(this);
        t1.setText(title);
        t1.setTextColor(Color.WHITE);
        t1.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        TextView t2 = new TextView(this);
        t2.setText(sub);
        t2.setTextColor(Color.parseColor("#94A3B8"));
        t2.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        col.addView(t1);
        col.addView(t2);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                BackupState st = BackupState.get(MainActivity.this);
                st.saveConfig(swAutoBackup.isChecked(), swWifiOnly.isChecked(), swIncludeVideo.isChecked(), st.getTargetFolder(), st.getSelectedBuckets());
                if (swAutoBackup.isChecked()) {
                    startBackupService(PhotoBackupService.ACTION_START_WATCHER, null);
                }
            }
        });
        row.addView(sw);
        parent.addView(row);
        return sw;
    }

    // ==================== UNIFIED LOCAL + CLOUD DATA ENGINE ====================

    private void loadUnifiedTimeline(final boolean forceCloudRefresh) {
        netExecutor.submit(new Runnable() {
            @Override
            public void run() {
                final List<GalleryItem> localList = queryLocalMediaItems();
                synchronized (allItems) {
                    if (allItems.isEmpty() && !localList.isEmpty()) {
                        allItems.addAll(localList);
                    }
                }
                uiHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyFilterAndRefreshTimeline();
                        rebuildAlbumCards();
                    }
                });

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
                        applyFilterAndRefreshTimeline();
                        rebuildAlbumCards();
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
        String[] proj = isVideo ? new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.Video.VideoColumns.DURATION
        } : new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        Cursor c = null;
        try {
            c = getContentResolver().query(contentUri, proj, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
            if (c != null) {
                int idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                int nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                int sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                int bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
                int dateCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
                int durCol = isVideo ? c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) : -1;

                int count = 0;
                while (c.moveToNext() && count < 800) {
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
                    if (isVideo && durCol >= 0) it.durationMs = c.getLong(durCol);
                    it.timestampMs = dateSec * 1000L;
                    it.dayGroupTitle = formatDayGroupTitle(it.timestampMs);
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
                        it.bucket = "云端相册";

                        String mod = o.optString("modified", "");
                        it.timestampMs = parseIsoTime(mod);
                        it.dayGroupTitle = formatDayGroupTitle(it.timestampMs);
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

    private String formatDayGroupTitle(long tsMs) {
        Calendar now = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(tsMs);

        if (now.get(Calendar.YEAR) == target.get(Calendar.YEAR)) {
            int dayDiff = now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR);
            if (dayDiff == 0) return "今天";
            if (dayDiff == 1) return "昨天";
            SimpleDateFormat sdf = new SimpleDateFormat("M月d日 EEEE", Locale.CHINA);
            return sdf.format(new Date(tsMs));
        }
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年M月d日", Locale.CHINA);
        return sdf.format(new Date(tsMs));
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

    private void applyFilterAndRefreshTimeline() {
        filteredItems.clear();
        int cloudCount = 0;

        Set<String> enabledBuckets = BackupState.get(this).getSelectedBuckets();
        Set<String> favKeys = BackupState.get(this).getFavoritesSet();

        synchronized (allItems) {
            for (GalleryItem it : allItems) {
                boolean inMainTimeline = it.isOnCloud
                        || enabledBuckets.contains(it.bucket)
                        || "Camera".equalsIgnoreCase(it.bucket)
                        || "DCIM".equalsIgnoreCase(it.bucket);

                if (it.isOnCloud) cloudCount++;

                if ("cloud".equals(currentFilter)) {
                    if (it.isOnCloud) filteredItems.add(it);
                } else if ("local".equals(currentFilter)) {
                    if (!it.isOnCloud && it.localUri != null && inMainTimeline) filteredItems.add(it);
                } else if ("video".equals(currentFilter)) {
                    if (it.isVideo && inMainTimeline) filteredItems.add(it);
                } else if ("fav".equals(currentFilter)) {
                    if (favKeys.contains(it.getUniqueKey())) filteredItems.add(it);
                } else if (currentFilter.startsWith("bucket:")) {
                    String targetB = currentFilter.substring(7);
                    if (targetB.equals(it.bucket)) filteredItems.add(it);
                } else {
                    if (inMainTimeline) filteredItems.add(it);
                }
            }
        }

        if (!BackupState.get(this).isRunning) {
            tvCloudPill.setText("已备份 " + cloudCount + " 项");
        }
        rebuildTimelineRows();
        tvEmptyState.setVisibility(filteredItems.isEmpty() ? View.VISIBLE : View.GONE);
        timelineAdapter.notifyDataSetChanged();
    }

    // ==================== FULLSCREEN VIEWER INTEGRATION ====================

    private void openViewerDialog(int startIndex) {
        PhotoViewerDialog viewer = new PhotoViewerDialog(this, filteredItems, startIndex, new PhotoViewerDialog.ViewerCallbacks() {
            @Override
            public Bitmap getCachedThumb(String key) {
                return memoryCache.get(key);
            }

            @Override
            public void loadHighResBitmap(final GalleryItem item, final PhotoViewerDialog.BitmapCallback cb) {
                netExecutor.submit(new Runnable() {
                    @Override
                    public void run() {
                        Bitmap fullBmp = null;
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
                                            if (w > 2200 || h > 2200) {
                                                float r = Math.min(2200f / w, 2200f / h);
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

                        if (fullBmp == null && item.fullUrl != null && !item.fullUrl.isEmpty() && !item.isVideo) {
                            fullBmp = loadNetworkBitmapWithDiskCache(item.fullUrl, 2048);
                        }

                        final Bitmap finalBmp = fullBmp;
                        uiHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.onLoaded(finalBmp);
                            }
                        });
                    }
                });
            }

            @Override
            public void onUploadItem(GalleryItem item) {
                if (item.localUri == null) return;
                synchronized (PhotoBackupService.pendingManualUris) {
                    PhotoBackupService.pendingManualUris.add(item.localUri);
                }
                Toast.makeText(MainActivity.this, "已加入云端备份队列: " + item.name, Toast.LENGTH_SHORT).show();
                startBackupService(PhotoBackupService.ACTION_UPLOAD_URIS, "/GoogleDrive/photos");
            }

            @Override
            public void onDownloadItem(GalleryItem item) {
                if (item.downloadUrl != null && !item.downloadUrl.isEmpty()) {
                    enqueueSystemDownload(item.downloadUrl, item.name);
                }
            }

            @Override
            public void onDeleteItem(GalleryItem item) {
                selectedKeys.clear();
                selectedKeys.add(item.getUniqueKey());
                deleteSelectedCloudItems();
            }

            @Override
            public boolean isFavorite(String key) {
                return BackupState.get(MainActivity.this).isFavorite(key);
            }

            @Override
            public void toggleFavorite(String key) {
                BackupState.get(MainActivity.this).toggleFavorite(key);
                rebuildAlbumCards();
            }
        });
        viewer.show();
    }

    // ==================== THUMBNAIL LOADER & CACHE ====================

    private void loadItemThumbnail(final GalleryItem item, final ImageView iv, final CellHolder ch) {
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
                if (ch != null && !key.equals(ch.boundKey)) return;
                Bitmap bmp = null;

                if (item.localUri != null) {
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            bmp = getContentResolver().loadThumbnail(item.localUri, new Size(340, 340), null);
                        } else {
                            if (item.isVideo) {
                                bmp = MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(), item.mediaId, MediaStore.Video.Thumbnails.MINI_KIND, null);
                            } else {
                                bmp = MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(), item.mediaId, MediaStore.Images.Thumbnails.MINI_KIND, null);
                            }
                        }
                    } catch (Exception ignored) {}
                }

                if (bmp == null && item.thumbUrl != null && !item.thumbUrl.isEmpty()) {
                    bmp = loadNetworkBitmapWithDiskCache(item.thumbUrl, 360);
                }

                if (bmp != null) {
                    memoryCache.put(key, bmp);
                    final Bitmap finalBmp = bmp;
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (ch == null || key.equals(ch.boundKey)) {
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
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
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
            while (opts.outWidth / inSample > maxDim || opts.outHeight / inSample > maxDim) inSample *= 2;
            opts.inJustDecodeBounds = false;
            opts.inSampleSize = inSample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
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
        Toast.makeText(this, "开始直传 " + uris.size() + " 项至云端", Toast.LENGTH_SHORT).show();
        startBackupService(PhotoBackupService.ACTION_UPLOAD_URIS, "/GoogleDrive/photos");
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
            for (int i = allItems.size() - 1; i >= 0; i--) {
                GalleryItem it = allItems.get(i);
                if (deletedNames.contains(it.name) && it.localUri == null) {
                    allItems.remove(i);
                }
            }
        }

        selectionMode = false;
        selectedKeys.clear();
        updateSelectionUi();
        applyFilterAndRefreshTimeline();
        rebuildAlbumCards();

        if (cloudPaths.isEmpty()) {
            Toast.makeText(this, "所选照片仅在本地，未占用云端空间", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "已从云端移除 " + cloudPaths.size() + " 项", Toast.LENGTH_SHORT).show();

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
                    conn.getOutputStream().write(body.toString().getBytes("UTF-8"));
                    conn.getResponseCode();
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        });
    }

    // ==================== STATUS POLLING & TAB NAVIGATION ====================

    private void pollBackupStatus() {
        BackupState st = BackupState.get(this);
        String speedMb = st.currentSpeedBps > 0 ? String.format(Locale.US, "%.2f MB/s", st.currentSpeedBps / 1048576.0) : (st.currentProgress + "%");

        if (st.isRunning) {
            liveSyncBanner.setVisibility(View.VISIBLE);
            tvLiveSyncTitle.setText(st.statusText + (st.currentFile.isEmpty() ? "" : (" · " + st.currentFile)));
            tvLiveSyncSpeed.setText(speedMb);
            pbLiveSync.setProgress(Math.max(5, st.currentProgress));
            tvCloudPill.setText("备份中 " + st.sessionUploadedCount + "/" + st.sessionTotalCount);
            if (btnBkStop != null) btnBkStop.setVisibility(View.VISIBLE);
        } else {
            liveSyncBanner.setVisibility(View.GONE);
            if (btnBkStop != null) btnBkStop.setVisibility(View.GONE);
            if (wasRunningLastPoll) {
                loadUnifiedTimeline(true);
            }
        }
        wasRunningLastPoll = st.isRunning;

        if (tvBkBadge != null) {
            tvBkBadge.setText(st.statusText);
            pbBkBar.setProgress(st.currentProgress);
            try {
                JSONObject j = new JSONObject(st.toJsonString());
                tvBkSummary.setText("已累计备份 " + j.optInt("totalBackedUpCount", 0) + " 项" + (st.currentFile.isEmpty() ? "" : (" · 当前: " + st.currentFile)));
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

    private LinearLayout createVectorTabItem(int iconType, String label, boolean active) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(0, dp(6), 0, dp(6));

        int color = Color.parseColor(active ? "#38BDF8" : "#64748B");
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(VectorIcons.create(iconType, color, dp(22)));

        TextView txt = new TextView(this);
        txt.setText(label);
        txt.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        txt.setIncludeFontPadding(false);
        txt.setPadding(0, dp(4), 0, 0);
        txt.setGravity(Gravity.CENTER);
        txt.setSingleLine(true);
        txt.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        txt.setTextColor(color);

        item.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));
        item.addView(txt);
        return item;
    }

    public void selectTab(final String tab) {
        currentTab = tab;
        boolean isPhotos = "photos".equals(tab);
        boolean isAlbums = "albums".equals(tab);
        boolean isBackup = "backup".equals(tab);
        boolean isFiles = "files".equals(tab);

        int cActive = Color.parseColor("#38BDF8");
        int cMuted = Color.parseColor("#64748B");

        navIconPhotos.setImageDrawable(VectorIcons.create(VectorIcons.ICON_PHOTOS, isPhotos ? cActive : cMuted, dp(22)));
        navTextPhotos.setTextColor(isPhotos ? cActive : cMuted);
        navTextPhotos.setTypeface(null, isPhotos ? Typeface.BOLD : Typeface.NORMAL);

        navIconAlbums.setImageDrawable(VectorIcons.create(VectorIcons.ICON_ALBUMS, isAlbums ? cActive : cMuted, dp(22)));
        navTextAlbums.setTextColor(isAlbums ? cActive : cMuted);
        navTextAlbums.setTypeface(null, isAlbums ? Typeface.BOLD : Typeface.NORMAL);

        navIconBackup.setImageDrawable(VectorIcons.create(VectorIcons.ICON_CLOUD_SYNC, isBackup ? cActive : cMuted, dp(22)));
        navTextBackup.setTextColor(isBackup ? cActive : cMuted);
        navTextBackup.setTypeface(null, isBackup ? Typeface.BOLD : Typeface.NORMAL);

        navIconFiles.setImageDrawable(VectorIcons.create(VectorIcons.ICON_DRIVE, isFiles ? cActive : cMuted, dp(22)));
        navTextFiles.setTextColor(isFiles ? cActive : cMuted);
        navTextFiles.setTypeface(null, isFiles ? Typeface.BOLD : Typeface.NORMAL);

        viewPhotosTab.setVisibility(isPhotos ? View.VISIBLE : View.GONE);
        viewAlbumsTab.setVisibility(isAlbums ? View.VISIBLE : View.GONE);
        viewBackupTab.setVisibility(isBackup ? View.VISIBLE : View.GONE);
        alistWebView.setVisibility(isFiles ? View.VISIBLE : View.GONE);

        if (isAlbums) {
            rebuildAlbumCards();
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
        startActivityForResult(Intent.createChooser(intent, "选择要上传的照片或文件"), REQ_PICK_MANUAL_UPLOAD);
    }

    private void enqueueSystemDownload(String url, String filename) {
        try {
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(filename);
            req.setDescription("正在通过边缘节点下载原文件...");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.addRequestHeader("User-Agent", USER_AGENT);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                Toast.makeText(this, "已开始下载: " + filename, Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, "开始上传 " + picked.size() + " 个文件", Toast.LENGTH_SHORT).show();
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
        if ("photos".equals(currentTab) && !"all".equals(currentFilter)) {
            setGalleryFilter("all");
            return;
        }
        if (!"photos".equals(currentTab)) {
            selectTab("photos");
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

    private TextView createPillButton(String text, String bgHex, String borderHex) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setSingleLine(true);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(11), dp(6), dp(11), dp(6));
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

    private String formatDuration(long ms) {
        long sec = Math.max(1, ms / 1000);
        long m = sec / 60;
        long s = sec % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
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
