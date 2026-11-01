package top.czyxh.pan;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mature Flagship Photo & Video Viewer powered by ViewPager2 + PhotoView + Glide + MD3 BottomSheetDialog.
 * Eliminates custom gesture math, gesture conflicts, and memory issues.
 */
public class PhotoViewerDialog {

    public interface ViewerCallbacks {
        void onUploadItem(MainActivity.GalleryItem item);
        void onDownloadItem(MainActivity.GalleryItem item);
        void onDeleteItem(MainActivity.GalleryItem item, Runnable onDeleted);
        void onToggleFavorite(MainActivity.GalleryItem item, boolean fav);
        boolean isFavorite(MainActivity.GalleryItem item);
    }

    private final AppCompatActivity activity;
    private final List<MainActivity.GalleryItem> items;
    private int currentIndex;
    private final ViewerCallbacks callbacks;

    private Dialog dialog;
    private ViewPager2 viewPager;
    private View topBar;
    private View bottomBar;
    private TextView tvTitle;
    private TextView tvSub;
    private ImageView ivFavorite;
    private ImageView ivActionCloud;
    private boolean barsVisible = true;

    public PhotoViewerDialog(AppCompatActivity activity, List<MainActivity.GalleryItem> items, int startIndex, ViewerCallbacks callbacks) {
        this.activity = activity;
        this.items = items;
        this.currentIndex = Math.max(0, Math.min(startIndex, items.size() - 1));
        this.callbacks = callbacks;
    }

