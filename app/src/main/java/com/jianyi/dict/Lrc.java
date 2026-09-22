package com.jianyi.dict;

import java.util.ArrayList;

/**
 * LRC 字幕解析。21voa 的字幕长这样：
 *   [ti:标题]
 *   [by:www.21voa.com]
 *   [00:00.04]About five kilometers
 *   [00:03.00]from the White House in Washington, D.C.,
 *
 * 只认 [mm:ss.xx] 开头的行，其余（元数据、空行）一律丢掉。
 */
public class Lrc {

    public static class Line {
        public long startMs;
        public String text;

        Line(long startMs, String text) {
            this.startMs = startMs;
            this.text = text;
        }
    }

    /** 解析成"时间 → 一句"的列表，按时间升序 */
    public static ArrayList<Line> parse(String raw) {
        ArrayList<Line> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split("\n")) {
            String line = s.trim();
            if (line.isEmpty() || line.charAt(0) != '[') continue;
            int close = line.indexOf(']');
            if (close < 0) continue;
            String tag = line.substring(1, close);
            int colon = tag.indexOf(':');
            if (colon <= 0) continue;                       // [ti:xxx] 这类元数据
            String mm = tag.substring(0, colon);
            String rest = tag.substring(colon + 1);
            if (!isNum(mm)) continue;
            int dot = rest.indexOf('.');
            String ss = dot >= 0 ? rest.substring(0, dot) : rest;
            if (!isNum(ss)) continue;
            long ms = 0;
            try {
                ms = Integer.parseInt(mm) * 60000L + Integer.parseInt(ss) * 1000L;
                if (dot >= 0 && dot + 1 < rest.length()) {
                    String frac = rest.substring(dot + 1);
                    if (isNum(frac)) ms += Long.parseLong(frac) * (frac.length() == 2 ? 10L : 1L);
                }
            } catch (NumberFormatException e) {
                continue;
            }
            String text = line.substring(close + 1).trim();
            if (text.isEmpty()) continue;
            // 站点在开头塞的广告语，去掉
            if (text.contains("21VOA.COM") || text.contains("21voa.com")) continue;
            out.add(new Line(ms, text));
        }
        return out;
    }

    /** 当前播放位置落在第几句；没找到就返回 -1 */
    public static int indexAt(ArrayList<Line> lines, long ms) {
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startMs <= ms) idx = i;
            else break;
        }
        return idx;
    }

    private static boolean isNum(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
