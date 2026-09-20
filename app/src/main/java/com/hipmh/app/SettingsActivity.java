package com.hipmh.app;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    private Prefs prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setContentView(R.layout.activity_settings);

        bindSwitch(R.id.swKeep, prefs.keepScreen(), v -> prefs.keepScreen(v));
        bindSwitch(R.id.swAd, prefs.adBlock(), v -> prefs.adBlock(v));
        bindSwitch(R.id.swVol, prefs.volumePage(), v -> prefs.volumePage(v));
        bindSwitch(R.id.swImm, prefs.immersiveRead(), v -> prefs.immersiveRead(v));
        bindSwitch(R.id.swDesk, prefs.desktopUa(), v -> prefs.desktopUa(v));
        bindSwitch(R.id.swCache, prefs.cacheAssets(), v -> prefs.cacheAssets(v));

        final android.widget.Button btnLine = findViewById(R.id.btnLine);
        if (btnLine != null) {
            updateLine(btnLine);
            btnLine.setOnClickListener(v -> {
                String cur = prefs.imgLine();
                prefs.imgLine("line1".equals(cur) ? "line2" : "line1");
                updateLine(btnLine);
            });
        }

        TextView ver = findViewById(R.id.tvVersion);
        try {
            String n = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ver.setText("版本 " + n);
        } catch (Throwable e) {
            ver.setText("版本 1.0.0");
        }

        findViewById(R.id.btnClearHistory).setOnClickListener(v -> {
            new Store(SettingsActivity.this).clear("hist");
            Toast.makeText(this, "历史已清空", Toast.LENGTH_SHORT).show();
        });

        TextView crash = findViewById(R.id.tvCrash);
        try {
            java.io.File f = new java.io.File(getFilesDir(), "last_crash.txt");
            if (f.exists() && f.length() > 0) {
                crash.setVisibility(View.VISIBLE);
                crash.setText("上次崩溃记录（点击复制）");
                crash.setOnClickListener(v -> {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager)
                                        getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText(
                                "crash", read(f)));
                        Toast.makeText(this, "已复制崩溃日志", Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignore) {
                    }
                });
            }
        } catch (Throwable ignore) {
        }
    }

    private void updateLine(android.widget.Button b) {
        String l = prefs.imgLine();
        b.setText("line1".equals(l) ? "图片线路：腾讯云（推荐）" : "图片线路：Cloudflare");
    }

    private interface OnChange {
        void set(boolean v);
    }

    private void bindSwitch(int id, boolean init, final OnChange cb) {
        try {
            View v = findViewById(id);
            if (!(v instanceof SwitchCompat)) return;
            final SwitchCompat sw = (SwitchCompat) v;
            sw.setChecked(init);
            sw.setOnCheckedChangeListener((button, checked) -> cb.set(checked));
            LinearLayout row = (LinearLayout) sw.getParent();
            if (row != null) {
                row.setOnClickListener(x -> sw.setChecked(!sw.isChecked()));
            }
        } catch (Throwable ignore) {
        }
    }

    private static String read(java.io.File f) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            in.close();
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable e) {
            return "";
        }
    }
}
