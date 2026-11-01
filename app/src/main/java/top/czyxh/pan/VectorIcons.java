package top.czyxh.pan;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/**
 * Clean Material 3 / ColorOS-style vector icons drawn natively via Canvas.
 * Eliminates ugly emoji icons throughout the gallery UI.
 */
public class VectorIcons {
    public static final int ICON_PHOTOS = 1;
    public static final int ICON_ALBUMS = 2;
    public static final int ICON_SEARCH_TOOLS = 3;
    public static final int ICON_DRIVE = 4;
    public static final int ICON_CLOUD_DONE = 5;
    public static final int ICON_CLOUD_UPLOAD = 6;
    public static final int ICON_CLOUD_SYNC = 7;
    public static final int ICON_CHECK_CIRCLE = 8;
    public static final int ICON_UNCHECK_CIRCLE = 9;
    public static final int ICON_SHARE = 10;
    public static final int ICON_HEART_OUTLINE = 11;
    public static final int ICON_HEART_FILLED = 12;
    public static final int ICON_INFO = 13;
    public static final int ICON_DELETE = 14;
    public static final int ICON_BACK = 15;
    public static final int ICON_PLUS = 16;
    public static final int ICON_GRID = 17;
    public static final int ICON_DOWNLOAD = 18;
    public static final int ICON_PLAY = 19;

    public static Drawable create(final int type, final int color, final int sizePx) {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path path = new Path();
            private final RectF rf = new RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                float w = b.width() > 0 ? b.width() : sizePx;
                float h = b.height() > 0 ? b.height() : sizePx;
                float cx = b.left + w / 2f;
                float cy = b.top + h / 2f;
                float u = Math.min(w, h) / 24f;

                paint.reset();
                paint.setAntiAlias(true);
                paint.setColor(color);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.0f * u);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                path.reset();

