package vn.herotran.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/** Cấu hình hệ thống điều khiển từ xa (Firebase Realtime Database + device_id). */
public final class RemoteConfig {
    private RemoteConfig() {
    }

    // >>>>>> SỬA DUY NHẤT DÒNG NÀY trước khi build: URL Realtime Database của bạn <<<<<<
    // Ví dụ: https://ten-du-an-default-rtdb.firebaseio.com  (không có dấu / ở cuối)
    public static final String DEFAULT_DB_URL = "https://tvcontrol-d7dc1-default-rtdb.asia-southeast1.firebasedatabase.app";

    public static final int ADB_PORT = 5555;

    private static final String ID_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

    public static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("remote_cfg", Context.MODE_PRIVATE);
    }

    public static String dbUrl(Context c) {
        String u = prefs(c).getString("db_url", DEFAULT_DB_URL);
        if (u == null) {
            u = DEFAULT_DB_URL;
        }
        u = u.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    public static boolean isConfigured(Context c) {
        String u = dbUrl(c);
        return u.startsWith("https://") && !u.contains("YOUR-PROJECT");
    }

    public static synchronized String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device_id", null);
        if (id == null || id.length() < 6) {
            id = randomId();
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    public static synchronized String newDeviceId(Context c) {
        String id = randomId();
        prefs(c).edit().putString("device_id", id).remove("last_cmd_ts").apply();
        return id;
    }

    private static String randomId() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(ID_ALPHABET.charAt(r.nextInt(ID_ALPHABET.length())));
        }
        return sb.toString();
    }
}
