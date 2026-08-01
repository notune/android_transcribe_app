package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private HistoryAdapter adapter;
    private View emptyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        setSupportActionBar(findViewById(R.id.toolbar));
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recyclerView = findViewById(R.id.history_list);
        emptyView = findViewById(R.id.history_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new HistoryAdapter();
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_clear_all).setOnClickListener(v -> confirmClearAll());

        loadHistory();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private void loadHistory() {
        List<TranscriptionHistory.Entry> entries =
                TranscriptionHistory.get(this).query(0);
        adapter.setEntries(entries);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void confirmClearAll() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_body)
                .setPositiveButton(R.string.history_clear_confirm, (d, w) -> {
                    TranscriptionHistory.get(this).clearAll();
                    loadHistory();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        private final List<TranscriptionHistory.Entry> entries = new ArrayList<>();
        private final DateFormat fmt = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT);

        void setEntries(List<TranscriptionHistory.Entry> list) {
            entries.clear();
            entries.addAll(list);
            notifyDataSetChanged();
        }

        @Override
        public VH onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_history, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(VH h, int position) {
            TranscriptionHistory.Entry e = entries.get(position);
            h.text.setText(e.text);
            h.meta.setText(getString(R.string.history_meta,
                    sourceLabel(e.source), fmt.format(new Date(e.timestamp))));

            h.copyBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Transcription", e.text));
                Toast.makeText(HistoryActivity.this,
                        R.string.history_copied, Toast.LENGTH_SHORT).show();
            });

            h.deleteBtn.setOnClickListener(v -> {
                TranscriptionHistory.get(HistoryActivity.this).delete(e.id);
                entries.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, entries.size());
                if (entries.isEmpty()) {
                    emptyView.setVisibility(View.VISIBLE);
                    recyclerView.setVisibility(View.GONE);
                }
            });
        }

        @Override
        public int getItemCount() {
            return entries.size();
        }

        private String sourceLabel(String source) {
            switch (source) {
                case TranscriptionHistory.SOURCE_BUBBLE:
                    return getString(R.string.history_source_bubble);
                case TranscriptionHistory.SOURCE_POPUP:
                    return getString(R.string.history_source_popup);
                case TranscriptionHistory.SOURCE_IME:
                    return getString(R.string.history_source_ime);
                case TranscriptionHistory.SOURCE_FILE:
                    return getString(R.string.history_source_file);
                case TranscriptionHistory.SOURCE_SERVICE:
                    return getString(R.string.history_source_service);
                default:
                    return source;
            }
        }

        class VH extends RecyclerView.ViewHolder {
            TextView text, meta;
            ImageButton copyBtn, deleteBtn;

            VH(View v) {
                super(v);
                text = v.findViewById(R.id.history_item_text);
                meta = v.findViewById(R.id.history_item_meta);
                copyBtn = v.findViewById(R.id.history_item_copy);
                deleteBtn = v.findViewById(R.id.history_item_delete);
            }
        }
    }
}