                switch (type) {
                    case ICON_PHOTOS: {
                        rf.set(cx - 8.5f * u, cy - 8.5f * u, cx + 8.5f * u, cy + 8.5f * u);
                        canvas.drawRoundRect(rf, 2.5f * u, 2.5f * u, paint);
                        canvas.drawCircle(cx - 3.5f * u, cy - 3.5f * u, 1.6f * u, paint);
                        path.moveTo(cx - 8f * u, cy + 5.5f * u);
                        path.lineTo(cx - 2.5f * u, cy + 0.5f * u);
                        path.lineTo(cx + 2f * u, cy + 4.5f * u);
                        path.lineTo(cx + 5f * u, cy + 1.5f * u);
                        path.lineTo(cx + 8.5f * u, cy + 5.5f * u);
                        canvas.drawPath(path, paint);
                        break;
                    }
                    case ICON_ALBUMS: {
                        rf.set(cx - 8.5f * u, cy - 6.5f * u, cx + 8.5f * u, cy + 8.5f * u);
                        canvas.drawRoundRect(rf, 2.2f * u, 2.2f * u, paint);
                        canvas.drawLine(cx - 6f * u, cy - 9.2f * u, cx + 6f * u, cy - 9.2f * u, paint);
                        break;
                    }
                    case ICON_SEARCH_TOOLS: {
                        canvas.drawCircle(cx - 1.5f * u, cy - 1.5f * u, 6.2f * u, paint);
                        canvas.drawLine(cx + 3.2f * u, cy + 3.2f * u, cx + 8.5f * u, cy + 8.5f * u, paint);
                        break;
                    }
                    case ICON_DRIVE: {
                        path.moveTo(cx - 8.5f * u, cy - 6.5f * u);
                        path.lineTo(cx - 2.5f * u, cy - 6.5f * u);
                        path.lineTo(cx - 0.5f * u, cy - 4f * u);
                        path.lineTo(cx + 8.5f * u, cy - 4f * u);
                        path.lineTo(cx + 8.5f * u, cy + 7.5f * u);
                        path.lineTo(cx - 8.5f * u, cy + 7.5f * u);
                        path.close();
                        canvas.drawPath(path, paint);
                        break;
                    }
                    case ICON_CLOUD_DONE: {
                        drawCloudOutline(canvas, cx, cy, u, paint, path);
                        path.reset();
                        path.moveTo(cx - 3.2f * u, cy + 1.0f * u);
                        path.lineTo(cx - 0.6f * u, cy + 3.5f * u);
                        path.lineTo(cx + 3.8f * u, cy - 1.2f * u);
                        canvas.drawPath(path, paint);
                        break;
                    }
                    case ICON_CLOUD_UPLOAD: {
                        drawCloudOutline(canvas, cx, cy, u, paint, path);
                        canvas.drawLine(cx, cy + 4f * u, cx, cy - 1.5f * u, paint);
                        path.reset();
                        path.moveTo(cx - 2.5f * u, cy + 1f * u);
                        path.lineTo(cx, cy - 1.5f * u);
                        path.lineTo(cx + 2.5f * u, cy + 1f * u);
                        canvas.drawPath(path, paint);
                        break;
                    }
                    case ICON_CLOUD_SYNC: {
                        drawCloudOutline(canvas, cx, cy, u, paint, path);
                        canvas.drawCircle(cx, cy + 1f * u, 1.5f * u, paint);
                        break;
                    }
                    case ICON_CHECK_CIRCLE: {
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy, 9.5f * u, paint);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(0xFFFFFFFF);
                        paint.setStrokeWidth(2.2f * u);
                        path.moveTo(cx - 4.2f * u, cy + 0.2f * u);
                        path.lineTo(cx - 1.0f * u, cy + 3.5f * u);
                        path.lineTo(cx + 4.6f * u, cy - 2.8f * u);
                        canvas.drawPath(path, paint);
                        break;
                    }
                    case ICON_UNCHECK_CIRCLE: {
                        paint.setStyle(Paint.Style.FILL);
                        paint.setColor(0x55000000);
                        canvas.drawCircle(cx, cy, 9.0f * u, paint);
                        paint.setStyle(Paint.Style.STROKE);
                        paint.setColor(color);
                        paint.setStrokeWidth(1.8f * u);
                        canvas.drawCircle(cx, cy, 9.0f * u, paint);
                        break;
                    }
                    case ICON_SHARE: {
                        canvas.drawCircle(cx + 5.5f * u, cy - 6f * u, 2.4f * u, paint);
                        canvas.drawCircle(cx - 5.5f * u, cy, 2.4f * u, paint);
                        canvas.drawCircle(cx + 5.5f * u, cy + 6f * u, 2.4f * u, paint);
                        canvas.drawLine(cx - 3.3f * u, cy - 1.2f * u, cx + 3.3f * u, cy - 4.8f * u, paint);
                        canvas.drawLine(cx - 3.3f * u, cy + 1.2f * u, cx + 3.3f * u, cy + 4.8f * u, paint);
                        break;
                    }
                    case ICON_HEART_OUTLINE:
                    case ICON_HEART_FILLED: {
                        if (type == ICON_HEART_FILLED) paint.setStyle(Paint.Style.FILL);
                        path.moveTo(cx, cy + 7.5f * u);
                        path.cubicTo(cx - 9f * u, cy + 2f * u, cx - 9f * u, cy - 6.5f * u, cx - 4f * u, cy - 6.5f * u);
                        path.cubicTo(cx - 1.5f * u, cy - 6.5f * u, cx, cy - 4f * u, cx, cy - 3.5f * u);
                        path.cubicTo(cx, cy - 4f * u, cx + 1.5f * u, cy - 6.5f * u, cx + 4f * u, cy - 6.5f * u);
                        path.cubicTo(cx + 9f * u, cy - 6.5f * u, cx + 9f * u, cy + 2f * u, cx, cy + 7.5f * u);
                        path.close();
                        canvas.drawPath(path, paint);
                        break;
                    }
                    case ICON_INFO: {
                        canvas.drawCircle(cx, cy, 8.5f * u, paint);
                        canvas.drawLine(cx, cy - 0.5f * u, cx, cy + 4.5f * u, paint);
                        paint.setStyle(Paint.Style.FILL);
                        canvas.drawCircle(cx, cy - 4.2f * u, 1.2f * u, paint);
                        break;
                    }
                    case ICON_DELETE: {
                        canvas.drawLine(cx - 7.5f * u, cy - 5f * u, cx + 7.5f * u, cy - 5f * u, paint);
                        canvas.drawLine(cx - 3f * u, cy - 7.5f * u, cx + 3f * u, cy - 7.5f * u, paint);
                        rf.set(cx - 5.5f * u, cy - 5f * u, cx + 5.5f * u, cy + 8f * u);
                        canvas.drawRoundRect(rf, 1.5f * u, 1.5f * u, paint);
                        canvas.drawLine(cx - 2f * u, cy - 1.5f * u, cx - 2f * u, cy + 4.5f * u, paint);
                        canvas.drawLine(cx + 2f * u, cy - 1.5f * u, cx + 2f * u, cy + 4.5f * u, paint);
                        break;
                    }
                    case ICON_BACK: {
                        path.moveTo(cx + 2.5f * u, cy - 6.5f * u);
                        path.lineTo(cx - 4f * u, cy);
                        path.lineTo(cx + 2.5f * u, cy + 6.5f * u);
                        canvas.drawPath(path, paint);
                        canvas.drawLine(cx - 3.5f * u, cy, cx + 6.5f * u, cy, paint);
                        break;
                    }
                    case ICON_PLUS: {
                        canvas.drawLine(cx - 6.5f * u, cy, cx + 6.5f * u, cy, paint);
                        canvas.drawLine(cx, cy - 6.5f * u, cx, cy + 6.5f * u, paint);
                        break;
                    }
                    case ICON_GRID: {
                        rf.set(cx - 8f * u, cy - 8f * u, cx - 1.5f * u, cy - 1.5f * u);
                        canvas.drawRoundRect(rf, 1.2f * u, 1.2f * u, paint);
                        rf.set(cx + 1.5f * u, cy - 8f * u, cx + 8f * u, cy - 1.5f * u);
                        canvas.drawRoundRect(rf, 1.2f * u, 1.2f * u, paint);
                        rf.set(cx - 8f * u, cy + 1.5f * u, cx - 1.5f * u, cy + 8f * u);
                        canvas.drawRoundRect(rf, 1.2f * u, 1.2f * u, paint);
                        rf.set(cx + 1.5f * u, cy + 1.5f * u, cx + 8f * u, cy + 8f * u);
                        canvas.drawRoundRect(rf, 1.2f * u, 1.2f * u, paint);
                        break;
                    }
                    case ICON_DOWNLOAD: {
                        canvas.drawLine(cx, cy - 6.5f * u, cx, cy + 3f * u, paint);
                        path.moveTo(cx - 4f * u, cy - 0.5f * u);
                        path.lineTo(cx, cy + 3.5f * u);
                        path.lineTo(cx + 4f * u, cy - 0.5f * u);
                        canvas.drawPath(path, paint);
                        canvas.drawLine(cx - 6.5f * u, cy + 7f * u, cx + 6.5f * u, cy + 7f * u, paint);
                        break;
                    }
                    case ICON_PLAY: {
                        paint.setStyle(Paint.Style.FILL);
                        path.moveTo(cx - 3.5f * u, cy - 5.5f * u);
                        path.lineTo(cx + 5.5f * u, cy);
                        path.lineTo(cx - 3.5f * u, cy + 5.5f * u);
                        path.close();
                        canvas.drawPath(path, paint);
                        break;
                    }
                }
            }

            private void drawCloudOutline(Canvas canvas, float cx, float cy, float u, Paint p, Path path) {
                path.reset();
                path.moveTo(cx - 5.5f * u, cy + 5.2f * u);
                path.lineTo(cx + 5.2f * u, cy + 5.2f * u);
                path.cubicTo(cx + 9.2f * u, cy + 5.2f * u, cx + 9.5f * u, cy - 0.8f * u, cx + 5.5f * u, cy - 1.2f * u);
                path.cubicTo(cx + 5.0f * u, cy - 6.2f * u, cx - 2.5f * u, cy - 6.5f * u, cx - 4.2f * u, cy - 2.0f * u);
                path.cubicTo(cx - 9.2f * u, cy - 1.8f * u, cx - 9.2f * u, cy + 5.2f * u, cx - 5.5f * u, cy + 5.2f * u);
                path.close();
                canvas.drawPath(path, p);
            }

            @Override
            public void setAlpha(int alpha) {
                paint.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
                paint.setColorFilter(colorFilter);
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }

            @Override
            public int getIntrinsicWidth() {
                return sizePx;
            }

            @Override
            public int getIntrinsicHeight() {
                return sizePx;
            }
        };
    }
}