    public void show() {
        if (items == null || items.isEmpty()) return;

        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        Window win = dialog.getWindow();
        if (win != null) {
            win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                win.setStatusBarColor(Color.TRANSPARENT);
                win.setNavigationBarColor(Color.parseColor("#80000000"));
            }
        }

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);

        viewPager = new ViewPager2(activity);
        viewPager.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        viewPager.setOrientation(ViewPager2.ORIENTATION_HORIZONTAL);
        viewPager.setOffscreenPageLimit(1);
        viewPager.setAdapter(new MediaPagerAdapter());

        topBar = buildTopBar();
        bottomBar = buildBottomBar();

        root.addView(viewPager);
        root.addView(topBar);
        root.addView(bottomBar);

        dialog.setContentView(root);
        dialog.show();

        viewPager.setCurrentItem(currentIndex, false);
        updateBarInfo(currentIndex);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                currentIndex = position;
                updateBarInfo(position);
            }
        });
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(36), dp(16), dp(14));
        bar.setBackground(makeGradient(Color.parseColor("#CC000000"), Color.TRANSPARENT));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        bar.setLayoutParams(lp);

        // Back button
        ImageView ivBack = new ImageView(activity);
        ivBack.setImageResource(R.drawable.ic_m3_back);
        ivBack.setColorFilter(Color.WHITE);
        ivBack.setPadding(dp(8), dp(8), dp(8), dp(8));
        ivBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        bar.addView(ivBack, new LinearLayout.LayoutParams(dp(40), dp(40)));

        // Title and count
        LinearLayout titleBox = new LinearLayout(activity);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(12), 0, dp(12), 0);

        tvTitle = new TextView(activity);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        tvSub = new TextView(activity);
        tvSub.setTextColor(Color.parseColor("#B0BEC5"));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);

        titleBox.addView(tvTitle);
        titleBox.addView(tvSub);

        LinearLayout.LayoutParams tblp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        bar.addView(titleBox, tblp);

        // Info button
        ImageView ivInfo = new ImageView(activity);
        ivInfo.setImageResource(R.drawable.ic_m3_info);
        ivInfo.setColorFilter(Color.WHITE);
        ivInfo.setPadding(dp(8), dp(8), dp(8), dp(8));
        ivInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExifBottomSheet(items.get(currentIndex));
            }
        });
        bar.addView(ivInfo, new LinearLayout.LayoutParams(dp(40), dp(40)));

        return bar;
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(16), dp(16), dp(16), dp(28));
        bar.setBackground(makeGradient(Color.TRANSPARENT, Color.parseColor("#CC000000")));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        bar.setLayoutParams(lp);

        // Share
        bar.addView(createBottomAction(R.drawable.ic_m3_share, "分享", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareCurrentItem(items.get(currentIndex));
            }
        }));

        // Favorite
        ivFavorite = new ImageView(activity);
        ivFavorite.setImageResource(R.drawable.ic_m3_star);
        ivFavorite.setColorFilter(Color.WHITE);
        ivFavorite.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout favBox = createActionBox(ivFavorite, "收藏", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.GalleryItem cur = items.get(currentIndex);
                boolean wasFav = callbacks != null && callbacks.isFavorite(cur);
                boolean nowFav = !wasFav;
                if (callbacks != null) callbacks.onToggleFavorite(cur, nowFav);
                updateFavoriteIcon(nowFav);
                Toast.makeText(activity, nowFav ? "已加入我的收藏 ⭐" : "已移出收藏", Toast.LENGTH_SHORT).show();
            }
        });
        bar.addView(favBox);

        // Cloud Upload/Download
        ivActionCloud = new ImageView(activity);
        ivActionCloud.setImageResource(R.drawable.ic_m3_cloud_upload);
        ivActionCloud.setColorFilter(Color.WHITE);
        ivActionCloud.setPadding(dp(10), dp(10), dp(10), dp(10));
        LinearLayout cloudBox = createActionBox(ivActionCloud, "云端", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.GalleryItem cur = items.get(currentIndex);
                if (cur.isLocalOnly()) {
                    if (callbacks != null) callbacks.onUploadItem(cur);
                    Toast.makeText(activity, "已加入直传队列: " + cur.name, Toast.LENGTH_SHORT).show();
                } else {
                    if (callbacks != null) callbacks.onDownloadItem(cur);
                    Toast.makeText(activity, "正在下载原图...", Toast.LENGTH_SHORT).show();
                }
            }
        });
        bar.addView(cloudBox);

        // Delete
        bar.addView(createBottomAction(R.drawable.ic_m3_delete, "删除", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final MainActivity.GalleryItem cur = items.get(currentIndex);
                new AlertDialog.Builder(activity)
                        .setTitle("确认删除")
                        .setMessage("确定要从云相册中删除此" + (cur.isVideo ? "视频" : "照片") + "吗？\n" + cur.name)
                        .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                if (callbacks != null) {
                                    callbacks.onDeleteItem(cur, new Runnable() {
                                        @Override
                                        public void run() {
                                            activity.runOnUiThread(new Runnable() {
                                                @Override
                                                public void run() {
                                                    items.remove(currentIndex);
                                                    if (items.isEmpty()) {
                                                        dialog.dismiss();
                                                    } else {
                                                        currentIndex = Math.min(currentIndex, items.size() - 1);
                                                        viewPager.setAdapter(new MediaPagerAdapter());
                                                        viewPager.setCurrentItem(currentIndex, false);
                                                        updateBarInfo(currentIndex);
                                                    }
                                                }
                                            });
                                        }
                                    });
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        }));

        return bar;
    }

    private LinearLayout createBottomAction(int iconRes, String title, View.OnClickListener l) {
        ImageView iv = new ImageView(activity);
        iv.setImageResource(iconRes);
        iv.setColorFilter(Color.WHITE);
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        return createActionBox(iv, title, l);
    }

    private LinearLayout createActionBox(ImageView iv, String title, View.OnClickListener l) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(14), dp(4), dp(14), dp(4));

        TextView tv = new TextView(activity);
        tv.setText(title);
        tv.setTextColor(Color.parseColor("#B0BEC5"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);

        box.addView(iv, new LinearLayout.LayoutParams(dp(44), dp(44)));
        box.addView(tv);
        box.setOnClickListener(l);
        return box;
    }

    private void updateBarInfo(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        MainActivity.GalleryItem cur = items.get(pos);

        tvTitle.setText(cur.name);
        tvSub.setText((pos + 1) + " / " + items.size() + (cur.isLocalOnly() ? " · 仅本地" : " · Google Drive 已归档"));

        boolean isFav = callbacks != null && callbacks.isFavorite(cur);
        updateFavoriteIcon(isFav);

        if (cur.isLocalOnly()) {
            ivActionCloud.setImageResource(R.drawable.ic_m3_cloud_upload);
        } else {
            ivActionCloud.setImageResource(R.drawable.ic_m3_download);
        }
    }

    private void updateFavoriteIcon(boolean isFav) {
        if (ivFavorite != null) {
            ivFavorite.setImageResource(isFav ? R.drawable.ic_m3_star_filled : R.drawable.ic_m3_star);
            ivFavorite.setColorFilter(isFav ? Color.parseColor("#FDD835") : Color.WHITE);
        }
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
        bottomBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
    }

    private void shareCurrentItem(MainActivity.GalleryItem item) {
        try {
            if (item.uri != null) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType(item.isVideo ? "video/*" : "image/*");
                intent.putExtra(Intent.EXTRA_STREAM, item.uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(intent, "分享"));
            } else if (!TextUtils.isEmpty(item.cloudUrl)) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_TEXT, item.name + "\n" + item.cloudUrl);
                activity.startActivity(Intent.createChooser(intent, "分享链接"));
            }
        } catch (Exception e) {
            Toast.makeText(activity, "分享失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Google Photos & M3 Expressive Dialog for EXIF & Cloud Metadata (Matching Screenshot 1).
     */
    private void showExifBottomSheet(MainActivity.GalleryItem item) {
        final M3Colors m3 = new M3Colors(activity);
        BottomSheetDialog bs = new BottomSheetDialog(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(makeRoundRect(m3.surfaceContainerLow, dp(28))); // 28dp Extra-Large Rounded Corners
        root.setPadding(dp(22), dp(14), dp(22), dp(28));

        // Drag handle
        View handle = new View(activity);
        handle.setBackground(makeRoundRect(m3.outline, dp(2)));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(36), dp(4));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.bottomMargin = dp(16);
        root.addView(handle, hlp);

        TextView tvH = new TextView(activity);
        tvH.setText(item.name);
        tvH.setTextColor(m3.onSurface);
        tvH.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        tvH.setTypeface(null, Typeface.BOLD);
        root.addView(tvH);

        TextView tvSub = new TextView(activity);
        tvSub.setText("参数详情 · Google Drive 云端已归档");
        tvSub.setTextColor(m3.outline);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        tvSub.setPadding(0, dp(4), 0, dp(14));
        root.addView(tvSub);

        // Fetch EXIF
        String camera = "未知相机";
        String params = "";
        String resolution = "";
        if (item.uri != null) {
            try (InputStream is = activity.getContentResolver().openInputStream(item.uri)) {
                if (is != null) {
                    ExifInterface exif = new ExifInterface(is);
                    String make = exif.getAttribute(ExifInterface.TAG_MAKE);
                    String model = exif.getAttribute(ExifInterface.TAG_MODEL);
                    if (model != null) camera = (make != null ? make + " " : "") + model;

                    String f = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
                    String exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
                    String focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH);
                    String iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
                    StringBuilder pb = new StringBuilder();
                    if (f != null) pb.append("f/").append(f).append("  ");
                    if (exp != null) {
                        try {
                            double ev = Double.parseDouble(exp);
                            if (ev < 1.0 && ev > 0) pb.append("1/").append((int) Math.round(1.0 / ev)).append("s  ");
                            else pb.append(exp).append("s  ");
                        } catch (Exception ignored) {
                            pb.append(exp).append("  ");
                        }
                    }
                    if (focal != null) pb.append(focal).append("mm  ");
                    if (iso != null) pb.append("ISO").append(iso);
                    params = pb.toString().trim();

                    int w = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0);
                    int h = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0);
                    if (w > 0 && h > 0) {
                        double mp = (w * h) / 1000000.0;
                        resolution = w + " × " + h + String.format(Locale.US, " (%.1f MP)", mp);
                    }
                }
            } catch (Exception ignored) {}
        }

        // Nested 16dp rounded content container (Matching Screenshot 1 inner box!)
        LinearLayout innerBox = new LinearLayout(activity);
        innerBox.setOrientation(LinearLayout.VERTICAL);
        innerBox.setBackground(makeRoundRect(m3.surfaceContainerLowest, dp(16)));
        innerBox.setPadding(dp(16), dp(12), dp(16), dp(12));

        innerBox.addView(makeInfoRow(m3, "存储类型", item.isLocalOnly() ? "仅本地相册" : "Google Drive 云端已归档"));
        if (item.dateTaken > 0) {
            String dateStr = new SimpleDateFormat("yyyy年M月d日 HH:mm:ss", Locale.getDefault()).format(new Date(item.dateTaken));
            innerBox.addView(makeInfoRow(m3, "拍摄时间", dateStr));
        }
        if (!camera.equals("未知相机")) innerBox.addView(makeInfoRow(m3, "拍摄设备", camera));
        if (!params.isEmpty()) innerBox.addView(makeInfoRow(m3, "曝光参数", params));
        if (!resolution.isEmpty()) innerBox.addView(makeInfoRow(m3, "画面尺寸", resolution));
        if (item.size > 0) innerBox.addView(makeInfoRow(m3, "文件大小", formatSize(item.size)));
        if (!TextUtils.isEmpty(item.cloudUrl)) {
            innerBox.addView(makeInfoRow(m3, "云端路径", item.cloudUrl));
        }

        root.addView(innerBox);

        // Copy download link row
        if (!TextUtils.isEmpty(item.cloudUrl)) {
            LinearLayout copyRow = new LinearLayout(activity);
            copyRow.setOrientation(LinearLayout.HORIZONTAL);
            copyRow.setGravity(Gravity.CENTER_VERTICAL);
            copyRow.setPadding(dp(4), dp(14), dp(4), dp(4));

            ImageView ivCopy = new ImageView(activity);
            ivCopy.setImageResource(R.drawable.ic_m3_share);
            ivCopy.setColorFilter(m3.primary);
            copyRow.addView(ivCopy, new LinearLayout.LayoutParams(dp(20), dp(20)));

            TextView tvCopy = new TextView(activity);
            tvCopy.setText(" 复制下载链接");
            tvCopy.setTextColor(m3.primary);
            tvCopy.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            tvCopy.setTypeface(null, Typeface.BOLD);
            copyRow.addView(tvCopy);

            copyRow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("URL", item.cloudUrl));
                        Toast.makeText(activity, "已复制下载链接", Toast.LENGTH_SHORT).show();
                    }
                }
            });
            root.addView(copyRow);
        }

        // Bottom Action Buttons Row (Matching Screenshot 1: "关闭" + "开始下载" Pill Button)
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        btnRow.setPadding(0, dp(18), 0, 0);

        TextView btnClose = new TextView(activity);
        btnClose.setText("关闭");
        btnClose.setTextColor(m3.onSurfaceVariant);
        btnClose.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setPadding(dp(18), dp(10), dp(18), dp(10));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bs.dismiss();
            }
        });
        btnRow.addView(btnClose);

        // 24dp Full Capsule Pill Button filled with Monet primary (Exact match to Screenshot 1!)
        TextView btnDownload = new TextView(activity);
        btnDownload.setText("⬇ 开始下载");
        btnDownload.setTextColor(m3.onPrimary);
        btnDownload.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        btnDownload.setTypeface(null, Typeface.BOLD);
        btnDownload.setBackground(makeRoundRect(m3.primary, dp(24)));
        btnDownload.setPadding(dp(22), dp(11), dp(22), dp(11));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = dp(12);
        btnDownload.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (callbacks != null) callbacks.onDownloadItem(item);
                Toast.makeText(activity, "正在下载原图...", Toast.LENGTH_SHORT).show();
                bs.dismiss();
            }
        });
        btnRow.addView(btnDownload, dlp);

        root.addView(btnRow);

        bs.setContentView(root);
        bs.show();
    }

    private LinearLayout makeInfoRow(M3Colors m3, String label, String value) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        TextView tvL = new TextView(activity);
        tvL.setText(label);
        tvL.setTextColor(m3.outline);
        tvL.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);

        TextView tvV = new TextView(activity);
        tvV.setText(value);
        tvV.setTextColor(m3.onSurface);
        tvV.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        tvV.setTypeface(null, Typeface.BOLD);

        row.addView(tvL);
        row.addView(tvV);
        return row;
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    private GradientDrawable makeGradient(int startColor, int endColor) {
        GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{startColor, endColor});
        return gd;
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

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
    }

    /**
     * ViewPager2 Adapter combining PhotoView & VideoView with Glide loading.
     */
    private class MediaPagerAdapter extends RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder> {

        @NonNull
        @Override
        public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            FrameLayout frame = new FrameLayout(parent.getContext());
            frame.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            frame.setBackgroundColor(Color.BLACK);
            return new MediaViewHolder(frame);
        }

        @Override
        public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class MediaViewHolder extends RecyclerView.ViewHolder {
            final FrameLayout root;
            PhotoView photoView;
            VideoView videoView;
            ProgressBar progress;
            ImageView ivPlayBtn;
            SeekBar seekBar;
            TextView tvTime;
            LinearLayout videoControls;
            final Handler timeHandler = new Handler(Looper.getMainLooper());
            Runnable timeUpdater;

            MediaViewHolder(FrameLayout root) {
                super(root);
                this.root = root;
            }

            void bind(final MainActivity.GalleryItem item) {
                root.removeAllViews();
                if (timeUpdater != null) timeHandler.removeCallbacks(timeUpdater);

                progress = new ProgressBar(activity);
                FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER);
                root.addView(progress, plp);

                if (!item.isVideo) {
                    // PhotoView for image
                    photoView = new PhotoView(activity);
                    photoView.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                    photoView.setMaximumScale(5.0f);
                    photoView.setMediumScale(2.5f);
                    photoView.setMinimumScale(1.0f);
                    photoView.setOnPhotoTapListener((view, x, y) -> toggleBars());
                    photoView.setOnViewTapListener((view, x, y) -> toggleBars());

                    root.addView(photoView);

                    Object model = item.uri != null ? item.uri : item.cloudUrl;
                    Glide.with(activity)
                            .load(model)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .into(photoView);

                    progress.setVisibility(View.GONE);
                } else {
                    // Video View
                    videoView = new VideoView(activity);
                    FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
                    root.addView(videoView, vlp);

                    ivPlayBtn = new ImageView(activity);
                    ivPlayBtn.setImageResource(R.drawable.ic_m3_play);
                    ivPlayBtn.setColorFilter(Color.WHITE);
                    ivPlayBtn.setBackground(makeRoundRect("#80000000", dp(36)));
                    ivPlayBtn.setPadding(dp(18), dp(18), dp(18), dp(18));
                    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER);
                    root.addView(ivPlayBtn, blp);

                    // Video Seek bar overlay
                    videoControls = new LinearLayout(activity);
                    videoControls.setOrientation(LinearLayout.HORIZONTAL);
                    videoControls.setGravity(Gravity.CENTER_VERTICAL);
                    videoControls.setPadding(dp(16), dp(8), dp(16), dp(8));
                    videoControls.setBackground(makeRoundRect("#80000000", dp(20)));

                    seekBar = new SeekBar(activity);
                    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                    videoControls.addView(seekBar, slp);

                    tvTime = new TextView(activity);
                    tvTime.setTextColor(Color.WHITE);
                    tvTime.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
                    tvTime.setText("00:00");
                    videoControls.addView(tvTime);

                    FrameLayout.LayoutParams vclp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    vclp.gravity = Gravity.BOTTOM;
                    vclp.bottomMargin = dp(88);
                    vclp.leftMargin = dp(20);
                    vclp.rightMargin = dp(20);
                    root.addView(videoControls, vclp);

                    Uri videoUri = item.uri != null ? item.uri : Uri.parse(item.cloudUrl);
                    videoView.setVideoURI(videoUri);
                    progress.setVisibility(View.GONE);

                    videoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                        @Override
                        public void onPrepared(MediaPlayer mp) {
                            seekBar.setMax(videoView.getDuration());
                        }
                    });

                    videoView.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                        @Override
                        public void onCompletion(MediaPlayer mp) {
                            ivPlayBtn.setVisibility(View.VISIBLE);
                        }
                    });

                    ivPlayBtn.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (!videoView.isPlaying()) {
                                videoView.start();
                                ivPlayBtn.setVisibility(View.GONE);
                                startTimeUpdater();
                            }
                        }
                    });

                    root.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (videoView.isPlaying()) {
                                videoView.pause();
                                ivPlayBtn.setVisibility(View.VISIBLE);
                            } else {
                                toggleBars();
                            }
                        }
                    });

                    seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                        @Override
                        public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                            if (fromUser) {
                                videoView.seekTo(p);
                                tvTime.setText(formatDuration(p));
                            }
                        }
                        @Override public void onStartTrackingTouch(SeekBar sb) {}
                        @Override public void onStopTrackingTouch(SeekBar sb) {}
                    });
                }
            }

            void startTimeUpdater() {
                timeUpdater = new Runnable() {
                    @Override
                    public void run() {
                        if (videoView != null && videoView.isPlaying()) {
                            int cur = videoView.getCurrentPosition();
                            seekBar.setProgress(cur);
                            tvTime.setText(formatDuration(cur));
                            timeHandler.postDelayed(this, 500);
                        }
                    }
                };
                timeHandler.post(timeUpdater);
            }

            String formatDuration(int ms) {
                int sec = ms / 1000;
                int m = sec / 60;
                int s = sec % 60;
                return String.format(Locale.US, "%02d:%02d", m, s);
            }
        }
    }
}
