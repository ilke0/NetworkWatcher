package com.example.networkwatcher;

import java.io.Serializable;

public class LogItem implements Serializable {
    public enum Status { SAFE, SUSPICIOUS, DANGEROUS }
    public enum Protocol { TCP, UDP, DNS, ICMP, OTHER } // ← YENİ

    private String ip;
    private String details;
    private String time;
    private Status status;
    private Protocol protocol; // ← YENİ

    public LogItem(String ip, String details, String time, Status status, Protocol protocol) {
        this.ip = ip;
        this.details = details;
        this.time = time;
        this.status = status;
        this.protocol = protocol; // ← YENİ
    }

    public String getIp() { return ip; }
    public String getDetails() { return details; }
    public String getTime() { return time; }
    public Status getStatus() { return status; }
    public Protocol getProtocol() { return protocol; } // ← YENİ
}
