package com.jianyi.dict;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 拍照翻译的框选层：显示照片，让用户拖出一个矩形，只识别框里的字。
 *
 * 手势：
 *  - 框内拖动 = 整体移动
 *  - 拖四个角 = 缩放
 *  - 框外拖动 = 重新画一个框
 *
 * 所有坐标都先按"图片显示区域"裁剪，取结果时再换算回原图像素坐标。
 */
public class CropView extends View {

    private Bitmap bmp;
    private final RectF sel = new RectF();      // 视图坐标下的选框
    private boolean needInit = true;
    private float scale = 1f, offX, offY, dispW, dispH;

    private static final int MODE_NONE = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_LT = 2;
    private static final int MODE_RT = 3;
    private static final int MODE_RB = 4;
    private static final int MODE_LB = 5;
    private static final int MODE_NEW = 6;
    private int mode = MODE_NONE;
    private float downX, downY;

    private final Paint maskPaint = new Paint();
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();      // 复用：拖框时每帧 new RectF 纯属白扔对象
    private float minSize;
    private float hot;          // 角柄热区半径

    public CropView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        maskPaint.setColor(0xAA000000);
        borderPaint.setColor(0xFFFFFFFF);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(2));
        handlePaint.setColor(0xFFFFFFFF);
        handlePaint.setStyle(Paint.Style.STROKE);
        handlePaint.setStrokeWidth(dp(3));
        minSize = dp(44);
        hot = dp(22);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setBitmap(Bitmap b) {
        bmp = b;
        needInit = true;
        invalidate();
    }

    /** 整张都选中（"识别整张"用） */
    public void selectAll() {
        if (bmp == null) return;
        sel.set(offX, offY, offX + dispW, offY + dispH);
        invalidate();
    }

    /** 选框换算回原图像素坐标；没图或没框返回 null */
    public Rect cropRect() {
        if (bmp == null || sel.width() < 1 || sel.height() < 1) return null;
        int x = (int) ((sel.left - offX) / scale);
        int y = (int) ((sel.top - offY) / scale);
        int w = (int) (sel.width() / scale);
        int h = (int) (sel.height() / scale);
        x = Math.max(0, Math.min(x, bmp.getWidth() - 1));
        y = Math.max(0, Math.min(y, bmp.getHeight() - 1));
        w = Math.max(1, Math.min(w, bmp.getWidth() - x));
        h = Math.max(1, Math.min(h, bmp.getHeight() - y));
        return new Rect(x, y, x + w, y + h);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) return;
        float vw = getWidth(), vh = getHeight();
        scale = Math.min(vw / bmp.getWidth(), vh / bmp.getHeight());
        dispW = bmp.getWidth() * scale;
        dispH = bmp.getHeight() * scale;
        offX = (vw - dispW) / 2;
        offY = (vh - dispH) / 2;
        dst.set(offX, offY, offX + dispW, offY + dispH);
        c.drawBitmap(bmp, null, dst, null);

        if (needInit) {
            // 默认框住中间大部分区域，省得用户从头拖
            float mx = dispW * 0.06f, my = dispH * 0.15f;
            sel.set(offX + mx, offY + my, offX + dispW - mx, offY + dispH - my);
            needInit = false;
        }

        // 框外压暗
        c.drawRect(offX, offY, offX + dispW, sel.top, maskPaint);
        c.drawRect(offX, sel.bottom, offX + dispW, offY + dispH, maskPaint);
        c.drawRect(offX, sel.top, sel.left, sel.bottom, maskPaint);
        c.drawRect(sel.right, sel.top, offX + dispW, sel.bottom, maskPaint);

        c.drawRect(sel, borderPaint);
        drawHandles(c);
    }

    /** 四个角画成 L 形，比实心小方块更清爽 */
    private void drawHandles(Canvas c) {
        float len = dp(16);
        // 左上
        c.drawLine(sel.left, sel.top + len, sel.left, sel.top, handlePaint);
        c.drawLine(sel.left, sel.top, sel.left + len, sel.top, handlePaint);
        // 右上
        c.drawLine(sel.right - len, sel.top, sel.right, sel.top, handlePaint);
        c.drawLine(sel.right, sel.top, sel.right, sel.top + len, handlePaint);
        // 右下
        c.drawLine(sel.right, sel.bottom - len, sel.right, sel.bottom, handlePaint);
        c.drawLine(sel.right, sel.bottom, sel.right - len, sel.bottom, handlePaint);
        // 左下
        c.drawLine(sel.left + len, sel.bottom, sel.left, sel.bottom, handlePaint);
        c.drawLine(sel.left, sel.bottom, sel.left, sel.bottom - len, handlePaint);
    }

    private int hitTest(float x, float y) {
        if (near(x, y, sel.left, sel.top)) return MODE_LT;
        if (near(x, y, sel.right, sel.top)) return MODE_RT;
        if (near(x, y, sel.right, sel.bottom)) return MODE_RB;
        if (near(x, y, sel.left, sel.bottom)) return MODE_LB;
        if (x > sel.left && x < sel.right && y > sel.top && y < sel.bottom) return MODE_MOVE;
        return MODE_NEW;
    }

    private boolean near(float x, float y, float px, float py) {
        return Math.abs(x - px) <= hot && Math.abs(y - py) <= hot;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        float x = clampX(e.getX()), y = clampY(e.getY());
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mode = hitTest(e.getX(), e.getY());
                downX = x;
                downY = y;
                if (mode == MODE_NEW) sel.set(x, y, x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == MODE_NONE) return true;
                if (mode == MODE_MOVE) {
                    float dx = x - downX, dy = y - downY;
                    float w = sel.width(), h = sel.height();
                    float l = sel.left + dx, t = sel.top + dy;
                    l = Math.max(offX, Math.min(l, offX + dispW - w));
                    t = Math.max(offY, Math.min(t, offY + dispH - h));
                    sel.set(l, t, l + w, t + h);
                    downX = x;
                    downY = y;
                } else if (mode == MODE_NEW) {
                    sel.set(Math.min(downX, x), Math.min(downY, y),
                            Math.max(downX, x), Math.max(downY, y));
                } else {
                    resize(mode, x, y);
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 拖出来的框太小就当作没选，回退到默认框
                if (sel.width() < minSize || sel.height() < minSize) {
                    float mx = dispW * 0.06f, my = dispH * 0.15f;
                    sel.set(offX + mx, offY + my, offX + dispW - mx, offY + dispH - my);
                }
                mode = MODE_NONE;
                invalidate();
                return true;
        }
        return false;
    }

    private void resize(int corner, float x, float y) {
        float l = sel.left, t = sel.top, r = sel.right, b = sel.bottom;
        switch (corner) {
            case MODE_LT:
                l = x;
                t = y;
                break;
            case MODE_RT:
                r = x;
                t = y;
                break;
            case MODE_RB:
                r = x;
                b = y;
                break;
            case MODE_LB:
                l = x;
                b = y;
                break;
            default:
                return;
        }
        if (r - l < minSize) {
            if (corner == MODE_LT || corner == MODE_LB) l = r - minSize;
            else r = l + minSize;
        }
        if (b - t < minSize) {
            if (corner == MODE_LT || corner == MODE_RT) t = b - minSize;
            else b = t + minSize;
        }
        sel.set(Math.max(offX, l), Math.max(offY, t),
                Math.min(offX + dispW, r), Math.min(offY + dispH, b));
    }

    private float clampX(float x) {
        return Math.max(offX, Math.min(x, offX + dispW));
    }

    private float clampY(float y) {
        return Math.max(offY, Math.min(y, offY + dispH));
    }
}
