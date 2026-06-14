package com.example.networkwatcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.example.networkwatcher.database.AppDatabase;
import com.example.networkwatcher.database.TrafficLog;
import com.example.networkwatcher.localvpn.ByteBufferPool;
import com.example.networkwatcher.localvpn.Packet;
import com.example.networkwatcher.localvpn.TCPInput;
import com.example.networkwatcher.localvpn.TCPOutput;
import com.example.networkwatcher.localvpn.UDPInput;
import com.example.networkwatcher.localvpn.UDPOutput;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.Selector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MyVpnService extends VpnService {
    public static final String ACTION_LOG_UPDATE = "com.example.networkwatcher.LOG_UPDATE";
    public static final String ACTION_STOP_VPN = "com.example.networkwatcher.STOP_VPN";
    private static final String CHANNEL_ID = "VpnTurboChannel";
    
    private ParcelFileDescriptor mInterface;
    private ScheduledExecutorService mScheduler;
    private final ExecutorService mWorkerExecutor = Executors.newFixedThreadPool(50); // Cihazın tüm sınırlarını zorlamak için 50 thread
    private ExecutorService vpnExecutorService;
    private AppDatabase db;

    // Performans için Önbellekler (Cache)
    private final ConcurrentHashMap<String, String> mAppNameCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> mHostCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> mPacketCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> mKnownIps = new ConcurrentHashMap<>();
    
    private static final int THRESHOLD_DANGEROUS = 3000; 
    private static final int WINDOW_DURATION_MS = 2000;

    @Override
    public void onCreate() {
        super.onCreate();
        db = AppDatabase.getInstance(this);
    }

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
        startVpnTurbo();
        return START_STICKY;
    }

    private void startVpnTurbo() {
        mScheduler = Executors.newSingleThreadScheduledExecutor();
        mScheduler.scheduleAtFixedRate(() -> {
            mPacketCounts.clear();
        }, WINDOW_DURATION_MS, WINDOW_DURATION_MS, TimeUnit.MILLISECONDS);

        try {
            Builder builder = new Builder();
            builder.setMtu(1500);
            builder.addAddress("10.0.0.2", 32);
            builder.addRoute("0.0.0.0", 0);
            
            // IPv6 trafiği yönlendirilmez, sistemden bypass etmesini engellediğimiz için zorla IPv4 kullanılır
            
            builder.addDnsServer("8.8.8.8");
            builder.setSession("NetworkWatcher");
            
            // Tüm uygulamaların trafiği izleniyor (akademik araştırma projesi)
            // Belirli bir uygulamayı kısıtlamak için: builder.addAllowedApplication("paket.adı");


            mInterface = builder.establish();

            Selector udpSelector = Selector.open();
            Selector tcpSelector = Selector.open();
            ConcurrentLinkedQueue<Packet> udpQ = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Packet> tcpQ = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<ByteBuffer> netToDevQ = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Runnable> udpSelectorQueue = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Runnable> tcpSelectorQueue = new ConcurrentLinkedQueue<>();

            vpnExecutorService = Executors.newFixedThreadPool(6);
            vpnExecutorService.execute(new UDPInput(netToDevQ, udpSelector, udpSelectorQueue));
            vpnExecutorService.execute(new UDPOutput(udpQ, udpSelector, udpSelectorQueue, this));
            vpnExecutorService.execute(new TCPInput(netToDevQ, tcpSelector, tcpSelectorQueue));
            vpnExecutorService.execute(new TCPOutput(tcpQ, netToDevQ, tcpSelector, tcpSelectorQueue, this));
            
            FileChannel vpnInput = new FileInputStream(mInterface.getFileDescriptor()).getChannel();
            FileChannel vpnOutput = new FileOutputStream(mInterface.getFileDescriptor()).getChannel();

            vpnExecutorService.execute(new VPNInputRunnable(vpnInput, udpQ, tcpQ));
            vpnExecutorService.execute(new VPNOutputRunnable(vpnOutput, netToDevQ));

        } catch (Exception e) {
            Log.e("VPNBuilder", "VPN Başlatılamadı", e);
            stopSelf();
        }
    }

    private void stopVpn() {
        if (mScheduler != null) mScheduler.shutdownNow();
        if (vpnExecutorService != null) vpnExecutorService.shutdownNow();
        mWorkerExecutor.shutdownNow();
        if (mInterface != null) { try { mInterface.close(); } catch (Exception ignored) {} }
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "Vpn Service", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private Notification getForegroundNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Limitsiz VPN İzleme Modu")
                .setContentText("Ağ trafiği şeffaf proxy ile analiz ediliyor.")
                .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
                .build();
    }

    private void requestAnalyzePacket(boolean isUDP, int srcPort, int dstPort, InetAddress srcAddr, InetAddress dstAddr, String targetIp, int targetPort) {
        mWorkerExecutor.execute(() -> {
            int count = mPacketCounts.getOrDefault(targetIp, 0) + 1;
            mPacketCounts.put(targetIp, count);

            boolean shouldLog = false;
            long lastLogTime = mKnownIps.getOrDefault(targetIp, 0L);
            long currentTime = System.currentTimeMillis();

            // Sınırsız kaynak/sürekli akış: Bağlantı devam ediyorsa her 3 saniyede bir logla
            if (!mKnownIps.containsKey(targetIp)) {
                shouldLog = true;
            } else if (currentTime - lastLogTime > 3000) {
                shouldLog = true; 
            } else if (count > THRESHOLD_DANGEROUS) {
                if (currentTime - lastLogTime > 1000) { 
                    shouldLog = true;
                }
            }

            if (shouldLog) {
                String appName = mAppNameCache.get(targetIp);
                if (appName == null) {
                    appName = getAppNameFromSystem(isUDP, srcPort, dstPort, srcAddr, dstAddr);
                    mAppNameCache.put(targetIp, appName);
                }

                String hostName = mHostCache.get(targetIp);
                if (hostName == null) {
                    try { hostName = InetAddress.getByName(targetIp).getHostName(); } 
                    catch (Exception e) { hostName = targetIp; }
                    mHostCache.put(targetIp, hostName);
                }

                String level = "SAFE";
                String detail = "VPN ile İzleniyor (" + (isUDP ? "UDP" : "TCP") + " Port: " + targetPort + ")";

                if (count > THRESHOLD_DANGEROUS) {
                    level = "DANGEROUS";
                    detail = "DDoS Saldırı Tespiti! Saniyede " + count + " paket geldi.";
                } 
                else if (targetPort == 22) {
                    level = "WARNING";
                    detail = "Şüpheli SSH Bağlantısı";
                } else if (targetPort == 23) {
                    level = "DANGEROUS";
                    detail = "Şifresiz Telnet Bağlantısı";
                } else if (targetPort == 3389) {
                    level = "WARNING";
                    detail = "Uzak Masaüstü (RDP) Bağlantı Girişimi";
                } else if (targetPort == 445 || targetPort == 139) {
                    level = "DANGEROUS";
                    detail = "SMB Dosya Paylaşımı (Fidye Yazılımı Taraması)";
                } else if (targetPort == 53 && !isUDP) {
                    level = "WARNING";
                    detail = "TCP DNS Sorgusu (Normal dışı aktivite)";
                }

                TrafficLog log = new TrafficLog(currentTime, "10.0.0.2", targetIp, hostName, appName, 
                        isUDP ? "UDP" : "TCP", level, detail);
                db.trafficLogDao().insert(log);
                mKnownIps.put(targetIp, currentTime);
                
                sendBroadcast(new Intent(ACTION_LOG_UPDATE));
            }
        });
    }

    private String getAppNameFromSystem(boolean isUDP, int srcPort, int dstPort, InetAddress srcAddr, InetAddress dstAddr) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            try {
                int protocol = isUDP ? 17 : 6;
                int uid = cm.getConnectionOwnerUid(protocol, 
                        new java.net.InetSocketAddress(srcAddr, srcPort), 
                        new java.net.InetSocketAddress(dstAddr, dstPort));
                if (uid != -1) return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(getPackageManager().getPackagesForUid(uid)[0], 0)).toString();
            } catch (Exception ignored) {}
        }
        return "Sistem";
    }

    private class VPNInputRunnable implements Runnable {
        private final FileChannel vpnInput;
        private final ConcurrentLinkedQueue<Packet> uq;
        private final ConcurrentLinkedQueue<Packet> tq;

        public VPNInputRunnable(FileChannel vpnInput, ConcurrentLinkedQueue<Packet> uq, ConcurrentLinkedQueue<Packet> tq) {
            this.vpnInput = vpnInput; this.uq = uq; this.tq = tq;
        }

        @Override
        public void run() {
            while (!Thread.interrupted()) {
                ByteBuffer buffer = null;
                try {
                    buffer = ByteBufferPool.acquire();
                    int readBytes = vpnInput.read(buffer);
                    if (readBytes > 0) {
                        buffer.flip();
                        if (readBytes < 20) { ByteBufferPool.release(buffer); continue; }

                        byte version = (byte) ((buffer.get(0) & 0xFF) >> 4);
                        if (version != 4) { ByteBufferPool.release(buffer); continue; }

                        Packet packet = new Packet(buffer);
                        
                        if (packet.ip4Header != null) {
                            final boolean isUdp = packet.isUDP();
                            final boolean isTcp = packet.isTCP();
                            
                            // HARİKA TAVİZ: Performansı x100 artırmak için sadece yeni açılan bağlantıları (TCP SYN) veya UDP'yi analiz et!
                            // Akıp giden binlerce paketi tekrar tekrar analize sokmak yerine sadece bağlantının başını yakalıyoruz.
                            boolean shouldAnalyze = isUdp || (isTcp && packet.tcpHeader != null && packet.tcpHeader.isSYN());

                            if (shouldAnalyze) {
                                final int srcPort = isUdp ? packet.udpHeader.sourcePort : packet.tcpHeader.sourcePort;
                                final int dstPort = isUdp ? packet.udpHeader.destinationPort : packet.tcpHeader.destinationPort;
                                
                                String srcIp = packet.ip4Header.sourceAddress.getHostAddress();
                                String dstIp = packet.ip4Header.destinationAddress.getHostAddress();
                                boolean isSrcLocal = srcIp.startsWith("10.");
                                String remoteIp = isSrcLocal ? dstIp : srcIp;
                                int remotePort = isSrcLocal ? dstPort : srcPort;

                                requestAnalyzePacket(isUdp, srcPort, dstPort, packet.ip4Header.sourceAddress, packet.ip4Header.destinationAddress, remoteIp, remotePort);
                            }
                        }

                        if (packet.isUDP()) uq.offer(packet);
                        else if (packet.isTCP()) tq.offer(packet);
                        else ByteBufferPool.release(buffer);
                    } else {
                        ByteBufferPool.release(buffer);
                        Thread.sleep(1);
                    }
                } catch (Throwable e) {
                    Log.e("VPNInputRunnable", "Kritik Hata: İplik çöktü!", e);
                    if (buffer != null) ByteBufferPool.release(buffer);
                }
            }
        }
    }

    private class VPNOutputRunnable implements Runnable {
        private final FileChannel vpnOutput;
        private final ConcurrentLinkedQueue<ByteBuffer> nq;

        public VPNOutputRunnable(FileChannel vpnOutput, ConcurrentLinkedQueue<ByteBuffer> nq) {
            this.vpnOutput = vpnOutput; this.nq = nq;
        }

        @Override
        public void run() {
            while (!Thread.interrupted()) {
                ByteBuffer fromNet = null;
                try {
                    fromNet = nq.poll();
                    if (fromNet != null) {
                        fromNet.flip();
                        while (fromNet.hasRemaining()) {
                            vpnOutput.write(fromNet);
                        }
                        ByteBufferPool.release(fromNet);
                    } else {
                        Thread.sleep(1); 
                    }
                } catch (Throwable e) {
                    Log.e("VPNOutputRunnable", "Kritik Hata: İplik çöktü!", e);
                    if (fromNet != null) ByteBufferPool.release(fromNet);
                }
            }
        }
    }
}
