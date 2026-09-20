package com.hipmh.app;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

public class ImageViewerActivity extends AppCompatActivity {

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    public static void open(Context c, String url, String referer) {
        try {
            Intent i = new Intent(c, ImageViewerActivity.class);
            i.putExtra("url", url);
            i.putExtra("ref", referer == null ? "" : referer);
            c.startActivity(i);
        } catch (Throwable ignore) {
        }
    }

    private ImageView img;
    private ProgressBar pb;
    private TextView tip;

    private Bitmap bmp;
    private final Matrix matrix = new Matrix();
    private float baseScale = 1f;
    private float userScale = 1f;
    private float transX = 0f;
    private float transY = 0f;
    private int viewW = 0;
    private int viewH = 0;
    private int bmpW = 0;
    private int bmpH = 0;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_image);

        img = findViewById(R.id.img);
        pb = findViewById(R.id.pb);
        tip = findViewById(R.id.tip);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        viewW = dm.widthPixels;
        viewH = dm.heightPixels;

        scaleDetector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector d) {
                        float f = d.getScaleFactor();
                        userScale *= f;
                        if (userScale < 0.3f) userScale = 0.3f;
                        if (userScale > 8f) userScale = 8f;
                        applyMatrix();
                        return true;
                    }
                });

        gestureDetector = new GestureDetector(this,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        if (userScale > 1.05f) {
                            userScale = 1f;
                            transX = 0f;
                            transY = 0f;
                        } else {
                            userScale = 2.2f;
                        }
                        applyMatrix();
                        return true;
                    }

                    @Override
                    public boolean onSingleTapConfirmed(MotionEvent e) {
                        finish();
                        return true;
                    }
                });

        img.setOnTouchListener(new View.OnTouchListener() {
            private float lastX = 0f;
            private float lastY = 0f;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                scaleDetector.onTouchEvent(e);
                gestureDetector.onTouchEvent(e);
                int n = e.getPointerCount();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastX = e.getX();
                        lastY = e.getY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (n == 1) {
                            float dx = e.getX() - lastX;
                            float dy = e.getY() - lastY;
                            lastX = e.getX();
                            lastY = e.getY();
                            transX += dx;
                            transY += dy;
                            clamp();
                            applyMatrix();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        lastX = 0f;
                        lastY = 0f;
                        return true;
                    default:
                        break;
                }
                return true;
            }
        });

        String url = getIntent().getStringExtra("url");
        String ref = getIntent().getStringExtra("ref");
        if (url == null || url.isEmpty()) {
            finish();
            return;
        }
        load(url, ref == null ? "" : ref);
    }

    private void load(final String url, final String ref) {
        pb.setVisibility(View.VISIBLE);
        tip.setVisibility(View.GONE);
        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] data = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setRequestMethod("GET");
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(25000);
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", UA);
                    if (!ref.isEmpty()) c.setRequestProperty("Referer", ref);
                    c.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
                    c.connect();
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
                    in.close();
                    c.disconnect();
                    data = bos.toByteArray();
                } catch (Throwable ignore) {
                    data = null;
                }
                final byte[] out = data;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        pb.setVisibility(View.GONE);
                        if (out == null || out.length == 0) {
                            tip.setVisibility(View.VISIBLE);
                            tip.setText("图片加载失败");
                            return;
                        }
                        show(out);
                    }
                });
            }
        }).start();
    }

    private void show(byte[] data) {
        try {
            BitmapFactoryProbe p = new BitmapFactoryProbe(data);
            bmpW = p.width;
            bmpH = p.height;
            if (bmpW <= 0 || bmpH <= 0) {
                tip.setVisibility(View.VISIBLE);
                tip.setText("图片格式不支持");
                return;
            }
            int sample = 1;
            while (bmpW / sample > viewW * 2 && bmpH / sample > viewH * 2) sample *= 2;
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = sample;
            o.inPreferredConfig = Bitmap.Config.RGB_565;
            bmp = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (bmp == null) {
                tip.setVisibility(View.VISIBLE);
                tip.setText("图片解码失败");
                return;
            }
            img.setImageBitmap(bmp);
            float sx = (float) viewW / (float) bmp.getWidth();
            float sy = (float) viewH / (float) bmp.getHeight();
            baseScale = Math.min(sx, sy);
            userScale = 1f;
            transX = 0f;
            transY = 0f;
            applyMatrix();
        } catch (Throwable e) {
            tip.setVisibility(View.VISIBLE);
            tip.setText("图片显示失败");
        }
    }

    private void clamp() {
        if (bmp == null) return;
        float w = bmp.getWidth() * baseScale * userScale;
        float h = bmp.getHeight() * baseScale * userScale;
        float maxX = Math.max(0f, (w - viewW) / 2f);
        float maxY = Math.max(0f, (h - viewH) / 2f);
        if (transX > maxX) transX = maxX;
        if (transX < -maxX) transX = -maxX;
        if (transY > maxY) transY = maxY;
        if (transY < -maxY) transY = -maxY;
    }

    private void applyMatrix() {
        if (bmp == null) return;
        clamp();
        float s = baseScale * userScale;
        matrix.reset();
        matrix.setScale(s, s);
        float dx = (viewW - bmp.getWidth() * s) / 2f + transX;
        float dy = (viewH - bmp.getHeight() * s) / 2f + transY;
        matrix.postTranslate(dx, dy);
        img.setImageMatrix(matrix);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (bmp != null && !bmp.isRecycled()) {
                bmp.recycle();
                bmp = null;
            }
        } catch (Throwable ignore) {
        }
    }

    private static final class BitmapFactoryProbe {
        final int width;
        final int height;

        BitmapFactoryProbe(byte[] data) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            width = o.outWidth;
            height = o.outHeight;
        }
    }

    static String fmt(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f));
    }
}
