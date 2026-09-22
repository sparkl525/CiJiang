package com.jianyi.dict;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * 退出拖拽把手：一枚常驻的淡色小胶囊，写着「↓ 下滑返回」或「↑ 上滑返回」。
 *
 * 为什么要单独做这么一个控件，而不是只靠 ScrollView：
 * 覆盖层里的内容是可点单词、选项按钮，会消费触摸；滚动视图父类又会在位移超过
 * touchSlop 后抢先拦截、之后不再回调 onInterceptTouchEvent —— 靠在滚动视图上做判断
 * 怎么调都不稳。这个把手**自己消费 ACTION_DOWN**，于是整段手势（含后续 MOVE/UP）
 * 都会交给它，与父容器、兄弟滚动视图的拦截规则完全无关。
 *
 * 它同时承担"告诉用户这里能拖"的职责：常驻显示（静置时半透明，不抢眼），
 * 手指按下时变清晰。
 *
 * 位置：记背页放在顶部标题栏中间，阅读页放在底部独立一条 —— 都**不压住滚动区**，
 * 所以不会出现"拖动和滚动打架"。
 */
public class DragHint extends View {

    public interface Listener {
        void onDrag(float dy);

        void onRelease(float totalDy);
    }

    private static final float PILL_W_DP = 130f;
    private static final float PILL_H_DP = 28f;
    private static final float IDLE_ALPHA = 0.40f;   // 静置透明度：看得见，但不抢眼
    private static final long FADE_DELAY = 3600;     // 进入页面后先亮着，再淡下去

    private boolean down = true;
    private String label = "↓ 下滑返回";
    private Listener listener;

    private final Paint pillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float dp;
    private final float slop;
    private final int pillIdle;
    private final int pillActive;

    private float startY;
    private boolean dragging;
    private final Runnable fadeTask = () -> animate().alpha(IDLE_ALPHA).setDuration(500).start();

    public DragHint(Context c, AttributeSet a) {
        super(c, a);
        dp = getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        textPaint.setTextSize(12f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
        // 跟随外观模式取色（浅色/深色都要看得见），不要写死黑
        int fg = c.getColor(R.color.text_secondary);
        textPaint.setColor(fg);
        pillIdle = (fg & 0x00FFFFFF) | 0x1C000000;     // 同色相，只留很低的透明度
        pillActive = (fg & 0x00FFFFFF) | 0x33000000;
        setAlpha(IDLE_ALPHA);
    }

    /** down=true 表示向下拖动退出（记背页）；false 表示向上拖动退出（阅读页） */
    public void setDirection(boolean down) {
        this.down = down;
        this.label = down ? "↓ 下滑返回" : "↑ 上滑返回";
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** 进入页面时调用：先亮起来提示一下，过几秒淡到静置透明度 */
    public void showHint() {
        removeCallbacks(fadeTask);
        setAlpha(1f);
        postDelayed(fadeTask, FADE_DELAY);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float w = PILL_W_DP * dp;
        float h = PILL_H_DP * dp;
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        pillPaint.setColor(dragging ? pillActive : pillIdle);
        canvas.drawRoundRect(rect, h / 2f, h / 2f, pillPaint);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float baseline = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, cx, baseline, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startY = e.getRawY();
                dragging = false;
                removeCallbacks(fadeTask);
                setAlpha(1f);
                return true;                 // 必须消费：这样整段手势都归本控件
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getRawY() - startY;
                if (!dragging && (down ? dy > slop : dy < -slop)) dragging = true;
                if (dragging && listener != null) listener.onDrag(dy);
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                // 被系统打断（CANCEL）不算退出，传 0 让调用方走回弹
                float dy = e.getActionMasked() == MotionEvent.ACTION_CANCEL
                        ? 0f : e.getRawY() - startY;
                boolean was = dragging;
                dragging = false;
                invalidate();
                if (was && listener != null) listener.onRelease(dy);
                postDelayed(fadeTask, 1200);
                return true;
            }
            default:
                return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(fadeTask);
        super.onDetachedFromWindow();
    }
}
