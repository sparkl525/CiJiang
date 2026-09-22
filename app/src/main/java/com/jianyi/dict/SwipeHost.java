package com.jianyi.dict;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.OverScroller;

/**
 * 三页横向滑动容器：跟手拖拽、松手吸附、边界阻尼回弹。
 *
 * 自己手写而不引 ViewPager2 的原因：工程刻意不依赖 AndroidX，
 * 而这里需要的能力（横向翻页 + 与竖向列表/输入框的手势协调）并不复杂。
 *
 * 手势协调规则：横向位移超过阈值且大于纵向位移时才拦截，
 * 这样列表竖向滚动、输入框点按、按钮点击都不受影响。
 */
public class SwipeHost extends FrameLayout {

    /** 页面变化回调（松手定页、标签点击都会触发） */
    public interface Listener {
        void onPageChanged(int page);
    }

    private static final float DAMP = 0.45f;    // 越界跟手的阻尼系数：只跟 45%
    private static final int FLING_VEL = 1200;  // 甩动判定阈值 px/s
    private static final int MIN_MS = 200;      // 吸附动画最短时长
    private static final int MAX_MS = 500;      // 吸附动画最长时长（调慢一点，切页更稳重）

    private final OverScroller scroller = new OverScroller(getContext());
    private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

    private Listener listener;
    private int page = 0;
    private int lastWidth = -1;

    // 某些页（全屏沉浸页）进出时要隐藏顶栏/标签栏，翻页慢一点更稳，别跟布局变化抢时间
    private int longSnapPage = -1;
    private int longSnapMs = MAX_MS;

    private float downX, downY;
    private int downScroll;
    private boolean dragging;
    private VelocityTracker velocity;

    public SwipeHost(Context c, AttributeSet attrs) {
        super(c, attrs);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    /** 指定某一页的翻页时长（进入和离开该页都生效） */
    public void setSnapMs(int page, int ms) {
        longSnapPage = page;
        longSnapMs = ms;
    }

    public int getPage() {
        return page;
    }

    /** 直接落到指定页、不带动画（启动定位用）。布局未完成时用 post 调用 */
    public void jumpTo(int i) {
        page = clamp(i);
        if (!scroller.isFinished()) scroller.abortAnimation();
        scrollTo(page * getWidth(), 0);
        if (listener != null) listener.onPageChanged(page);
    }

    /** 滚到指定页（标签点击用，带动画） */
    public void snapTo(int i) {
        int target = clamp(i);
        page = target;
        if (!scroller.isFinished()) scroller.abortAnimation();
        scroller.startScroll(getScrollX(), 0, target * getWidth() - getScrollX(), 0, MAX_MS);
        invalidate();
        if (listener != null) listener.onPageChanged(page);
    }

    private int clamp(int i) {
        return Math.max(0, Math.min(getChildCount() - 1, i));
    }

    // ================= 布局：三个子页横向铺开 =================

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(
                View.resolveSize(getSuggestedMinimumWidth(), widthSpec),
                View.resolveSize(getSuggestedMinimumHeight(), heightSpec));
        int w = MeasureSpec.makeMeasureSpec(getMeasuredWidth(), MeasureSpec.EXACTLY);
        int h = MeasureSpec.makeMeasureSpec(getMeasuredHeight(), MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(w, h);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            c.layout(i * w, 0, (i + 1) * w, b - t);
        }
        // 只有**宽度**变化才需要重新定位、打断动画。
        // 高度变化（比如切到听力页时顶栏、标签栏隐藏）不能掐掉正在播放的翻页动画 ——
        // 之前这里无条件 abortAnimation()，导致"首页→听力"被瞬间跳页，看着就是"太快了"。
        // 横向滚动位置与高度无关，所以高度变化时什么都不用做。
        if (w != lastWidth) {
            lastWidth = w;
            if (!scroller.isFinished()) scroller.abortAnimation();
            scrollTo(page * w, 0);
        }
    }

    // ================= 手势 =================

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downScroll = getScrollX();
                dragging = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging) return true;
                float dx = ev.getX() - downX;
                float dy = ev.getY() - downY;
                // 横向为主才接管；竖向滚动和点击都还给孩子
                if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                    dragging = true;
                    return true;
                }
                break;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        velocity.addMovement(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                downScroll = getScrollX();
                dragging = true;
                if (!scroller.isFinished()) scroller.abortAnimation();
                break;
            case MotionEvent.ACTION_MOVE:
                scrollToDamped(downScroll - (ev.getX() - downX));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                velocity.computeCurrentVelocity(1000);
                finishDrag(velocity.getXVelocity());
                velocity.recycle();
                velocity = null;
                dragging = false;
                break;
        }
        return true;
    }

    /** 跟手滚动；越界部分按阻尼打折，产生"橡皮筋"手感 */
    private void scrollToDamped(float target) {
        int w = getWidth();
        int max = (getChildCount() - 1) * w;
        int x;
        if (target < 0) {
            x = (int) (target * DAMP);
        } else if (target > max) {
            x = (int) (max + (target - max) * DAMP);
        } else {
            x = (int) target;
        }
        scrollTo(x, 0);
    }

    /** 松手：快甩按方向翻页，慢移按距离吸附；越界时滚回边界（回弹） */
    private void finishDrag(float vx) {
        int w = getWidth();
        int cur = getScrollX();
        final int from = page;
        int target = page;
        if (Math.abs(vx) > FLING_VEL) {
            target = clamp(page + (vx < 0 ? 1 : -1));   // 向左甩 → 下一页
        } else if (cur > page * w + w / 4) {
            target = clamp(page + 1);
        } else if (cur < page * w - w / 4) {
            target = clamp(page - 1);
        }
        page = target;
        if (listener != null) listener.onPageChanged(page);

        int dx = page * w - cur;
        int cap = (from == longSnapPage || page == longSnapPage) ? longSnapMs : MAX_MS;
        int duration = Math.max(MIN_MS, Math.min(cap, Math.abs(dx) * 2));
        scroller.startScroll(cur, 0, dx, 0, duration);
        invalidate();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.getCurrX(), 0);
            invalidate();
        }
    }
}
