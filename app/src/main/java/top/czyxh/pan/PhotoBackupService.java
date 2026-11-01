package top.czyxh.pan;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhotoBackupService extends Service {
    public static final String ACTION_SCAN_AND_BACKUP = "top.czyxh.pan.SCAN_AND_BACKUP";
    public static final String ACTION_UPLOAD_URIS = "top.czyxh.pan.UPLOAD_URIS";
    public static final String ACTION_START_WATCHER = "top.czyxh.pan.START_WATCHER";
    public static final String EXTRA_TARGET_FOLDER = "target_folder";

    private static final String CHANNEL_ID = "cloud_photo_backup_channel";
    private static final int NOTIF_ID = 1001;
    private static final String BASE_API = "https://pan.czyxh.top";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public static final List<Uri> pendingManualUris = new ArrayList<>();

    private ExecutorService executor;
    private ContentObserver mediaObserver;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable debounceScanRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createNotificationChannel();
        registerMediaObserver();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundSafe("云相册后台守护运行中", "随时自动直传新照片至 Cloudflare R2 -> Google Drive");

        String action = intent != null ? intent.getAction() : ACTION_START_WATCHER;
        if (ACTION_UPLOAD_URIS.equals(action)) {
            final String folder = intent.getStringExtra(EXTRA_TARGET_FOLDER);
            final List<Uri> toUpload;
            synchronized (pendingManualUris) {
                toUpload = new ArrayList<>(pendingManualUris);
                pendingManualUris.clear();
            }
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    uploadManualUris(toUpload, folder != null ? folder : "/GoogleDrive/photos");
                }
            });
        } else if (ACTION_SCAN_AND_BACKUP.equals(action)) {
            executor.submit(new Runnable() {
                @Override
                public void run() {
                    scanAndBackupAlbums(true);
                }
            });
        } else {
            if (BackupState.get(this).isAutoBackupEnabled()) {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        scanAndBackupAlbums(false);
                    }
                });
            }
        }
        return START_STICKY;
    }

    private void registerMediaObserver() {
        try {
            mediaObserver = new ContentObserver(mainHandler) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);
                    if (!BackupState.get(PhotoBackupService.this).isAutoBackupEnabled()) {
                        return;
                    }
                    if (debounceScanRunnable != null) {
                        mainHandler.removeCallbacks(debounceScanRunnable);
                    }
                    debounceScanRunnable = new Runnable() {
                        @Override
                        public void run() {
                            executor.submit(new Runnable() {
                                @Override
                                public void run() {
                                    BackupState.get(PhotoBackupService.this).addLog("检测到相机新增媒体文件，触发自动备份...");
                                    scanAndBackupAlbums(false);
                                }
                            });
                        }
                    };
                    mainHandler.postDelayed(debounceScanRunnable, 2500);
                }
            };
            getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
            getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver);
        } catch (Exception e) {
            BackupState.get(this).addLog("监听相册变动注册提示: " + e.getMessage());
        }
    }

    private boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Exception e) {
            return true;
        }
    }

    private static class MediaItem {
        Uri uri;
        String name;
        long size;
        String bucket;
    }

    private void syncCloudBackedUpKeys(BackupState state) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_API + "/api/cloud_transfer/photos");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
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
                        JSONObject item = arr.getJSONObject(i);
                        String name = item.optString("name", "");
                        if (!name.isEmpty()) {
                            state.markBackedUp(name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void scanAndBackupAlbums(boolean forceManual) {
        BackupState state = BackupState.get(this);
        if (state.isRunning) {
            return;
        }
        if (!forceManual && state.isWifiOnly() && !isWifiConnected()) {
            state.statusText = "等待连接 Wi-Fi 后自动备份";
            state.addLog("当前非 Wi-Fi 环境，已暂停自动备份");
            return;
        }

        state.isRunning = true;
        state.statusText = "正在扫描手机相册与比对云端...";
        state.currentFile = "";
        state.currentProgress = 0;
        state.currentSpeedBps = 0;

        try {
            syncCloudBackedUpKeys(state);
            Set<String> backedUp = state.getBackedUpSet();
            Set<String> selectedBuckets = state.getSelectedBuckets();
            boolean includeVideos = state.isIncludeVideos();

            List<MediaItem> candidates = new ArrayList<>();
            queryMediaUri(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, selectedBuckets, backedUp, candidates);
            if (includeVideos) {
                queryMediaUri(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, selectedBuckets, backedUp, candidates);
            }

            if (candidates.isEmpty()) {
                state.statusText = "所有相册照片已是最新同步状态";
                state.addLog("扫描完成：没有发现未备份的新照片/视频");
                updateNotification("云相册已全部同步", "所有照片均已安全存放在 Google Drive");
                state.isRunning = false;
                return;
            }

            state.sessionTotalCount = candidates.size();
            state.sessionUploadedCount = 0;
            state.addLog("发现 " + candidates.size() + " 个新照片/视频，开始极速直传 R2...");

            String targetFolder = state.getTargetFolder();
            for (int i = 0; i < candidates.size(); i++) {
                if (!forceManual && !state.isAutoBackupEnabled()) {
                    state.addLog("自动备份开关已关闭，中止队列");
                    break;
                }
                if (!forceManual && state.isWifiOnly() && !isWifiConnected()) {
                    state.addLog("Wi-Fi 已断开，暂停剩余备份队列");
                    break;
                }
                MediaItem item = candidates.get(i);
                state.statusText = "正在备份 (" + (i + 1) + "/" + candidates.size() + ")";
                updateNotification("正在备份照片 (" + (i + 1) + "/" + candidates.size() + ")", item.name);

                boolean ok = uploadSingleStreamToR2(item.uri, item.name, item.size, targetFolder, state);
                if (ok) {
                    state.markBackedUp(item.name);
                    state.markBackedUp(item.name + ":" + item.size);
                    state.sessionUploadedCount++;
                }
            }

            triggerServerR2Sync();
            state.statusText = "本次备份完成 (" + state.sessionUploadedCount + "/" + state.sessionTotalCount + ")";
            state.currentFile = "";
            state.currentProgress = 100;
            state.currentSpeedBps = 0;
            updateNotification("云相册备份完成", "成功直传 " + state.sessionUploadedCount + " 个文件至 R2 并同步 Google Drive");
        } catch (Exception e) {
            state.statusText = "备份出错: " + e.getMessage();
            state.addLog("扫描异常: " + e.getMessage());
        } finally {
            state.isRunning = false;
        }
    }

    private void queryMediaUri(Uri contentUri, Set<String> selectedBuckets, Set<String> backedUp, List<MediaItem> out) {
        String[] projection = new String[]{
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_MODIFIED
        };
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    contentUri,
                    projection,
                    null,
                    null,
                    MediaStore.MediaColumns.DATE_MODIFIED + " DESC"
            );
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
                int bucketCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    long size = cursor.getLong(sizeCol);
                    String bucket = cursor.getString(bucketCol);
                    if (bucket == null || bucket.isEmpty()) bucket = "Default";
                    if (name == null || name.isEmpty() || size <= 0) continue;

                    if (!selectedBuckets.isEmpty() && !selectedBuckets.contains(bucket) && !selectedBuckets.contains("ALL")) {
                        continue;
                    }
                    if (backedUp.contains(name) || backedUp.contains(name + ":" + size)) {
                        continue;
                    }

                    MediaItem item = new MediaItem();
                    item.uri = ContentUris.withAppendedId(contentUri, id);
                    item.name = name;
                    item.size = size;
                    item.bucket = bucket;
                    out.add(item);
                }
            }
        } catch (Exception e) {
            BackupState.get(this).addLog("读取相册目录提示: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void uploadManualUris(List<Uri> uris, String targetFolder) {
        if (uris == null || uris.isEmpty()) return;
        BackupState state = BackupState.get(this);
        state.isRunning = true;
        state.sessionTotalCount = uris.size();
        state.sessionUploadedCount = 0;
        state.addLog("开始手动直传 " + uris.size() + " 个文件至 " + targetFolder);

        try {
            for (int i = 0; i < uris.size(); i++) {
                Uri uri = uris.get(i);
                String name = "upload_" + System.currentTimeMillis();
                long size = -1;
                Cursor c = getContentResolver().query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            int nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int sizeIdx = c.getColumnIndex(OpenableColumns.SIZE);
                            if (nameIdx >= 0 && c.getString(nameIdx) != null) name = c.getString(nameIdx);
                            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx);
                        }
                    } finally {
                        c.close();
                    }
                }
                state.statusText = "正在直传 (" + (i + 1) + "/" + uris.size() + ")";
                updateNotification("正在上传 (" + (i + 1) + "/" + uris.size() + ")", name);
                boolean ok = uploadSingleStreamToR2(uri, name, size, targetFolder, state);
                if (ok) {
                    state.markBackedUp(name);
                    if (size > 0) state.markBackedUp(name + ":" + size);
                    state.sessionUploadedCount++;
                }
            }
            triggerServerR2Sync();
            state.statusText = "上传完成 (" + state.sessionUploadedCount + "/" + uris.size() + ")";
            state.currentFile = "";
            state.currentProgress = 100;
            state.currentSpeedBps = 0;
            updateNotification("上传完成", "已直传 " + state.sessionUploadedCount + " 个文件并触发 Google Drive 同步");
        } catch (Exception e) {
            state.statusText = "上传异常: " + e.getMessage();
            state.addLog("上传异常: " + e.getMessage());
        } finally {
            state.isRunning = false;
        }
    }

    private boolean uploadSingleStreamToR2(Uri fileUri, String fileName, long fileSize, String targetFolder, BackupState state) {
        state.currentFile = fileName;
        state.currentProgress = 0;
        state.currentSpeedBps = 0;

        HttpURLConnection presignConn = null;
        HttpURLConnection putConn = null;
        InputStream in = null;
        OutputStream out = null;

        try {
            // 1. Determine exact byte length if unknown
            if (fileSize <= 0) {
                InputStream tmp = getContentResolver().openInputStream(fileUri);
                if (tmp != null) {
                    fileSize = tmp.available();
                    tmp.close();
                }
            }

            // 2. Request R2 Presigned PUT URL from server
            String cleanFolder = (targetFolder == null || targetFolder.isEmpty()) ? "/GoogleDrive/photos" : targetFolder;
            if (!cleanFolder.startsWith("/")) cleanFolder = "/" + cleanFolder;
            String fullTarget = cleanFolder.replaceAll("/+$", "") + "/" + fileName;
            String presignApi = BASE_API + "/api/cloud_transfer/presign_upload?path=" + URLEncoder.encode(fullTarget, "UTF-8");

            presignConn = (HttpURLConnection) new URL(presignApi).openConnection();
            presignConn.setRequestMethod("GET");
            presignConn.setRequestProperty("User-Agent", USER_AGENT);
            presignConn.setConnectTimeout(15000);
            presignConn.setReadTimeout(15000);

            int code = presignConn.getResponseCode();
            if (code != 200) {
                state.addLog("获取签名失败 (" + code + "): " + fileName);
                return false;
            }

            BufferedReader br = new BufferedReader(new InputStreamReader(presignConn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();

            JSONObject resJson = new JSONObject(sb.toString());
            String putUrl = resJson.optString("url", "");
            if (putUrl.isEmpty()) {
                state.addLog("签名链接为空: " + fileName);
                return false;
            }

            // 3. Stream directly to Cloudflare R2 Storage (bypasses 100MB CDN limit & uses 0 server bandwidth)
            putConn = (HttpURLConnection) new URL(putUrl).openConnection();
            putConn.setRequestMethod("PUT");
            putConn.setDoOutput(true);
            putConn.setConnectTimeout(20000);
            putConn.setReadTimeout(300000);
            if (fileSize > 0) {
                putConn.setFixedLengthStreamingMode(fileSize);
                putConn.setRequestProperty("Content-Length", String.valueOf(fileSize));
            } else {
                putConn.setChunkedStreamingMode(65536);
            }

            in = getContentResolver().openInputStream(fileUri);
            if (in == null) {
                state.addLog("无法打开本地文件流: " + fileName);
                return false;
            }

            out = putConn.getOutputStream();
            byte[] buf = new byte[65536];
            long transferred = 0;
            long startTime = System.currentTimeMillis();
            long lastUpdate = startTime;
            int n;

            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                transferred += n;
                long now = System.currentTimeMillis();
                if (now - lastUpdate >= 200) {
                    if (fileSize > 0) {
                        state.currentProgress = (int) ((transferred * 100L) / fileSize);
                    }
                    long elapsedMs = Math.max(1, now - startTime);
                    state.currentSpeedBps = (transferred * 1000L) / elapsedMs;
                    lastUpdate = now;
                }
            }
            out.flush();

            int putStatus = putConn.getResponseCode();
            if (putStatus >= 200 && putStatus < 300) {
                state.currentProgress = 100;
                String sizeMb = String.format("%.2f MB", transferred / 1048576.0);
                state.addLog("✅ 直传 R2 成功: " + fileName + " (" + sizeMb + ")");
                triggerServerR2Sync();
                return true;
            } else {
                state.addLog("❌ R2 返回 HTTP " + putStatus + ": " + fileName);
                return false;
            }
        } catch (Exception e) {
            state.addLog("❌ 上传失败 " + fileName + ": " + e.getMessage());
            return false;
        } finally {
            try { if (in != null) in.close(); } catch (Exception ignored) {}
            try { if (out != null) out.close(); } catch (Exception ignored) {}
            if (presignConn != null) presignConn.disconnect();
            if (putConn != null) putConn.disconnect();
        }
    }

    private void triggerServerR2Sync() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE_API + "/api/cloud_transfer/trigger_sync");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.getResponseCode();
        } catch (Exception ignored) {
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID,
                    "云相册自动备份服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            chan.setDescription("后台静默直传相册至 Cloudflare R2 与 Google Drive");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
        }
    }

    private Notification buildNotification(String title, String text) {
        Intent i = new Intent(this, MainActivity.class);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, i, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        builder.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentIntent(pi)
                .setOngoing(true);
        return builder.build();
    }

    private void startForegroundSafe(String title, String text) {
        try {
            Notification notif = buildNotification(title, text);
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Exception ignored) {
        }
    }

    private void updateNotification(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification(title, text));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        if (mediaObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(mediaObserver);
            } catch (Exception ignored) {}
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
