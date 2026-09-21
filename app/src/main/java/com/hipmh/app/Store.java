package com.hipmh.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public final class Store extends SQLiteOpenHelper {

    /** 书架条目状态（fav.st） */
    public static final int ST_NONE = 0;   // 未读（默认，刚收藏）
    public static final int ST_READING = 1; // 在看
    public static final int ST_DONE = 2;    // 已读完

    public static final class Item {
        public final String title;
        public final String url;
        public final long ts;
        /** 书架状态：{@link #ST_NONE} / {@link #ST_READING} / {@link #ST_DONE} */
        public final int st;
        /** ★ v1.9：封面图 URL（站点 cdn，可能为 null） */
        public final String cover;
        /** ★ v2.0.0：最近读到的章节号（0 = 还没读过） */
        public final int ch;
        /** ★ v2.0.0：最近读到的章节标题（如「第12话」，可能为 null） */
        public final String chTitle;
        /** ★ v2.0.0：作品总章数（0 = 未知） */
        public final int chTotal;
        /** ★ v2.0.0：本章 API hid（{@code c:<章节id>} 形态，用于还原「继续阅读」链接） */
        public final String chHid;

        Item(String t, String u, long s) {
            this(t, u, s, ST_NONE, null);
        }

        Item(String t, String u, long s, int st) {
            this(t, u, s, st, null);
        }

        Item(String t, String u, long s, int st, String cover) {
            this(t, u, s, st, cover, 0, null, 0, null);
        }

        Item(String t, String u, long s, int st, String cover,
             int ch, String chTitle, int chTotal) {
            this(t, u, s, st, cover, ch, chTitle, chTotal, null);
        }

        Item(String t, String u, long s, int st, String cover,
             int ch, String chTitle, int chTotal, String chHid) {
            title = t;
            url = u;
            ts = s;
            this.st = st;
            this.cover = cover;
            this.ch = ch;
            this.chTitle = chTitle;
            this.chTotal = chTotal;
            this.chHid = chHid;
        }
    }

    private static final String DB = "hip.db";
    /**
     * v2：fav 表新增 st 列（书架手动标记：未读 / 在看 / 已读完）。
     * v3：fav 表新增 cover 列（书架列表显示封面图）。
     * v4：fav 表新增 ch / ch_title / ch_total / ch_hid 列（阅读进度镜像，书架展示用）。
     * ★ v2.0.0 (v5)：新增 prog 表 —— 所有读过的书都记阅读进度（不依赖收藏）。
     */
    private static final int VER = 5;

    Store(Context c) {
        super(c.getApplicationContext(), DB, null, VER);
    }

    @Override
    public void onCreate(SQLiteDatabase d) {
        // ★ v1.7：hist 表已废弃（浏览记录功能整体下线），这里不再建。
        //   老库里的 hist 表留着不删也无害，只是没人再读它。
        d.execSQL("CREATE TABLE IF NOT EXISTS fav("
                + "url TEXT PRIMARY KEY, title TEXT, ts INTEGER, st INTEGER DEFAULT 0,"
                + " cover TEXT,"
                + " ch INTEGER DEFAULT 0, ch_title TEXT, ch_total INTEGER DEFAULT 0,"
                + " ch_hid TEXT)");
        // ★ v2.0.0：阅读进度表 —— **所有读过的书都记**（不管有没有收藏），
        //   详情页「继续阅读」按钮从这读；fav 表里的进度列只是镜像（书架展示用）。
        d.execSQL("CREATE TABLE IF NOT EXISTS prog("
                + "url TEXT PRIMARY KEY, ch INTEGER DEFAULT 0, ch_title TEXT,"
                + " ch_total INTEGER DEFAULT 0, ch_hid TEXT, ts INTEGER)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase d, int o, int n) {
        if (o < 2) {
            // 老库补列；已升级过的会抛「duplicate column」，忽略即可
            try {
                d.execSQL("ALTER TABLE fav ADD COLUMN st INTEGER DEFAULT 0");
            } catch (Throwable ignore) {
            }
        }
        if (o < 3) {
            // ★ v1.9：补 cover 列（书架列表显示封面）
            try {
                d.execSQL("ALTER TABLE fav ADD COLUMN cover TEXT");
            } catch (Throwable ignore) {
            }
        }
        if (o < 4) {
            // ★ v2.0.0：补阅读进度四列（章节号 / 章节标题 / 总章数 / 本章 API hid）
            try {
                d.execSQL("ALTER TABLE fav ADD COLUMN ch INTEGER DEFAULT 0");
            } catch (Throwable ignore) {
            }
            try {
                d.execSQL("ALTER TABLE fav ADD COLUMN ch_title TEXT");
            } catch (Throwable ignore) {
            }
            try {
                d.execSQL("ALTER TABLE fav ADD COLUMN ch_total INTEGER DEFAULT 0");
            } catch (Throwable ignore) {
            }
            try {
                d.execSQL("ALTER TABLE fav ADD COLUMN ch_hid TEXT");
            } catch (Throwable ignore) {
            }
        }
        if (o < 5) {
            // ★ v2.0.0：阅读进度独立表（所有读过的书都记，不依赖收藏）
            try {
                d.execSQL("CREATE TABLE IF NOT EXISTS prog("
                        + "url TEXT PRIMARY KEY, ch INTEGER DEFAULT 0, ch_title TEXT,"
                        + " ch_total INTEGER DEFAULT 0, ch_hid TEXT, ts INTEGER)");
            } catch (Throwable ignore) {
            }
        }
        onCreate(d);
    }

    void put(String table, String title, String url) {
        put(table, title, url, null);
    }

    /**
     * ★ v1.9：写入收藏（带封面）。
     *
     * <p>cover 为空时**不覆盖**已有封面 —— 否则从阅读页收藏（拿不到封面）会把
     * 之前从详情页存下的封面冲掉。
     */
    void put(String table, String title, String url, String cover) {
        if (url == null || url.isEmpty()) return;
        try {
            SQLiteDatabase d = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("url", url);
            v.put("title", title == null ? url : title);
            v.put("ts", System.currentTimeMillis());
            if (cover != null && !cover.isEmpty()) {
                v.put("cover", cover);
            } else {
                // 保留老封面：先查一把，有就不动
                String old = coverOf(url);
                if (old != null && !old.isEmpty()) v.put("cover", old);
            }
            d.insertWithOnConflict(table, null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable ignore) {
        }
    }

    /** 单独补写封面（详情页解析出封面后回填） */
    void setCover(String url, String cover) {
        if (url == null || url.isEmpty() || cover == null || cover.isEmpty()) return;
        try {
            SQLiteDatabase d = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("cover", cover);
            d.update("fav", v, "url=?", new String[]{url});
        } catch (Throwable ignore) {
        }
    }

    /**
     * ★ v2.0.0：保存阅读进度。
     *
     * <p><b>prog 表所有读过的书都写</b>（不依赖收藏 —— 详情页「继续阅读」对
     * 任何读过的书都生效）；fav 表的进度列只在这本书已收藏时镜像一份
     * （书架展示用，没收藏绝不往书架塞数据）。
     * num &lt;= 0 视为无效进度直接忽略；total &lt;= 0 / hid 为空时保留旧值。
     * 章号回退（重读旧章）也照写 —— 「继续阅读」以最后一次实际打开的章为准。
     */
    void saveProgress(String url, int num, String chTitle, int total, String chHid) {
        if (url == null || url.isEmpty() || num <= 0) return;
        try {
            SQLiteDatabase d = getWritableDatabase();
            // 1) prog 表：无条件写（upsert，保留旧 total/hid 当新值为空）
            ContentValues p = new ContentValues();
            p.put("url", url);
            p.put("ch", num);
            if (chTitle != null && !chTitle.isEmpty()) p.put("ch_title", chTitle);
            if (total > 0) p.put("ch_total", total);
            if (chHid != null && !chHid.isEmpty()) p.put("ch_hid", chHid);
            p.put("ts", System.currentTimeMillis());
            d.insertWithOnConflict("prog", null, p, SQLiteDatabase.CONFLICT_REPLACE);
            // 2) fav 表：只镜像已收藏的
            if (has("fav", url)) {
                ContentValues v = new ContentValues();
                v.put("ch", num);
                if (chTitle != null && !chTitle.isEmpty()) v.put("ch_title", chTitle);
                if (total > 0) v.put("ch_total", total);
                if (chHid != null && !chHid.isEmpty()) v.put("ch_hid", chHid);
                d.update("fav", v, "url=?", new String[]{url});
            }
        } catch (Throwable ignore) {
        }
    }

    /** 读某本书的阅读进度（prog 优先，老数据回退 fav；没有记录返回 null） */
    Progress progressOf(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT ch, ch_title, ch_total, ch_hid FROM prog WHERE url=?", new String[]{url});
            Progress r = null;
            if (c.moveToFirst()) {
                int n = c.getInt(0);
                if (n > 0) r = new Progress(n, c.getString(1), c.getInt(2), c.getString(3));
            }
            c.close();
            if (r != null) return r;
            // 老库回退：v2.0.0 之前进度只写 fav
            c = getReadableDatabase().rawQuery(
                    "SELECT ch, ch_title, ch_total, ch_hid FROM fav WHERE url=?", new String[]{url});
            if (c.moveToFirst()) {
                int n = c.getInt(0);
                if (n > 0) r = new Progress(n, c.getString(1), c.getInt(2), c.getString(3));
            }
            c.close();
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 阅读进度快照 */
    static final class Progress {
        final int ch;
        final String chTitle;
        final int chTotal;
        final String chHid;

        Progress(int ch, String chTitle, int chTotal, String chHid) {
            this.ch = ch;
            this.chTitle = chTitle;
            this.chTotal = chTotal;
            this.chHid = chHid;
        }
    }

    /** 读某条收藏当前的封面 */
    String coverOf(String url) {
        try {
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT cover FROM fav WHERE url=?", new String[]{url});
            String r = c.moveToFirst() ? c.getString(0) : null;
            c.close();
            return r;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 改书架条目的阅读状态（{@link #ST_NONE} / {@link #ST_READING} / {@link #ST_DONE}） */
    void setFavStatus(String url, int st) {
        if (url == null || url.isEmpty()) return;
        try {
            SQLiteDatabase d = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("st", st);
            d.update("fav", v, "url=?", new String[]{url});
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
            // ★ v1.7：只服务书架（fav）。传别的表名一律当 fav，避免遗留调用读到空/旧表。
            Cursor c = getReadableDatabase().rawQuery(
                    "SELECT title,url,ts,st,cover,ch,ch_title,ch_total,ch_hid"
                            + " FROM fav ORDER BY ts DESC LIMIT 300", null);
            while (c.moveToNext()) {
                int st = c.getInt(3);
                if (st != ST_READING && st != ST_DONE) st = ST_NONE;
                out.add(new Item(c.getString(0), c.getString(1), c.getLong(2), st,
                        c.getString(4), c.getInt(5), c.getString(6), c.getInt(7),
                        c.getString(8)));
            }
            c.close();
        } catch (Throwable ignore) {
        }
        return out;
    }
}
