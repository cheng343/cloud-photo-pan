package top.czyxh.pan;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/**
 * Native multi-touch zoomable & pannable ImageView for photo gallery viewer.
 * Supports:
 * - Two-finger pinch zoom (1.0x to 5.0x)
 * - Double-tap focal zoom (1.0x <-> 2.6x)
 * - Single-finger pan when zoomed in, with boundary clamping
 * - Single-tap callback for toggling immersive fullscreen bars
 */
public class ZoomableImageView extends ImageView {
    private final Matrix baseMatrix = new Matrix();
    private final Matrix suppMatrix = new Matrix();
    private final Matrix drawMatrix = new Matrix();
    private final RectF displayRect = new RectF();
    private final float[] matrixValues = new float[9];

    private float minScale = 1.0f;
    private float midScale = 2.6f;
    private float maxScale = 5.0f;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;

    private final PointF lastTouch = new PointF();
    private boolean isDragging = false;
    private boolean isScaling = false;

    public interface OnTapListener {
        void onSingleTap();
    }

    private OnTapListener tapListener;

    public ZoomableImageView(Context context) {
        super(context);
        super.setScaleType(ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                isScaling = true;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                if (Float.isNaN(scaleFactor) || Float.isInfinite(scaleFactor)) return false;
                float curScale = getCurrentScale();
                if ((curScale < maxScale && scaleFactor > 1.0f) || (curScale > minScale * 0.85f && scaleFactor < 1.0f)) {
                    if (curScale * scaleFactor > maxScale) {
                        scaleFactor = maxScale / curScale;
                    } else if (curScale * scaleFactor < minScale) {
                        scaleFactor = minScale / curScale;
                    }
                    suppMatrix.postScale(scaleFactor, scaleFactor, detector.getFocusX(), detector.getFocusY());
                    checkAndDisplayMatrix();
                }
                return true;
            }

            @Override
            public void onScaleEnd(ScaleGestureDetector detector) {
                isScaling = false;
                if (getCurrentScale() < minScale) {
                    resetZoom();
                }
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (tapListener != null) {
                    tapListener.onSingleTap();
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float curScale = getCurrentScale();
                float x = e.getX();
                float y = e.getY();
                if (curScale < midScale * 0.9f) {
                    float factor = midScale / curScale;
                    suppMatrix.postScale(factor, factor, x, y);
                    checkAndDisplayMatrix();
                } else {
                    resetZoom();
                }
                return true;
            }
        });
    }

    public void setOnTapListener(OnTapListener listener) {
        this.tapListener = listener;
    }

    public boolean isZoomed() {
        return getCurrentScale() > 1.06f;
    }

    public void resetZoom() {
        suppMatrix.reset();
        checkAndDisplayMatrix();
    }

    public float getCurrentScale() {
        suppMatrix.getValues(matrixValues);
        float sx = matrixValues[Matrix.MSCALE_X];
        float skx = matrixValues[Matrix.MSKEW_Y];
        return (float) Math.sqrt(sx * sx + skx * skx);
    }

    public boolean canPanHorizontally(int direction) {
        if (!isZoomed()) return false;
        RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) return false;
        int vw = getWidth();
        if (rect.width() <= vw + 2) return false;
        if (direction < 0) {
            // Finger moving right -> content moving right -> check left edge
            return rect.left < -4f;
        } else {
            // Finger moving left -> content moving left -> check right edge
            return rect.right > vw + 4f;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        scaleDetector.onTouchEvent(event);

        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                lastTouch.set(event.getX(), event.getY());
                isDragging = false;
                if (isZoomed() && getParent() != null) {
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (!isScaling && event.getPointerCount() == 1 && isZoomed()) {
                    float dx = event.getX() - lastTouch.x;
                    float dy = event.getY() - lastTouch.y;
                    if (!isDragging) {
                        isDragging = Math.hypot(dx, dy) > 6f;
                    }
                    if (isDragging) {
                        suppMatrix.postTranslate(dx, dy);
                        checkAndDisplayMatrix();
                        lastTouch.set(event.getX(), event.getY());

                        RectF r = getDisplayRect(getDrawMatrix());
                        if (r != null && getParent() != null) {
                            boolean atLeftEdge = r.left >= -2f && dx > 0 && Math.abs(dx) > Math.abs(dy);
                            boolean atRightEdge = r.right <= getWidth() + 2f && dx < 0 && Math.abs(dx) > Math.abs(dy);
                            if (atLeftEdge || atRightEdge) {
                                getParent().requestDisallowInterceptTouchEvent(false);
                            } else {
                                getParent().requestDisallowInterceptTouchEvent(true);
                            }
                        }
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                isScaling = false;
                break;
        }
        return true;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (changed) {
            updateBaseMatrix();
        }
    }

    @Override
    public void setImageDrawable(Drawable drawable) {
        super.setImageDrawable(drawable);
        updateBaseMatrix();
    }

    @Override
    public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        updateBaseMatrix();
    }

    private void updateBaseMatrix() {
        Drawable d = getDrawable();
        if (d == null) return;
        int vw = getWidth() - getPaddingLeft() - getPaddingRight();
        int vh = getHeight() - getPaddingTop() - getPaddingBottom();
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (vw <= 0 || vh <= 0 || dw <= 0 || dh <= 0) return;

        baseMatrix.reset();
        float scale = Math.min((float) vw / dw, (float) vh / dh);
        float dx = (vw - dw * scale) * 0.5f;
        float dy = (vh - dh * scale) * 0.5f;
        baseMatrix.postScale(scale, scale);
        baseMatrix.postTranslate(dx, dy);

        suppMatrix.reset();
        checkAndDisplayMatrix();
    }

    private Matrix getDrawMatrix() {
        drawMatrix.set(baseMatrix);
        drawMatrix.postConcat(suppMatrix);
        return drawMatrix;
    }

    private RectF getDisplayRect(Matrix matrix) {
        Drawable d = getDrawable();
        if (d != null) {
            displayRect.set(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            matrix.mapRect(displayRect);
            return displayRect;
        }
        return null;
    }

    private void checkAndDisplayMatrix() {
        RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) return;

        float height = rect.height();
        float width = rect.width();
        float deltaX = 0, deltaY = 0;

        int viewHeight = getHeight();
        if (height <= viewHeight) {
            deltaY = (viewHeight - height) / 2f - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        int viewWidth = getWidth();
        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2f - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
        }

        suppMatrix.postTranslate(deltaX, deltaY);
        setImageMatrix(getDrawMatrix());
    }
}
