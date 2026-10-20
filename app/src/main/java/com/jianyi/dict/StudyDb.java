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

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long[] GAPS = {DAY, 2 * DAY, 4 * DAY, 8 * DAY, 16 * DAY};

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
        db.execSQL("DELETE FROM game_pending");
        if (rows == null) return;
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r == null || r.length < 2 || r[0] == null) continue;
            int lv = 0;
            try {
                if (r.length > 2) lv = Integer.parseInt(r[2]);
            } catch (NumberFormatException ignore) {
            }
            db.execSQL("INSERT OR REPLACE INTO game_pending(ord,word,zh,level) VALUES(?,?,?,?)",
                    new Object[]{i, r[0], r[1] == null ? "" : r[1], lv});
        }
    }

    public ArrayList<String[]> loadGamePending() {
        ArrayList<String[]> out = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT word, zh, level FROM game_pending ORDER BY ord", null);
        while (c.moveToNext()) {
            out.add(new String[]{c.getString(0), c.getString(1), String.valueOf(c.getInt(2))});
        }
        c.close();
        return out;
    }

    /** 整局重来：清进度和残留牌面（错词回炉队列不动，那是学习记录） */
    public void clearGameState() {
        db.execSQL("DELETE FROM game_state");
        db.execSQL("DELETE FROM game_pending");
    }


    // ================= 背词进度（v3.5）=================

    /** 这个词背过了（出题即记，不要求答对） */
    public void seen(String w, int band) {
        if (w == null || w.isEmpty()) return;
        db.execSQL("INSERT OR IGNORE INTO learn(word, band, ts) VALUES(?,?,?)",
                new Object[]{w, band, System.currentTimeMillis()});
    }

    public boolean isSeen(String w) {
        if (w == null) return false;
        Cursor c = db.rawQuery("SELECT 1 FROM learn WHERE word=? LIMIT 1", new String[]{w});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    /** 已背过的全部词（出题时用来跳过，避免重复出现） */
    public HashSet<String> seenSet() {
        HashSet<String> out = new HashSet<>();
        Cursor c = db.rawQuery("SELECT word FROM learn", null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    /** 某档已背多少词；band < 0 表示全部 */
    public int seenCount(int band) {
        Cursor c = band < 0
                ? db.rawQuery("SELECT COUNT(*) FROM learn", null)
                : db.rawQuery("SELECT COUNT(*) FROM learn WHERE band=?",
                        new String[]{String.valueOf(band)});
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }

    /** 清空背词进度（重新开始背，不影响收藏和错题） */
    public void resetProgress() {
        db.execSQL("DELETE FROM learn");
    }

    /** 背过的词，最近背的排前面（配对小游戏的词池就取这个） */
    public ArrayList<String> learnedWords(int limit) {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = db.rawQuery("SELECT word FROM learn ORDER BY ts DESC LIMIT ?",
                new String[]{String.valueOf(Math.max(1, limit))});
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
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
            } catch (SQLException ignore) { }
        }
    }

    // ================= 复习调度（原有语义不变：加入 Leitner 复习，不算"收藏"）=================

    /** 标记为陌生词（首次收藏：立刻到期，下一组就会出现） */
    public void mark(String w) {
        if (w == null || w.isEmpty()) return;
        db.execSQL("INSERT OR IGNORE INTO fav(word, box, due, wrong, right) VALUES(?,0,0,0,0)",
                new Object[]{w});
    }

    public boolean isMarked(String w) {
        if (w == null) return false;
        Cursor c = db.rawQuery("SELECT 1 FROM fav WHERE word=? LIMIT 1", new String[]{w});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

    // ================= 收藏（v3.0 新增：进「单词本」，与复习调度解耦）=================

    /** 主动收藏：确保行存在并置 fav_flag=1 */
    public void favorite(String w) {
        if (w == null || w.isEmpty()) return;
        db.execSQL("INSERT OR IGNORE INTO fav(word, box, due, wrong, right, fav_flag) VALUES(?,0,0,0,0,1)",
                new Object[]{w});
        db.execSQL("UPDATE fav SET fav_flag=1 WHERE word=?", new Object[]{w});
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
        if (w == null || !isMarked(w)) return;
        Cursor c = db.rawQuery("SELECT box FROM fav WHERE word=?", new String[]{w});
        int box = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        box = ok ? Math.min(box + 1, GAPS.length - 1) : 0;
        long due = System.currentTimeMillis() + GAPS[box];
        db.execSQL("UPDATE fav SET box=?, due=?, wrong=wrong+?, right=right+? WHERE word=?",
                new Object[]{box, due, ok ? 0 : 1, ok ? 1 : 0, w});
    }

    /** 到期的陌生词（该复习的），最多 n 个 */
    public ArrayList<String> dueWords(int n) {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = db.rawQuery(
                "SELECT word FROM fav WHERE due<=? ORDER BY due LIMIT ?",
                new String[]{String.valueOf(System.currentTimeMillis()), String.valueOf(n)});
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }
}
