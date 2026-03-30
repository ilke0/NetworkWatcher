package com.example.networkwatcher;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class LogAdapter extends RecyclerView.Adapter<LogAdapter.LogViewHolder> {
    private List<LogItem> logItems;

    public LogAdapter(List<LogItem> logItems) {
        this.logItems = logItems;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_log, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogItem item = logItems.get(position);
        
        holder.tvIp.setText("IP: " + (item.getIp() != null ? item.getIp() : "Bilinmiyor"));
        holder.tvDetails.setText(item.getDetails() != null ? item.getDetails() : "");
        holder.tvTime.setText(item.getTime() != null ? item.getTime() : "");

        // KRİTİK DÜZELTME: Eski verilerde status null olabilir, kontrol ediyoruz.
        LogItem.Status status = item.getStatus();
        if (status == null) {
            status = LogItem.Status.SAFE; // Varsayılan olarak güvenli kabul et
        }

        switch (status) {
            case SAFE:
                holder.statusIndicator.setBackgroundColor(Color.parseColor("#4CAF50"));
                holder.tvStatusLabel.setText("GÜVENLİ");
                holder.tvStatusLabel.setTextColor(Color.parseColor("#4CAF50"));
                break;
            case SUSPICIOUS:
                holder.statusIndicator.setBackgroundColor(Color.parseColor("#FFC107"));
                holder.tvStatusLabel.setText("ŞÜPHELİ");
                holder.tvStatusLabel.setTextColor(Color.parseColor("#FFC107"));
                break;
            case DANGEROUS:
                holder.statusIndicator.setBackgroundColor(Color.parseColor("#F44336"));
                holder.tvStatusLabel.setText("TEHLİKELİ");
                holder.tvStatusLabel.setTextColor(Color.parseColor("#F44336"));
                break;
        }
    }

    @Override
    public int getItemCount() {
        return logItems.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        TextView tvIp, tvDetails, tvTime, tvStatusLabel;
        View statusIndicator;

        public LogViewHolder(@NonNull View itemView) {
            super(itemView);
            tvIp = itemView.findViewById(R.id.tvIp);
            tvDetails = itemView.findViewById(R.id.tvDetails);
            tvTime = itemView.findViewById(R.id.tvTime);
            tvStatusLabel = itemView.findViewById(R.id.tvStatusLabel);
            statusIndicator = itemView.findViewById(R.id.statusIndicator);
        }
    }
}
