package top.czyxh.pan;

import android.Manifest;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
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
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Flagship Google MD3 (Material You) Cloud Photo Gallery & Drive App.
 * Full wallpaper-adaptive Monet dynamic color theming, ViewPager2, PhotoView, Glide & BottomNavigationView.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMS = 2001;
    private static final int REQ_CHOOSE_FILE = 2002;
    private static final String BASE_API = "https://pan.czyxh.top";

    public static class GalleryItem {
        public Uri uri;
        public String name;
        public long dateTaken;
        public long size;
        public boolean isVideo;
        public long durationMs;
        public String bucketName;
        public String cloudUrl;
        public String localPath;

        public boolean isLocalOnly() {
            return uri != null && (cloudUrl == null || cloudUrl.isEmpty());
        }

        public boolean isCloudOnly() {
            return uri == null && cloudUrl != null && !cloudUrl.isEmpty();
        }

        public String getUniqueKey() {
            if (name != null && !name.isEmpty()) return name;
            return uri != null ? uri.toString() : (cloudUrl != null ? cloudUrl : "");
        }
    }

    private static class TimelineEntry {
        static final int TYPE_HEADER = 0;
        static final int TYPE_PHOTO = 1;

        int type;
        String headerTitle;
        String headerSub;
        GalleryItem item;
        int photoIndex;
    }

    private static class AlbumCard {
        String title;
        String countText;
        int iconRes;
        Uri coverUri;
        String coverUrl;
        String bucketName;
        boolean isVirtual;
        boolean autoBackup;
    }

    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final List<GalleryItem> allMediaItems = new ArrayList<>();
    private final List<TimelineEntry> timelineEntries = new ArrayList<>();
    private final List<AlbumCard> albumCards = new ArrayList<>();

    private M3Colors m3;
    private FrameLayout contentFrame;
    private BottomNavigationView bottomNav;
    private View searchTopBar;
    private TextView tvTopTitle;
    private ImageView ivTopColToggle;

    // Tab Views
    private SwipeRefreshLayout photosRefreshLayout;
    private RecyclerView photosRecyclerView;
    private TimelineAdapter timelineAdapter;
    private GridLayoutManager photosLayoutManager;

    private SwipeRefreshLayout albumsRefreshLayout;
    private RecyclerView albumsRecyclerView;
    private AlbumsAdapter albumsAdapter;

    private ScrollView syncScrollView;
    private LinearLayout syncContainer;

    private WebView alistWebView;
    private boolean alistLoaded = false;
    private ValueCallback<Uri[]> uploadMessageCallback;

    private int gridColumns = 3;
    private String currentTab = "photos";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        m3 = new M3Colors(this);
        gridColumns = BackupState.get(this).getGridColumns();
        if (gridColumns < 3 || gridColumns > 4) gridColumns = 3;

        buildM3Layout();
        checkPermissionsAndLoad();
    }

    private void buildM3Layout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(m3.surface);

        // Top Search Bar (Google Photos MD3 SearchBar with Monet Dynamic Palette)
        searchTopBar = buildM3TopBar();
        root.addView(searchTopBar);

        // Content Area
        contentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        root.addView(contentFrame, clp);

        // Initialize Tab Views
        setupPhotosView();
        setupAlbumsView();
        setupSyncView();
        setupAlistWebView();

        // Bottom Navigation (Material 3 NavigationBar with native Monet Dynamic Pill & Theme)
        bottomNav = new BottomNavigationView(this);
        bottomNav.inflateMenu(R.menu.bottom_nav_menu);
        bottomNav.setBackgroundColor(m3.surfaceContainer);

        bottomNav.setOnItemSelectedListener(new BottomNavigationView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.nav_photos) switchTab("photos");
                else if (id == R.id.nav_albums) switchTab("albums");
                else if (id == R.id.nav_sync) switchTab("sync");
                else if (id == R.id.nav_drive) switchTab("drive");
                return true;
            }
        });

        root.addView(bottomNav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));

        setContentView(root);
        switchTab("photos");
    }

    private View buildM3TopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(36), dp(16), dp(10));
        bar.setBackgroundColor(m3.surface);

        // M3 Pill Container (Monet surfaceContainerHigh)
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(16), dp(10), dp(16), dp(10));
        pill.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(28)));

        // Authentic Google Photos 4-color Pinwheel Logo
        ImageView ivLogo = new ImageView(this);
        ivLogo.setImageResource(R.drawable.ic_google_photos_logo);
        pill.addView(ivLogo, new LinearLayout.LayoutParams(dp(26), dp(26)));

        // Title
        tvTopTitle = new TextView(this);
        tvTopTitle.setText("Google Photos · 云相册");
        tvTopTitle.setTextColor(m3.onSurface);
        tvTopTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tvTopTitle.setTypeface(null, Typeface.BOLD);
        tvTopTitle.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        pill.addView(tvTopTitle, tlp);

        // Column Switcher (3 / 4 columns)
        ivTopColToggle = new ImageView(this);
        ivTopColToggle.setImageResource(R.drawable.ic_m3_grid);
        ivTopColToggle.setColorFilter(m3.onSurfaceVariant);
        ivTopColToggle.setPadding(dp(4), dp(4), dp(4), dp(4));
        ivTopColToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                gridColumns = gridColumns == 3 ? 4 : 3;
                BackupState.get(MainActivity.this).setGridColumns(gridColumns);
                photosLayoutManager.setSpanCount(gridColumns);
                timelineAdapter.notifyDataSetChanged();
                Toast.makeText(MainActivity.this, "已切换为 " + gridColumns + " 列视图", Toast.LENGTH_SHORT).show();
            }
        });
        pill.addView(ivTopColToggle, new LinearLayout.LayoutParams(dp(32), dp(32)));

        // Sync Avatar Badge (Monet primaryContainer circle)
        ImageView ivSyncBadge = new ImageView(this);
        ivSyncBadge.setImageResource(R.drawable.ic_m3_sync);
        ivSyncBadge.setColorFilter(m3.onPrimaryContainer);
        ivSyncBadge.setPadding(dp(6), dp(6), dp(6), dp(6));
        ivSyncBadge.setBackground(makeRoundRect(m3.primaryContainer, dp(16)));
        ivSyncBadge.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bottomNav.setSelectedItemId(R.id.nav_sync);
            }
        });
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(32), dp(32));
        slp.leftMargin = dp(8);
        pill.addView(ivSyncBadge, slp);

        bar.addView(pill, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private void setupPhotosView() {
        photosRefreshLayout = new SwipeRefreshLayout(this);
        photosRefreshLayout.setColorSchemeColors(m3.primary);
        photosRefreshLayout.setProgressBackgroundColorSchemeColor(m3.surfaceContainer);
        photosRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadAllMediaData();
            }
        });

        photosRecyclerView = new RecyclerView(this);
        photosLayoutManager = new GridLayoutManager(this, gridColumns);
        photosLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position >= 0 && position < timelineEntries.size()) {
                    if (timelineEntries.get(position).type == TimelineEntry.TYPE_HEADER) {
                        return photosLayoutManager.getSpanCount();
                    }
                }
                return 1;
            }
        });
        photosRecyclerView.setLayoutManager(photosLayoutManager);
        photosRecyclerView.setHasFixedSize(true);
        photosRecyclerView.setItemViewCacheSize(25);

        timelineAdapter = new TimelineAdapter();
        photosRecyclerView.setAdapter(timelineAdapter);

        photosRefreshLayout.addView(photosRecyclerView);
        contentFrame.addView(photosRefreshLayout);
    }

    private void setupAlbumsView() {
        albumsRefreshLayout = new SwipeRefreshLayout(this);
        albumsRefreshLayout.setColorSchemeColors(m3.primary);
        albumsRefreshLayout.setProgressBackgroundColorSchemeColor(m3.surfaceContainer);
        albumsRefreshLayout.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                loadAllMediaData();
            }
        });

        albumsRecyclerView = new RecyclerView(this);
        albumsRecyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        albumsAdapter = new AlbumsAdapter();
        albumsRecyclerView.setAdapter(albumsAdapter);

        albumsRefreshLayout.addView(albumsRecyclerView);
        albumsRefreshLayout.setVisibility(View.GONE);
        contentFrame.addView(albumsRefreshLayout);
    }

    private void setupSyncView() {
        syncScrollView = new ScrollView(this);
        syncScrollView.setBackgroundColor(m3.surface);
        syncContainer = new LinearLayout(this);
        syncContainer.setOrientation(LinearLayout.VERTICAL);
        syncContainer.setPadding(dp(16), dp(16), dp(16), dp(32));
        syncScrollView.addView(syncContainer);
        syncScrollView.setVisibility(View.GONE);
        contentFrame.addView(syncScrollView);
    }

    private void setupAlistWebView() {
        alistWebView = new WebView(this);
        WebSettings ws = alistWebView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);

        alistWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });

        alistWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessageCallback != null) uploadMessageCallback.onReceiveValue(null);
                uploadMessageCallback = filePathCallback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(intent, "选择文件上传到网盘"), REQ_CHOOSE_FILE);
                return true;
            }
        });

        alistWebView.setVisibility(View.GONE);
        contentFrame.addView(alistWebView);
    }

    private void switchTab(String tab) {
        currentTab = tab;
        photosRefreshLayout.setVisibility("photos".equals(tab) ? View.VISIBLE : View.GONE);
        albumsRefreshLayout.setVisibility("albums".equals(tab) ? View.VISIBLE : View.GONE);
        syncScrollView.setVisibility("sync".equals(tab) ? View.VISIBLE : View.GONE);
        alistWebView.setVisibility("drive".equals(tab) ? View.VISIBLE : View.GONE);

        ivTopColToggle.setVisibility("photos".equals(tab) ? View.VISIBLE : View.GONE);

        if ("photos".equals(tab)) {
            tvTopTitle.setText("Google Photos · 云相册");
        } else if ("albums".equals(tab)) {
            tvTopTitle.setText("精选相册与目录");
        } else if ("sync".equals(tab)) {
            tvTopTitle.setText("备份与直传引擎");
            renderSyncDashboard();
        } else if ("drive".equals(tab)) {
            tvTopTitle.setText("AList 全能网盘");
            if (!alistLoaded) {
                alistLoaded = true;
                alistWebView.loadUrl(BASE_API + "/GoogleDrive");
            }
        }
    }

    private void checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean hasImages = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED;
            boolean hasVideo = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            if (!hasImages || !hasVideo) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
                return;
            }
        } else {
            boolean hasStorage = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            if (!hasStorage) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_PERMS);
                return;
            }
        }
        loadAllMediaData();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            loadAllMediaData();
        }
    }

    private void loadAllMediaData() {
        photosRefreshLayout.setRefreshing(true);
        albumsRefreshLayout.setRefreshing(true);

        executor.submit(new Runnable() {
            @Override
            public void run() {
                final List<GalleryItem> local = scanLocalMedia();
                final List<GalleryItem> cloud = fetchCloudMedia();

                // Merge & deduplicate
                final Map<String, GalleryItem> map = new LinkedHashMap<>();
                for (GalleryItem item : local) {
                    map.put(item.getUniqueKey(), item);
                }
                for (GalleryItem cItem : cloud) {
                    String key = cItem.getUniqueKey();
                    if (map.containsKey(key)) {
                        GalleryItem existing = map.get(key);
                        existing.cloudUrl = cItem.cloudUrl;
                    } else {
                        map.put(key, cItem);
                    }
                }

                final List<GalleryItem> merged = new ArrayList<>(map.values());
                Collections.sort(merged, new Comparator<GalleryItem>() {
                    @Override
                    public int compare(GalleryItem a, GalleryItem b) {
                        return Long.compare(b.dateTaken, a.dateTaken);
                    }
                });

                final List<TimelineEntry> tEntries = buildTimelineEntries(merged);
                final List<AlbumCard> aCards = buildAlbumCards(merged);

                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        allMediaItems.clear();
                        allMediaItems.addAll(merged);

                        timelineEntries.clear();
                        timelineEntries.addAll(tEntries);
                        timelineAdapter.notifyDataSetChanged();

                        albumCards.clear();
                        albumCards.addAll(aCards);
                        albumsAdapter.notifyDataSetChanged();

                        photosRefreshLayout.setRefreshing(false);
                        albumsRefreshLayout.setRefreshing(false);
                    }
                });
            }
        });
    }

    private List<GalleryItem> scanLocalMedia() {
        List<GalleryItem> list = new ArrayList<>();
        Uri[] uris = new Uri[]{
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        };

        for (Uri queryUri : uris) {
            boolean isVideoQuery = queryUri == MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String[] projection = new String[]{
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.DATE_MODIFIED,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    isVideoQuery ? MediaStore.Video.VideoColumns.DURATION : MediaStore.MediaColumns._ID
            };

            Cursor cursor = null;
            try {
                cursor = getContentResolver().query(queryUri, projection, null, null, MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
                if (cursor != null) {
                    int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                    int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                    int dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
                    int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                    int bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);
                    int durCol = isVideoQuery ? cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) : -1;

                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(idCol);
                        String name = cursor.getString(nameCol);
                        long dateMod = cursor.getLong(dateCol);
                        long size = cursor.getLong(sizeCol);
                        String bucket = cursor.getString(bucketCol);
                        long dur = durCol >= 0 ? cursor.getLong(durCol) : 0;

                        if (name == null || name.isEmpty() || size <= 0) continue;

                        GalleryItem item = new GalleryItem();
                        item.uri = ContentUris.withAppendedId(queryUri, id);
                        item.name = name;
                        item.dateTaken = dateMod * 1000L;
                        item.size = size;
                        item.bucketName = bucket != null ? bucket : "相机";
                        item.isVideo = isVideoQuery;
                        item.durationMs = dur;
                        list.add(item);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) cursor.close();
            }
        }
        return list;
    }

    private List<GalleryItem> fetchCloudMedia() {
        List<GalleryItem> list = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_API + "/api/cloud_transfer/photos");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            if (conn.getResponseCode() == 200) {
                BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray arr = root.optJSONArray("data");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String name = o.optString("name", "");
                        if (name.isEmpty()) continue;

                        GalleryItem item = new GalleryItem();
                        item.name = name;
                        item.size = o.optLong("size", 0);
                        item.cloudUrl = o.optString("url", "");
                        item.dateTaken = o.optLong("modified", System.currentTimeMillis());
                        item.isVideo = o.optBoolean("is_video", false) || name.toLowerCase().endsWith(".mp4") || name.toLowerCase().endsWith(".mov");
                        item.bucketName = "Google Drive";
                        list.add(item);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
        return list;
    }

    private List<TimelineEntry> buildTimelineEntries(List<GalleryItem> items) {
        List<TimelineEntry> entries = new ArrayList<>();
        SimpleDateFormat dayFormat = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
        String lastDayKey = "";
        List<GalleryItem> currentGroup = new ArrayList<>();
        TimelineEntry currentHeader = null;

        for (int i = 0; i < items.size(); i++) {
            GalleryItem item = items.get(i);
            String dayKey = dayFormat.format(new Date(item.dateTaken));
            if (!dayKey.equals(lastDayKey)) {
                lastDayKey = dayKey;
                currentGroup.clear();

                currentHeader = new TimelineEntry();
                currentHeader.type = TimelineEntry.TYPE_HEADER;
                currentHeader.headerTitle = formatTimelineHeader(item.dateTaken);
                entries.add(currentHeader);
            }

            currentGroup.add(item);
            if (currentHeader != null) {
                currentHeader.headerSub = currentGroup.size() + " 张照片与视频";
            }

            TimelineEntry pEntry = new TimelineEntry();
            pEntry.type = TimelineEntry.TYPE_PHOTO;
            pEntry.item = item;
            pEntry.photoIndex = i;
            entries.add(pEntry);
        }
        return entries;
    }

    private String formatTimelineHeader(long time) {
        Calendar target = Calendar.getInstance();
        target.setTimeInMillis(time);

        Calendar today = Calendar.getInstance();
        if (isSameDay(target, today)) return "今天";

        today.add(Calendar.DAY_OF_YEAR, -1);
        if (isSameDay(target, today)) return "昨天";

        SimpleDateFormat f = target.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)
                ? new SimpleDateFormat("M月d日 EEEE", Locale.CHINESE)
                : new SimpleDateFormat("yyyy年M月d日", Locale.CHINESE);
        return f.format(new Date(time));
    }

    private boolean isSameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private List<AlbumCard> buildAlbumCards(List<GalleryItem> items) {
        List<AlbumCard> cards = new ArrayList<>();

        // Virtual 1: Cloud Backed Up
        int cloudCount = 0;
        Uri cloudCover = null;
        String cloudCoverUrl = null;
        for (GalleryItem item : items) {
            if (!item.isLocalOnly()) {
                cloudCount++;
                if (cloudCover == null && item.uri != null) cloudCover = item.uri;
                if (cloudCoverUrl == null && item.cloudUrl != null) cloudCoverUrl = item.cloudUrl;
            }
        }
        AlbumCard acCloud = new AlbumCard();
        acCloud.title = "云端已备份";
        acCloud.countText = cloudCount + " 个项目 · Google Drive";
        acCloud.iconRes = R.drawable.ic_m3_cloud_done;
        acCloud.coverUri = cloudCover;
        acCloud.coverUrl = cloudCoverUrl;
        acCloud.isVirtual = true;
        cards.add(acCloud);

        // Virtual 2: Favorites
        int favCount = 0;
        Uri favCover = null;
        String favCoverUrl = null;
        Set<String> favs = BackupState.get(this).getFavoritesSet();
        for (GalleryItem item : items) {
            if (favs.contains(item.getUniqueKey())) {
                favCount++;
                if (favCover == null && item.uri != null) favCover = item.uri;
                if (favCoverUrl == null && item.cloudUrl != null) favCoverUrl = item.cloudUrl;
            }
        }
        AlbumCard acFav = new AlbumCard();
        acFav.title = "我的收藏";
        acFav.countText = favCount + " 个收藏项目";
        acFav.iconRes = R.drawable.ic_m3_star_filled;
        acFav.coverUri = favCover;
        acFav.coverUrl = favCoverUrl;
        acFav.isVirtual = true;
        cards.add(acFav);

        // Virtual 3: Videos
        int vidCount = 0;
        Uri vidCover = null;
        for (GalleryItem item : items) {
            if (item.isVideo) {
                vidCount++;
                if (vidCover == null && item.uri != null) vidCover = item.uri;
            }
        }
        AlbumCard acVid = new AlbumCard();
        acVid.title = "视频合辑";
        acVid.countText = vidCount + " 个视频";
        acVid.iconRes = R.drawable.ic_m3_play;
        acVid.coverUri = vidCover;
        acVid.isVirtual = true;
        cards.add(acVid);

        // Group by Bucket
        Map<String, List<GalleryItem>> buckets = new LinkedHashMap<>();
        for (GalleryItem item : items) {
            String b = item.bucketName != null ? item.bucketName : "未分类";
            if (!buckets.containsKey(b)) buckets.put(b, new ArrayList<GalleryItem>());
            buckets.get(b).add(item);
        }

        Set<String> selectedBuckets = BackupState.get(this).getSelectedBuckets();
        for (Map.Entry<String, List<GalleryItem>> e : buckets.entrySet()) {
            if (e.getKey().equals("Google Drive")) continue;
            AlbumCard ac = new AlbumCard();
            ac.title = e.getKey();
            ac.countText = e.getValue().size() + " 项内容";
            ac.bucketName = e.getKey();
            ac.iconRes = R.drawable.ic_m3_albums;
            ac.autoBackup = selectedBuckets.contains(e.getKey()) || selectedBuckets.contains("ALL");
            if (!e.getValue().isEmpty()) {
                ac.coverUri = e.getValue().get(0).uri;
                ac.coverUrl = e.getValue().get(0).cloudUrl;
            }
            cards.add(ac);
        }

        return cards;
    }

    /**
     * Timeline RecyclerView Adapter (Google Photos Material You Grid).
     */
    private class TimelineAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            return timelineEntries.get(position).type;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == TimelineEntry.TYPE_HEADER) {
                LinearLayout header = new LinearLayout(parent.getContext());
                header.setOrientation(LinearLayout.VERTICAL);
                header.setPadding(dp(16), dp(22), dp(16), dp(8));

                TextView tvTitle = new TextView(parent.getContext());
                tvTitle.setTextColor(m3.onSurface);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
                tvTitle.setTypeface(null, Typeface.BOLD);

                TextView tvSub = new TextView(parent.getContext());
                tvSub.setTextColor(m3.outline);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);

                header.addView(tvTitle);
                header.addView(tvSub);
                return new HeaderViewHolder(header, tvTitle, tvSub);
            } else {
                FrameLayout cell = new FrameLayout(parent.getContext());
                int m = dp(1);
                ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0);
                mlp.setMargins(m, m, m, m);
                cell.setLayoutParams(mlp);

                ImageView iv = new ImageView(parent.getContext());
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                cell.addView(iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

                // Cloud-done subtle badge
                ImageView ivCloud = new ImageView(parent.getContext());
                ivCloud.setImageResource(R.drawable.ic_m3_cloud_done);
                ivCloud.setColorFilter(m3.primary);
                ivCloud.setBackground(makeRoundRect(Color.parseColor("#99000000"), dp(10)));
                ivCloud.setPadding(dp(3), dp(3), dp(3), dp(3));
                FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM | Gravity.RIGHT);
                clp.setMargins(0, 0, dp(4), dp(4));
                cell.addView(ivCloud, clp);

                // Video duration badge
                TextView tvDur = new TextView(parent.getContext());
                tvDur.setTextColor(Color.WHITE);
                tvDur.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                tvDur.setTypeface(null, Typeface.BOLD);
                tvDur.setBackground(makeRoundRect(Color.parseColor("#99000000"), dp(8)));
                tvDur.setPadding(dp(6), dp(2), dp(6), dp(2));
                FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
                vlp.setMargins(dp(4), 0, 0, dp(4));
                cell.addView(tvDur, vlp);

                return new PhotoViewHolder(cell, iv, ivCloud, tvDur);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            TimelineEntry entry = timelineEntries.get(position);
            if (holder instanceof HeaderViewHolder) {
                HeaderViewHolder hh = (HeaderViewHolder) holder;
                hh.tvTitle.setText(entry.headerTitle);
                hh.tvSub.setText(entry.headerSub != null ? entry.headerSub : "");
            } else if (holder instanceof PhotoViewHolder) {
                final PhotoViewHolder ph = (PhotoViewHolder) holder;
                final GalleryItem item = entry.item;

                int screenW = getResources().getDisplayMetrics().widthPixels;
                int size = screenW / gridColumns;
                ViewGroup.LayoutParams lp = ph.itemView.getLayoutParams();
                lp.width = size;
                lp.height = size;
                ph.itemView.setLayoutParams(lp);

                Object model = item.uri != null ? item.uri : item.cloudUrl;
                Glide.with(MainActivity.this)
                        .load(model)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(ph.ivThumb);

                ph.ivCloudBadge.setVisibility(item.isLocalOnly() ? View.GONE : View.VISIBLE);

                if (item.isVideo) {
                    ph.tvDuration.setVisibility(View.VISIBLE);
                    ph.tvDuration.setText(formatDuration(item.durationMs));
                } else {
                    ph.tvDuration.setVisibility(View.GONE);
                }

                final int pIdx = entry.photoIndex;
                ph.itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openPhotoViewer(allMediaItems, pIdx);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return timelineEntries.size();
        }

        class HeaderViewHolder extends RecyclerView.ViewHolder {
            final TextView tvTitle;
            final TextView tvSub;
            HeaderViewHolder(View root, TextView t, TextView s) {
                super(root);
                tvTitle = t;
                tvSub = s;
            }
        }

        class PhotoViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivThumb;
            final ImageView ivCloudBadge;
            final TextView tvDuration;
            PhotoViewHolder(View root, ImageView iv, ImageView ivC, TextView tvD) {
                super(root);
                ivThumb = iv;
                ivCloudBadge = ivC;
                tvDuration = tvD;
            }
        }
    }

    /**
     * Albums Grid Adapter (Google Photos Library Style M3 Cards with Monet Palette).
     */
    private class AlbumsAdapter extends RecyclerView.Adapter<AlbumsAdapter.AlbumViewHolder> {

        @NonNull
        @Override
        public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(parent.getContext());
            card.setRadius(dp(16));
            card.setCardElevation(dp(2));
            card.setCardBackgroundColor(m3.surfaceContainer);
            card.setStrokeColor(m3.outlineVariant);
            card.setStrokeWidth(dp(1));

            int m = dp(8);
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.setMargins(m, m, m, m);
            card.setLayoutParams(mlp);

            LinearLayout root = new LinearLayout(parent.getContext());
            root.setOrientation(LinearLayout.VERTICAL);

            // Cover Image
            ImageView ivCover = new ImageView(parent.getContext());
            ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(ivCover, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(130)));

            // Text info container
            LinearLayout info = new LinearLayout(parent.getContext());
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(12), dp(10), dp(12), dp(12));

            TextView tvTitle = new TextView(parent.getContext());
            tvTitle.setTextColor(m3.onSurface);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tvTitle.setTypeface(null, Typeface.BOLD);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);

            TextView tvCount = new TextView(parent.getContext());
            tvCount.setTextColor(m3.outline);
            tvCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);

            info.addView(tvTitle);
            info.addView(tvCount);

            // Auto-backup Material Switch
            MaterialSwitch swBackup = new MaterialSwitch(parent.getContext());
            swBackup.setText("自动备份");
            swBackup.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
            swBackup.setTextColor(m3.onSurfaceVariant);
            info.addView(swBackup);

            root.addView(info);
            card.addView(root);
            return new AlbumViewHolder(card, ivCover, tvTitle, tvCount, swBackup);
        }

        @Override
        public void onBindViewHolder(@NonNull AlbumViewHolder holder, int position) {
            final AlbumCard card = albumCards.get(position);
            holder.tvTitle.setText(card.title);
            holder.tvCount.setText(card.countText);

            Object model = card.coverUri != null ? card.coverUri : card.coverUrl;
            if (model != null) {
                Glide.with(MainActivity.this)
                        .load(model)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(holder.ivCover);
            } else {
                holder.ivCover.setImageResource(card.iconRes);
                holder.ivCover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                holder.ivCover.setColorFilter(m3.primary);
            }

            if (card.isVirtual) {
                holder.swBackup.setVisibility(View.GONE);
                holder.itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        filterAndShowVirtualAlbum(card.title);
                    }
                });
            } else {
                holder.swBackup.setVisibility(View.VISIBLE);
                holder.swBackup.setChecked(card.autoBackup);
                holder.swBackup.setOnCheckedChangeListener((btn, isChecked) -> {
                    card.autoBackup = isChecked;
                    Set<String> sel = BackupState.get(MainActivity.this).getSelectedBuckets();
                    if (isChecked) sel.add(card.bucketName);
                    else sel.remove(card.bucketName);
                    BackupState.get(MainActivity.this).saveConfig(
                            BackupState.get(MainActivity.this).isAutoBackupEnabled(),
                            BackupState.get(MainActivity.this).isWifiOnly(),
                            BackupState.get(MainActivity.this).isIncludeVideos(),
                            BackupState.get(MainActivity.this).getTargetFolder(),
                            sel
                    );
                    Toast.makeText(MainActivity.this, (isChecked ? "已启用 " : "已关闭 ") + card.title + " 的自动备份", Toast.LENGTH_SHORT).show();
                });

                holder.itemView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        filterAndShowBucketAlbum(card.bucketName);
                    }
                });
            }
        }

        @Override
        public int getItemCount() {
            return albumCards.size();
        }

        class AlbumViewHolder extends RecyclerView.ViewHolder {
            final ImageView ivCover;
            final TextView tvTitle;
            final TextView tvCount;
            final MaterialSwitch swBackup;

            AlbumViewHolder(View root, ImageView iv, TextView t, TextView c, MaterialSwitch sw) {
                super(root);
                ivCover = iv;
                tvTitle = t;
                tvCount = c;
                swBackup = sw;
            }
        }
    }

    private void filterAndShowVirtualAlbum(String title) {
        List<GalleryItem> filtered = new ArrayList<>();
        if ("云端已备份".equals(title)) {
            for (GalleryItem item : allMediaItems) if (!item.isLocalOnly()) filtered.add(item);
        } else if ("我的收藏".equals(title)) {
            Set<String> favs = BackupState.get(this).getFavoritesSet();
            for (GalleryItem item : allMediaItems) if (favs.contains(item.getUniqueKey())) filtered.add(item);
        } else if ("视频合辑".equals(title)) {
            for (GalleryItem item : allMediaItems) if (item.isVideo) filtered.add(item);
        }
        if (filtered.isEmpty()) {
            Toast.makeText(this, title + " 暂无内容", Toast.LENGTH_SHORT).show();
            return;
        }
        openPhotoViewer(filtered, 0);
    }

    private void filterAndShowBucketAlbum(String bucketName) {
        List<GalleryItem> filtered = new ArrayList<>();
        for (GalleryItem item : allMediaItems) {
            if (bucketName.equals(item.bucketName)) filtered.add(item);
        }
        if (filtered.isEmpty()) {
            Toast.makeText(this, "该目录暂无内容", Toast.LENGTH_SHORT).show();
            return;
        }
        openPhotoViewer(filtered, 0);
    }

    private void openPhotoViewer(List<GalleryItem> list, int index) {
        PhotoViewerDialog dialog = new PhotoViewerDialog(this, list, index, new PhotoViewerDialog.ViewerCallbacks() {
            @Override
            public void onUploadItem(GalleryItem item) {
                if (item.uri != null) {
                    synchronized (PhotoBackupService.pendingManualUris) {
                        PhotoBackupService.pendingManualUris.add(item.uri);
                    }
                    Intent intent = new Intent(MainActivity.this, PhotoBackupService.class);
                    intent.setAction(PhotoBackupService.ACTION_UPLOAD_URIS);
                    intent.putExtra(PhotoBackupService.EXTRA_TARGET_FOLDER, BackupState.get(MainActivity.this).getTargetFolder());
                    startService(intent);
                }
            }

            @Override
            public void onDownloadItem(GalleryItem item) {
                if (!TextUtils.isEmpty(item.cloudUrl)) {
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    if (dm != null) {
                        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(item.cloudUrl));
                        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_PICTURES, item.name);
                        dm.enqueue(req);
                    }
                }
            }

            @Override
            public void onDeleteItem(final GalleryItem item, final Runnable onDeleted) {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        boolean ok = deletePhotoEverywhere(item);
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (ok) {
                                    Toast.makeText(MainActivity.this, "已成功删除: " + item.name, Toast.LENGTH_SHORT).show();
                                    if (onDeleted != null) onDeleted.run();
                                    loadAllMediaData();
                                } else {
                                    Toast.makeText(MainActivity.this, "删除失败或已被删除", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void onToggleFavorite(GalleryItem item, boolean fav) {
                BackupState.get(MainActivity.this).toggleFavorite(item.getUniqueKey());
            }

            @Override
            public boolean isFavorite(GalleryItem item) {
                return BackupState.get(MainActivity.this).isFavorite(item.getUniqueKey());
            }
        });
        dialog.show();
    }

    private boolean deletePhotoEverywhere(GalleryItem item) {
        boolean ok = false;
        try {
            if (item.uri != null) {
                getContentResolver().delete(item.uri, null, null);
                ok = true;
            }
        } catch (Exception ignored) {}

        try {
            URL url = new URL(BASE_API + "/api/cloud_transfer/delete_photo");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject post = new JSONObject();
            post.put("name", item.name);
            post.put("path", BackupState.get(this).getTargetFolder() + "/" + item.name);
            OutputStream os = conn.getOutputStream();
            os.write(post.toString().getBytes("UTF-8"));
            os.flush();
            os.close();
            if (conn.getResponseCode() == 200) ok = true;
            conn.disconnect();
        } catch (Exception ignored) {}

        return ok;
    }

    private void renderSyncDashboard() {
        syncContainer.removeAllViews();

        final BackupState state = BackupState.get(this);

        // M3 Elevated Card for Cloud Engine Status
        MaterialCardView cardStatus = new MaterialCardView(this);
        cardStatus.setRadius(dp(20));
        cardStatus.setCardBackgroundColor(m3.surfaceContainer);
        cardStatus.setStrokeColor(m3.outlineVariant);
        cardStatus.setStrokeWidth(dp(1));
        cardStatus.setCardElevation(dp(2));

        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView tvState = new TextView(this);
        tvState.setText(state.isRunning ? "⚡ 正在直传直备中..." : "☁️ 云端归档引擎就绪");
        tvState.setTextColor(m3.primary);
        tvState.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        tvState.setTypeface(null, Typeface.BOLD);

        TextView tvDesc = new TextView(this);
        tvDesc.setText("手机直传 Cloudflare R2，通过 Worker 自动秒级归档到 Google Drive，零 VPN 极速访问，0 字节腾讯云服务器磁盘占用。");
        tvDesc.setTextColor(m3.outline);
        tvDesc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvDesc.setPadding(0, dp(6), 0, dp(12));

        statusBox.addView(tvState);
        statusBox.addView(tvDesc);

        // Action Buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnScan = new TextView(this);
        btnScan.setText("🚀 立即扫描并备份");
        btnScan.setTextColor(m3.onPrimary);
        btnScan.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btnScan.setTypeface(null, Typeface.BOLD);
        btnScan.setBackground(makeRoundRect(m3.primary, dp(12)));
        btnScan.setPadding(dp(16), dp(10), dp(16), dp(10));
        btnScan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, PhotoBackupService.class);
                it.setAction(PhotoBackupService.ACTION_SCAN_AND_BACKUP);
                startService(it);
                Toast.makeText(MainActivity.this, "已触发后台相册扫描备份", Toast.LENGTH_SHORT).show();
                renderSyncDashboard();
            }
        });

        TextView btnStop = new TextView(this);
        btnStop.setText("⏹️ 停止");
        btnStop.setTextColor(Color.parseColor("#F2B8B5"));
        btnStop.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btnStop.setTypeface(null, Typeface.BOLD);
        btnStop.setBackground(makeRoundRect(Color.parseColor("#601410"), dp(12)));
        btnStop.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.leftMargin = dp(12);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, PhotoBackupService.class);
                it.setAction(PhotoBackupService.ACTION_STOP_BACKUP);
                startService(it);
                Toast.makeText(MainActivity.this, "已请求停止传输", Toast.LENGTH_SHORT).show();
                renderSyncDashboard();
            }
        });

        btnRow.addView(btnScan);
        btnRow.addView(btnStop, stlp);
        statusBox.addView(btnRow);

        cardStatus.addView(statusBox);
        syncContainer.addView(cardStatus);

        // Config Switches
        MaterialCardView cardOpts = new MaterialCardView(this);
        cardOpts.setRadius(dp(20));
        cardOpts.setCardBackgroundColor(m3.surfaceContainer);
        cardOpts.setStrokeColor(m3.outlineVariant);
        cardOpts.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colp.topMargin = dp(16);
        cardOpts.setLayoutParams(colp);

        LinearLayout optsBox = new LinearLayout(this);
        optsBox.setOrientation(LinearLayout.VERTICAL);
        optsBox.setPadding(dp(18), dp(14), dp(18), dp(14));

        MaterialSwitch swWifi = new MaterialSwitch(this);
        swWifi.setText("仅在 Wi-Fi 网络下自动备份");
        swWifi.setChecked(state.isWifiOnly());
        swWifi.setTextColor(m3.onSurface);
        swWifi.setOnCheckedChangeListener((btn, isChecked) -> {
            state.saveConfig(state.isAutoBackupEnabled(), isChecked, state.isIncludeVideos(), state.getTargetFolder(), state.getSelectedBuckets());
        });

        MaterialSwitch swVid = new MaterialSwitch(this);
        swVid.setText("包含视频备份");
        swVid.setChecked(state.isIncludeVideos());
        swVid.setTextColor(m3.onSurface);
        swVid.setOnCheckedChangeListener((btn, isChecked) -> {
            state.saveConfig(state.isAutoBackupEnabled(), state.isWifiOnly(), isChecked, state.getTargetFolder(), state.getSelectedBuckets());
        });

        optsBox.addView(swWifi);
        optsBox.addView(swVid);
        cardOpts.addView(optsBox);
        syncContainer.addView(cardOpts);
    }

    private GradientDrawable makeRoundRect(String colorHex, int radiusPx) {
        return makeRoundRect(Color.parseColor(colorHex), radiusPx);
    }

    private GradientDrawable makeRoundRect(int color, int radiusPx) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(radiusPx);
        return gd;
    }

    private String formatDuration(long ms) {
        long sec = Math.max(1, ms / 1000);
        long m = sec / 60;
        long s = sec % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    public void onBackPressed() {
        if ("drive".equals(currentTab) && alistWebView != null && alistWebView.canGoBack()) {
            alistWebView.goBack();
            return;
        }
        if (!"photos".equals(currentTab)) {
            bottomNav.setSelectedItemId(R.id.nav_photos);
            return;
        }
        super.onBackPressed();
    }
}
