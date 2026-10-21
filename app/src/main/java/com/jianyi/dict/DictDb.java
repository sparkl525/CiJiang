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

    /** 一条常用搭配：短语 + 中文释义 */
    public static class Phrase {
        public String phrase;   // 完整短语，如 "give up"
        public String zh;       // 中文释义
        public int score;       // 优先级分：越大越常用（出题时只挑常用的高分搭配当答案）
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
        public boolean phrase;     // 这条是从 phrases（常用搭配）表兜底出来的词组
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
        // v3.10：词典加了 phrases（常用搭配）表。老用户手机上的 dict.db 是旧版，
        // 不会自动更新 —— 这里探测到缺表就重新解压一次新词典，否则搭配功能对老用户无效。
        // 代价是升级后首次启动多花几秒解压 50MB，在后台线程做，不卡界面。
        // 同理，现在的词典带了 frq 索引（背词分页从 ~150ms 降到 ~0.5ms），
        // 老库没索引就得再解压一次，否则装了新版还是全表扫。
        if (!hasPhrases(d) || !hasFrqIndex(d)) {
            try {
                d.close();
            } catch (Throwable ignored) { JLog.w("DictDb.upgradeOldPack", ignored);
             }
            extract(ctx, f);          // extract 内部会覆盖写入新库
            d = open(f);
        }
        db = d;
    }

    /** 词典里有没有 frq 索引（判断是不是没建索引的老库） */
    private static boolean hasFrqIndex(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='index' AND name='idx_frq'", null)) {
            return c.moveToFirst();
        } catch (Throwable t) {
            // 报"没有索引"就会触发重新解压 50MB，所以出错一定要留下痕迹
            JLog.w("DictDb.hasFrqIndex", t);
            return false;
        }
    }

    /** 词典里有没有 phrases 表（判断是不是老版本的库） */
    private static boolean hasPhrases(SQLiteDatabase d) {
        try (Cursor c = d.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='phrases'", null)) {
            return c.moveToFirst();
        } catch (Throwable t) {
            // 报"没有新表"就会触发重新解压 50MB，所以出错一定要留下痕迹
            JLog.w("DictDb.hasPhrases", t);
            return false;
        }
    }

    private static SQLiteDatabase open(File f) {
        SQLiteDatabase d = SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor probe = d.rawQuery("SELECT COUNT(*) FROM stardict LIMIT 1", null)) {
            probe.moveToFirst();
        } catch (Throwable t) {
            d.close();
            throw t;
        }
        return d;
    }

    private static void extract(Context ctx, File f) throws IOException {
        File tmp = new File(ctx.getFilesDir(), "dict.db.tmp");
        if (tmp.exists() && !tmp.delete()) {
            JLog.w("DictDb.extract", "残留的 dict.db.tmp 删不掉，解压可能写到旧内容");
        }
        // 流必须任何一条路径都关掉：中途抛异常时留下半个 .tmp 和两个打开的 fd
        try (InputStream in = new GZIPInputStream(
                     new BufferedInputStream(ctx.getAssets().open("db/dict.dbz"), 1 << 16));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
        // 目标已存在时 renameTo 在部分平台会失败，先删掉旧库，保证下面的 rename 干净利落。
        // （v3.10 升级旧词典时走的就是这条路径：删旧库 → 解压新库 → rename）
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) {
            copyFile(tmp, f);
            tmp.delete();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst))) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** 进程退出前释放只读库句柄 */
    public void close() {
        try {
            db.close();
        } catch (Throwable t) {
            JLog.w("DictDb.close", t);
        }
    }

    public Entry lookup(String input) {
        String w = normalize(input);
        if (w.isEmpty()) return null;
        try (Cursor c = db.rawQuery(
                "SELECT word,phonetic,translation,definition,tag,collins,oxford,frq,exchange " +
                        "FROM stardict WHERE word = ? LIMIT 1",
                new String[]{w})) {
            return c.moveToFirst() ? readEntry(c) : null;
        }
    }

    /**
     * 词组兜底查释义（v3.14.1）。
     *
     * 记背每组题里掺了两道词组题（buildPhraseQuestions），答错就按整条短语
     * （"give up"）收进错题集；可短语在 stardict 里没有独立词条——实测 16485 条搭配
     * 只有 61 条能按 word 精确查到，剩下 16424 条一律"未收录"。释义其实就在
     * phrases.zh 里，点进去却拿不到，就是用户报的"词组点开看不到释义、也查不出来"。
     *
     * phrase 这一列没建索引，但整表只有 16485 行，精确匹配全表扫实测 0.2~3.4ms，
     * 而且这条路只在 dbPool 那一条线上跑（列表渲染、查词兜底），不值得为它重压一次
     * 23MB 词典——老用户的 dict.db 也不会因为多个索引就自动重新解压。
     */
    public Entry lookupPhrase(String input) {
        String w = normalize(input);
        // 单词不该走这条路：没有空格的查询串在搭配表里必然查不到，白扫一遍
        if (w.isEmpty() || w.indexOf(' ') < 0) return null;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT phrase, zh FROM phrases WHERE phrase = ? COLLATE NOCASE LIMIT 1",
                    new String[]{w});
            if (!c.moveToFirst()) return null;
            Entry e = new Entry();
            e.word = c.getString(0);
            e.translation = c.getString(1);
            e.phrase = true;
            return e;
        } catch (Throwable t) {
            // 老词典没有 phrases 表，查不到就当没这条词组，不能崩
            JLog.w("DictDb.lookupPhrase", t);
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * 前缀联想（只要词形）：拼写纠错等场景用，不碰任何长字段。
     */
    public ArrayList<String> suggest(String prefix, int limit) {
        ArrayList<String> out = new ArrayList<>();
        for (Entry e : suggestScan(prefix, limit, false)) out.add(e.word);
        return out;
    }

    /**
     * 前缀联想（带联想列表要显示的列）：一次扫描就把结果取全。
     * 老写法是 suggest() 拿 15 个词、再逐个 lookup()，每敲一个字符 = 15 次查询、
     * 每次还连 definition（英文释义长块）一起 9 列全读，而列表只用其中 3 列。
     */
    public ArrayList<Entry> suggestEntries(String prefix, int limit) {
        return suggestScan(prefix, limit, true);
    }

    private ArrayList<Entry> suggestScan(String prefix, int limit, boolean withDetail) {
        ArrayList<Entry> out = new ArrayList<>();
        String p = normalize(prefix);
        if (p.isEmpty()) return out;
        String cols = withDetail ? "word,translation,tag,frq" : "word";
        String end = p + Character.MAX_VALUE;
        // 第一批：常用词优先
        try (Cursor c = db.rawQuery(
                "SELECT " + cols + " FROM stardict WHERE word >= ? AND word < ? AND frq > 0 " +
                        "ORDER BY frq DESC LIMIT ?",
                new String[]{p, end, String.valueOf(limit)})) {
            while (c.moveToNext()) out.add(readSuggestRow(c, withDetail));
        }
        if (out.size() < limit) {
            // 补充其余词条（字母序），保持索引扫描
            try (Cursor c2 = db.rawQuery(
                    "SELECT " + cols + " FROM stardict WHERE word >= ? AND word < ? " +
                            "ORDER BY word LIMIT ?",
                    new String[]{p, end, String.valueOf(limit * 3)})) {
                while (c2.moveToNext()) {
                    String w = c2.getString(0);
                    boolean dup = false;
                    for (Entry x : out) {
                        if (w.equals(x.word)) { dup = true; break; }
                    }
                    if (!dup) out.add(readSuggestRow(c2, withDetail));
                    if (out.size() >= limit) break;
                }
            }
        }
        return out;
    }

    /**
     * 中文反查：拿中文在英文单词的释义里搜。
     * 释义字段没法建索引（前导通配 LIKE），仍要扫；建了 frq 索引后
     * "高频在前"这条能边扫边停，桌面实测从 ~85ms 降到 ~10ms；
     * 高频词凑不满 limit 时再补一轮无频数据的词条。
     *
     * 只投联想列表用得着的 4 列（全表扫出来的行数可能很多，definition 读进 Java
     * 就是白读）；调用方是列表渲染，点进去会由 doLookup 重新取完整词条。
     */
    public ArrayList<Entry> lookupZh(String zh, int limit) {
        ArrayList<Entry> out = new ArrayList<>();
        String q = zh == null ? "" : zh.trim();
        if (q.isEmpty()) return out;
        // 查询里的 % 和 _ 不当通配符用：搜 "50%" 不该匹配到任意以 50 开头的释义
        String like = "%" + escapeLike(q) + "%";
        try (Cursor c = db.rawQuery(
                "SELECT word,translation,tag,frq " +
                        "FROM stardict WHERE translation LIKE ? ESCAPE '\\' AND frq > 0 " +
                        "ORDER BY frq LIMIT ?",
                new String[]{like, String.valueOf(limit)})) {
            while (c.moveToNext() && out.size() < limit) out.add(readSuggestRow(c, true));
        }
        if (out.size() < limit) {
            // 判据必须写成 ifnull(frq,0)=0，不能写 (frq IS NULL OR frq=0)：
            // 后者会让查询规划器改走 frq 索引，把全部无频词捞出来再建临时 B 树排序，
            // 而这里要的是沿 word 索引顺序边扫边过滤、凑满 limit 就停。实测 13ms -> 94ms。
            try (Cursor c2 = db.rawQuery(
                    "SELECT word,translation,tag,frq " +
                            "FROM stardict WHERE translation LIKE ? ESCAPE '\\' AND ifnull(frq,0) = 0 " +
                            "ORDER BY word LIMIT ?",
                    new String[]{like, String.valueOf(limit - out.size())})) {
                while (c2.moveToNext() && out.size() < limit) out.add(readSuggestRow(c2, true));
            }
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
        } catch (Throwable ignored) { JLog.w("DictDb.examples", ignored);
         } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 查某个词的常用搭配（v3.10 词组训练）。
     * 搭配存在独立的 phrases 表里（构建期由 tools/build_phrases.py 从 ECDICT 筛出来）。
     *
     * 兼容老数据：老用户手机上的 dict.db 是旧版，根本没有 phrases 表，
     * 这里把异常吞掉返回空列表——不能因为老词典没新表就崩。
     */
    public ArrayList<Phrase> phrases(String head, int limit) {
        ArrayList<Phrase> out = new ArrayList<>();
        if (head == null || head.isEmpty() || limit <= 0) return out;
        Cursor c = null;
        try {
            // 按 score 倒序：give 这种词有一百多个搭配，只展示最有用的那几个。
            // 同分时取更短的中文释义（义项越短通常越通用），再按短语字母序兜底。
            c = db.rawQuery("SELECT phrase, zh, score FROM phrases WHERE head = ? "
                            + "ORDER BY score DESC, length(zh), phrase LIMIT ?",
                    new String[]{head.trim().toLowerCase(Locale.US), String.valueOf(limit)});
            while (c.moveToNext()) {
                Phrase p = new Phrase();
                p.phrase = c.getString(0);
                p.zh = c.getString(1);
                p.score = c.getInt(2);
                if (p.phrase != null && !p.phrase.isEmpty()) out.add(p);
            }
        } catch (Throwable ignored) { JLog.w("DictDb.phrases", ignored);
            // 老词典没有 phrases 表 —— 静默当作"这个词没有搭配"
         } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /**
     * 词组训练用的搭配候选：取一批两词短语当题目素材。
     * 只要两个词的（give up / deal with / work out）——这种最常考也最清晰；
     * 三个词往上的（put something out）太长，出题读起来累、干扰项也难凑。
     *
     * 两段取：先取分数最高的一批（结构上就是最常用的搭配），
     * 再随机补一些，保证每次背词看到的题不一样、也不会全是生僻货。
     */
    public ArrayList<Phrase> phrasePool(int limit) {
        ArrayList<Phrase> out = new ArrayList<>();
        if (limit <= 0) return out;
        Cursor c = null;
        try {
            // 第一段：常用搭配（score 高）
            c = db.rawQuery("SELECT phrase, zh, score FROM phrases "
                            + "WHERE phrase NOT LIKE '% % %' AND length(zh) <= 12 "
                            + "AND score >= 150 ORDER BY score DESC, phrase LIMIT ?",
                    new String[]{String.valueOf(limit)});
            while (c.moveToNext()) {
                Phrase p = new Phrase();
                p.phrase = c.getString(0);
                p.zh = c.getString(1);
                p.score = c.getInt(2);
                if (p.phrase != null && !p.phrase.isEmpty() && p.zh != null && !p.zh.isEmpty()) {
                    out.add(p);
                }
            }
            c.close();
            c = null;
            // 第二段：随机补一些，打散重复感
            if (out.size() < limit) {
                c = db.rawQuery("SELECT phrase, zh, score FROM phrases "
                                + "WHERE phrase NOT LIKE '% % %' AND length(zh) <= 12 "
                                + "ORDER BY RANDOM() LIMIT ?",
                        new String[]{String.valueOf(limit * 4)});
                while (c.moveToNext() && out.size() < limit * 2) {
                    Phrase p = new Phrase();
                    p.phrase = c.getString(0);
                    p.zh = c.getString(1);
                    p.score = c.getInt(2);
                    if (p.phrase == null || p.phrase.isEmpty()) continue;
                    if (p.zh == null || p.zh.isEmpty()) continue;
                    boolean dup = false;
                    for (Phrase q : out) {
                        if (p.phrase.equals(q.phrase)) { dup = true; break; }
                    }
                    if (!dup) out.add(p);
                }
            }
        } catch (Throwable ignored) { JLog.w("DictDb.phrasePool", ignored);
         } finally {
            if (c != null) c.close();
        }
        return out;
    }

    /** 搭配库里一共有多少条（设置页/统计用）；老词典返回 0 */
    public int phraseCount() {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM phrases", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable ignored) { JLog.w("DictDb.phraseCount", ignored);
            return 0;
        }
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
        try (Cursor c = db.rawQuery(
                "SELECT word FROM stardict WHERE frq > 0 " +
                        "AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\" " +
                        "ORDER BY frq LIMIT ?",
                new String[]{String.valueOf(n)})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    // ================= 背词顺序：六级词表按词频分档 =================

    /**
     * 出题口径：纯小写单词、有中文释义、有词频数据。
     * cet6Count / cet6Page 共用这一份条件，改口径只改这里（改了进度分母也会跟着变）。
     */
    private static final String CET6_WHERE =
            "(' '||ifnull(tag,'')||' ') LIKE '% cet6 %' AND frq > 0"
            + " AND translation IS NOT NULL AND translation <> ''"
            + " AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\""
            + " AND word NOT GLOB '*[^a-z]*'"
            + " AND length(word) >= 3 AND length(word) <= 20";

    /**
     * 六级可用词总数——背词进度的分母。
     * 词典随 APK 打包，运行期一条都不会变，可这个 COUNT(*) 带 LIKE，问一次就是一次全表扫描，
     * 而出每组题、进每次小游戏、开每次面板都要问它。所以查过一次就存下来（-1 表示还没查过），
     * 代价是一个 int。写它的只有 dbPool 那一条线；volatile 不是为锁，
     * 是为了以后哪天界面线程来读时不会拿到一个永远看不见的旧值。
     */
    private volatile int cet6TotalCache = -1;

    public int cet6Count() {
        if (cet6TotalCache >= 0) return cet6TotalCache;
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM stardict WHERE " + CET6_WHERE, null)) {
            cet6TotalCache = c.moveToFirst() ? c.getInt(0) : 0;
        }
        return cet6TotalCache;
    }

    /**
     * 六级词表按词频从高到低分页（offset 从 0 开始）。
     * frq 是 ECDICT 的当代语料库词频序号，越小越常用，所以 ORDER BY frq 就是"高频在前"。
     * 同频次再按字母排，保证每次分页顺序一致（否则进度的游标会抖）。
     */
    public ArrayList<String> cet6Page(int offset, int limit) {
        ArrayList<String> out = new ArrayList<>();
        if (limit <= 0) return out;
        try (Cursor c = db.rawQuery(
                "SELECT word FROM stardict WHERE " + CET6_WHERE
                        + " ORDER BY frq, word LIMIT ? OFFSET ?",
                new String[]{String.valueOf(limit), String.valueOf(Math.max(0, offset))})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    /**
     * 配对小游戏的第五档：不是六级词，但释义干净、有词频、是纯单词。
     * 按词频从常用到生僻 —— 六级词刷完之后接着出这些，别一上来就丢冷僻词。
     */
    private static final String OTHER_WHERE =
            "frq > 0 AND (tag IS NULL OR (' '||tag||' ') NOT LIKE '% cet6 %')"
            + " AND translation IS NOT NULL AND translation <> ''"
            + " AND word NOT LIKE '% %' AND word NOT LIKE '%-%' AND word NOT LIKE \"%'%\""
            + " AND word NOT GLOB '*[^a-z]*'"
            + " AND length(word) >= 3 AND length(word) <= 20";

    public ArrayList<String> otherPage(int offset, int limit) {
        ArrayList<String> out = new ArrayList<>();
        if (limit <= 0) return out;
        try (Cursor c = db.rawQuery(
                "SELECT word FROM stardict WHERE " + OTHER_WHERE
                        + " ORDER BY frq, word LIMIT ? OFFSET ?",
                new String[]{String.valueOf(limit), String.valueOf(Math.max(0, offset))})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.US);
    }

    /** 把用户输入里的 LIKE 通配符（% _ 和转义符本身）压平，查询串只当普通文本匹配 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** 联想列表的行：只读它真正会显示的 4 列（definition 那个英文释义长块根本不碰） */
    private static Entry readSuggestRow(Cursor c, boolean withDetail) {
        Entry e = new Entry();
        e.word = c.getString(0);
        if (withDetail) {
            e.translation = c.getString(1);
            e.tag = c.getString(2);
            e.frq = c.getInt(3);
        }
        return e;
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
