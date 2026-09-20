package com.hipmh.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class ListActivity extends AppCompatActivity {

    private Store store;
    private ListView list;
    private TextView empty;
    private Button btnClear;

    private List<Store.Item> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_list);

        store = new Store(this);
        list = findViewById(R.id.list);
        empty = findViewById(R.id.empty);
        btnClear = findViewById(R.id.btnClear);

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
                store.remove("hist", items.get(pos).url);
                reload();
                Toast.makeText(ListActivity.this, "已删除", Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        btnClear.setOnClickListener(v -> {
            store.clear("hist");
            reload();
            Toast.makeText(ListActivity.this, "历史已清空", Toast.LENGTH_SHORT).show();
        });

        reload();
    }

    private void reload() {
        items = store.list("hist");
        List<String> titles = new ArrayList<>();
        for (Store.Item it : items) {
            titles.add(it.title == null ? it.url : it.title);
        }
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, titles);
        list.setAdapter(ad);
        boolean e = items.isEmpty();
        empty.setVisibility(e ? View.VISIBLE : View.GONE);
        list.setVisibility(e ? View.GONE : View.VISIBLE);
        empty.setText("暂无浏览记录");
    }
}
