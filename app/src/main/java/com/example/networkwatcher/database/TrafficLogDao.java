package com.example.networkwatcher.database;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import java.util.List;

@Dao
public interface TrafficLogDao {
    @Insert
    void insert(TrafficLog log);

    @Query("SELECT * FROM traffic_logs ORDER BY timestamp DESC LIMIT 200")
    List<TrafficLog> getAllLogs();

    @Query("DELETE FROM traffic_logs")
    void deleteAll();
}
