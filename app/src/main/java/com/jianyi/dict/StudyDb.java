package com.jianyi.dict;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;

/**
 * 学习进度库：陌生词收藏 + Leitner 盒子复习调度。
 *
 * Leitner 盒子：答对进下一格（隔更久再来），答错退回第一格（明天就来）。
 * 间隔按格数递增：1天 → 2天 → 4天 → 8天 → 16天。
 * 没上完整的 SM-2 算法，个人项目用不到那么精细，这套够用且好调。
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
                + "right INTEGER DEFAULT 0)");
    }

    /** 标记为陌生词（首次收藏：立刻到期，下一组就会出现） */
    public void mark(String w) {
        if (w == null || w.isEmpty()) return;
        db.execSQL("INSERT OR IGNORE INTO fav(word, box, due, wrong, right) VALUES(?,0,0,0,0)",
                new Object[]{w});
    }

    public void unmark(String w) {
        if (w == null) return;
        db.execSQL("DELETE FROM fav WHERE word=?", new Object[]{w});
    }

    public boolean isMarked(String w) {
        if (w == null) return false;
        Cursor c = db.rawQuery("SELECT 1 FROM fav WHERE word=? LIMIT 1", new String[]{w});
        boolean has = c.moveToFirst();
        c.close();
        return has;
    }

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

    /** 陌生词总数（用于显示） */
    public int markedCount() {
        Cursor c = db.rawQuery("SELECT COUNT(*) FROM fav", null);
        int n = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return n;
    }
}
