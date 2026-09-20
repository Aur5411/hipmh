package com.hipmh.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public final class Store extends SQLiteOpenHelper {

    public static final class Item {
        public final String title;
        public final String url;
        public final long ts;

        Item(String t, String u, long s) {
            title = t;
            url = u;
            ts = s;
        }
    }

    private static final String DB = "hip.db";
    private static final int VER = 1;

    Store(Context c) {
        super(c.getApplicationContext(), DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE IF NOT EXISTS hist("
                + "url TEXT PRIMARY KEY, title TEXT, ts INTEGER)");
        d.execSQL("CREATE TABLE IF NOT EXISTS fav("
                + "url TEXT PRIMARY KEY, title TEXT, ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int o, int n) {
        onCreate(d);
    }

    void put(String table, String title, String url) {
        if (url == null || url.isEmpty()) return;
        try {
            SQLiteDatabase d = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("url", url);
            v.put("title", title == null ? url : title);
            v.put("ts", System.currentTimeMillis());
            d.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable ignore) {
        }
    }

    void remove(String table, String url) {
        try {
            getWritableDatabase().delete(table, "url=?", new String[]{url});
        } catch (Throwable ignore) {
        }
    }

    boolean has(String table, String url) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT 1 FROM " + table + " WHERE url=?", new String[]{url});
            boolean r = c.moveToFirst();
            c.close();
            return r;
        } catch (Throwable e) {
            return false;
        }
    }

    void clear(String table) {
        try {
            getWritableDatabase().delete(table, null, null);
        } catch (Throwable ignore) {
        }
    }

    List<Item> list(String table) {
        List<Item> out = new ArrayList<>();
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT title,url,ts FROM " + table + " ORDER BY ts DESC LIMIT 300", null);
            while (c.moveToNext()) {
                out.add(new Item(c.getString(0), c.getString(1), c.getLong(2)));
            }
            c.close();
        } catch (Throwable ignore) {
        }
        return out;
    }
}
