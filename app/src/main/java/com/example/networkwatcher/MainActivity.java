package com.example.networkwatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int VPN_REQUEST_CODE = 0x0F;
    private TextView textStatus;
    private Button btnToggleVpn;
    private RecyclerView rvLogs;
    private LogAdapter adapter;
    private List<LogItem> logList;
    private boolean isVpnActive = false;

    private final BroadcastReceiver logUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshLogs();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initUI();
    }

    private void initUI() {
        textStatus = findViewById(R.id.textStatus);
        btnToggleVpn = findViewById(R.id.btnToggleVpn);
        rvLogs = findViewById(R.id.rvLogs);

        logList = new ArrayList<>();
        adapter = new LogAdapter(logList);
        rvLogs.setLayoutManager(new LinearLayoutManager(this));
        rvLogs.setAdapter(adapter);

        btnToggleVpn.setOnClickListener(v -> {
            if (isVpnActive) {
                stopVpnService();
            } else {
                prepareVpn();
            }
        });
        
        refreshLogs();
    }

    private void refreshLogs() {
        logList.clear();
        logList.addAll(LogPersistence.getLogs(this));
        adapter.notifyDataSetChanged();
    }

    private void prepareVpn() {
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            startActivityForResult(intent, VPN_REQUEST_CODE);
        } else {
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
        }
    }

    private void stopVpnService() {
        Intent intent = new Intent(this, MyVpnService.class);
        intent.setAction(MyVpnService.ACTION_STOP_VPN);
        startService(intent);
        
        isVpnActive = false;
        btnToggleVpn.setText("İzlemeyi Başlat");
        textStatus.setText("Durum: Durduruldu");
        Toast.makeText(this, "İzleme Durduruldu. İnternet normale döndü.", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            Intent intent = new Intent(this, MyVpnService.class);
            startService(intent);
            
            isVpnActive = true;
            btnToggleVpn.setText("İzlemeyi Durdur");
            textStatus.setText("Durum: İzleme Aktif (İnternet Kısıtlı)");
            Toast.makeText(this, "Servis Başlatıldı", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(logUpdateReceiver, new IntentFilter(MyVpnService.ACTION_LOG_UPDATE), Context.RECEIVER_EXPORTED);
        refreshLogs();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(logUpdateReceiver);
    }
}
