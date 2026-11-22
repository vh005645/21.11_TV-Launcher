package vn.herotran.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Các lớp phủ toàn màn hình vẽ đè lên mọi ứng dụng (SYSTEM_ALERT_WINDOW). Chỉ gọi từ luồng chính. */
public class OverlayController {
    private static final String TAG = "OverlayController";

    public interface Listener {
        /** Người dùng đóng ảnh bằng phím BACK/HOME trên remote TV. */
        void onImageClosedByUser();
    }

    private final Context ctx;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    private View lockView;
    private View messageView;
    private View imageView;
    private ImageView imageViewImg;
    private Bitmap imageBitmap;

    private volatile boolean lockShown;
    private volatile boolean messageShown;
    private volatile boolean imageShown;

    private final Runnable hideMessageRunnable = new Runnable() {
        @Override
        public void run() {
            hideMessage();
        }
    };

    public OverlayController(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.listener = listener;
    }

    public boolean canDraw() {
        try {
            return Settings.canDrawOverlays(ctx);
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isLockShown() {
        return lockShown;
    }

    public boolean isMessageShown() {
        return messageShown;
    }

    public boolean isImageShown() {
        return imageShown;
    }

    private int dp(float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private WindowManager.LayoutParams fullParams(int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        return lp;
    }

    // ------------------------------------------------------------------ KHÓA TV

    public boolean showLock() {
        if (lockShown) {
            return true;
        }
        if (!canDraw()) {
            Log.w(TAG, "Chưa có quyền overlay");
            return false;
        }
        try {
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(Color.BLACK);
            TextView tv = new TextView(ctx);
            tv.setText("Tắt TV ngay !");
            tv.setTextColor(Color.WHITE);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
            tv.setGravity(Gravity.CENTER);
            root.addView(tv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    return true; // chặn mọi phím khi đang khóa
                }
            });
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            wm.addView(root, fullParams(flags));
            lockView = root;
            lockShown = true;
            root.requestFocus();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "showLock lỗi", t);
            return false;
        }
    }

    public void hideLock() {
        lockShown = false;
        if (lockView != null) {
            try {
                wm.removeView(lockView);
            } catch (Throwable ignored) {
            }
            lockView = null;
        }
    }

    // ------------------------------------------------------------------ TIN NHẮN

    /** Tin nhắn phủ 100% màn hình TV (giữ khung viền vàng). Đóng bằng BACK/HOME hoặc tự tắt sau durationMs. */
    public boolean showMessage(String text, long durationMs) {
        if (!canDraw()) {
            return false;
        }
        hideMessage();
        try {
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(dp(48), dp(32), dp(48), dp(32));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#F2101828"));
            bg.setCornerRadius(dp(20));
            bg.setStroke(dp(4), Color.parseColor("#FFC107"));
            box.setBackground(bg);

            TextView title = new TextView(ctx);
            title.setText("TIN NHẮN TỪ ĐIỆN THOẠI");
            title.setTextColor(Color.parseColor("#FFC107"));
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
            title.setGravity(Gravity.CENTER);
            box.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView msg = new TextView(ctx);
            msg.setText(text);
            msg.setTextColor(Color.WHITE);
            msg.setTypeface(Typeface.DEFAULT_BOLD);
            msg.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
            msg.setGravity(Gravity.CENTER);
            msg.setMaxLines(8);
            msg.setPadding(0, dp(24), 0, 0);
            box.addView(msg, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            box.setFocusable(true);
            box.setFocusableInTouchMode(true);
            box.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    hideMessage();
                                }
                            });
                        }
                        return true;
                    }
                    return false;
                }
            });

            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            wm.addView(box, fullParams(flags));
            messageView = box;
            messageShown = true;
            box.requestFocus();
            main.postDelayed(hideMessageRunnable, durationMs);
            playBeep();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "showMessage lỗi", t);
            return false;
        }
    }

    public void hideMessage() {
        main.removeCallbacks(hideMessageRunnable);
        messageShown = false;
        if (messageView != null) {
            try {
                wm.removeView(messageView);
            } catch (Throwable ignored) {
            }
            messageView = null;
        }
    }

    private void playBeep() {
        try {
            final ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 700);
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    tg.release();
                }
            }, 1200);
        } catch (Throwable t) {
            Log.w(TAG, "Không phát được âm báo", t);
        }
    }

    // ------------------------------------------------------------------ ẢNH TỪ ĐIỆN THOẠI

    public boolean showImage(Bitmap bmp) {
        if (!canDraw() || bmp == null) {
            return false;
        }
        hideImageInternal();
        try {
            FrameLayout root = new FrameLayout(ctx);
            root.setBackgroundColor(Color.argb(225, 0, 0, 0));
            ImageView iv = new ImageView(ctx);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setImageBitmap(bmp);
            root.addView(iv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            root.setFocusable(true);
            root.setFocusableInTouchMode(true);
            root.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (imageShown) {
                                        hideImage();
                                        if (listener != null) {
                                            listener.onImageClosedByUser();
                                        }
                                    }
                                }
                            });
                        }
                        return true;
                    }
                    return true; // ảnh đang hiển thị: không cho phím lọt xuống app bên dưới
                }
            });
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
            wm.addView(root, fullParams(flags));
            imageView = root;
            imageViewImg = iv;
            imageBitmap = bmp;
            imageShown = true;
            root.requestFocus();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "showImage lỗi", t);
            return false;
        }
    }

    private void hideImageInternal() {
        imageShown = false;
        if (imageView != null) {
            try {
                wm.removeView(imageView);
            } catch (Throwable ignored) {
            }
            imageView = null;
        }
        if (imageViewImg != null) {
            imageViewImg.setImageDrawable(null);
            imageViewImg = null;
        }
        if (imageBitmap != null) {
            imageBitmap.recycle();
            imageBitmap = null;
        }
    }

    public void hideImage() {
        hideImageInternal();
    }

    public void hideAll() {
        hideLock();
        hideMessage();
        hideImageInternal();
    }
}
