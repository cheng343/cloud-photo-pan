package top.czyxh.pan;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
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
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Flagship-style Fullscreen Photo & Video Viewer Dialog:
 * - Real-time finger-following horizontal swipe paging between photos
 * - Two-finger pinch zoom (1x-5x) & pan via ZoomableImageView
 * - Inline VideoView playback with custom SeekBar & time controls
 * - Immersive single-tap toolbar hide/show
 * - Native EXIF & metadata details dialog
 */
public class PhotoViewerDialog {

    public interface ViewerCallbacks {
        Bitmap getCachedThumb(String key);
        void loadHighResBitmap(MainActivity.GalleryItem item, BitmapCallback cb);
        void onUploadItem(MainActivity.GalleryItem item);
        void onDownloadItem(MainActivity.GalleryItem item);
        void onDeleteItem(MainActivity.GalleryItem item);
        boolean isFavorite(String key);
        void toggleFavorite(String key);
    }

    public interface BitmapCallback {
        void onLoaded(Bitmap bmp);
    }

    private final MainActivity activity;
    private final List<MainActivity.GalleryItem> items;
    private final ViewerCallbacks callbacks;
    private int currentIndex;
    private boolean barsVisible = true;

    private Dialog dialog;
    private SwipeStageLayout stageLayout;
    private LinearLayout topBar;
    private LinearLayout bottomBar;
    private LinearLayout videoControlsBar;
    private TextView tvTitle, tvSubtitle;
    private ImageView ivFavIcon, ivCloudActionIcon;
    private TextView tvFavLabel, tvCloudActionLabel;

    private ZoomableImageView prevImageView, currImageView, nextImageView;
    private VideoView activeVideoView;
    private ProgressBar loadingSpinner;
    private SeekBar videoSeekBar;
    private TextView tvVideoTime, btnVideoPlayPause;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable videoProgressRunnable;
    private boolean isVideoPlaying = false;

    public PhotoViewerDialog(MainActivity activity, List<MainActivity.GalleryItem> items, int startIndex, ViewerCallbacks callbacks) {
        this.activity = activity;
        this.items = items;
        this.currentIndex = Math.max(0, Math.min(startIndex, items.size() - 1));
        this.callbacks = callbacks;
    }

    public void show() {
        if (items.isEmpty()) return;
        dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        FrameLayout root = new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);

        // 1. Finger-following 3-page swipe stage (Prev, Curr, Next)
        stageLayout = new SwipeStageLayout(activity);
        prevImageView = new ZoomableImageView(activity);
        currImageView = new ZoomableImageView(activity);
        nextImageView = new ZoomableImageView(activity);

        ZoomableImageView.OnTapListener tapToggle = new ZoomableImageView.OnTapListener() {
            @Override
            public void onSingleTap() {
                toggleBars();
            }
        };
        prevImageView.setOnTapListener(tapToggle);
        currImageView.setOnTapListener(tapToggle);
        nextImageView.setOnTapListener(tapToggle);

        stageLayout.addView(prevImageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stageLayout.addView(currImageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        stageLayout.addView(nextImageView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.addView(stageLayout, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        loadingSpinner = new ProgressBar(activity);
        FrameLayout.LayoutParams spinLp = new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER);
        loadingSpinner.setVisibility(View.GONE);
        root.addView(loadingSpinner, spinLp);

        // 2. Top Overlay Bar
        topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(Color.parseColor("#CC0B0F17"));
        topBar.setPadding(dp(12), dp(14), dp(14), dp(12));

        ImageView btnBack = new ImageView(activity);
        btnBack.setImageDrawable(VectorIcons.create(VectorIcons.ICON_BACK, Color.WHITE, dp(24)));
        btnBack.setPadding(dp(6), dp(6), dp(10), dp(6));
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopVideoIfAny();
                dialog.dismiss();
            }
        });
        topBar.addView(btnBack, new LinearLayout.LayoutParams(dp(40), dp(36)));

        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        tvTitle = new TextView(activity);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        tvSubtitle = new TextView(activity);
        tvSubtitle.setTextColor(Color.parseColor("#CBD5E1"));
        tvSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvSubtitle.setSingleLine(true);

