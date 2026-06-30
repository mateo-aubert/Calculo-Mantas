package com.mateo.hojadevuelo.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Vista de recorte pensada para que el usuario marque únicamente la columna
 * que contiene las matrículas de la hoja.
 */
public final class RegistrationColumnCropView extends View {
    private static final float MIN_SELECTION_RATIO = 0.08f;

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint dimPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF displayedImage = new RectF();
    private final RectF selection = new RectF();

    private Bitmap bitmap;
    private float imageScale = 1f;
    private float touchRadius;
    private float lastX;
    private float lastY;
    private DragMode dragMode = DragMode.NONE;

    public RegistrationColumnCropView(Context context) {
        this(context, null);
    }

    public RegistrationColumnCropView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        touchRadius = 34f * density;
        dimPaint.setColor(0x99000000);
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f * density);
        handlePaint.setColor(0xFF0B75C9);
        setBackgroundColor(Color.BLACK);
    }

    public void setBitmap(Bitmap value) {
        bitmap = value;
        displayedImage.setEmpty();
        selection.setEmpty();
        requestLayout();
        invalidate();
    }

    public boolean hasBitmap() {
        return bitmap != null && !bitmap.isRecycled();
    }

    /** Devuelve una copia de la zona marcada; el llamador pasa a ser su propietario. */
    public Bitmap createCroppedBitmap() {
        if (!hasBitmap() || displayedImage.isEmpty() || selection.isEmpty()) {
            throw new IllegalStateException("No hay una imagen preparada para recortar.");
        }

        int left = clamp(
                Math.round((selection.left - displayedImage.left) / imageScale),
                0,
                bitmap.getWidth() - 1);
        int top = clamp(
                Math.round((selection.top - displayedImage.top) / imageScale),
                0,
                bitmap.getHeight() - 1);
        int right = clamp(
                Math.round((selection.right - displayedImage.left) / imageScale),
                left + 1,
                bitmap.getWidth());
        int bottom = clamp(
                Math.round((selection.bottom - displayedImage.top) / imageScale),
                top + 1,
                bitmap.getHeight());
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasBitmap()) {
            return;
        }

        updateGeometryIfNeeded();
        canvas.drawBitmap(bitmap, null, displayedImage, bitmapPaint);

        canvas.drawRect(displayedImage.left, displayedImage.top,
                displayedImage.right, selection.top, dimPaint);
        canvas.drawRect(displayedImage.left, selection.bottom,
                displayedImage.right, displayedImage.bottom, dimPaint);
        canvas.drawRect(displayedImage.left, selection.top,
                selection.left, selection.bottom, dimPaint);
        canvas.drawRect(selection.right, selection.top,
                displayedImage.right, selection.bottom, dimPaint);

        canvas.drawRect(selection, borderPaint);
        float radius = 8f * getResources().getDisplayMetrics().density;
        canvas.drawCircle(selection.left, selection.top, radius, handlePaint);
        canvas.drawCircle(selection.right, selection.top, radius, handlePaint);
        canvas.drawCircle(selection.left, selection.bottom, radius, handlePaint);
        canvas.drawCircle(selection.right, selection.bottom, radius, handlePaint);
    }

    private void updateGeometryIfNeeded() {
        float availableWidth = getWidth() - getPaddingLeft() - getPaddingRight();
        float availableHeight = getHeight() - getPaddingTop() - getPaddingBottom();
        float scale = Math.min(
                availableWidth / bitmap.getWidth(),
                availableHeight / bitmap.getHeight());
        float width = bitmap.getWidth() * scale;
        float height = bitmap.getHeight() * scale;
        float left = getPaddingLeft() + (availableWidth - width) / 2f;
        float top = getPaddingTop() + (availableHeight - height) / 2f;

        RectF newImage = new RectF(left, top, left + width, top + height);
        if (displayedImage.equals(newImage)) {
            return;
        }

        boolean firstLayout = displayedImage.isEmpty();
        displayedImage.set(newImage);
        imageScale = scale;
        if (firstLayout || selection.isEmpty()) {
            // Punto de partida visible y fácil de desplazar hacia la columna real.
            selection.set(
                    displayedImage.left + displayedImage.width() * 0.32f,
                    displayedImage.top + displayedImage.height() * 0.08f,
                    displayedImage.left + displayedImage.width() * 0.68f,
                    displayedImage.bottom - displayedImage.height() * 0.08f);
        } else {
            constrainSelection();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!hasBitmap()) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragMode = findDragMode(x, y);
                if (dragMode == DragMode.NONE) {
                    return false;
                }
                lastX = x;
                lastY = y;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                applyDrag(x - lastX, y - lastY);
                lastX = x;
                lastY = y;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = DragMode.NONE;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    private DragMode findDragMode(float x, float y) {
        boolean nearLeft = Math.abs(x - selection.left) <= touchRadius;
        boolean nearRight = Math.abs(x - selection.right) <= touchRadius;
        boolean nearTop = Math.abs(y - selection.top) <= touchRadius;
        boolean nearBottom = Math.abs(y - selection.bottom) <= touchRadius;
        boolean withinHorizontal = x >= selection.left - touchRadius
                && x <= selection.right + touchRadius;
        boolean withinVertical = y >= selection.top - touchRadius
                && y <= selection.bottom + touchRadius;

        if (nearLeft && nearTop) return DragMode.TOP_LEFT;
        if (nearRight && nearTop) return DragMode.TOP_RIGHT;
        if (nearLeft && nearBottom) return DragMode.BOTTOM_LEFT;
        if (nearRight && nearBottom) return DragMode.BOTTOM_RIGHT;
        if (nearLeft && withinVertical) return DragMode.LEFT;
        if (nearRight && withinVertical) return DragMode.RIGHT;
        if (nearTop && withinHorizontal) return DragMode.TOP;
        if (nearBottom && withinHorizontal) return DragMode.BOTTOM;
        if (selection.contains(x, y)) return DragMode.MOVE;
        return DragMode.NONE;
    }

    private void applyDrag(float dx, float dy) {
        switch (dragMode) {
            case MOVE:
                selection.offset(dx, dy);
                break;
            case LEFT:
                selection.left += dx;
                break;
            case RIGHT:
                selection.right += dx;
                break;
            case TOP:
                selection.top += dy;
                break;
            case BOTTOM:
                selection.bottom += dy;
                break;
            case TOP_LEFT:
                selection.left += dx;
                selection.top += dy;
                break;
            case TOP_RIGHT:
                selection.right += dx;
                selection.top += dy;
                break;
            case BOTTOM_LEFT:
                selection.left += dx;
                selection.bottom += dy;
                break;
            case BOTTOM_RIGHT:
                selection.right += dx;
                selection.bottom += dy;
                break;
            default:
                return;
        }
        constrainSelection();
    }

    private void constrainSelection() {
        float minimumWidth = displayedImage.width() * MIN_SELECTION_RATIO;
        float minimumHeight = displayedImage.height() * MIN_SELECTION_RATIO;

        if (selection.width() < minimumWidth) {
            if (dragMode == DragMode.LEFT
                    || dragMode == DragMode.TOP_LEFT
                    || dragMode == DragMode.BOTTOM_LEFT) {
                selection.left = selection.right - minimumWidth;
            } else {
                selection.right = selection.left + minimumWidth;
            }
        }
        if (selection.height() < minimumHeight) {
            if (dragMode == DragMode.TOP
                    || dragMode == DragMode.TOP_LEFT
                    || dragMode == DragMode.TOP_RIGHT) {
                selection.top = selection.bottom - minimumHeight;
            } else {
                selection.bottom = selection.top + minimumHeight;
            }
        }

        if (selection.left < displayedImage.left) {
            selection.offset(displayedImage.left - selection.left, 0f);
        }
        if (selection.right > displayedImage.right) {
            selection.offset(displayedImage.right - selection.right, 0f);
        }
        if (selection.top < displayedImage.top) {
            selection.offset(0f, displayedImage.top - selection.top);
        }
        if (selection.bottom > displayedImage.bottom) {
            selection.offset(0f, displayedImage.bottom - selection.bottom);
        }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private enum DragMode {
        NONE, MOVE, LEFT, RIGHT, TOP, BOTTOM,
        TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
    }
}
