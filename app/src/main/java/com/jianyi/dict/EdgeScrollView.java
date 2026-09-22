package com.jianyi.dict;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/**
 * 滚到边界后继续拖动即"退出"的 ScrollView（记背页上滑关闭 / 阅读页下滑关闭）。
 *
 * 退出方向＝进入方向的反向：下拉进入的页面上滑推回，上拉进入的页面下滑推回，
 * 页面始终跟着手指走。
 *
 * 两个必须记住的坑，这正是前两版手势一直失效的原因：
 *
 * 1) 不能用 setOnTouchListener。Android 只在**没有任何子 View 消费事件**时才会调它，
 *    而这里的子 View 是可点单词（ClickableSpan）、选项按钮，手指一落上去事件就被吃掉，
 *    容器永远收不到通知。
 *
 * 2) 光靠 onInterceptTouchEvent 也不够。ScrollView 父类在位移超过 touchSlop（约 8dp）
 *    时就会抢先拦截 —— 它不管内容还能不能滚。一旦被拦截，子 View 收到 CANCEL、
 *    mFirstTouchTarget 变 null，此后 ViewGroup 直接 intercepted = true，
 *    **onInterceptTouchEvent 再也不会被回调**。所以阈值只要比父类的 touchSlop 大一点，
 *    判断代码就永远等不到第二次执行的机会。
 *
 * 因此这里的判断逻辑同时放在 onInterceptTouchEvent 与 onTouchEvent 两处：
 * 父类抢先拦截后，后续 MOVE 会走 onTouchEvent，在那里补上接管。
 */
public class EdgeScrollView extends ScrollView {

    public interface EdgeListener {
        /** 跟手过程中的位移（相对按下点） */
        void onEdgeDrag(float dy);

        /** 松手，totalDy 为总位移 */
        void onEdgeRelease(float totalDy);
    }

    private boolean exitDown = true;   // true=下滑退出（阅读页）；false=上滑退出（记背页）
    private EdgeListener listener;

    private float startY;
    private boolean atEdgeAtDown;
    private boolean dragging;
    private final float slop;

    public EdgeScrollView(Context c, AttributeSet a) {
        super(c, a);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
    }

    public void setExitDown(boolean down) {
        exitDown = down;
    }

    public void setEdgeListener(EdgeListener l) {
        listener = l;
    }

    private void onDown(MotionEvent e) {
        startY = e.getRawY();
        dragging = false;
        // 按下那一刻就得在出口边界上，避免"刚滚到顶就被顺手甩退"
        atEdgeAtDown = exitDown ? !canScrollVertically(-1) : !canScrollVertically(1);
    }

    /** 满足"该退出了"的条件就置位 dragging；返回当前是否已接管 */
    private boolean checkStart(MotionEvent e) {
        if (dragging) return true;
        if (!atEdgeAtDown || listener == null) return false;
        float dy = e.getRawY() - startY;
        if (exitDown ? dy > slop : dy < -slop) {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(e);
                break;
            case MotionEvent.ACTION_MOVE:
                if (checkStart(e)) return true;
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(e);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                onDown(e);
                break;
            case MotionEvent.ACTION_MOVE:
                // 父类抢先拦截之后，onInterceptTouchEvent 不再回调，只能在这里接管
                if (checkStart(e)) {
                    listener.onEdgeDrag(e.getRawY() - startY);
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    // 被系统打断（CANCEL）不算退出，传 0 让调用方走回弹
                    float dy = e.getActionMasked() == MotionEvent.ACTION_CANCEL
                            ? 0f : e.getRawY() - startY;
                    super.onTouchEvent(e);          // 让父类把内部拖动状态复位
                    listener.onEdgeRelease(dy);
                    return true;
                }
                break;
            default:
                break;
        }
        return super.onTouchEvent(e);
    }

    /**
     * 吞掉子 View 的"别拦我的触摸"请求。
     * 个别控件（文本选择的 MovementMethod 等）会调它，一旦生效，
     * MOVE 就不会再走 onInterceptTouchEvent，退出手势又会被绕开。
     * 本类的接管只在"已在边界 + 方向朝出口"时发生，不会干扰正常滚动，可以安全忽略。
     */
    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        super.requestDisallowInterceptTouchEvent(false);
    }
}
