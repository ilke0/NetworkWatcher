package com.example.networkwatcher;

import java.io.Serializable;

/**
 * UI katmanı için kullanılan log veri modeli.
 * Veritabanı entity'si DEĞİLDİR — bunun için database.TrafficLog kullanılır.
 */
public class LogItem implements Serializable {
    public enum Status { SAFE, SUSPICIOUS, DANGEROUS }

    private String ip;
    private String hostName;
    private String details;
    private String time;
    private Status status;
    private int count;

    public LogItem(String ip, String hostName, String details, String time, Status status) {
        this.ip = ip;
        this.hostName = hostName;
        this.details = details;
        this.time = time;
        this.status = status;
        this.count = 1;
    }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getHostName() { return hostName; }
    public void setHostName(String hostName) { this.hostName = hostName; }
    public String getDetails() { return details; }
    public void setDetails(String details) { this.details = details; }
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public void incrementCount() { this.count++; }
}
