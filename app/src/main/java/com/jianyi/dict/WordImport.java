package com.jianyi.dict;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

/**
 * 「神秘开关」导入文本 → 词表（v3.15.1）。
 *
 * 只做文本解析：不查词典、不碰 Android 类型，所以能直接在 JVM 上跑单元测试。
 * 一行一个词，词和释义之间可以写 = ＝ ： | 制表符，也可以什么都不写、
 * 直接 apple 苹果 这样用空格分开；什么都没给的（整行只有一个英文词）交出去由词典补释义。
 * 释义里连着写好几个义项的（放弃, 遗弃, 抛弃）只取第一个——方块装不下整条，
 * 硬截断又会把两条长释义切成同一个前缀，牌面上就出现两个一模一样的中文。
 * 常见的粘贴来源都照顾到了：带序号的（1. apple 苹果）、带词性的（abandon v. 放弃）、
 * 中英写反的（苹果 = apple / 放弃 abandon）、注释行（# 开头）。
 */
public class WordImport {

    /**
     * 显式的「词 | 释义」分隔符。
     * 半角逗号 / 分号故意不在这儿：贴进来的英文列表（apple, banana, cherry）
     * 会被它切成一堆怪配对；中英之间真用逗号时，下面「按第一个中文切」那条路照样认。
     */
    private static final String[] SEPS = {"=", "＝", "：", "|", "\t", "，", "；"};

    /** 词形：以字母开头，允许空格 / 连字符 / 撇号（take off、well-being 这种词组要收） */
    private static final String WORD_OK = "[A-Za-z][A-Za-z0-9 .'\\-]*";

    /** 释义前面的词性标记，粘过来的单词表几乎都带着，方块上占地方、没有信息量 */
    private static final String POS_HEAD = "^((n|v|vt|vi|adj|adv|prep|pron|conj|num|int"
            + "|art|aux|abbr|na|ad)[.]\\s*)+";

    /** 夹在词和中文之间的词性尾巴：「abandon v. 放弃」里那个 v. */
    private static final String POS_TAIL = "[ ]((n|v|vt|vi|adj|adv|prep|pron|conj|num|int"
            + "|art|aux|abbr|na|ad)[.])";

    public static final int MAX_WORDS = 300;    // 一次最多导这么多，再多牌面也铺不出花样

    /**
     * 解析成 {词, 释义} 列表；释义没给的是空串，由调用方（GameWords）去词典里查。
     * 词形和释义在这里就去重——同一屏出现两块一模一样的中文，玩家根本没法配。
     */
    public static ArrayList<String[]> parse(String text) {
        ArrayList<String[]> out = new ArrayList<>();
        if (text == null) return out;
        HashSet<String> seenEn = new HashSet<>();
        HashSet<String> seenZh = new HashSet<>();
        for (String raw : text.split("\\R")) {          // \R = 任意换行符（\r\n / \n / \r）
            String[] row = parseLine(raw);
            if (row == null) continue;
            if (out.size() >= MAX_WORDS) break;         // 超出的部分直接不要
            if (!seenEn.add(row[0])) continue;          // 同一个词写了两遍，取第一条
            if (!row[1].isEmpty() && !seenZh.add(row[1])) continue;
            out.add(row);
        }
        return out;
    }

    /** 单行 → {小写词形, 释义}；不像词表的一律返回 null */
    public static String[] parseLine(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty() || s.startsWith("#") || s.startsWith("//") || s.startsWith("[")) return null;
        s = s.replaceFirst("^\\d+[.、)）]\\s*", "");      // 去掉「12. 」这种序号
        if (s.isEmpty()) return null;

        String en;
        String zh;
        int cut = firstSep(s);
        if (cut >= 0) {
            en = s.substring(0, cut);
            zh = s.substring(cut + 1);
        } else {
            int cjk = firstCjk(s);
            int lat = firstLatin(s);
            if (cjk > 0 && (lat < 0 || cjk > lat)) {         // apple 苹果：前英后中
                en = s.substring(0, cjk);
                zh = s.substring(cjk);
            } else if (cjk == 0 && lat > 0) {                // 放弃 abandon：前中后英
                en = s.substring(lat);
                zh = s.substring(0, lat);
            } else if (cjk < 0) {                            // 整行没有中文：只给了词
                en = s;
                zh = "";
            } else {
                return null;                                 // 只有中文（「苹果」这种）没法出题
            }
        }
        // 中英写反（苹果 = apple）：哪边像英文词就把哪边当词
        if (!isWord(en) && isWord(zh)) {
            String t = en;
            en = zh;
            zh = t;
        }
        zh = cleanMeaning(zh);
        en = cleanWord(en);
        if (!isWord(en)) return null;
        if (zh.length() > 16) zh = zh.substring(0, 16);   // 方块就那么大，太长读不完
        return new String[]{en, zh};
    }

    private static int firstSep(String s) {
        int best = -1;
        for (String sep : SEPS) {
            int i = s.indexOf(sep);
            if (i > 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    /** 第一个中文字符的位置；没有返回 -1 */
    private static int firstCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return i;
        }
        return -1;
    }

    private static int firstLatin(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return i;
        }
        return -1;
    }

    private static String cleanWord(String w) {
        if (w == null) return "";
        String s = w.trim().toLowerCase(Locale.US).replaceAll("\\s+", " ");
        s = s.replaceFirst(POS_TAIL + "$", "");        // 「abandon v.」尾巴上的词性
        s = s.replaceAll("[.、,，]+$", "").trim();
        return s;
    }

    private static String cleanMeaning(String m) {
        if (m == null) return "";
        String s = m.trim().replace("\n", " ").replace("\t", " ");
        s = s.replaceFirst(POS_HEAD, "").trim();       // 词性前缀剥掉
        s = s.replaceAll("[,，]\\s*$", "").trim();      // 尾巴上的逗号
        s = s.replaceAll("\\s+", " ");
        return firstClause(s);
    }

    /**
     * 一长串义项只留第一个：「放弃, 遗弃, 抛弃」→「放弃」。
     * 方块装不下整条，而硬截断会把两条长释义切成同一个前缀——牌面上就出现两个一模一样的中文。
     * 也给词典补出来的释义用（GameWords.useImport），口径必须和贴进来的文本一致。
     */
    public static String firstClause(String s) {
        if (s == null) return "";
        String[] parts = s.split("[,，、;；/]");
        if (parts.length > 1 && !parts[0].trim().isEmpty()) return parts[0].trim();
        return s;
    }

    private static boolean isWord(String w) {
        if (w == null) return false;
        String t = w.trim();
        if (t.length() < 2 || t.length() > 30) return false;
        return t.matches(WORD_OK);
    }
}
