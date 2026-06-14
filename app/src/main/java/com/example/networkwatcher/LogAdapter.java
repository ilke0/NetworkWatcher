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

    private final List<LogItem> logList;

    public LogAdapter(List<LogItem> logList) {
        this.logList = logList;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.log_item, parent, false);
        return new LogViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogItem item = logList.get(position);
        holder.textIp.setText(item.getIp());
        holder.textHostName.setText(item.getHostName());
        holder.textDetail.setText(item.getDetails());
        holder.textTime.setText(item.getTime());

        // Renk Yönetimi — hem detay metni hem de sol renkli çizgi güncelleniyor
        if (item.getStatus() == LogItem.Status.DANGEROUS) {
            holder.textDetail.setTextColor(Color.parseColor("#FF3D3D"));   // kırmızı
            holder.viewStatusIndicator.setBackgroundColor(Color.parseColor("#FF3D3D"));
        } else if (item.getStatus() == LogItem.Status.SUSPICIOUS) {
            holder.textDetail.setTextColor(Color.parseColor("#FFA500"));   // turuncu (WARNING)
            holder.viewStatusIndicator.setBackgroundColor(Color.parseColor("#FFA500"));
        } else {
            holder.textDetail.setTextColor(Color.parseColor("#10B981"));   // yeşil (SAFE)
            holder.viewStatusIndicator.setBackgroundColor(Color.parseColor("#10B981"));
        }
    }

    @Override
    public int getItemCount() {
        return logList.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        TextView textIp, textHostName, textDetail, textTime;
        View viewStatusIndicator;

        public LogViewHolder(@NonNull View itemView) {
            super(itemView);
            textIp = itemView.findViewById(R.id.textIp);
            textHostName = itemView.findViewById(R.id.textHostName);
            textDetail = itemView.findViewById(R.id.textDetail);
            textTime = itemView.findViewById(R.id.textTime);
            viewStatusIndicator = itemView.findViewById(R.id.viewStatusIndicator);
        }
    }
}
