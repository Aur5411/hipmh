package com.hipmh.app;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

public final class ResCache {

    private static final long MAX_AGE = 7L * 24 * 60 * 60 * 1000;
    private static final long MAX_BYTES = 120L * 1024 * 1024;

    private static File dir;

    static synchronized void init(Context c) {
        try {
            if (dir == null) {
                dir = new File(c.getApplicationContext().getCacheDir(), "res");
                if (!dir.exists()) dir.mkdirs();
            }
        } catch (Throwable ignore) {
        }
    }

    static boolean isCacheable(String u) {
        if (u == null) return false;
        String low = u.toLowerCase(Locale.US);
        if (low.startsWith("http") && low.indexOf('/', 8) < 0) return false;
        String p = path(low);
        return p.endsWith(".css") || p.endsWith(".js") || p.endsWith(".mjs")
                || p.endsWith(".png") || p.endsWith(".jpg") || p.endsWith(".jpeg")
                || p.endsWith(".webp") || p.endsWith(".gif") || p.endsWith(".avif")
                || p.endsWith(".svg") || p.endsWith(".ico")
                || p.endsWith(".woff") || p.endsWith(".woff2") || p.endsWith(".ttf")
                || p.endsWith(".otf");
    }

    private static String path(String low) {
        int q = low.indexOf('?');
        return q > 0 ? low.substring(0, q) : low;
    }

    static String mime(String u) {
        String p = path(u.toLowerCase(Locale.US));
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".gif")) return "image/gif";
        if (p.endsWith(".avif")) return "image/avif";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".woff")) return "font/woff";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".otf")) return "font/otf";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        return "application/octet-stream";
    }

    private static File file(String u) {
        return new File(dir, md5(u));
    }

    static byte[] get(String u) {
        try {
            if (dir == null) return null;
            File f = file(u);
            if (f.exists() && f.length() > 0
                    && System.currentTimeMillis() - f.lastModified() < MAX_AGE) {
                return read(f);
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    // 在途去重：同一 URL 的并发下载（WebView 的 shouldInterceptRequest 与预加载线程池）
    // 合并为一次网络请求，避免重复带宽、并消除两个线程同时 save() 写同一文件导致缓存损坏。
    private static final ConcurrentHashMap<String, Future<byte[]>> inflight = new ConcurrentHashMap<>();

    static byte[] fetch(String u, String ua, String ref) {
        if (dir == null) return null;
        Future<byte[]> f = inflight.get(u);
        if (f == null) {
            FutureTask<byte[]> task = new FutureTask<>(() -> doFetch(u, ua, ref));
            f = inflight.putIfAbsent(u, task);
            if (f == null) {
                f = task;
                try {
                    task.run();          // 获胜线程执行下载，其余等待者复用同一结果
                } finally {
                    inflight.remove(u);
                }
            }
        }
        try {
            return f.get();             // doFetch 自带 10s/20s 超时，不会无限阻塞
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static byte[] doFetch(String u, String ua, String ref) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(true);
            if (ua != null && !ua.isEmpty()) c.setRequestProperty("User-Agent", ua);
            if (ref != null && !ref.isEmpty()) c.setRequestProperty("Referer", ref);
            c.setRequestProperty("Accept", "*/*");
            c.connect();
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                c.disconnect();
                return null;
            }
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            c.disconnect();
            byte[] data = bos.toByteArray();
            if (data.length > 0 && data.length < 8 * 1024 * 1024) {
                save(u, data);
                return data;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private static void save(String u, byte[] data) {
        try {
            File f = file(u);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(data);
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    private static byte[] read(File f) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            return bos.toByteArray();
        } catch (Throwable e) {
            return null;
        }
    }

    static ByteArrayInputStream stream(byte[] d) {
        return new ByteArrayInputStream(d);
    }

    static void clear() {
        try {
            if (dir == null) return;
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    try {
                        f.delete();
                    } catch (Throwable ignore) {
                    }
                }
            }
        } catch (Throwable ignore) {
        }
    }

    static long size() {
        try {
            if (dir == null) return 0;
            File[] fs = dir.listFiles();
            if (fs == null) return 0;
            long s = 0;
            for (File f : fs) s += f.length();
            return s;
        } catch (Throwable ignore) {
            return 0;
        }
    }

    static void trim() {
        try {
            if (size() > MAX_BYTES) clear();
        } catch (Throwable ignore) {
        }
    }

    private static String md5(String s) {
        try {
            MessageDigest d = MessageDigest.getInstance("MD5");
            byte[] b = d.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) {
                String h = Integer.toHexString(0xFF & x);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (Throwable e) {
            return String.valueOf(Math.abs(s.hashCode()));
        }
    }
}
