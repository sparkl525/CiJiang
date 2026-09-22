package com.jianyi.dict;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ListView;

/**
 * 把每次触摸事件先"抄送"一份给观察者，用来在列表上做上拉/下拉手势（进入记背、进入阅读）。
 *
 * 为什么不用 setOnTouchListener 或 onInterceptTouchEvent：
 * - setOnTouchListener 只在**没有任何子 View 消费事件**时才会被调用，列表项一旦消费就收不到；
 * - onInterceptTouchEvent 在本控件自己接管滚动之后就**不再收到回调**（ViewGroup 在
 *   mFirstTouchTarget 变 null 后会直接 intercepted = true）。
 *
 * dispatchTouchEvent 是本控件的事件入口，凡是派发给它的触摸都会经过这里，
 * 用来做"只观察、不干预"的手势最稳。
 */
public class PullListView extends ListView {

    public interface Watcher {
        void onWatch(View v, MotionEvent ev);
    }

    private Watcher watcher;

    public PullListView(Context c, AttributeSet a) {
        super(c, a);
    }

    public void setWatcher(Watcher w) {
        watcher = w;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (watcher != null) watcher.onWatch(this, ev);
        return super.dispatchTouchEvent(ev);   // 只观察，不改变原有滚动/点击行为
    }
}
