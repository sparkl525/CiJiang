package com.jianyi.dict;

import java.util.Locale;

/**
 * 崩溃栈落盘前的脱敏。
 *
 * 为什么要有这一层：
 * 崩溃栈里最常出现的是请求地址。百度那侧的 URL 带着 appid 和 sign，
 * 腾讯侧的 Authorization 头带着签名。这份 crash.log 是要发给开发者定位问题的，
 * 原样落盘就等于把用户的凭据一起发出去——而且它还躺在外部存储里，
 * Android 10 及以下别的 App 读得到。
 *
 * 所以落盘前先把两类东西抹掉：
 *   ① 整条 URL（从 http 开始，后面的全不要）
 *   ② appid= / sign= / secret= / token= 这类参数的值（名=值、名: 值、"名": "值" 三种写法都认）
 */
final class Redact {

    private Redact() {
    }

    /** 一行里从 http 开头的那一段（整条 URL 丢掉） */
    private static final String URL_MARK = "http";

    /**
     * 需要抹掉取值的参数名。
     * 2026-10-23 扩充：authorization（腾讯 TC3 的签名头）、credential / signature
     * （TC3 头值里的两个组成部分，光有 sign= 拦不住）、appsecret / apikey /
     * accesskey / password / pwd。宁可多抹：崩溃日志不是给人当正文读的。
     */
    private static final String PARAMS = "(appid|appsecret|secretkey|secretid|secret|apikey"
            + "|accesskey|key|sign|signature|token|authorization|credential|password|pwd)";

    /** 崩溃栈脱敏 */
    static String crash(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            sb.append(oneLine(line)).append('\n');
        }
        return sb.toString();
    }

    private static String oneLine(String line) {
        String out = line == null ? "" : line;
        int at = out.toLowerCase(Locale.US).indexOf(URL_MARK);
        if (at >= 0) out = out.substring(0, at) + "[URL 已隐去]";
        // 分隔符等号冒号都认、名字两边可以带引号（JSON 的 "secret": "abc"）：
        //   appid=123        → appid=[已隐去]        （URL 查询串）
        //   Authorization: x → Authorization=[已隐去] （HTTP 头）
        //   "secret": "abc"  → secret=[已隐去]        （JSON 片段）
        // 值一律吃到空白或 & " 为止——URL 查询串里 & 后面是下一个参数，不能一起吃掉。
        return out.replaceAll("(?i)\"?" + PARAMS + "\"?\\s*[=:]\\s*\"?[^\\s&\"]*",
                "$1=[已隐去]");
    }
}
