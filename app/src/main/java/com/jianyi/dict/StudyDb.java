package com.jianyi.dict;

import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;

/**
 * 学习进度库：陌生词收藏 + Leitner 盒子复习调度。
 *
 * Leitner 盒子：答对进下一格（隔更久再来），答错退回第一格（明天就来）。
 * 间隔按格数递增：1天 → 2天 → 4天 → 8天 → 16天。
 * 没上完整的 SM-2 算法，个人项目用不到那么精细，这套够用且好调。
 *
 * v3.0 起把「收藏」和「错词」拆成两个概念（都还在 fav 一张表里，用字段区分）：
 *   fav_flag = 1        主动收藏 → 进「单词本」（查词页收藏按钮）
 *   wrong    > 0        答错过   → 进「错题集」（记背答错自动记）
 * 两者可以有交集：先收藏、后来答题又答错的词，两个本里都会出现。
 * 复习调度（box/due）是另一套逻辑，和这两个本解耦，互不影响。
 */
public class StudyDb {

    // 复习间隔的算法在 Leitner（纯计算、可单测），这里只负责把它落进 fav 表。

    private final SQLiteDatabase db;

    public StudyDb(Context ctx) {
        File f = new File(ctx.getFilesDir(), "study.db");
        db = SQLiteDatabase.openOrCreateDatabase(f, null);
        db.execSQL("CREATE TABLE IF NOT EXISTS fav("
                + "word TEXT PRIMARY KEY,"
                + "box INTEGER DEFAULT 0,"
                + "due INTEGER DEFAULT 0,"
                + "wrong INTEGER DEFAULT 0,"
                + "right INTEGER DEFAULT 0,"
                + "fav_flag INTEGER DEFAULT 0)");   // v3.0：主动收藏标记，1=进「单词本」
        ensureFavFlag();   // 老库平滑升级：缺列就补，绝不清数据
        // v3.5：背词进度。band 0=高频 1=中频 2=低频（按六级词表词频三等分）
        db.execSQL("CREATE TABLE IF NOT EXISTS learn("
                + "word TEXT PRIMARY KEY,"
                + "band INTEGER DEFAULT 0,"
                + "ts INTEGER DEFAULT 0)");
        // v3.8.2：配对游戏的错词回炉存档。remain = 还要再过几屏才回来
        db.execSQL("CREATE TABLE IF NOT EXISTS game_wrong("
                + "word TEXT PRIMARY KEY,"
                + "zh TEXT,"
                + "remain INTEGER DEFAULT 0)");
        // v3.9：整局游戏进度存档（词序游标 / 分数 / 没消完的牌），退出再进接着玩
        db.execSQL("CREATE TABLE IF NOT EXISTS game_state("
                + "k TEXT PRIMARY KEY,"
                + "v INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS game_pending("
                + "ord INTEGER PRIMARY KEY,"
                + "word TEXT,"
                + "zh TEXT,"
                + "level INTEGER DEFAULT 0)");
    }

    /**
     * Activity 销毁时归还句柄。不关的话每次 Activity 重建（系统回收进程后重进、
     * 语言/字体等配置变化）都会多一个打开的 study.db，直到进程退出。
     */
    public void close() {
        try {
            db.close();
        } catch (Throwable t) {
            JLog.w("StudyDb.close", t);
        }
    }

    // ================= 小游戏整局存档（v3.9）=================

    public void setGameState(String k, int v) {
        db.execSQL("INSERT OR REPLACE INTO game_state(k,v) VALUES(?,?)", new Object[]{k, v});
    }

    public int getGameState(String k, int def) {
        Cursor c = db.rawQuery("SELECT v FROM game_state WHERE k=?", new String[]{k});
        int v = c.moveToFirst() ? c.getInt(0) : def;
        c.close();
        return v;
    }

    /** 存"还没消完的牌面"：每行 {word, zh, level}，顺序即牌序 */
    public void saveGamePending(ArrayList<String[]> rows) {
        // 清空 + 重插在同一个事务里：中途被杀不会留下"牌面只剩一半"的存档
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM game_pending");
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    String[] r = rows.get(i);
                    if (r == null || r.length < 2 || r[0] == null) continue;
                    int lv = 0;
                    try {
                        if (r.length > 2) lv = Integer.parseInt(r[2]);
                    } catch (NumberFormatException ignore) { JLog.w("StudyDb.saveGamePending", ignore);
                     }
                    db.execSQL("INSERT OR REPLACE INTO game_pending(ord,word,zh,level) VALUES(?,?,?,?)",
                            new Object[]{i, r[0], r[1] == null ? "" : r[1], lv});
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public ArrayList<String[]> loadGamePending() {
        ArrayList<String[]> out = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT word, zh, level FROM game_pending ORDER BY ord", null)) {
            while (c.moveToNext()) {
                out.add(new String[]{c.getString(0), c.getString(1), String.valueOf(c.getInt(2))});
            }
        }
        return out;
    }

