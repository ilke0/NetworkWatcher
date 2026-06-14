package com.example.networkwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.networkwatcher.database.AppDatabase;
import com.example.networkwatcher.database.TrafficLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 0x0F;
    private TextView textStatus;
    private Button btnToggleVpn, btnClearLogs;
    private RecyclerView rvLogs;
    private LogAdapter adapter;
    private List<LogItem> logList;
    private boolean isVpnActive = false;
    private AppDatabase db;

    private final BroadcastReceiver logUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLogs();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        db = AppDatabase.getInstance(this);
        initUI();
    }

    private void initUI() {
        textStatus = findViewById(R.id.textStatus);
        btnToggleVpn = findViewById(R.id.btnToggleVpn);
        btnClearLogs = findViewById(R.id.btnClearLogs);
        rvLogs = findViewById(R.id.rvLogs);

        logList = new ArrayList<>();
        adapter = new LogAdapter(logList);
        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        rvLogs.setAdapter(adapter);

        btnToggleVpn.setOnClickListener(v -> {
            if (isVpnActive) stopVpnService();
            else prepareVpn();
        });

        btnClearLogs.setOnClickListener(v -> {
            new Thread(() -> {
                db.trafficLogDao().deleteAll();
                runOnUiThread(() -> {
                    refreshLogs();
                    Toast.makeText(this, "Veriler temizlendi.", Toast.LENGTH_SHORT).show();
                });
            }).start();
        });
        
        refreshLogs();
    }

    private void refreshLogs() {
        new Thread(() -> {
            List<TrafficLog> dbLogs = db.trafficLogDao().getAllLogs();
            List<LogItem> newList = new ArrayList<>();
            SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            
            for (TrafficLog dbLog : dbLogs) {
                LogItem.Status status = LogItem.Status.SAFE;
                if ("DANGEROUS".equals(dbLog.threatLevel)) status = LogItem.Status.DANGEROUS;
                else if ("WARNING".equals(dbLog.threatLevel)) status = LogItem.Status.SUSPICIOUS;

                String time = sdf.format(new Date(dbLog.timestamp));
                LogItem item = new LogItem(dbLog.dstIp, dbLog.hostName, dbLog.details, time, status);
                newList.add(item);
            }

            runOnUiThread(() -> {
                logList.clear();
                logList.addAll(newList);
                adapter.notifyDataSetChanged();
                if (!logList.isEmpty()) rvLogs.scrollToPosition(0);
            });
        }).start();
    }

    private void prepareVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) startActivityForResult(intent, VPN_REQUEST_CODE);
        else onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, MyVpnService.class);
        intent.setAction(MyVpnService.ACTION_STOP_VPN);
        startService(intent);
        isVpnActive = false;
        btnToggleVpn.setText("İZLEMEYİ BAŞLAT");
        textStatus.setText("Sistem Durduruldu");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            Intent intent = new Intent(this, MyVpnService.class);
            startService(intent);
            isVpnActive = true;
            btnToggleVpn.setText("İZLEMEYİ DURDUR");
            textStatus.setText("İzleme Aktif");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logUpdateReceiver, new IntentFilter(MyVpnService.ACTION_LOG_UPDATE), Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(logUpdateReceiver, new IntentFilter(MyVpnService.ACTION_LOG_UPDATE));
        }
        refreshLogs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(logUpdateReceiver);
    }
}
