package com.example.networkwatcher;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class LogPersistence {
    private static final String PREF_NAME = "NetworkLogs";
    private static final String KEY_LOGS = "logs_list";

    public static void saveLog(Context context, LogItem item) {
        List<LogItem> logs = getLogs(context);
        logs.add(0, item); // Yeni logu başa ekle
        if (logs.size() > 100) logs.remove(logs.size() - 1); // Limit 100
        
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = new Gson().toJson(logs);
        prefs.edit().putString(KEY_LOGS, json).apply();
    }

    public static List<LogItem> getLogs(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_LOGS, null);
        if (json == null) return new ArrayList<>();
        
        Type type = new TypeToken<ArrayList<LogItem>>() {}.getType();
        return new Gson().fromJson(json, type);
    }
}
