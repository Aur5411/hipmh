package com.hipmh.app;

import android.app.Application;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;

public class MyApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            private final Thread.UncaughtExceptionHandler prev =
                    Thread.getDefaultUncaughtExceptionHandler();

            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    StringBuilder sb = new StringBuilder();
                    sb.append("device=").append(Build.MANUFACTURER).append(' ')
                            .append(Build.MODEL).append('\n');
                    sb.append("android=").append(Build.VERSION.RELEASE)
                            .append(" api=").append(Build.VERSION.SDK_INT).append('\n');
                    sb.append("thread=").append(t.getName()).append('\n');
                    java.io.StringWriter sw = new java.io.StringWriter();
                    java.io.PrintWriter pw = new java.io.PrintWriter(sw);
                    e.printStackTrace(pw);
                    pw.flush();
                    sb.append(sw.toString());
                    File f = new File(getFilesDir(), "last_crash.txt");
                    FileOutputStream fos = new FileOutputStream(f, false);
                    OutputStreamWriter ow = new OutputStreamWriter(fos, "UTF-8");
                    ow.write(sb.toString());
                    ow.close();
                    fos.close();
                } catch (Throwable ignore) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });

        warmUpWebView();
        ResCache.init(this);
    }

    private void warmUpWebView() {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.MutableContextWrapper ctx =
                                new android.content.MutableContextWrapper(getApplicationContext());
                        android.webkit.WebView w = new android.webkit.WebView(ctx);
                        w.getSettings().setJavaScriptEnabled(true);
                        w.getSettings().setDomStorageEnabled(true);
                        w.loadUrl("about:blank");
                        warm = w;
                    } catch (Throwable ignore) {
                    }
                }
            });
        } catch (Throwable ignore) {
        }
    }

    private static android.webkit.WebView warm;
}