    /** 整局重来：清进度和残留牌面（错词回炉队列不动，那是学习记录） */
    public void clearGameState() {
        db.beginTransaction();
        try {
            db.execSQL("DELETE FROM game_state");
            db.execSQL("DELETE FROM game_pending");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }


    // ================= 背词进度（v3.5）=================

    /** 这个词背过了（出题即记，不要求答对） */
    public void seen(String w, int band) {
        if (w == null || w.isEmpty()) return;
        db.execSQL("INSERT OR IGNORE INTO learn(word, band, ts) VALUES(?,?,?)",
                new Object[]{w, band, System.currentTimeMillis()});
    }

    /** 已背过的全部词（出题时用来跳过，避免重复出现） */
    public HashSet<String> seenSet() {
        HashSet<String> out = new HashSet<>();
        Cursor c = db.rawQuery("SELECT word FROM learn", null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    /**
     * 三档已背数 + 总数，一条查询出齐。
     * 原来是每档 COUNT(*) 跑一遍（外加 band<0 再数一次全表），面板一次刷新要 4 趟查询；
     * GROUP BY 扫 learn 一遍就够了，反正这表迟早要全扫。
     * 返回 {高频, 中频, 低频, 全部}；band 落在 0..2 之外的行只进总数，
     * 免得旧版本留下的脏数据凭空消失。
     */
    public int[] seenSummary() {
        int[] out = new int[4];
        try (Cursor c = db.rawQuery("SELECT band, COUNT(*) FROM learn GROUP BY band", null)) {
            while (c.moveToNext()) {
                int n = c.getInt(1);
                int band = c.getInt(0);
                if (band >= 0 && band <= 2) out[band] = n;
                out[3] += n;
            }
        }
        return out;
    }

    /** 清空背词进度（重新开始背，不影响收藏和错题） */
    public void resetProgress() {
        db.execSQL("DELETE FROM learn");
    }

    // ================= 配对游戏错词回炉（v3.8.2 存档）=================

    /**
     * 记一个错词：remain 屏之后再回来一次。
     * 同一个词重复错就覆盖成新的倒计时，不叠着排队；存的是单词 + 当时的中文释义，
     * 回来时不用重新查词典（也保证和当初看到的释义一致）。
     */
    public void saveGameWrong(String word, String zh, int remain) {
        if (word == null || word.isEmpty()) return;
        db.execSQL("INSERT OR REPLACE INTO game_wrong(word, zh, remain) VALUES(?,?,?)",
                new Object[]{word, zh == null ? "" : zh, Math.max(0, remain)});
    }

    public int countGameWrong() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM game_wrong", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 每过一屏减一次倒计时（只减还没到期的） */
    public void tickGameWrong() {
        db.execSQL("UPDATE game_wrong SET remain = remain - 1 WHERE remain > 0");
    }

    /** 到期的错词，最多 n 个，先排最早的 */
    public ArrayList<String[]> dueGameWrong(int n) {
        ArrayList<String[]> out = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT word, zh FROM game_wrong WHERE remain <= 0 ORDER BY rowid LIMIT ?",
                new String[]{String.valueOf(Math.max(1, n))});
        while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
        c.close();
        return out;
    }

    /** 出过就销号 */
    public void removeGameWrong(String word) {
        if (word == null) return;
        db.execSQL("DELETE FROM game_wrong WHERE word=?", new Object[]{word});
    }

    public boolean hasGameWrong(String word) {
        if (word == null || word.isEmpty()) return false;
        Cursor c = db.rawQuery("SELECT 1 FROM game_wrong WHERE word=? LIMIT 1",
                new String[]{word});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    /**
     * 待回炉清单：词、当时看到的中文、还剩几屏。
     * 排在前面的先到期（remain 小），一眼能看出「马上要回来的」是哪几个。
     */
    public ArrayList<String[]> gameWrongList() {
        ArrayList<String[]> out = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT word, zh, remain FROM game_wrong ORDER BY remain ASC, rowid ASC", null);
        while (c.moveToNext()) {
            out.add(new String[]{c.getString(0), c.getString(1), String.valueOf(c.getInt(2))});
        }
        c.close();
        return out;
    }

    /** 老版本没有 fav_flag 列，补上；已存在则跳过 */
    private void ensureFavFlag() {
        Cursor c = db.rawQuery("PRAGMA table_info(fav)", null);
        boolean has = false;
        while (c.moveToNext()) {
            if ("fav_flag".equals(c.getString(1))) { has = true; break; }
        }
        c.close();
        if (!has) {
            try {
                db.execSQL("ALTER TABLE fav ADD COLUMN fav_flag INTEGER DEFAULT 0");
            } catch (SQLException ignore) { JLog.w("StudyDb.ensureFavFlag", ignore);  }
        }
    }

    // ================= 复习调度（原有语义不变：加入 Leitner 复习，不算"收藏"）=================

    /** 标记为陌生词（首次收藏：立刻到期，下一组就会出现） */
    public void mark(String w) {
        if (w == null || w.isEmpty()) return;
        db.execSQL("INSERT OR IGNORE INTO fav(word, box, due, wrong, right) VALUES(?,0,0,0,0)",
                new Object[]{w});
    }

    /**
     * 「其实没记住」：把词退回第一格并立刻到期，下一组就会再来。
     *
     * 这里不能用 mark()——它是 INSERT OR IGNORE，对已在表里的词是条空语句，
     * 而答对时 correct() 已经把 box 升过一格、due 推到十几天后，
     * 用户点了"没记住"却什么也没发生。
     * 也不能用 wrong()——它会 wrong+1，把词塞进「错题集」；用户明明答对了，
     * 只是自觉不牢，按 v3.0 的划分只该动 box/due，不动 wrong/right。
     */
    public void relearn(String w) {
        if (w == null || w.isEmpty()) return;
        mark(w);            // 没入过库的词先建行（box=0, due=0）
        db.execSQL("UPDATE fav SET box=0, due=0 WHERE word=?", new Object[]{w});
    }

    // ================= 收藏（v3.0 新增：进「单词本」，与复习调度解耦）=================

    /** 主动收藏：确保行存在并置 fav_flag=1 */
    public void favorite(String w) {
        if (w == null || w.isEmpty()) return;
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO fav(word, box, due, wrong, right, fav_flag) VALUES(?,0,0,0,0,1)",
                    new Object[]{w});
            db.execSQL("UPDATE fav SET fav_flag=1 WHERE word=?", new Object[]{w});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 取消收藏：只清 fav_flag，保留 Leitner / 错词记录不动 */
    public void unfavorite(String w) {
        if (w == null) return;
        db.execSQL("UPDATE fav SET fav_flag=0 WHERE word=?", new Object[]{w});
    }

    public boolean isFavorite(String w) {
        if (w == null) return false;
        Cursor c = db.rawQuery("SELECT 1 FROM fav WHERE word=? AND fav_flag=1 LIMIT 1", new String[]{w});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    /** 收藏的词（「单词本」内容），后进的排前面 */
    public ArrayList<String> favoriteWords() {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT word FROM fav WHERE fav_flag=1 ORDER BY rowid DESC", null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    /** 答错过的词（「错题集」内容），错得多的排前面 */
    public ArrayList<String> wrongWords() {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT word FROM fav WHERE wrong>0 ORDER BY wrong DESC, rowid DESC", null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    /**
     * 从错题集里删掉一个词。
     *
     * 只把 wrong 记零，不动收藏标记和复习进度：这个词是「不再算错题」，
     * 不是「不再收藏」——用户要是想连收藏一起清，词条卡片上的星标还在。
     */
    public void removeWrong(String w) {
        if (w == null || w.isEmpty()) return;
        db.execSQL("UPDATE fav SET wrong=0 WHERE word=?", new Object[]{w});
    }

    // ================= Leitner 复习调度（原有）=================

    /** 答对：进下一格，到期时间往后推。没收藏过的词不做记录 */
    public void correct(String w) {
        step(w, true);
    }

    /** 答错：自动收藏并退回第一格，明天再来 */
    public void wrong(String w) {
        mark(w);
        step(w, false);
    }

    private void step(String w, boolean ok) {
        if (w == null) return;
        // 「读 box → 算新格 → 写回」必须在一个事务里：连点两下答案时，
        // 三条独立语句会读到同一个旧 box，把进步度算丢（也可能写进错误的到期时间）。
        // 收藏状态也在这一个事务里探（SELECT box 为空即未收藏），省一次查询。
        db.beginTransaction();
        try {
            int box = 0;
            try (Cursor c = db.rawQuery("SELECT box FROM fav WHERE word=?", new String[]{w})) {
                if (!c.moveToFirst()) {              // 没收藏过的词不做记录
                    db.setTransactionSuccessful();
                    return;
                }
                box = c.getInt(0);
            }
            box = Leitner.nextBox(box, ok);
            long due = Leitner.dueAt(box, System.currentTimeMillis());
            db.execSQL("UPDATE fav SET box=?, due=?, wrong=wrong+?, right=right+? WHERE word=?",
                    new Object[]{box, due, ok ? 0 : 1, ok ? 1 : 0, w});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * 到期的陌生词（该复习的），最多 n 个。
     *
     * 次级排序键 rowid 不是可选的：所有新 mark 的词 due 都写成 0，
     * 只按 due 排时这一大批全等值的行由 SQLite 随手返回，每组取 4 个就会
     * 反复拿到同几个词，剩下收藏了几百个词的用户永远见不到它们。
     * rowid 是建行顺序、之后不再变，于是到期时间相同的词按加入先后公平轮转。
     */
    public ArrayList<String> dueWords(int n) {
        ArrayList<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT word FROM fav WHERE due<=? ORDER BY due, rowid LIMIT ?",
                new String[]{String.valueOf(System.currentTimeMillis()),
                        String.valueOf(Math.max(0, n))})) {
            while (c.moveToNext()) out.add(c.getString(0));
        }
        return out;
    }
}
