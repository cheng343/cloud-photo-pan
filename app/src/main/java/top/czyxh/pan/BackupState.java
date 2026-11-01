package top.czyxh.pan;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class BackupState {
    private static final String PREF_NAME = "cloud_photo_pan_prefs";
    private static BackupState instance;
    private final SharedPreferences prefs;

    public volatile boolean isRunning = false;
    public volatile boolean stopRequested = false;
    public volatile String statusText = "已就绪";
    public volatile String currentFile = "";
    public volatile int currentProgress = 0;
    public volatile long currentSpeedBps = 0;
    public volatile int sessionUploadedCount = 0;
    public volatile int sessionTotalCount = 0;
    private final List<String> logs = new ArrayList<>();

    private BackupState(Context ctx) {
        this.prefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized BackupState get(Context ctx) {
        if (instance == null) {
            instance = new BackupState(ctx);
        }
        return instance;
    }

    public boolean isAutoBackupEnabled() {
        return prefs.getBoolean("auto_backup_enabled", false);
    }

    public boolean isWifiOnly() {
        return prefs.getBoolean("wifi_only", true);
    }

    public boolean isIncludeVideos() {
        return prefs.getBoolean("include_videos", true);
    }

    public int getGridColumns() {
        return prefs.getInt("grid_columns", 3);
    }

    public void setGridColumns(int cols) {
        prefs.edit().putInt("grid_columns", cols).apply();
    }

    public String getTargetFolder() {
        return prefs.getString("target_folder", "/GoogleDrive/photos");
    }

    public Set<String> getSelectedBuckets() {
        Set<String> def = new HashSet<>(Collections.singletonList("Camera"));
        return new HashSet<>(prefs.getStringSet("selected_buckets", def));
    }

    public void saveConfig(boolean autoEnabled, boolean wifiOnly, boolean includeVideos, String targetFolder, Set<String> buckets) {
        prefs.edit()
                .putBoolean("auto_backup_enabled", autoEnabled)
                .putBoolean("wifi_only", wifiOnly)
                .putBoolean("include_videos", includeVideos)
                .putString("target_folder", (targetFolder == null || targetFolder.trim().isEmpty()) ? "/GoogleDrive/photos" : targetFolder.trim())
                .putStringSet("selected_buckets", buckets)
                .apply();
    }

    public synchronized Set<String> getFavoritesSet() {
        return new HashSet<>(prefs.getStringSet("favorite_keys", new HashSet<String>()));
    }

    public synchronized boolean isFavorite(String key) {
        return getFavoritesSet().contains(key);
    }

    public synchronized void toggleFavorite(String key) {
        Set<String> favs = getFavoritesSet();
        if (favs.contains(key)) favs.remove(key);
        else favs.add(key);
        prefs.edit().putStringSet("favorite_keys", favs).apply();
    }

    public synchronized Set<String> getBackedUpSet() {
        return new HashSet<>(prefs.getStringSet("backed_up_keys", new HashSet<String>()));
    }

    public synchronized void markBackedUp(String key) {
        Set<String> cur = getBackedUpSet();
        cur.add(key);
        prefs.edit().putStringSet("backed_up_keys", cur).apply();
    }

    public synchronized void clearBackedUpLedger() {
        prefs.edit().putStringSet("backed_up_keys", new HashSet<String>()).apply();
        addLog("已重置本地备份记录账本");
    }

    public synchronized void addLog(String msg) {
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        logs.add(0, "[" + ts + "] " + msg);
        if (logs.size() > 60) {
            logs.remove(logs.size() - 1);
        }
    }

    public synchronized String toJsonString() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("autoBackupEnabled", isAutoBackupEnabled());
            obj.put("wifiOnly", isWifiOnly());
            obj.put("includeVideos", isIncludeVideos());
            obj.put("targetFolder", getTargetFolder());

            JSONArray bArr = new JSONArray();
            for (String b : getSelectedBuckets()) {
                bArr.put(b);
            }
            obj.put("selectedBuckets", bArr);

            obj.put("isRunning", isRunning);
            obj.put("statusText", statusText);
            obj.put("currentFile", currentFile);
            obj.put("currentProgress", currentProgress);
            obj.put("currentSpeedBps", currentSpeedBps);
            obj.put("sessionUploadedCount", sessionUploadedCount);
            obj.put("sessionTotalCount", sessionTotalCount);

            int distinctFiles = 0;
            for (String k : getBackedUpSet()) {
                if (!k.contains(":")) distinctFiles++;
            }
            obj.put("totalBackedUpCount", distinctFiles);

            JSONArray lArr = new JSONArray();
            for (String l : logs) {
                lArr.put(l);
            }
            obj.put("logs", lArr);
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
