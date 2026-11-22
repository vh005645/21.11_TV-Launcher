package vn.herotran.launcher;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * Foreground service chạy 24/7: nghe lệnh từ Firebase, thực thi qua Local ADB,
 * báo trạng thái ngược lại cho điện thoại.
 */
public class RemoteService extends Service {
    private static final String TAG = "RemoteService";
    public static final String ACTION_FIRE_SCHEDULE = "vn.herotran.launcher.FIRE_SCHEDULE";
    private static final String ACTION_ALARM = "vn.herotran.launcher.SCHEDULE_ALARM";
    private static final long HEARTBEAT_MS = 30000L;

    private static volatile RemoteService sInstance;

    public static RemoteService get() {
        return sInstance;
    }

    public static void start(Context c) {
        try {
            Intent i = new Intent(c, RemoteService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Không khởi động được RemoteService", t);
        }
    }

    // ------------------------------------------------------------------ phím từ AccessibilityService

    private static final List<Long> menuPresses = new ArrayList<Long>();

    /**
     * Được VoiceAccessibilityService gọi cho mọi phím trên remote.
     * Trả về true nếu phím đã được xử lý (tiêu thụ).
     */
    public static boolean handleKey(Context ctx, KeyEvent event) {
        RemoteService s = sInstance;
        int code = event.getKeyCode();
        int action = event.getAction();

        // Phím nguồn luôn được để hệ thống xử lý (tắt TV bằng remote = mở khóa)
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_SLEEP || code == KeyEvent.KEYCODE_WAKEUP
                || code == KeyEvent.KEYCODE_TV_POWER || code == KeyEvent.KEYCODE_STB_POWER) {
            return false;
        }
        // Đang khóa: chặn toàn bộ các phím còn lại
        if (s != null && s.isLocked()) {
            return true;
        }
        // Đang hiện ảnh hoặc tin nhắn: BACK/HOME để đóng
        if (s != null && s.overlay != null && (s.overlay.isImageShown() || s.overlay.isMessageShown())
                && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_HOME)) {
            if (action == KeyEvent.ACTION_DOWN) {
                s.main.post(new Runnable() {
                    @Override
                    public void run() {
                        RemoteService x = sInstance;
                        if (x != null) {
                            boolean wasImage = x.overlay.isImageShown();
                            x.overlay.hideMessage();
                            x.overlay.hideImage();
                            if (wasImage) {
                                x.onImageClosed();
                            }
                        }
                    }
                });
            }
            return true;
        }
        // Menu x5 trong 4 giây: mở màn hình cài đặt ẩn
        if (code == KeyEvent.KEYCODE_MENU && action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            long now = System.currentTimeMillis();
            synchronized (menuPresses) {
                menuPresses.add(now);
                while (!menuPresses.isEmpty() && now - menuPresses.get(0) > 4000L) {
                    menuPresses.remove(0);
                }
                if (menuPresses.size() >= 5) {
                    menuPresses.clear();
                    try {
                        Intent i = new Intent(ctx, RemoteSettingsActivity.class);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    } catch (Throwable t) {
                        Log.w(TAG, "Không mở được màn hình cài đặt", t);
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ trạng thái

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread workerThread;
    private Handler worker;
    private final ExecutorService adbExec = Executors.newSingleThreadExecutor();
    private final AtomicBoolean adbBusy = new AtomicBoolean(false);
    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver screenReceiver;

    private volatile boolean running;
    private Thread sseThread;
    private volatile FirebaseRest fb;
    private AdbClient adb;
    private OverlayController overlay;

    private volatile String adbMessage = "Chưa kết nối";
    private volatile String lastResult = "Ready";

    private final Object scheduleLock = new Object();

    public boolean isLocked() {
        return RemoteConfig.prefs(this).getBoolean("is_locked", false);
    }

    public boolean isAdbConnected() {
        return adb != null && adb.isConnected();
    }

    public String getAdbMessage() {
        return adbMessage;
    }

    public boolean overlayGranted() {
        return overlay != null && overlay.canDraw();
    }

    // ------------------------------------------------------------------ vòng đời

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        startAsForeground();

        workerThread = new HandlerThread("RemoteWorker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        adb = new AdbClient(this, RemoteConfig.ADB_PORT);
        overlay = new OverlayController(this, new OverlayController.Listener() {
            @Override
            public void onImageClosedByUser() {
                onImageClosed();
            }
        });

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tvlauncher:remote");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "WakeLock lỗi", t);
        }

        registerScreenReceiver();
        running = true;
        restoreState();
        worker.post(heartbeat);
        startSseThread();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        if (intent != null && ACTION_FIRE_SCHEDULE.equals(intent.getAction())) {
            requestFireSchedule();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        sInstance = null;
        FirebaseRest f = fb;
        if (f != null) {
            f.closeStream();
        }
        if (screenReceiver != null) {
            try {
                unregisterReceiver(screenReceiver);
            } catch (Throwable ignored) {
            }
            screenReceiver = null;
        }
        main.post(new Runnable() {
            @Override
            public void run() {
                if (overlay != null) {
                    overlay.hideAll();
                }
            }
        });
        if (adb != null) {
            adb.close();
        }
        adbExec.shutdownNow();
        if (workerThread != null) {
            workerThread.quitSafely();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Throwable ignored) {
            }
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startAsForeground() {
        try {
            String channelId = "tv_remote_fg";
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(channelId, "TV Remote Service",
                        NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(ch);
            }
            Notification n = new Notification.Builder(this, channelId)
                    .setContentTitle("TV Remote")
                    .setSmallIcon(android.R.drawable.ic_menu_info_details)
                    .build();
            startForeground(1002, n);
        } catch (Throwable t) {
            Log.e(TAG, "startForeground lỗi", t);
        }
    }

    // ------------------------------------------------------------------ khôi phục sau khởi động lại

    private int currentBootCount() {
        try {
            return Settings.Global.getInt(getContentResolver(), "boot_count", -1);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Người dùng bấm phím nguồn trên remote (TV tắt màn hình) trong lúc đang khóa -> mở khóa. */
    private void autoUnlockByPower() {
        RemoteConfig.prefs(this).edit().putBoolean("is_locked", false).apply();
        main.post(new Runnable() {
            @Override
            public void run() {
                overlay.hideLock();
            }
        });
        worker.post(new Runnable() {
            @Override
            public void run() {
                restoreVolumeAfterLock();
                reportResult("TV đã tắt nguồn bằng remote - tự mở khóa");
                publishStatus();
            }
        });
    }

    private void restoreState() {
        final SharedPreferences p = RemoteConfig.prefs(this);
        if (p.getBoolean("is_locked", false)) {
            int boot = currentBootCount();
            if (boot != -1 && p.getInt("lock_boot", -2) != boot) {
                // TV đã tắt rồi bật lại kể từ lúc khóa: trở lại bình thường
                p.edit().putBoolean("is_locked", false).apply();
                worker.post(new Runnable() {
                    @Override
                    public void run() {
                        restoreVolumeAfterLock();
                    }
                });
            }
        }
        if (p.getBoolean("is_locked", false)) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    overlay.showLock();
                }
            });
        }
        long at = p.getLong("schedule_at", 0);
        if (at > 0) {
            long now = System.currentTimeMillis();
            if (at > now + 2000) {
                armAlarm(at);
            } else if (at >= now - 120000L) {
                requestFireSchedule();
            } else {
                clearSchedule();
            }
        }
    }

    // ------------------------------------------------------------------ nhịp tim + ADB

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                if (!isAdbConnected()) {
                    connectAdbAsync(false);
                }
                maintainSystem();
                publishStatus();
            } catch (Throwable t) {
                Log.w(TAG, "heartbeat lỗi", t);
            }
            worker.postDelayed(this, HEARTBEAT_MS);
        }
    };

