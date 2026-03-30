package com.example.networkwatcher;

import java.io.Serializable;

public class LogItem implements Serializable {
    public enum Status { SAFE, SUSPICIOUS, DANGEROUS }

    private String ip;
    private String details;
    private String time;
    private Status status;

    public LogItem(String ip, String details, String time, Status status) {
        this.ip = ip;
        this.details = details;
        this.time = time;
        this.status = status;
    }

    public String getIp() { return ip; }
    public String getDetails() { return details; }
    public String getTime() { return time; }
    public Status getStatus() { return status; }
}
