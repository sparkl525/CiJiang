package com.jianyi.dict;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * 离线词典数据库。所有词条以小写存储，查询走主键索引，毫秒级响应。
 * 首次启动从 APK 内的 gzip 包解压（约 50MB，在后台线程执行）。
 */
public class DictDb {

    /** 一条中英对照例句 */
    public static class Example {
        public String en;
        public String zh;
    }

    /** 一个词条的全部展示数据 */
    public static class Entry {
        public String word;
        public String phonetic;
        public String translation; // 中文释义（换行分隔）
        public String definition;  // 英文释义
        public String tag;         // 考试标签，如 "cet4 cet6 ky"
        public String exchange;    // 词形变化，如 "s:books d:booked"
        public int collins;        // 柯林斯星级 1-5
        public int oxford;         // 是否牛津3000
        public int frq;            // 当代语料库词频
    }

    private final SQLiteDatabase db;

    public DictDb(Context ctx) throws IOException {
        File f = new File(ctx.getFilesDir(), "dict.db");
        if (!f.exists() || f.length() == 0) {
            extract(ctx, f);
        }
        SQLiteDatabase d;
        try {
            d = open(f);
        } catch (Throwable t) {
            // 上次解压可能被中断导致文件损坏，删掉重来一次
            f.delete();
            extract(ctx, f);
            d = open(f);
        }
        db = d;
    }

    private static SQLiteDatabase open(File f) {
        SQLiteDatabase d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        Cursor probe = d.rawQuery("SELECT COUNT(*) FROM stardict LIMIT 1", null);
        probe.moveToFirst();
        probe.close();
        return d;
    }

    private static void extract(Context ctx, File f) throws IOException {
        File tmp = new File(ctx.getFilesDir(), "dict.db.tmp");
        if (tmp.exists()) tmp.delete();
            InputStream in = new GZIPInputStream(
                    new BufferedInputStream(ctx.getAssets().open("db/dict.dbz"), 1 << 16));
        OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp));
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
        if (!tmp.renameTo(f)) {
            copyFile(tmp, f);
            tmp.delete();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    public Entry lookup(String input) {
        String w = normalize(input);
        if (w.isEmpty()) return null;
        Cursor c = db.rawQuery(
                "SELECT word,phonetic,translation,definition,tag,collins,oxford,frq,exchange " +
                        "FROM stardict WHERE word = ? LIMIT 1",
                new String[]{w});
        Entry e = null;
        if (c.moveToFirst()) e = readEntry(c);
        c.close();
        return e;
    }

    /** 前缀联想：走主键索引的范围扫描，先出常用词（按词频） */
    public ArrayList<String> suggest(String prefix, int limit) {
        ArrayList<String> out = new ArrayList<>();
        String p = normalize(prefix);
        if (p.isEmpty()) return out;
        String end = p + Character.MAX_VALUE;
        // 第一批：常用词优先
        Cursor c = db.rawQuery(
                "SELECT word FROM stardict WHERE word >= ? AND word < ? AND frq > 0 " +
                        "ORDER BY frq DESC LIMIT ?",
                new String[]{p, end, String.valueOf(limit)});
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        if (out.size() < limit) {
            // 补充其余词条（字母序），保持索引扫描
            Cursor c2 = db.rawQuery(
                    "SELECT word FROM stardict WHERE word >= ? AND word < ? " +
                            "ORDER BY word LIMIT ?",
                    new String[]{p, end, String.valueOf(limit * 3)});
            while (c2.moveToNext()) {
                String w = c2.getString(0);
                if (!out.contains(w)) out.add(w);
                if (out.size() >= limit) break;
            }
            c2.close();
        }
        return out;
    }

    /**
     * 中文反查：拿中文在释义里搜英文单词。
     * 释义字段没有索引，只能全表 LIKE（实测桌面约 130ms/次，可接受）。
     * 高频词排前面，不够再补无词频数据的词条。
     */
    public ArrayList<Entry> lookupZh(String zh, int limit) {
        ArrayList<Entry> out = new ArrayList<>();
        String q = zh == null ? "" : zh.trim();
        if (q.isEmpty()) return out;
        String like = "%" + q + "%";
        Cursor c = db.rawQuery(
                "SELECT word,phonetic,translation,definition,tag,collins,oxford,frq,exchange " +
                        "FROM stardict WHERE translation LIKE ? AND frq > 0 " +
                        "ORDER BY frq LIMIT ?",
                new String[]{like, String.valueOf(limit)});
        while (c.moveToNext() && out.size() < limit) out.add(readEntry(c));
        c.close();
        if (out.size() < limit) {
            Cursor c2 = db.rawQuery(
                    "SELECT word,phonetic,translation,definition,tag,collins,oxford,frq,exchange " +
                            "FROM stardict WHERE translation LIKE ? AND (frq IS NULL OR frq = 0) " +
                            "ORDER BY word LIMIT ?",
                    new String[]{like, String.valueOf(limit - out.size())});
            while (c2.moveToNext() && out.size() < limit) out.add(readEntry(c2));
            c2.close();
        }
        return out;
    }

    /**
     * 查例句（Tatoeba 语料，构建期离线打进词典）。
     * 老版本词典没有 examples 表，这里吞掉异常返回空——保证升级时不崩。
     */
    public ArrayList<Example> examples(String word) {
        ArrayList<Example> out = new ArrayList<>();
        if (word == null || word.isEmpty()) return out;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT en1,zh1,en2,zh2 FROM examples WHERE word = ?",
                    new String[]{word.trim().toLowerCase(Locale.US)});
            if (c.moveToFirst()) {
                addExample(out, c.getString(0), c.getString(1));
                addExample(out, c.getString(2), c.getString(3));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return out;
    }

    private static void addExample(ArrayList<Example> out, String en, String zh) {
        if (en == null || en.isEmpty()) return;
        Example x = new Example();
        x.en = en;
        x.zh = zh == null ? "" : zh;
        out.add(x);
    }

    /** 最常用的前 N 个单词（排除短语和带连字符的），用于批量预缓存发音 */
    public ArrayList<String> topWords(int n) {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT word FROM stardict WHERE frq > 0 " +
                        "AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" " +
                        "ORDER BY frq LIMIT ?",
                new String[]{String.valueOf(n)});
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    /**
     * 指定考试标签的词表（如 cet6），按词频从常用到生僻。
     * 清洗规则：必须有中文释义、必须是纯单词（排除短语/连字符/撇号/非字母）。
     */
    public ArrayList<String> wordsByTag(String tag, int limit) {
        ArrayList<String> out = new ArrayList<>();
        if (tag == null || tag.isEmpty()) return out;
        Cursor c = db.rawQuery(
                "SELECT word FROM stardict WHERE tag LIKE ? AND frq > 0 "
                        + "AND translation IS NOT NULL AND translation <> '' "
                        + "AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" "
                        + "AND word NOT GLOB '*[^a-z]*' "
                        + "AND length(word) >= 3 AND length(word) <= 20 "
                        + "ORDER BY frq LIMIT ?",
                new String[]{"%" + tag + "%", String.valueOf(limit)});
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.US);
    }

    static Entry readEntry(Cursor c) {
        Entry e = new Entry();
        e.word = c.getString(0);
        e.phonetic = c.getString(1);
        e.translation = c.getString(2);
        e.definition = c.getString(3);
        e.tag = c.getString(4);
        e.collins = c.getInt(5);
        e.oxford = c.getInt(6);
        e.frq = c.getInt(7);
        e.exchange = c.getString(8);
        return e;
    }
}
