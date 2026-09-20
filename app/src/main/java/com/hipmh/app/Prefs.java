package com.hipmh.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class Prefs {

    public static final String HOME = "https://m.hipmh.com/";

    private static final String N = "hip";
    private static final String K_KEEP = "keep_screen";
    private static final String K_AD = "ad_block";
    private static final String K_VOL = "volume_page";
    private static final String K_IMM = "immersive_read";
    private static final String K_DESK = "desktop_ua";
    private static final String K_ZOOM = "text_zoom";
    private static final String K_CACHE = "cache_assets";
    private static final String K_LINE = "img_line";
    private static final String K_PRELOAD = "preload_chapter";

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(N, Context.MODE_PRIVATE);
    }

    boolean keepScreen() {
        return sp.getBoolean(K_KEEP, true);
    }

    void keepScreen(boolean v) {
        sp.edit().putBoolean(K_KEEP, v).apply();
    }

    boolean adBlock() {
        return sp.getBoolean(K_AD, true);
    }

    void adBlock(boolean v) {
        sp.edit().putBoolean(K_AD, v).apply();
    }

    boolean volumePage() {
        return sp.getBoolean(K_VOL, true);
    }

    void volumePage(boolean v) {
        sp.edit().putBoolean(K_VOL, v).apply();
    }

    boolean immersiveRead() {
        return sp.getBoolean(K_IMM, true);
    }

    void immersiveRead(boolean v) {
        sp.edit().putBoolean(K_IMM, v).apply();
    }

    boolean desktopUa() {
        return sp.getBoolean(K_DESK, false);
    }

    void desktopUa(boolean v) {
        sp.edit().putBoolean(K_DESK, v).apply();
    }

    int textZoom() {
        return sp.getInt(K_ZOOM, 100);
    }

    void textZoom(int v) {
        sp.edit().putInt(K_ZOOM, v).apply();
    }

    boolean cacheAssets() {
        return sp.getBoolean(K_CACHE, true);
    }

    void cacheAssets(boolean v) {
        sp.edit().putBoolean(K_CACHE, v).apply();
    }

    String imgLine() {
        return sp.getString(K_LINE, "line1");
    }

    void imgLine(String v) {
        sp.edit().putString(K_LINE, v).apply();
    }

    /** ★ v1.4：进入章节后，原生线程池并发预抓本章图片进 ResCache，翻页零等待 */
    boolean preloadChapter() {
        return sp.getBoolean(K_PRELOAD, true);
    }

    void preloadChapter(boolean v) {
        sp.edit().putBoolean(K_PRELOAD, v).apply();
    }
}
