package com.hipmh.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 我的书架列表页（本地收藏的作品）。
 *
 * <p>★ v1.7：浏览历史功能已整体下线，本页只剩书架一个用途。
 *
 * <p>★ v1.9：
 * <ul>
 *   <li>列表改为「封面缩略图 + 标题」两栏卡片式行（{@code item_shelf.xml}）。
 *       封面走 {@link ResCache} 磁盘缓存（命中即用，未命中后台线程下载）。</li>
 *   <li>长按菜单新增「从书架删除」（用户要求可长按删除漫画）。</li>
 *   <li>修正布局：空态与列表同层互换（以前两者都是 {@code layout_weight=1}
 *       且同时存在，列表非空时空态虽 GONE 但底部按钮仍把列表压出一段空白）。</li>
 * </ul>
 */
public class ListActivity extends AppCompatActivity {

    private Store store;
    private ListView list;
    private TextView empty;
    private TextView emptyHint;
    private TextView title;
    private Button btnClear;

    /** 固定为收藏表 */
    private final String tab = "fav";

    private List<Store.Item> items = new ArrayList<>();
    private final MyAdapter adapter = new MyAdapter();

    /** 封面下载线程池（小并发足够，列表最多 300 条且大多已缓存） */
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 已解码的封面内存缓存，key = cover url */
    private final ConcurrentHashMap<String, Bitmap> coverMem = new ConcurrentHashMap<>();
    /** 正在下载的封面，避免同一张图重复排队 */
    private final Set<String> coverLoading = new HashSet<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_list);
        ResCache.init(this);

        store = new Store(this);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        emptyHint = findViewById(R.id.emptyHint);
        btnClear = findViewById(R.id.btnClear);
        title = findViewById(R.id.title);

        title.setText(R.string.title_shelf);
        btnClear.setText(R.string.shelf_clear);
        empty.setText(R.string.shelf_empty);
        emptyHint.setText(R.string.shelf_empty_hint);

        list.setAdapter(adapter);

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return;
                String u = items.get(pos).url;
                Intent i = new Intent(ListActivity.this, MainActivity.class);
                i.putExtra("url", u);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                finish();
            }
        });

        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> p, View v, int pos, long id) {
                if (pos < 0 || pos >= items.size()) return true;
                showFavMenu(pos);
                return true;
            }
        });

        btnClear.setOnClickListener(v -> confirmClear());

        reload();
    }

    @Override
    protected void onDestroy() {
        try {
            pool.shutdownNow();
        } catch (Throwable ignore) {
        }
        super.onDestroy();
    }

    /** 清空书架（先确认，避免误触把整个书架清掉） */
    private void confirmClear() {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.shelf_empty_toast, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.shelf_clear)
                .setMessage(getString(R.string.shelf_clear_confirm, items.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    store.clear(tab);
                    reload();
                    Toast.makeText(ListActivity.this, R.string.shelf_cleared,
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /** 书架长按菜单：手动标记阅读状态 / 从书架删除 */
    private void showFavMenu(int pos) {
        final Store.Item it = items.get(pos);
        final String[] opts = {
                getString(R.string.shelf_mark_reading),
                getString(R.string.shelf_mark_done),
                getString(R.string.shelf_mark_clear),
                getString(R.string.shelf_delete),
        };
        new AlertDialog.Builder(this)
                .setTitle(it.title == null ? it.url : it.title)
                .setItems(opts, (d, w) -> {
                    switch (w) {
                        case 0:
                            store.setFavStatus(it.url, Store.ST_READING);
                            Toast.makeText(ListActivity.this, R.string.shelf_marked_reading,
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            store.setFavStatus(it.url, Store.ST_DONE);
                            Toast.makeText(ListActivity.this, R.string.shelf_marked_done,
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            store.setFavStatus(it.url, Store.ST_NONE);
                            Toast.makeText(ListActivity.this, R.string.shelf_marked_clear,
                                    Toast.LENGTH_SHORT).show();
                            break;
                        case 3:
                            store.remove(tab, it.url);
                            Toast.makeText(ListActivity.this, R.string.shelf_deleted,
                                    Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            break;
                    }
                    reload();
                })
                .show();
    }

    private void reload() {
        items = store.list(tab);
        adapter.notifyDataSetChanged();
        boolean e = items.isEmpty();
        empty.setVisibility(e ? View.VISIBLE : View.GONE);
        if (emptyHint != null) emptyHint.setVisibility(e ? View.VISIBLE : View.GONE);
        list.setVisibility(e ? View.GONE : View.VISIBLE);
        btnClear.setEnabled(!e);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 取封面：内存 → 磁盘 → 后台下载 */
    private void loadCover(String url, ImageView iv) {
        if (url == null || url.isEmpty()) {
            iv.setImageDrawable(null);
            return;
        }
        Bitmap m = coverMem.get(url);
        if (m != null) {
            iv.setImageBitmap(m);
            return;
        }
        iv.setImageDrawable(null);
        synchronized (coverLoading) {
            if (coverLoading.contains(url)) return;
            coverLoading.add(url);
        }
        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                byte[] d = ResCache.get(url);
                if (d == null) {
                    d = ResCache.fetch(url, null, "https://m.hipmh.com/");
                }
                if (d != null) {
                    final BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(d, 0, d.length, o);
                    // 目标显示 52x72 dp，按 2 倍下采样即可，省内存
                    int req = dp(72) * 2;
                    int sample = 1;
                    int longer = Math.max(o.outWidth, o.outHeight);
                    while (longer / sample > req * 2) sample *= 2;
                    BitmapFactory.Options o2 = new BitmapFactory.Options();
                    o2.inSampleSize = sample;
                    bmp = BitmapFactory.decodeByteArray(d, 0, d.length, o2);
                }
            } catch (Throwable ignore) {
            } finally {
                synchronized (coverLoading) {
                    coverLoading.remove(url);
                }
            }
            final Bitmap fb = bmp;
            if (fb != null) {
                coverMem.put(url, fb);
                ui.post(() -> adapter.notifyDataSetChanged());
            }
        });
    }

    private final class MyAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int pos) {
            return items.get(pos);
        }

        @Override
        public long getItemId(int pos) {
            return pos;
        }

        @Override
        public View getView(int pos, View cv, ViewGroup parent) {
            View v = cv;
            if (v == null) {
                v = LayoutInflater.from(ListActivity.this)
                        .inflate(R.layout.item_shelf, parent, false);
            }
            Store.Item it = items.get(pos);

            ImageView cover = v.findViewById(R.id.cover);
            TextView tv = v.findViewById(R.id.tv_title);
            TextView stv = v.findViewById(R.id.tv_state);

            tv.setText(it.title == null || it.title.isEmpty() ? it.url : it.title);
            loadCover(it.cover, cover);

            int markColor;
            int stateText;
            String sym;
            if (it.st == Store.ST_DONE) {
                sym = "●";
                stateText = R.string.shelf_state_done;
                markColor = R.color.shelf_done;
            } else if (it.st == Store.ST_READING) {
                sym = "◐";
                stateText = R.string.shelf_state_reading;
                markColor = R.color.shelf_reading;
            } else {
                sym = "○";
                stateText = R.string.shelf_state_none;
                markColor = R.color.shelf_none;
            }
            stv.setText(sym + " " + getString(stateText));
            stv.setTextColor(ContextCompat.getColor(ListActivity.this, markColor));
            return v;
        }
    }
}
