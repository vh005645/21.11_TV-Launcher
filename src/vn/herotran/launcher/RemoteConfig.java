package vn.herotran.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** Cấu hình hệ thống điều khiển từ xa (Firebase Realtime Database + device_id). */
public final class RemoteConfig {
    private RemoteConfig() {
    }

    // >>>>>> SỬA DUY NHẤT DÒNG NÀY trước khi build: URL Realtime Database của bạn <<<<<<
    // Ví dụ: https://ten-du-an-default-rtdb.firebaseio.com  (không có dấu / ở cuối)
    public static final String DEFAULT_DB_URL = "https://tvcontrol-d7dc1-default-rtdb.asia-southeast1.firebasedatabase.app";

    // Mã thiết bị CỐ ĐỊNH (dãy số). App điện thoại dùng đúng mã này (RemoteViewModel.DEFAULT_DEVICE_ID).
    // Muốn đổi thì sửa ở CẢ HAI nơi cho giống nhau.
    public static final String DEVICE_ID = "246500068385";

    public static final int ADB_PORT = 5555;

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

    public static String deviceId(Context c) {
        return DEVICE_ID;
    }
}
