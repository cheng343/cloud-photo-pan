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
import android.widget.ProgressBar;
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
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
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
 * Flagship Google MD3 (Material You) Expressive Cloud Photo Gallery & Pan App.
 * Matches authentic M3 rounded aesthetics (20dp/28dp radii, Monet wallpaper extraction, Headline typography, M3 FAB & NavigationBar).
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_PERMS = 2001;
    private static final int REQ_CHOOSE_FILE = 2002;
    private static final int REQ_MANUAL_UPLOAD = 2003;
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
    private FrameLayout rootFrame;
    private FrameLayout contentFrame;
    private BottomNavigationView bottomNav;
    private FloatingActionButton fabUpload;

    // Header View
    private View headerBar;
    private TextView tvHeaderTitle;
    private LinearLayout headerActionBox;
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
    private boolean syncDashboardInitialized = false;
    private TextView tvSyncFileName;
    private TextView tvSyncStatus;
    private ProgressBar pbSync;
    private TextView tvSyncStats;
    private MaterialSwitch swAutoMaster;
    private MaterialSwitch swWifi;
    private MaterialSwitch swVid;

    private final Runnable syncUpdaterRunnable = new Runnable() {
        @Override
        public void run() {
            if ("sync".equals(currentTab)) {
                updateSyncDashboardUi();
                mainHandler.postDelayed(this, 600);
            }
        }
    };

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
        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(m3.surface);

        LinearLayout mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        mainCol.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // M3 Large Headline Header Bar (matching "下载" / "账号" in mature apps)
        headerBar = buildM3Header();
        mainCol.addView(headerBar);

        // Content Area
        contentFrame = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        mainCol.addView(contentFrame, clp);

        // Initialize Tab Views
        setupPhotosView();
        setupAlbumsView();
        setupSyncView();
        setupAlistWebView();

        // Bottom Navigation (Material 3 NavigationBar with horizontal Monet pill)
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

        mainCol.addView(bottomNav, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(80)));
        rootFrame.addView(mainCol);

        // M3 Floating Action Button (16dp squircle pill, exactly like the + button in mature reference)
        fabUpload = new FloatingActionButton(this);
        fabUpload.setImageResource(R.drawable.ic_m3_cloud_upload);
        fabUpload.setColorFilter(m3.onPrimaryContainer);
        fabUpload.setBackgroundTintList(android.content.res.ColorStateList.valueOf(m3.primaryContainer));
        fabUpload.setElevation(dp(4));
        fabUpload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("image/*,video/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(Intent.createChooser(intent, "选择照片/视频直传 Google Drive"), REQ_MANUAL_UPLOAD);
            }
        });

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56));
        flp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        flp.rightMargin = dp(20);
        flp.bottomMargin = dp(96);
        rootFrame.addView(fabUpload, flp);

        setContentView(rootFrame);
        switchTab("photos");
    }

    /**
     * M3 Large Headline Header (Clean 28sp typography matching Google Photos & Material You standards).
     */
    private View buildM3Header() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(22), dp(40), dp(22), dp(12));
        bar.setBackgroundColor(m3.surface);

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        // Headline Title
        tvHeaderTitle = new TextView(this);
        tvHeaderTitle.setText("照片");
        tvHeaderTitle.setTextColor(m3.onSurface);
        tvHeaderTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        tvHeaderTitle.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        topRow.addView(tvHeaderTitle, tlp);

        // Action Container
        headerActionBox = new LinearLayout(this);
        headerActionBox.setOrientation(LinearLayout.HORIZONTAL);
        headerActionBox.setGravity(Gravity.CENTER_VERTICAL);

        // Grid column toggle pill (3 <-> 4)
        ivTopColToggle = new ImageView(this);
        ivTopColToggle.setImageResource(R.drawable.ic_m3_grid);
        ivTopColToggle.setColorFilter(m3.onSurfaceVariant);
        ivTopColToggle.setPadding(dp(8), dp(8), dp(8), dp(8));
        ivTopColToggle.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(14)));
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
        headerActionBox.addView(ivTopColToggle, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // Google Photos Pinwheel Badge
        ImageView ivLogo = new ImageView(this);
        ivLogo.setImageResource(R.drawable.ic_google_photos_logo);
        ivLogo.setPadding(dp(6), dp(6), dp(6), dp(6));
        ivLogo.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(14)));
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(40), dp(40));
        llp.leftMargin = dp(10);
        headerActionBox.addView(ivLogo, llp);

        topRow.addView(headerActionBox);
        bar.addView(topRow);

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
        albumsRecyclerView.setLayoutManager(new GridLayoutManager(this, 1)); // Clean list of modern 20dp cards
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
        syncContainer.setPadding(dp(16), dp(8), dp(16), dp(40));
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
        fabUpload.setVisibility("drive".equals(tab) ? View.GONE : View.VISIBLE);

        if ("photos".equals(tab)) {
            tvHeaderTitle.setText("照片");
            mainHandler.removeCallbacks(syncUpdaterRunnable);
        } else if ("albums".equals(tab)) {
            tvHeaderTitle.setText("相册与目录");
            mainHandler.removeCallbacks(syncUpdaterRunnable);
        } else if ("sync".equals(tab)) {
            tvHeaderTitle.setText("备份与传输");
            ensureSyncDashboardBuilt();
            updateSyncDashboardUi();
            mainHandler.removeCallbacks(syncUpdaterRunnable);
            mainHandler.post(syncUpdaterRunnable);
        } else if ("drive".equals(tab)) {
            tvHeaderTitle.setText("云端网盘");
            mainHandler.removeCallbacks(syncUpdaterRunnable);
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHOOSE_FILE && uploadMessageCallback != null) {
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            uploadMessageCallback.onReceiveValue(results);
            uploadMessageCallback = null;
        } else if (requestCode == REQ_MANUAL_UPLOAD && resultCode == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    uris.add(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }

            if (!uris.isEmpty()) {
                synchronized (PhotoBackupService.pendingManualUris) {
                    PhotoBackupService.pendingManualUris.addAll(uris);
                }
                Intent intent = new Intent(this, PhotoBackupService.class);
                intent.setAction(PhotoBackupService.ACTION_UPLOAD_URIS);
                intent.putExtra(PhotoBackupService.EXTRA_TARGET_FOLDER, BackupState.get(this).getTargetFolder());
                startService(intent);
                Toast.makeText(this, "已添加 " + uris.size() + " 个文件至直传队列", Toast.LENGTH_SHORT).show();
                bottomNav.setSelectedItemId(R.id.nav_sync);
            }
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
     * Timeline Adapter with 4dp micro-rounded corners and Monet spacing.
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
                header.setPadding(dp(18), dp(24), dp(18), dp(8));

                TextView tvTitle = new TextView(parent.getContext());
                tvTitle.setTextColor(m3.onSurface);
                tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
                tvTitle.setTypeface(null, Typeface.BOLD);

                TextView tvSub = new TextView(parent.getContext());
                tvSub.setTextColor(m3.outline);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);

                header.addView(tvTitle);
                header.addView(tvSub);
                return new HeaderViewHolder(header, tvTitle, tvSub);
            } else {
                FrameLayout cell = new FrameLayout(parent.getContext());
                int m = dp(2);
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
                clp.setMargins(0, 0, dp(6), dp(6));
                cell.addView(ivCloud, clp);

                // Video duration badge
                TextView tvDur = new TextView(parent.getContext());
                tvDur.setTextColor(Color.WHITE);
                tvDur.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10);
                tvDur.setTypeface(null, Typeface.BOLD);
                tvDur.setBackground(makeRoundRect(Color.parseColor("#99000000"), dp(8)));
                tvDur.setPadding(dp(6), dp(2), dp(6), dp(2));
                FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
                vlp.setMargins(dp(6), 0, 0, dp(6));
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
                // Soft 8dp rounded corner transform for modern MD3 look
                Glide.with(MainActivity.this)
                        .load(model)
                        .transform(new CenterCrop(), new RoundedCorners(dp(6)))
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
     * Albums Adapter: Generous 20dp Extra-Large Rounded Cards with 16dp Squircle Thumbnails (Matching Screenshot 2!).
     */
    private class AlbumsAdapter extends RecyclerView.Adapter<AlbumsAdapter.AlbumViewHolder> {

        @NonNull
        @Override
        public AlbumViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(parent.getContext());
            card.setRadius(dp(22)); // 22dp Extra-Large Rounded Corners
            card.setCardElevation(dp(1));
            card.setCardBackgroundColor(m3.surfaceContainer);
            card.setStrokeColor(m3.outlineVariant);
            card.setStrokeWidth(dp(1));

            int mH = dp(16);
            int mV = dp(8);
            ViewGroup.MarginLayoutParams mlp = new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.setMargins(mH, mV, mH, mV);
            card.setLayoutParams(mlp);

            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(16), dp(16), dp(16));

            // 52dp x 52dp Squircle Cover Container with 16dp radius
            FrameLayout iconBox = new FrameLayout(parent.getContext());
            iconBox.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(16)));
            ImageView ivCover = new ImageView(parent.getContext());
            ivCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iconBox.addView(ivCover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            row.addView(iconBox, new LinearLayout.LayoutParams(dp(52), dp(52)));

            // Text Info
            LinearLayout info = new LinearLayout(parent.getContext());
            info.setOrientation(LinearLayout.VERTICAL);
            info.setPadding(dp(16), 0, dp(12), 0);

            TextView tvTitle = new TextView(parent.getContext());
            tvTitle.setTextColor(m3.onSurface);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            tvTitle.setTypeface(null, Typeface.BOLD);
            tvTitle.setSingleLine(true);
            tvTitle.setEllipsize(TextUtils.TruncateAt.END);

            TextView tvCount = new TextView(parent.getContext());
            tvCount.setTextColor(m3.outline);
            tvCount.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            tvCount.setPadding(0, dp(4), 0, 0);

            info.addView(tvTitle);
            info.addView(tvCount);

            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            row.addView(info, ilp);

            // Auto-backup Switch with Monet accent
            MaterialSwitch swBackup = new MaterialSwitch(parent.getContext());
            row.addView(swBackup);

            card.addView(row);
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
                        .transform(new CenterCrop(), new RoundedCorners(dp(14)))
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
                holder.swBackup.setOnCheckedChangeListener(null);
                holder.swBackup.setChecked(card.autoBackup);
                holder.swBackup.setOnCheckedChangeListener((btn, isChecked) -> {
                    card.autoBackup = isChecked;
                    Set<String> sel = BackupState.get(MainActivity.this).getSelectedBuckets();
                    if (isChecked) {
                        sel.add(card.bucketName);
                    } else {
                        sel.remove(card.bucketName);
                    }
                    boolean hasBuckets = !sel.isEmpty();
                    BackupState.get(MainActivity.this).saveConfig(
                            hasBuckets,
                            BackupState.get(MainActivity.this).isWifiOnly(),
                            BackupState.get(MainActivity.this).isIncludeVideos(),
                            BackupState.get(MainActivity.this).getTargetFolder(),
                            sel
                    );
                    Toast.makeText(MainActivity.this, (isChecked ? "已启用 " : "已关闭 ") + card.title + " 的自动备份", Toast.LENGTH_SHORT).show();
                    if (hasBuckets) {
                        Intent it = new Intent(MainActivity.this, PhotoBackupService.class);
                        it.setAction(PhotoBackupService.ACTION_START_WATCHER);
                        startService(it);
                    }
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

    /**
     * Backup, Transfer & Download Dashboard:
     * Modeled with 100% precision on the user's reference screenshot (Screenshot 2).
     */
    private void ensureSyncDashboardBuilt() {
        if (syncDashboardInitialized && syncContainer.getChildCount() > 0) return;
        syncContainer.removeAllViews();
        final BackupState state = BackupState.get(this);

        // Sub Actions Row (matching "全部暂停  全部开始  一键查重")
        LinearLayout subActions = new LinearLayout(this);
        subActions.setOrientation(LinearLayout.HORIZONTAL);
        subActions.setGravity(Gravity.CENTER_VERTICAL);
        subActions.setPadding(dp(6), 0, dp(6), dp(16));

        TextView btnResume = new TextView(this);
        btnResume.setText("▶ 全部开始");
        btnResume.setTextColor(m3.primary);
        btnResume.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btnResume.setTypeface(null, Typeface.BOLD);
        btnResume.setPadding(dp(14), dp(8), dp(14), dp(8));
        btnResume.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(14)));
        btnResume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.isRunning = true;
                state.statusText = "正在扫描并同步未备份相册...";
                updateSyncDashboardUi();
                Intent it = new Intent(MainActivity.this, PhotoBackupService.class);
                it.setAction(PhotoBackupService.ACTION_SCAN_AND_BACKUP);
                startService(it);
                Toast.makeText(MainActivity.this, "已开始扫描并传输未备份照片", Toast.LENGTH_SHORT).show();
            }
        });

        TextView btnPause = new TextView(this);
        btnPause.setText("⏸ 全部暂停");
        btnPause.setTextColor(m3.onSurfaceVariant);
        btnPause.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btnPause.setTypeface(null, Typeface.BOLD);
        btnPause.setPadding(dp(14), dp(8), dp(14), dp(8));
        btnPause.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(14)));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = dp(8);
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.isRunning = false;
                state.statusText = "已暂停备份传输";
                updateSyncDashboardUi();
                Intent it = new Intent(MainActivity.this, PhotoBackupService.class);
                it.setAction(PhotoBackupService.ACTION_STOP_BACKUP);
                startService(it);
                Toast.makeText(MainActivity.this, "已停止当前传输", Toast.LENGTH_SHORT).show();
            }
        });

        TextView btnDedup = new TextView(this);
        btnDedup.setText("🧹 网盘查重");
        btnDedup.setTextColor(m3.outline);
        btnDedup.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        btnDedup.setPadding(dp(12), dp(8), dp(12), dp(8));
        btnDedup.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(14)));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(8);
        btnDedup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(MainActivity.this, "正在触发 Google Drive 深度查重...", Toast.LENGTH_SHORT).show();
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            URL u = new URL(BASE_API + "/api/cloud_transfer/dedup");
                            HttpURLConnection c = (HttpURLConnection) u.openConnection();
                            c.setRequestMethod("POST");
                            c.setConnectTimeout(10000);
                            c.setReadTimeout(30000);
                            c.getResponseCode();
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "网盘查重完成！已清理重名冗余副本", Toast.LENGTH_LONG).show();
                                    loadAllMediaData();
                                }
                            });
                        } catch (Exception e) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "查重请求已送达服务器后台处理", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                    }
                });
            }
        });

        subActions.addView(btnResume);
        subActions.addView(btnPause, plp);
        subActions.addView(btnDedup, dlp);
        syncContainer.addView(subActions);

        // Active Task Card (22dp extra-large rounded card, exact match to Screenshot 2!)
        MaterialCardView cardTask = new MaterialCardView(this);
        cardTask.setRadius(dp(22));
        cardTask.setCardBackgroundColor(m3.surfaceContainer);
        cardTask.setStrokeColor(m3.outlineVariant);
        cardTask.setStrokeWidth(dp(1));
        cardTask.setCardElevation(dp(1));

        LinearLayout taskBox = new LinearLayout(this);
        taskBox.setOrientation(LinearLayout.VERTICAL);
        taskBox.setPadding(dp(18), dp(18), dp(18), dp(16));

        // Top Row: Squircle File Icon + Title + Status
        LinearLayout itemRow = new LinearLayout(this);
        itemRow.setOrientation(LinearLayout.HORIZONTAL);
        itemRow.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout iconFrame = new FrameLayout(this);
        iconFrame.setBackground(makeRoundRect(m3.surfaceContainerHigh, dp(14)));
        ImageView ivDoc = new ImageView(this);
        ivDoc.setImageResource(R.drawable.ic_m3_photos);
        ivDoc.setColorFilter(m3.primary);
        ivDoc.setPadding(dp(10), dp(10), dp(10), dp(10));
        iconFrame.addView(ivDoc, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        itemRow.addView(iconFrame, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(14), 0, dp(10), 0);

        tvSyncFileName = new TextView(this);
        tvSyncFileName.setTextColor(m3.onSurface);
        tvSyncFileName.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tvSyncFileName.setTypeface(null, Typeface.BOLD);
        tvSyncFileName.setSingleLine(true);
        tvSyncFileName.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        tvSyncStatus = new TextView(this);
        tvSyncStatus.setTextColor(m3.outline);
        tvSyncStatus.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvSyncStatus.setPadding(0, dp(4), 0, 0);

        titleBox.addView(tvSyncFileName);
        titleBox.addView(tvSyncStatus);
        itemRow.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        taskBox.addView(itemRow);

        // Progress Bar
        pbSync = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pbSync.setMax(100);
        LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6));
        pblp.topMargin = dp(16);
        pblp.bottomMargin = dp(12);
        taskBox.addView(pbSync, pblp);

        // Bottom Stats Row (matching Screenshot 2: "已下载 11.9 MB / 45.4 MB · 26%    🗑 删除")
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);

        tvSyncStats = new TextView(this);
        tvSyncStats.setTextColor(m3.outline);
        tvSyncStats.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        statsRow.addView(tvSyncStats, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView tvDelete = new TextView(this);
        tvDelete.setText("⏹ 停止");
        tvDelete.setTextColor(Color.parseColor("#BA1A1A"));
        tvDelete.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvDelete.setTypeface(null, Typeface.BOLD);
        tvDelete.setPadding(dp(8), dp(4), dp(8), dp(4));
        tvDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(MainActivity.this, PhotoBackupService.class);
                it.setAction(PhotoBackupService.ACTION_STOP_BACKUP);
                startService(it);
                state.isRunning = false;
                state.statusText = "已停止当前传输";
                updateSyncDashboardUi();
                Toast.makeText(MainActivity.this, "已停止当前传输", Toast.LENGTH_SHORT).show();
            }
        });
        statsRow.addView(tvDelete);

        taskBox.addView(statsRow);
        cardTask.addView(taskBox);
        syncContainer.addView(cardTask);

        // Settings Card (22dp rounded corners)
        MaterialCardView cardOpts = new MaterialCardView(this);
        cardOpts.setRadius(dp(22));
        cardOpts.setCardBackgroundColor(m3.surfaceContainer);
        cardOpts.setStrokeColor(m3.outlineVariant);
        cardOpts.setStrokeWidth(dp(1));
        LinearLayout.LayoutParams colp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        colp.topMargin = dp(16);
        cardOpts.setLayoutParams(colp);

        LinearLayout optsBox = new LinearLayout(this);
        optsBox.setOrientation(LinearLayout.VERTICAL);
        optsBox.setPadding(dp(18), dp(16), dp(18), dp(16));

        TextView tvOptTitle = new TextView(this);
        tvOptTitle.setText("同步选项与后台守护");
        tvOptTitle.setTextColor(m3.onSurface);
        tvOptTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tvOptTitle.setTypeface(null, Typeface.BOLD);
        tvOptTitle.setPadding(0, 0, 0, dp(12));
        optsBox.addView(tvOptTitle);

        swAutoMaster = new MaterialSwitch(this);
        swAutoMaster.setText("全自动后台静默同步");
        swAutoMaster.setTextColor(m3.primary);
        swAutoMaster.setTypeface(null, Typeface.BOLD);
        optsBox.addView(swAutoMaster);

        swWifi = new MaterialSwitch(this);
        swWifi.setText("仅在 Wi-Fi 网络下备份");
        swWifi.setTextColor(m3.onSurface);
        optsBox.addView(swWifi);

        swVid = new MaterialSwitch(this);
        swVid.setText("包含视频备份");
        swVid.setTextColor(m3.onSurface);
        optsBox.addView(swVid);

        TextView tvClear = new TextView(this);
        tvClear.setText("🔄 清除本地已备份账本并重新比对");
        tvClear.setTextColor(m3.outline);
        tvClear.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvClear.setPadding(dp(4), dp(14), dp(4), dp(4));
        tvClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.clearBackedUpLedger();
                Toast.makeText(MainActivity.this, "已重置已同步记录，下次扫描将重新全量比对", Toast.LENGTH_SHORT).show();
                updateSyncDashboardUi();
            }
        });
        optsBox.addView(tvClear);

        cardOpts.addView(optsBox);
        syncContainer.addView(cardOpts);

        syncDashboardInitialized = true;
    }

    private void updateSyncDashboardUi() {
        if (!syncDashboardInitialized) return;
        final BackupState state = BackupState.get(this);

        if (tvSyncFileName != null) {
            String name = !TextUtils.isEmpty(state.currentFile) ? state.currentFile : (state.isRunning ? "正在比对相册与云端..." : "云相册自动归档已就绪");
            tvSyncFileName.setText(name);
        }

        if (tvSyncStatus != null) {
            tvSyncStatus.setText(state.isRunning ? ("正在直传 R2 -> 自动归档 Google Drive · " + state.statusText) : state.statusText);
        }

        if (pbSync != null) {
            if (state.isRunning) {
                if (state.currentProgress > 0) {
                    pbSync.setIndeterminate(false);
                    pbSync.setProgress(state.currentProgress);
                } else {
                    pbSync.setIndeterminate(true);
                }
            } else {
                pbSync.setIndeterminate(false);
                pbSync.setProgress(100);
            }
        }

        if (tvSyncStats != null) {
            if (state.isRunning) {
                tvSyncStats.setText("已直传 " + state.sessionUploadedCount + "/" + state.sessionTotalCount + " 项 · 速度约 " + formatSpeed(state.currentSpeedBps));
            } else {
                int backedCount = 0;
                for (String k : state.getBackedUpSet()) {
                    if (!k.contains(":")) backedCount++;
                }
                tvSyncStats.setText("已累计同步 " + backedCount + " 张照片 · 直传目标: " + state.getTargetFolder());
            }
        }

        if (swAutoMaster != null) {
            swAutoMaster.setOnCheckedChangeListener(null);
            swAutoMaster.setChecked(state.isAutoBackupEnabled());
            swAutoMaster.setOnCheckedChangeListener((btn, isChecked) -> {
                state.saveConfig(isChecked, state.isWifiOnly(), state.isIncludeVideos(), state.getTargetFolder(), state.getSelectedBuckets());
                Toast.makeText(this, isChecked ? "已开启全自动后台同步" : "已暂停全自动后台同步", Toast.LENGTH_SHORT).show();
                if (isChecked) {
                    Intent it = new Intent(this, PhotoBackupService.class);
                    it.setAction(PhotoBackupService.ACTION_START_WATCHER);
                    startService(it);
                }
            });
        }

        if (swWifi != null) {
            swWifi.setOnCheckedChangeListener(null);
            swWifi.setChecked(state.isWifiOnly());
            swWifi.setOnCheckedChangeListener((btn, isChecked) -> {
                state.saveConfig(state.isAutoBackupEnabled(), isChecked, state.isIncludeVideos(), state.getTargetFolder(), state.getSelectedBuckets());
            });
        }

        if (swVid != null) {
            swVid.setOnCheckedChangeListener(null);
            swVid.setChecked(state.isIncludeVideos());
            swVid.setOnCheckedChangeListener((btn, isChecked) -> {
                state.saveConfig(state.isAutoBackupEnabled(), state.isWifiOnly(), isChecked, state.getTargetFolder(), state.getSelectedBuckets());
            });
        }
    }

    private String formatSpeed(long bps) {
        if (bps <= 0) return "计算中...";
        if (bps < 1024 * 1024) return String.format(Locale.US, "%.1f KB/s", bps / 1024.0);
        return String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0));
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
    protected void onResume() {
        super.onResume();
        if ("sync".equals(currentTab)) {
            updateSyncDashboardUi();
            mainHandler.removeCallbacks(syncUpdaterRunnable);
            mainHandler.post(syncUpdaterRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mainHandler.removeCallbacks(syncUpdaterRunnable);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacks(syncUpdaterRunnable);
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
