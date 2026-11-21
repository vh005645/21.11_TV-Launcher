package vn.herotran.launcher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Màn hình cài đặt ẩn: mở bằng cách bấm phím Menu 5 lần trên remote. Điều khiển hoàn toàn bằng D-Pad. */
public class RemoteSettingsActivity extends Activity {
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView tvId;
    private TextView tvInfo;
    private Button firstButton;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateTexts();
            h.postDelayed(this, 1000);
        }
    };

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) {
            t.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return t;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(24), dp(12), dp(24), dp(12));
        StateListDrawable sl = new StateListDrawable();
        sl.addState(new int[] { android.R.attr.state_focused }, new ColorDrawable(Color.parseColor("#2979FF")));
        sl.addState(new int[] { android.R.attr.state_pressed }, new ColorDrawable(Color.parseColor("#2979FF")));
        sl.addState(new int[] {}, new ColorDrawable(Color.parseColor("#2A2F3A")));
        b.setBackground(sl);
        b.setFocusable(true);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        return lp;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        RemoteService.start(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#10141C"));
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(32), dp(48), dp(32));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(label("Cài đặt điều khiển từ xa", 30, Color.WHITE, true));
        root.addView(label("Mã thiết bị (cố định, app điện thoại dùng cùng mã này):", 18, Color.parseColor("#9AA4B2"), false), rowParams());

        tvId = label("", 44, Color.parseColor("#FFC107"), true);
        tvId.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        root.addView(tvId);

        tvInfo = label("", 18, Color.parseColor("#C5CCD6"), false);
        tvInfo.setLineSpacing(0, 1.25f);
        root.addView(tvInfo, rowParams());

        firstButton = button("1. Cấp quyền Overlay (Hiển thị trên ứng dụng khác)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                grantOverlay();
            }
        });
        root.addView(firstButton, rowParams());

        root.addView(button("2. Kết nối Local ADB (127.0.0.1:" + RemoteConfig.ADB_PORT + ")", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RemoteService s = RemoteService.get();
                if (s == null) {
                    RemoteService.start(RemoteSettingsActivity.this);
                    toast("Đang khởi động dịch vụ, hãy bấm lại sau vài giây");
                    return;
                }
                toast("Nếu TV hiện hộp thoại Allow USB debugging: tích Always allow rồi chọn Allow");
                s.connectAdbAsync(true);
            }
        }), rowParams());

        root.addView(button("3. Test ADB", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RemoteService s = RemoteService.get();
                if (s == null) {
                    toast("Dịch vụ chưa chạy");
                    return;
                }
                s.testAdbAsync();
            }
        }), rowParams());

        root.addView(button("Đóng", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        }), rowParams());

        setContentView(sv);
        updateTexts();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(refresh);
        if (firstButton != null) {
            firstButton.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(refresh);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void grantOverlay() {
        if (Settings.canDrawOverlays(this)) {
            toast("Đã có quyền Overlay");
            return;
        }
        final RemoteService s = RemoteService.get();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = false;
                if (s != null) {
                    ok = s.grantOverlayViaAdb();
                }
                final boolean granted = ok;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (granted) {
                            toast("Đã cấp quyền Overlay qua ADB");
                        } else {
                            openOverlaySettings();
                        }
                    }
                });
            }
        }).start();
    }

    private void openOverlaySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("TV không có màn hình cấp quyền này. Hãy kết nối Local ADB (nút 2) rồi bấm lại nút 1.");
        } catch (Throwable t) {
            toast("Không mở được cài đặt quyền: " + t.getMessage());
        }
    }

    private void updateTexts() {
        tvId.setText(RemoteConfig.deviceId(this));
        RemoteService s = RemoteService.get();
        StringBuilder sb = new StringBuilder();
        if (RemoteConfig.isConfigured(this)) {
            sb.append("Firebase: ").append(RemoteConfig.dbUrl(this)).append('\n');
        } else {
            sb.append("Firebase: CHƯA CẤU HÌNH - hãy sửa DEFAULT_DB_URL trong RemoteConfig.java rồi build lại\n");
        }
        sb.append("Overlay: ").append(Settings.canDrawOverlays(this) ? "Đã cấp" : "CHƯA cấp").append('\n');
        if (s == null) {
            sb.append("Dịch vụ: chưa chạy\n");
            sb.append("ADB: Disconnected");
        } else {
            sb.append("Dịch vụ: đang chạy\n");
            sb.append("ADB: ").append(s.isAdbConnected() ? "ADB Connected" : "Disconnected")
                    .append("  (").append(s.getAdbMessage()).append(")");
        }
        tvInfo.setText(sb.toString());
    }
}
