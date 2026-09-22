package com.jianyi.dict;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;

/** 查词历史：本地 SQLite，最近 200 条 */
public class HistoryDb extends SQLiteOpenHelper {

    private static final String DB_NAME = "history.db";
    private static final int VERSION = 1;

    public HistoryDb(Context ctx) {
        super(ctx, DB_NAME, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, word TEXT UNIQUE, ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        db.execSQL("DROP TABLE IF EXISTS history");
        onCreate(db);
    }

    /** 记一次查询；重复词自动置顶 */
    public void add(String word) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("history", "word = ?", new String[]{word});
        ContentValues v = new ContentValues();
        v.put("word", word);
        v.put("ts", System.currentTimeMillis());
        db.insert("history", null, v);
        // 只保留最近 200 条
        db.execSQL("DELETE FROM history WHERE id NOT IN " +
                "(SELECT id FROM history ORDER BY ts DESC LIMIT 200)");
    }

    public ArrayList<String> recent(int limit) {
        ArrayList<String> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT word FROM history ORDER BY ts DESC LIMIT " + limit, null);
        while (c.moveToNext()) out.add(c.getString(0));
        c.close();
        return out;
    }

    public void clear() {
        getWritableDatabase().delete("history", null, null);
    }

    public void delete(String word) {
        getWritableDatabase().delete("history", "word = ?", new String[]{word});
    }
}