        titleCol.addView(tvTitle);
        titleCol.addView(tvSubtitle);
        topBar.addView(titleCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        root.addView(topBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // 3. Bottom Overlay Container (Video Scrubbing Bar + Action Icons Bar)
        LinearLayout bottomOverlay = new LinearLayout(activity);
        bottomOverlay.setOrientation(LinearLayout.VERTICAL);
        bottomOverlay.setBackgroundColor(Color.parseColor("#CC0B0F17"));

        // Video Playback Scrubbing Bar (shown only for videos)
        videoControlsBar = new LinearLayout(activity);
        videoControlsBar.setOrientation(LinearLayout.HORIZONTAL);
        videoControlsBar.setGravity(Gravity.CENTER_VERTICAL);
        videoControlsBar.setPadding(dp(14), dp(10), dp(14), dp(4));
        videoControlsBar.setVisibility(View.GONE);

        btnVideoPlayPause = new TextView(activity);
        btnVideoPlayPause.setText("⏸");
        btnVideoPlayPause.setTextColor(Color.WHITE);
        btnVideoPlayPause.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        btnVideoPlayPause.setPadding(dp(6), dp(2), dp(10), dp(2));
        btnVideoPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activeVideoView != null) {
                    if (activeVideoView.isPlaying()) {
                        activeVideoView.pause();
                        btnVideoPlayPause.setText("▶");
                    } else {
                        activeVideoView.start();
                        btnVideoPlayPause.setText("⏸");
                    }
                }
            }
        });
        videoControlsBar.addView(btnVideoPlayPause);

        videoSeekBar = new SeekBar(activity);
        videoSeekBar.setMax(1000);
        videoSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && activeVideoView != null && activeVideoView.getDuration() > 0) {
                    int pos = (int) ((progress / 1000f) * activeVideoView.getDuration());
                    activeVideoView.seekTo(pos);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        videoControlsBar.addView(videoSeekBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        tvVideoTime = new TextView(activity);
        tvVideoTime.setText("00:00 / 00:00");
        tvVideoTime.setTextColor(Color.parseColor("#E2E8F0"));
        tvVideoTime.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tvVideoTime.setPadding(dp(8), 0, 0, 0);
        videoControlsBar.addView(tvVideoTime);

        bottomOverlay.addView(videoControlsBar);

        // 5-Action Bottom Bar (Share, Favorite, Backup/Download, Info, Delete)
        bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(8), dp(10), dp(8), dp(14));

        LinearLayout actShare = createBottomActionItem(VectorIcons.ICON_SHARE, "分享");
        actShare.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareCurrentItem();
            }
        });

        LinearLayout actFav = createBottomActionItem(VectorIcons.ICON_HEART_OUTLINE, "收藏");
        ivFavIcon = (ImageView) actFav.getChildAt(0);
        tvFavLabel = (TextView) actFav.getChildAt(1);
        actFav.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.GalleryItem item = items.get(currentIndex);
                callbacks.toggleFavorite(item.getUniqueKey());
                updateBottomBarState(item);
            }
        });

        LinearLayout actCloud = createBottomActionItem(VectorIcons.ICON_CLOUD_UPLOAD, "备份");
        ivCloudActionIcon = (ImageView) actCloud.getChildAt(0);
        tvCloudActionLabel = (TextView) actCloud.getChildAt(1);
        actCloud.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.GalleryItem item = items.get(currentIndex);
                if (item.isOnCloud) {
                    callbacks.onDownloadItem(item);
                } else {
                    callbacks.onUploadItem(item);
                }
            }
        });

        LinearLayout actInfo = createBottomActionItem(VectorIcons.ICON_INFO, "详情");
        actInfo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showExifInfoDialog(items.get(currentIndex));
            }
        });

        LinearLayout actDelete = createBottomActionItem(VectorIcons.ICON_DELETE, "删除");
        actDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MainActivity.GalleryItem item = items.get(currentIndex);
                stopVideoIfAny();
                callbacks.onDeleteItem(item);
                if (items.isEmpty()) {
                    dialog.dismiss();
                } else {
                    if (currentIndex >= items.size()) currentIndex = items.size() - 1;
                    bindPageData();
                }
            }
        });

        bottomBar.addView(actShare, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(actFav, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(actCloud, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(actInfo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottomBar.addView(actDelete, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bottomOverlay.addView(bottomBar);
        root.addView(bottomOverlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        dialog.setContentView(root);
        dialog.show();
        bindPageData();
    }

    private LinearLayout createBottomActionItem(int iconType, String label) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        ImageView iv = new ImageView(activity);
        iv.setImageDrawable(VectorIcons.create(iconType, Color.WHITE, dp(22)));
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(Color.parseColor("#E2E8F0"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        tv.setPadding(0, dp(4), 0, 0);
        col.addView(iv, new LinearLayout.LayoutParams(dp(24), dp(24)));
        col.addView(tv);
        return col;
    }

    private void toggleBars() {
        barsVisible = !barsVisible;
        float alpha = barsVisible ? 1.0f : 0.0f;
        topBar.animate().alpha(alpha).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                topBar.setVisibility(barsVisible ? View.VISIBLE : View.GONE);
            }
        }).start();
        if (barsVisible) topBar.setVisibility(View.VISIBLE);

        View botParent = (View) bottomBar.getParent();
        botParent.animate().alpha(alpha).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                ((View) bottomBar.getParent()).setVisibility(barsVisible ? View.VISIBLE : View.GONE);
            }
        }).start();
        if (barsVisible) botParent.setVisibility(View.VISIBLE);
    }

    private void updateBottomBarState(MainActivity.GalleryItem item) {
        boolean fav = callbacks.isFavorite(item.getUniqueKey());
        ivFavIcon.setImageDrawable(VectorIcons.create(
                fav ? VectorIcons.ICON_HEART_FILLED : VectorIcons.ICON_HEART_OUTLINE,
                fav ? Color.parseColor("#F43F5E") : Color.WHITE,
                dp(22)
        ));
        tvFavLabel.setText(fav ? "已收藏" : "收藏");

        if (item.isOnCloud) {
            ivCloudActionIcon.setImageDrawable(VectorIcons.create(VectorIcons.ICON_DOWNLOAD, Color.WHITE, dp(22)));
            tvCloudActionLabel.setText("下载原片");
        } else {
            ivCloudActionIcon.setImageDrawable(VectorIcons.create(VectorIcons.ICON_CLOUD_UPLOAD, Color.parseColor("#38BDF8"), dp(22)));
            tvCloudActionLabel.setText("备份上云");
        }
    }

    private void bindPageData() {
        stopVideoIfAny();
        if (items.isEmpty()) return;

        final MainActivity.GalleryItem curr = items.get(currentIndex);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault());
        tvTitle.setText(sdf.format(new Date(curr.timestampMs)));
        String statusStr = curr.isOnCloud ? (curr.localUri != null ? "已备份至云端" : "云端原片") : "仅在手机本地";
        tvSubtitle.setText((currentIndex + 1) + " / " + items.size() + "  ·  " + statusStr);

        updateBottomBarState(curr);

        // Reset positions
        int w = stageLayout.getWidth() > 0 ? stageLayout.getWidth() : activity.getResources().getDisplayMetrics().widthPixels;
        prevImageView.resetZoom();
        currImageView.resetZoom();
        nextImageView.resetZoom();
        prevImageView.setTranslationX(-w);
        currImageView.setTranslationX(0);
        nextImageView.setTranslationX(w);

        // Bind neighbor thumbnails for instant finger-following preview
        if (currentIndex > 0) {
            MainActivity.GalleryItem prev = items.get(currentIndex - 1);
            prevImageView.setImageBitmap(callbacks.getCachedThumb(prev.getUniqueKey()));
        } else {
            prevImageView.setImageBitmap(null);
        }

        if (currentIndex < items.size() - 1) {
            MainActivity.GalleryItem next = items.get(currentIndex + 1);
            nextImageView.setImageBitmap(callbacks.getCachedThumb(next.getUniqueKey()));
        } else {
            nextImageView.setImageBitmap(null);
        }

        // Bind current item
        currImageView.setImageBitmap(callbacks.getCachedThumb(curr.getUniqueKey()));

        if (curr.isVideo) {
            videoControlsBar.setVisibility(View.VISIBLE);
            startInlineVideo(curr);
        } else {
            videoControlsBar.setVisibility(View.GONE);
            loadingSpinner.setVisibility(View.VISIBLE);
            final int boundIdx = currentIndex;
            callbacks.loadHighResBitmap(curr, new BitmapCallback() {
                @Override
                public void onLoaded(Bitmap bmp) {
                    if (boundIdx == currentIndex) {
                        loadingSpinner.setVisibility(View.GONE);
                        if (bmp != null) {
                            currImageView.setImageBitmap(bmp);
                        }
                    }
                }
            });
        }
    }

    private void startInlineVideo(MainActivity.GalleryItem item) {
        Uri playUri = item.localUri != null ? item.localUri : (item.fullUrl != null ? Uri.parse(item.fullUrl) : null);
        if (playUri == null) return;

        activeVideoView = new VideoView(activity);
        FrameLayout.LayoutParams vLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        stageLayout.addView(activeVideoView, vLp);

        activeVideoView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleBars();
            }
        });

        loadingSpinner.setVisibility(View.VISIBLE);
        activeVideoView.setVideoURI(playUri);
        activeVideoView.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                loadingSpinner.setVisibility(View.GONE);
                currImageView.setVisibility(View.INVISIBLE);
                mp.setLooping(true);
                activeVideoView.start();
                isVideoPlaying = true;
                btnVideoPlayPause.setText("⏸");
                startVideoProgressUpdater();
            }
        });
        activeVideoView.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                loadingSpinner.setVisibility(View.GONE);
                Toast.makeText(activity, "视频加载中或需外部解码器播放", Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    private void startVideoProgressUpdater() {
        if (videoProgressRunnable != null) handler.removeCallbacks(videoProgressRunnable);
        videoProgressRunnable = new Runnable() {
            @Override
            public void run() {
                if (activeVideoView != null && isVideoPlaying) {
                    try {
                        int cur = activeVideoView.getCurrentPosition();
                        int dur = activeVideoView.getDuration();
                        if (dur > 0) {
                            videoSeekBar.setProgress((int) ((cur * 1000L) / dur));
                            tvVideoTime.setText(formatMs(cur) + " / " + formatMs(dur));
                        }
                    } catch (Exception ignored) {}
                    handler.postDelayed(this, 300);
                }
            }
        };
        handler.post(videoProgressRunnable);
    }

    private void stopVideoIfAny() {
        isVideoPlaying = false;
        if (videoProgressRunnable != null) {
            handler.removeCallbacks(videoProgressRunnable);
        }
        currImageView.setVisibility(View.VISIBLE);
        if (activeVideoView != null) {
            try {
                activeVideoView.stopPlayback();
                stageLayout.removeView(activeVideoView);
            } catch (Exception ignored) {}
            activeVideoView = null;
        }
    }

    private String formatMs(int ms) {
        int totalSec = Math.max(0, ms / 1000);
        int m = totalSec / 60;
        int s = totalSec % 60;
        return String.format(Locale.US, "%02d:%02d", m, s);
    }

    private void shareCurrentItem() {
        MainActivity.GalleryItem item = items.get(currentIndex);
        if (item.localUri != null) {
            try {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType(item.isVideo ? "video/*" : "image/*");
                share.putExtra(Intent.EXTRA_STREAM, item.localUri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(Intent.createChooser(share, "分享原文件"));
                return;
            } catch (Exception ignored) {}
        }
        if (item.downloadUrl != null && !item.downloadUrl.isEmpty()) {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("云相册直链", item.downloadUrl));
                Toast.makeText(activity, "已复制国内免梯高速直链，可直接粘贴分享给好友", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void showExifInfoDialog(MainActivity.GalleryItem item) {
        StringBuilder info = new StringBuilder();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        info.append("文件名称：").append(item.name).append("\n\n");
        info.append("拍摄时间：").append(sdf.format(new Date(item.timestampMs))).append("\n\n");
        info.append("文件大小：").append(String.format(Locale.US, "%.2f MB (%d 字节)", item.size / 1048576.0, item.size)).append("\n\n");
        info.append("所属相册：").append(item.bucket != null ? item.bucket : "云端相册").append("\n\n");
        info.append("备份状态：").append(item.isOnCloud ? "已安全备份至 Google Drive" : "仅保存在手机本地").append("\n\n");

        if (item.localUri != null && !item.isVideo && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                InputStream in = activity.getContentResolver().openInputStream(item.localUri);
                if (in != null) {
                    ExifInterface exif = new ExifInterface(in);
                    String w = exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH);
                    String h = exif.getAttribute(ExifInterface.TAG_IMAGE_LENGTH);
                    String make = exif.getAttribute(ExifInterface.TAG_MAKE);
                    String model = exif.getAttribute(ExifInterface.TAG_MODEL);
                    String fnum = exif.getAttribute(ExifInterface.TAG_F_NUMBER);
                    String iso = exif.getAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS);
                    String exp = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME);
                    if (w != null && h != null && !"0".equals(w)) {
                        info.append("图像尺寸：").append(w).append(" × ").append(h).append(" px\n\n");
                    }
                    if (make != null || model != null) {
                        info.append("拍摄设备：").append(make != null ? make + " " : "").append(model != null ? model : "").append("\n\n");
                    }
                    if (fnum != null || iso != null) {
                        info.append("镜头参数：");
                        if (fnum != null) info.append("f/").append(fnum).append("  ");
                        if (exp != null) info.append(exp).append("s  ");
                        if (iso != null) info.append("ISO ").append(iso);
                        info.append("\n\n");
                    }
                    in.close();
                }
            } catch (Exception ignored) {}
        }

        if (item.cloudPath != null) {
            info.append("云端路径：").append(item.cloudPath);
        }

        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("照片详细信息")
                .setMessage(info.toString().trim())
                .setPositiveButton("确定", null)
                .show();
    }

    /**
     * Custom FrameLayout that intercepts horizontal swipes when ZoomableImageView is not panning,
     * moving prev/curr/next views 1:1 with the user's finger and animating snap transitions.
     */
    private class SwipeStageLayout extends FrameLayout {
        private float downX, downY, lastX;
        private boolean isSwiping = false;
        private final int touchSlop;
        private VelocityTracker velocityTracker;

        public SwipeStageLayout(Context context) {
            super(context);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            int action = ev.getActionMasked();
            if (ev.getPointerCount() > 1) {
                isSwiping = false;
                return false;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    downX = lastX = ev.getX();
                    downY = ev.getY();
                    isSwiping = false;
                    if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
                    else velocityTracker.clear();
                    velocityTracker.addMovement(ev);
                    break;

                case MotionEvent.ACTION_MOVE:
                    float dx = ev.getX() - downX;
                    float dy = ev.getY() - downY;
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.2f) {
                        // Check if current ZoomableImageView can still pan in this direction
                        if (!currImageView.canPanHorizontally((int) -dx)) {
                            isSwiping = true;
                            lastX = ev.getX();
                            return true;
                        }
                    }
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (velocityTracker != null) velocityTracker.addMovement(ev);
            int w = getWidth();
            if (w <= 0) return true;

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_MOVE:
                    float offset = ev.getX() - downX;
                    // Add resistance at first/last item
                    if ((currentIndex == 0 && offset > 0) || (currentIndex == items.size() - 1 && offset < 0)) {
                        offset *= 0.32f;
                    }
                    prevImageView.setTranslationX(-w + offset);
                    currImageView.setTranslationX(offset);
                    nextImageView.setTranslationX(w + offset);
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float totalDx = ev.getX() - downX;
                    float vx = 0;
                    if (velocityTracker != null) {
                        velocityTracker.computeCurrentVelocity(1000);
                        vx = velocityTracker.getXVelocity();
                    }
                    int targetDelta = 0;
                    if ((totalDx < -w * 0.22f || vx < -650) && currentIndex < items.size() - 1) {
                        targetDelta = 1;
                    } else if ((totalDx > w * 0.22f || vx > 650) && currentIndex > 0) {
                        targetDelta = -1;
                    }
                    animateToPage(targetDelta, totalDx, w);
                    isSwiping = false;
                    break;
            }
            return true;
        }

        private void animateToPage(final int deltaIdx, float startOffset, final int width) {
            float endOffset = deltaIdx == 1 ? -width : (deltaIdx == -1 ? width : 0);
            ValueAnimator anim = ValueAnimator.ofFloat(startOffset, endOffset);
            anim.setDuration(210);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    float val = (Float) animation.getAnimatedValue();
                    prevImageView.setTranslationX(-width + val);
                    currImageView.setTranslationX(val);
                    nextImageView.setTranslationX(width + val);
                }
            });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (deltaIdx != 0) {
                        currentIndex += deltaIdx;
                        bindPageData();
                    }
                }
            });
            anim.start();
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, activity.getResources().getDisplayMetrics());
    }
}
