package com.example.networkwatcher.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "traffic_logs")
public class TrafficLog {
    @PrimaryKey(autoGenerate = true)
    public int id;
    
    public long timestamp;
    public String srcIp;
    public String dstIp;
    public String hostName;
    public String appName;
    public String protocol;
    public String threatLevel;
    public String details;
    public int count; // Rapor Bölüm 10.3: Paket sayacı

    public TrafficLog(long timestamp, String srcIp, String dstIp, String hostName, 
                      String appName, String protocol, String threatLevel, String details) {
        this.timestamp = timestamp;
        this.srcIp = srcIp;
        this.dstIp = dstIp;
        this.hostName = hostName;
        this.appName = appName;
        this.protocol = protocol;
        this.threatLevel = threatLevel;
        this.details = details;
        this.count = 1;
    }
}
