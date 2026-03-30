package com.example.networkwatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MyVpnService extends VpnService implements Runnable {
    public static final String ACTION_LOG_UPDATE = "com.example.networkwatcher.LOG_UPDATE";
    public static final String ACTION_STOP_VPN = "com.example.networkwatcher.STOP_VPN";
    private static final String TAG = "MyVpnService";
    private static final String CHANNEL_ID = "VpnChannel";
    private static final String ALERT_CHANNEL_ID = "AlertChannel";
    
    private Thread mThread;
    private ParcelFileDescriptor mInterface;
    private ScheduledExecutorService mScheduler;

    private final Set<String> mKnownIps = new HashSet<>();
    private final Map<String, Integer> mPacketCounts = new HashMap<>();
    private final Map<String, Long> mLastAlertTime = new HashMap<>();
    
    // DDoS Analiz Eşikleri
    private static final int THRESHOLD_SUSPICIOUS = 300; // Şüpheli sınırı
    private static final int THRESHOLD_DANGEROUS = 1500; // Tehlikeli sınırı
    private static final int WINDOW_DURATION_MS = 2000;
    private static final long ALERT_COOLDOWN = 30000;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_VPN.equals(intent.getAction())) {
            stopVpn();
            return START_NOT_STICKY;
        }

        createNotificationChannels();
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, getForegroundNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, getForegroundNotification());
        }

        startAnalysis();
        return START_STICKY;
    }

    private void startAnalysis() {
        if (mThread != null) mThread.interrupt();
        mThread = new Thread(this, "MyVpnThread");
        mThread.start();

        mScheduler = Executors.newSingleThreadScheduledExecutor();
        mScheduler.scheduleAtFixedRate(() -> mPacketCounts.clear(), 
                WINDOW_DURATION_MS, WINDOW_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    private void stopVpn() {
        if (mThread != null) mThread.interrupt();
        if (mScheduler != null) mScheduler.shutdownNow();
        try {
            if (mInterface != null) mInterface.close();
        } catch (Exception ignored) {}
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel vpnChannel = new NotificationChannel(
                    CHANNEL_ID, "Security Monitor", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(vpnChannel);

            NotificationChannel alertChannel = new NotificationChannel(
                    ALERT_CHANNEL_ID, "Threat Alerts", NotificationManager.IMPORTANCE_HIGH);
            nm.createNotificationChannel(alertChannel);
        }
    }

    private Notification getForegroundNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Güvenlik Duvarı Aktif")
                .setContentText("Ağ trafiği gerçek zamanlı analiz ediliyor.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .setOngoing(true)
                .build();
    }

    private void saveAndNotify(String ip, String detail, LogItem.Status status) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        LogItem item = new LogItem(ip, detail, time, status);
        LogPersistence.saveLog(this, item);
        sendBroadcast(new Intent(ACTION_LOG_UPDATE));
    }

    private void sendDdosAlert(String ip) {
        long now = System.currentTimeMillis();
        if (mLastAlertTime.containsKey(ip) && (now - mLastAlertTime.get(ip) < ALERT_COOLDOWN)) {
            return;
        }
        mLastAlertTime.put(ip, now);

        Notification alert = new NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setContentTitle("KRİTİK TEHDİT!")
                .setContentText(ip + " adresinden DDoS saldırısı saptandı!")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(ip.hashCode(), alert);
        saveAndNotify(ip, "Saldırı Engellendi: Olağandışı trafik!", LogItem.Status.DANGEROUS);
    }

    @Override
    public void run() {
        try {
            Builder builder = new Builder();
            builder.setMtu(1500);
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            builder.setSession("SecurityGuard");
            mInterface = builder.establish();

            FileInputStream in = new FileInputStream(mInterface.getFileDescriptor());
            ByteBuffer packet = ByteBuffer.allocate(32767);

            while (!Thread.interrupted()) {
                int length = in.read(packet.array());
                if (length > 0) {
                    analyzePacket(packet);
                    packet.clear();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "VPN Durdu");
        }
    }

    private void analyzePacket(ByteBuffer packet) {
        if ((packet.get(0) >> 4 & 0x0F) == 4) {
            String sourceIp = String.format("%d.%d.%d.%d",
                    packet.get(12) & 0xFF, packet.get(13) & 0xFF,
                    packet.get(14) & 0xFF, packet.get(15) & 0xFF);

            if (!mKnownIps.contains(sourceIp)) {
                mKnownIps.add(sourceIp);
                saveAndNotify(sourceIp, "Yeni güvenli bağlantı sağlandı.", LogItem.Status.SAFE);
            }

            int count = mPacketCounts.getOrDefault(sourceIp, 0) + 1;
            mPacketCounts.put(sourceIp, count);

            if (count > THRESHOLD_DANGEROUS) {
                sendDdosAlert(sourceIp);
            } else if (count > THRESHOLD_SUSPICIOUS) {
                saveAndNotify(sourceIp, "Şüpheli trafik artışı: " + count + " paket/sn", LogItem.Status.SUSPICIOUS);
            }
        }
    }

    @Override
    public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
