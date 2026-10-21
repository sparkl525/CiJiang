package com.jianyi.dict;

import android.util.Log;

/**
 * 统一日志入口。
 *
 * 之前全项目上百个 catch 全部一声不响，用户反馈「翻译没反应」「素材还没准备好」
 * 时没有任何线索可查。TAG 固定为 JianYi，`adb logcat -s JianYi` 即可看全。
 * 只记录「哪里失败 + 为什么失败」，不记录用户输入的原文（可能含私人内容）。
 */
final class JLog {

    private static final String TAG = "JianYi";

    private JLog() {
    }

    /** 预期内的降级：功能还能用，但结果可能不如人意 */
    static void w(String where, Throwable t) {
        Log.w(TAG, where + (t == null ? "" : " · " + t));
    }

    static void w(String where, String detail) {
        Log.w(TAG, where + " · " + detail);
    }

    /** 预期外的失败：这条路径被走到就说明有问题 */
    static void e(String where, Throwable t) {
        Log.e(TAG, where, t);
    }
}