    /**
     * Tự duy trì các cài đặt hệ thống (chạy mỗi 30 giây và ngay khi service khởi động lại,
     * kể cả sau khi xóa đa nhiệm hoặc tắt/bật TV):
     * - tự cấp lại WRITE_SECURE_SETTINGS và quyền Overlay qua Local ADB nếu bị mất
     * - bật lại ADB và dịch vụ trợ năng (accessibility) nếu hệ thống đã tắt
     * - dựng lại lớp phủ khóa nếu TV đang ở trạng thái khóa
     */
    private void maintainSystem() {
        String pkg = getPackageName();
        try {
            if (adb != null && adb.isConnected()) {
                if (checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                        != PackageManager.PERMISSION_GRANTED) {
                    try {
                        adb.shell("pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS", 10000);
                    } catch (IOException e) {
                        Log.w(TAG, "Tự cấp WRITE_SECURE_SETTINGS lỗi: " + e.getMessage());
                    }
                }
                if (overlay != null && !overlay.canDraw()) {
                    try {
                        adb.shell("cmd appops set " + pkg + " SYSTEM_ALERT_WINDOW allow", 10000);
                    } catch (IOException e) {
                        Log.w(TAG, "Tự cấp Overlay lỗi: " + e.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "maintainSystem(grant) lỗi", t);
        }
        try {
            AdbHelper.ensureAdbEnabled(this, false);
        } catch (Throwable ignored) {
        }
        try {
            if (VoiceAccessibilityService.getInstance() == null) {
                VoiceHelper.ensureVoiceConfigured(this);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (isLocked() && overlay != null && !overlay.isLockShown() && overlay.canDraw()) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        overlay.showLock();
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /** Kết nối ADB ở luồng riêng. userInitiated=true: chờ tới 70s cho người dùng bấm Allow. */
    public void connectAdbAsync(final boolean userInitiated) {
        if (!adbBusy.compareAndSet(false, true)) {
            return;
        }
        try {
            adbExec.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (adb.isConnected()) {
                            adbMessage = "Đã kết nối";
                            return;
                        }
                        adbMessage = userInitiated ? "Đang kết nối... hãy bấm Allow trên TV" : "Đang kết nối...";
                        try {
                            AdbHelper.ensureAdbEnabled(RemoteService.this, userInitiated);
                        } catch (Throwable ignored) {
                        }
                        adb.connect(userInitiated ? 70000 : 8000);
                        adbMessage = "Đã kết nối";
                    } catch (IOException e) {
                        adbMessage = "Lỗi: " + e.getMessage();
                    } catch (Throwable t) {
                        adbMessage = "Lỗi: " + t;
                    } finally {
                        adbBusy.set(false);
                        if (worker != null) {
                            worker.post(new Runnable() {
                                @Override
                                public void run() {
                                    publishStatus();
                                }
                            });
                        }
                    }
                }
            });
        } catch (Throwable t) {
            adbBusy.set(false);
        }
    }

    /** Test ADB: chạy "echo" và hiển thị kết quả trong adbMessage. */
    public void testAdbAsync() {
        worker.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String r = sh("echo ADB_OK");
                    adbMessage = "Test ADB: " + r;
                } catch (Throwable t) {
                    adbMessage = "Test ADB lỗi: " + t.getMessage();
                }
            }
        });
    }

    /** Cấp quyền SYSTEM_ALERT_WINDOW cho chính app qua ADB. */
    public boolean grantOverlayViaAdb() {
        try {
            sh("cmd appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            Thread.sleep(500);
        } catch (Throwable t) {
            Log.w(TAG, "grantOverlayViaAdb lỗi", t);
        }
        return overlay != null && overlay.canDraw();
    }

    /** Chạy lệnh shell; nếu chưa kết nối sẽ thử kết nối nhanh một lần. */
    private String sh(String cmd) throws IOException {
        ensureAdbOrThrow();
        try {
            return adb.shell(cmd, 10000);
        } catch (IOException first) {
            // kết nối có thể đã chết: thử nối lại một lần
            ensureAdbOrThrow();
            return adb.shell(cmd, 10000);
        }
    }

    private void ensureAdbOrThrow() throws IOException {
        if (adb.isConnected()) {
            return;
        }
        try {
            AdbHelper.ensureAdbEnabled(this, false);
        } catch (Throwable ignored) {
        }
        try {
            adb.connect(8000);
            adbMessage = "Đã kết nối";
        } catch (IOException e) {
            adbMessage = "Lỗi: " + e.getMessage();
            throw e;
        }
    }

    // ------------------------------------------------------------------ nghe Firebase (SSE)

    private void startSseThread() {
        sseThread = new Thread(new Runnable() {
            @Override
            public void run() {
                long backoff = 3000L;
                while (running) {
                    try {
                        if (!RemoteConfig.isConfigured(RemoteService.this)) {
                            Thread.sleep(15000L);
                            continue;
                        }
                        String id = RemoteConfig.deviceId(RemoteService.this);
                        FirebaseRest f = new FirebaseRest(RemoteConfig.dbUrl(RemoteService.this));
                        fb = f;
                        final String cmdPath = "devices/" + id + "/command";

                        // Mốc lệnh cũ: không chạy lại lệnh đã tồn tại từ trước
                        SharedPreferences p = RemoteConfig.prefs(RemoteService.this);
                        if (!p.contains("last_cmd_ts")) {
                            long ts = 0;
                            try {
                                ts = new JSONObject(f.get(cmdPath)).optLong("timestamp", 0);
                            } catch (org.json.JSONException ignored) {
                            }
                            p.edit().putLong("last_cmd_ts", ts).apply();
                        }

                        worker.post(new Runnable() {
                            @Override
                            public void run() {
                                publishStatus();
                            }
                        });

                        f.stream(cmdPath, new FirebaseRest.StreamListener() {
                            @Override
                            public void onEvent(String eventName, final String dataJson) {
                                backoffReset();
                                handleStreamEvent(dataJson);
                            }
                        });
                        backoff = 3000L;
                    } catch (InterruptedException ie) {
                        return;
                    } catch (Throwable t) {
                        Log.w(TAG, "SSE lỗi: " + t.getMessage());
                    }
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        return;
                    }
                    backoff = Math.min(backoff * 2, 30000L);
                }
            }
        }, "RemoteSSE");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    private void backoffReset() {
        // chỗ móc để mở rộng; luồng SSE tự đặt lại backoff khi kết thúc bình thường
    }

    /** Ngắt luồng nghe hiện tại để kết nối lại (ví dụ sau khi đổi device_id). */
    public void restartStream() {
        FirebaseRest f = fb;
        if (f != null) {
            f.closeStream();
        }
        worker.post(new Runnable() {
            @Override
            public void run() {
                publishStatus();
            }
        });
    }

    private void handleStreamEvent(final String dataJson) {
        try {
            JSONObject ev = new JSONObject(dataJson);
            String path = ev.optString("path", "/");
            Object data = ev.opt("data");
            if ("/".equals(path) && data instanceof JSONObject) {
                final JSONObject cmd = (JSONObject) data;
                worker.post(new Runnable() {
                    @Override
                    public void run() {
                        handleCommand(cmd);
                    }
                });
            } else if (data != null && !JSONObject.NULL.equals(data)) {
                // Thay đổi một phần: tải lại cả node command
                worker.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            FirebaseRest f = fb;
                            if (f == null) {
                                return;
                            }
                            String body = f.get("devices/" + RemoteConfig.deviceId(RemoteService.this) + "/command");
                            handleCommand(new JSONObject(body));
                        } catch (Throwable t) {
                            Log.w(TAG, "Tải lại command lỗi: " + t.getMessage());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            Log.w(TAG, "Sự kiện SSE không hiểu được: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ thực thi lệnh

    private void handleCommand(JSONObject c) {
        SharedPreferences p = RemoteConfig.prefs(this);
        long ts = c.optLong("timestamp", 0);
        long last = p.getLong("last_cmd_ts", 0);
        if (ts == 0 || ts == last) {
            return;
        }
        p.edit().putLong("last_cmd_ts", ts).apply();

        String action = c.optString("action", "NONE");
        String payload = c.optString("payload", "");
        if ("NONE".equals(action) || action.length() == 0) {
            return;
        }
        Log.i(TAG, "Lệnh: " + action);

        c = null; // giải phóng bản sao lệnh (có thể chứa ảnh Base64) khỏi bộ nhớ
        String result;
        try {
            result = execute(action, payload);
        } catch (Throwable t) {
            Log.e(TAG, "Lệnh " + action + " lỗi", t);
            result = "Lỗi " + action + ": " + t.getMessage();
        }
        if ("SHOW_IMAGE".equals(action)) {
            clearCommandPayload(ts);
        }
        payload = null;
        reportResult(result);
        publishStatus();
    }

    /** Xóa ảnh Base64 khỏi node command trên Firebase (giữ nguyên timestamp để không chạy lại lệnh). */
    private void clearCommandPayload(long ts) {
        try {
            FirebaseRest f = fb;
            if (f == null) {
                return;
            }
            JSONObject o = new JSONObject();
            o.put("action", "NONE");
            o.put("payload", "");
            o.put("timestamp", ts);
            f.put("devices/" + RemoteConfig.deviceId(this) + "/command", o.toString());
        } catch (Throwable t) {
            Log.w(TAG, "clearCommandPayload lỗi: " + t.getMessage());
        }
    }

    private String execute(String action, String payload) throws Exception {
        if ("LOCK_NOW".equals(action)) {
            return doLock();
        } else if ("UNLOCK".equals(action)) {
            return doUnlock();
        } else if ("POWER_OFF".equals(action)) {
            return doPowerOff();
        } else if ("VOL_UP".equals(action)) {
            return doVolumeKey(24, AudioManager.ADJUST_RAISE, "Đã tăng âm lượng");
        } else if ("VOL_DOWN".equals(action)) {
            return doVolumeKey(25, AudioManager.ADJUST_LOWER, "Đã giảm âm lượng");
        } else if ("MUTE".equals(action)) {
            return doVolumeKey(164, AudioManager.ADJUST_TOGGLE_MUTE, "Đã bật/tắt tiếng");
        } else if ("SET_VOL".equals(action)) {
            return doSetVolume(payload);
        } else if ("SHOW_MSG".equals(action)) {
            return doShowMessage(payload);
        } else if ("SCREENSHOT".equals(action)) {
            return doScreenshot();
        } else if ("SCHEDULE_ACTION".equals(action)) {
            return doSchedule(payload);
        } else if ("CANCEL_SCHEDULE".equals(action)) {
            clearSchedule();
            return "Đã hủy hẹn giờ";
        } else if ("SHOW_IMAGE".equals(action)) {
            return doShowImage(payload);
        } else if ("HIDE_IMAGE".equals(action)) {
            return doHideImage();
        }
        return "Lệnh không hỗ trợ: " + action;
    }

    // --- KHÓA / MỞ KHÓA / TẮT NGUỒN

    /** Tắt tiếng TV khi khóa (nhớ mức âm lượng cũ để trả lại sau khi mở khóa). */
    private void muteForLock() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            SharedPreferences p = RemoteConfig.prefs(this);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (cur > 0 && !p.contains("lock_prev_vol")) {
                p.edit().putInt("lock_prev_vol", cur).apply();
            }
            try {
                sh("cmd media_session volume --stream 3 --set 0");
            } catch (Throwable t) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            }
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            Log.w(TAG, "muteForLock lỗi: " + t.getMessage());
        }
    }

    /** Trả lại âm lượng như trước khi khóa. */
    private void restoreVolumeAfterLock() {
        try {
            SharedPreferences p = RemoteConfig.prefs(this);
            int prev = p.getInt("lock_prev_vol", -1);
            p.edit().remove("lock_prev_vol").apply();
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            } catch (Throwable ignored) {
            }
            if (prev > 0) {
                try {
                    sh("cmd media_session volume --stream 3 --set " + prev);
                } catch (Throwable t) {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, prev, 0);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "restoreVolumeAfterLock lỗi: " + t.getMessage());
        }
    }

    private String doLock() {
        RemoteConfig.prefs(this).edit()
                .putBoolean("is_locked", true)
                .putInt("lock_boot", currentBootCount())
                .apply();
        muteForLock();
        final boolean[] drawn = new boolean[1];
        runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                drawn[0] = overlay.showLock();
            }
        });
        return drawn[0]
                ? "Đã khóa TV (chữ \"Tắt TV ngay !\" luôn hiện; bấm nguồn trên remote hoặc dùng app để mở khóa)"
                : "Đã khóa TV nhưng THIẾU quyền Overlay (đang tự cấp qua ADB, thử lại sau ~30 giây)";
    }

    private String doUnlock() {
        RemoteConfig.prefs(this).edit().putBoolean("is_locked", false).apply();
        restoreVolumeAfterLock();
        runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                overlay.hideLock();
            }
        });
        try {
            sh("input keyevent 224"); // WAKEUP
        } catch (Throwable t) {
            return "Đã mở khóa (ADB lỗi: " + t.getMessage() + ")";
        }
        return "Đã mở khóa TV";
    }

    /** Tắt TV như bấm nhanh phím nguồn trên remote: adb shell input keyevent 26 (KEYCODE_POWER). */
    private String doPowerOff() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                // Màn hình đã tắt: bấm phím nguồn lúc này sẽ BẬT TV lên, nên bỏ qua
                return "TV đang tắt sẵn, không gửi lệnh nguồn";
            }
        } catch (Throwable ignored) {
        }
        reportResult("Đang tắt TV...");
        try {
            putStatusOffline();
            sh("input keyevent 26");
        } catch (Throwable t) {
            return "Tắt TV lỗi: " + t.getMessage();
        }
        return "Đã gửi lệnh tắt TV (input keyevent 26)";
    }

    // --- ÂM LƯỢNG

    private String doVolumeKey(int keyCode, int audioAdjust, String okMsg) {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean viaAdb = true;
        try {
            sh("input keyevent " + keyCode);
        } catch (Throwable t) {
            viaAdb = false;
            try {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, audioAdjust, AudioManager.FLAG_SHOW_UI);
            } catch (Throwable t2) {
                return "Không chỉnh được âm lượng: " + t2.getMessage();
            }
        }
        sleepQuiet(400);
        return okMsg + (viaAdb ? "" : " (qua AudioManager)");
    }

    private String doSetVolume(String payload) {
        int pct;
        try {
            pct = Integer.parseInt(payload.trim());
        } catch (NumberFormatException e) {
            return "SET_VOL: payload không hợp lệ";
        }
        pct = Math.max(0, Math.min(100, pct));
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        int idx = Math.round(pct * max / 100f);
        boolean viaAdb = true;
        try {
            sh("cmd media_session volume --show --stream 3 --set " + idx);
        } catch (Throwable t) {
            viaAdb = false;
            try {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, AudioManager.FLAG_SHOW_UI);
            } catch (Throwable t2) {
                return "Không đặt được âm lượng: " + t2.getMessage();
            }
        }
        sleepQuiet(400);
        return "Đã đặt âm lượng " + pct + "%" + (viaAdb ? "" : " (qua AudioManager)");
    }

    // --- TIN NHẮN

    private String doShowMessage(final String text) {
        final boolean[] ok = new boolean[1];
        runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                ok[0] = overlay.showMessage(text, 40000L);
            }
        });
        return ok[0] ? "Đã hiện tin nhắn trên TV" : "Không hiện được tin nhắn (thiếu quyền Overlay?)";
    }

    // --- ẢNH

    private String doShowImage(String payload) {
        String b64 = payload;
        int comma = b64.indexOf(',');
        if (b64.startsWith("data:") && comma > 0) {
            b64 = b64.substring(comma + 1);
        }
        byte[] raw;
        try {
            raw = Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return "Ảnh Base64 không hợp lệ";
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        int sample = 1;
        while (o.outWidth / sample > 2600 || o.outHeight / sample > 1500) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        final Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (bmp == null) {
            return "Không giải mã được ảnh";
        }
        final boolean[] ok = new boolean[1];
        runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                ok[0] = overlay.showImage(bmp);
            }
        });
        if (!ok[0]) {
            bmp.recycle();
            return "Không hiện được ảnh (thiếu quyền Overlay?)";
        }
        return "Đang hiện ảnh trên TV";
    }

    private String doHideImage() {
        runOnMainAndWait(new Runnable() {
            @Override
            public void run() {
                overlay.hideImage();
            }
        });
        return "Đã tắt ảnh trên TV";
    }

    /** Người dùng đóng ảnh bằng remote TV: báo lại cho điện thoại. */
    private void onImageClosed() {
        if (worker == null) {
            return;
        }
        worker.post(new Runnable() {
            @Override
            public void run() {
                reportResult("Ảnh đã được tắt trên TV");
                publishStatus();
            }
        });
    }

    // --- CHỤP MÀN HÌNH

    private String doScreenshot() throws Exception {
        sleepQuiet(200);
        byte[] png;
        try {
            ensureAdbOrThrow();
            png = adb.exec("screencap -p", 20000, 40 * 1024 * 1024);
        } catch (IOException first) {
            ensureAdbOrThrow();
            png = adb.exec("screencap -p", 20000, 40 * 1024 * 1024);
        }
        // Bỏ rác trước chữ ký PNG (nếu có)
        int start = 0;
        for (int i = 0; i + 3 < png.length && i < 512; i++) {
            if ((png[i] & 0xff) == 0x89 && png[i + 1] == 'P' && png[i + 2] == 'N' && png[i + 3] == 'G') {
                start = i;
                break;
            }
        }
        if (png.length - start < 1000) {
            return "Chụp màn hình thất bại (dữ liệu rỗng)";
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(png, start, png.length - start, o);
        if (o.outWidth <= 0) {
            return "Chụp màn hình thất bại (không đọc được PNG)";
        }
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 480) {
            sample *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap full = BitmapFactory.decodeByteArray(png, start, png.length - start, o);
        if (full == null) {
            return "Chụp màn hình thất bại (giải mã lỗi)";
        }
        int w = 480;
        int h = Math.max(1, Math.round(full.getHeight() * 480f / full.getWidth()));
        Bitmap small = Bitmap.createScaledBitmap(full, w, h, true);
        if (small != full) {
            full.recycle();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 45, bos);
        small.recycle();
        String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);

        FirebaseRest f = fb;
        if (f == null) {
            return "Chưa kết nối Firebase";
        }
        String base = "devices/" + RemoteConfig.deviceId(this) + "/response";
        f.put(base + "/screenshot_base64", JSONObject.quote(b64));
        f.put(base + "/screenshot_time", String.valueOf(System.currentTimeMillis()));
        return "Đã chụp màn hình TV";
    }

    // --- HẸN GIỜ

    private String doSchedule(String payload) throws Exception {
        JSONObject o = new JSONObject(payload);
        String type = o.optString("type", "LOCK_NOW");
        int minutes = o.optInt("minutes", 0);
        if (minutes <= 0 || minutes > 24 * 60) {
            return "Hẹn giờ: số phút không hợp lệ";
        }
        if (!"LOCK_NOW".equals(type) && !"POWER_OFF".equals(type)) {
            return "Hẹn giờ: hành động không hợp lệ";
        }
        long at = System.currentTimeMillis() + minutes * 60000L;
        synchronized (scheduleLock) {
            RemoteConfig.prefs(this).edit()
                    .putLong("schedule_at", at)
                    .putString("schedule_type", type)
                    .apply();
        }
        armAlarm(at);
        return "Đã hẹn " + ("LOCK_NOW".equals(type) ? "khóa" : "tắt") + " TV sau " + minutes + " phút";
    }

    private PendingIntent alarmIntent() {
        Intent i = new Intent(this, ScheduleReceiver.class);
        i.setAction(ACTION_ALARM);
        return PendingIntent.getBroadcast(this, 7, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void armAlarm(long at) {
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = alarmIntent();
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (Throwable t) {
            Log.w(TAG, "Exact alarm lỗi, dùng alarm thường", t);
            try {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    private void clearSchedule() {
        synchronized (scheduleLock) {
            RemoteConfig.prefs(this).edit().remove("schedule_at").remove("schedule_type").apply();
        }
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            am.cancel(alarmIntent());
        } catch (Throwable ignored) {
        }
    }

    /** Gọi từ ScheduleReceiver / onStartCommand khi hết giờ. */
    public void requestFireSchedule() {
        if (worker == null) {
            return;
        }
        worker.post(new Runnable() {
            @Override
            public void run() {
                String type;
                synchronized (scheduleLock) {
                    SharedPreferences p = RemoteConfig.prefs(RemoteService.this);
                    if (p.getLong("schedule_at", 0) <= 0) {
                        return; // đã xử lý rồi
                    }
                    type = p.getString("schedule_type", "LOCK_NOW");
                }
                clearSchedule();
                String r;
                try {
                    r = "POWER_OFF".equals(type) ? doPowerOff() : doLock();
                } catch (Throwable t) {
                    r = "Hẹn giờ lỗi: " + t.getMessage();
                }
                reportResult("Hết giờ: " + r);
                publishStatus();
            }
        });
    }

    // ------------------------------------------------------------------ nghe bật/tắt màn hình

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String a = intent == null ? null : intent.getAction();
                if (a == null || worker == null) {
                    return;
                }
                if (Intent.ACTION_SCREEN_OFF.equals(a) && isLocked()) {
                    autoUnlockByPower();
                } else if (Intent.ACTION_SCREEN_ON.equals(a) && isLocked()) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            overlay.showLock();
                        }
                    });
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
    }

    // ------------------------------------------------------------------ báo cáo lên Firebase

    private void reportResult(String msg) {
        lastResult = msg;
        try {
            FirebaseRest f = fb;
            if (f != null) {
                f.put("devices/" + RemoteConfig.deviceId(this) + "/response/last_result", JSONObject.quote(msg));
            }
        } catch (Throwable t) {
            Log.w(TAG, "reportResult lỗi: " + t.getMessage());
        }
    }

    private int volumePercent(AudioManager am) {
        int max = Math.max(1, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max);
    }

    private JSONObject buildStatus(boolean online) throws Exception {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        SharedPreferences p = RemoteConfig.prefs(this);
        JSONObject s = new JSONObject();
        s.put("online", online);
        s.put("adb_connected", isAdbConnected());
        JSONObject sv = new JSONObject();
        sv.put(".sv", "timestamp");
        s.put("last_seen", sv);
        s.put("volume", volumePercent(am));
        s.put("is_muted", am.isStreamMute(AudioManager.STREAM_MUSIC));
        s.put("is_locked", p.getBoolean("is_locked", false));
        s.put("is_showing_image", overlay != null && overlay.isImageShown());
        s.put("schedule_at", p.getLong("schedule_at", 0));
        s.put("schedule_type", p.getString("schedule_type", ""));
        return s;
    }

    private void publishStatus() {
        try {
            FirebaseRest f = fb;
            if (f == null || !RemoteConfig.isConfigured(this)) {
                return;
            }
            f.put("devices/" + RemoteConfig.deviceId(this) + "/status", buildStatus(true).toString());
        } catch (Throwable t) {
            Log.w(TAG, "publishStatus lỗi: " + t.getMessage());
        }
    }

    private void putStatusOffline() {
        try {
            FirebaseRest f = fb;
            if (f != null) {
                f.put("devices/" + RemoteConfig.deviceId(this) + "/status", buildStatus(false).toString());
            }
        } catch (Throwable t) {
            Log.w(TAG, "putStatusOffline lỗi: " + t.getMessage());
        }
    }

    // ------------------------------------------------------------------ tiện ích

    private void runOnMainAndWait(final Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final Object lock = new Object();
        final boolean[] done = new boolean[1];
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } finally {
                    synchronized (lock) {
                        done[0] = true;
                        lock.notifyAll();
                    }
                }
            }
        });
        synchronized (lock) {
            long end = System.currentTimeMillis() + 5000;
            while (!done[0]) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
