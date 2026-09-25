package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** The History tab: every transcription, newest first, with copy and delete. */
final class HistoryPage {

    private final Activity activity;
    private final RecyclerView recyclerView;
    private final HistoryAdapter adapter;
    private final View emptyView;

    HistoryPage(Activity activity, View root) {
        this.activity = activity;
        recyclerView = root.findViewById(R.id.history_list);
        emptyView = root.findViewById(R.id.history_empty);
        recyclerView.setLayoutManager(new LinearLayoutManager(activity));
        adapter = new HistoryAdapter();
        recyclerView.setAdapter(adapter);

        root.findViewById(R.id.btn_clear_all).setOnClickListener(v -> confirmClearAll());

        loadHistory();
    }

    /** Reloads the list (call when the tab is shown or retention changes). */
    void loadHistory() {
        List<TranscriptionHistory.Entry> entries =
                TranscriptionHistory.get(activity).query(0);
        adapter.setEntries(entries);
        emptyView.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void confirmClearAll() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.history_clear_title)
                .setMessage(R.string.history_clear_body)
                .setPositiveButton(R.string.history_clear_confirm, (d, w) -> {
                    TranscriptionHistory.get(activity).clearAll();
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
            h.meta.setText(activity.getString(R.string.history_meta,
                    sourceLabel(e.source), fmt.format(new Date(e.timestamp))));

            h.copyBtn.setOnClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Transcription", e.text));
                Toast.makeText(activity,
                        R.string.history_copied, Toast.LENGTH_SHORT).show();
            });

            h.deleteBtn.setOnClickListener(v -> {
                TranscriptionHistory.get(activity).delete(e.id);
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
                    return activity.getString(R.string.history_source_bubble);
                case TranscriptionHistory.SOURCE_POPUP:
                    return activity.getString(R.string.history_source_popup);
                case TranscriptionHistory.SOURCE_IME:
                    return activity.getString(R.string.history_source_ime);
                case TranscriptionHistory.SOURCE_FILE:
                    return activity.getString(R.string.history_source_file);
                case TranscriptionHistory.SOURCE_SERVICE:
                    return activity.getString(R.string.history_source_service);
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
