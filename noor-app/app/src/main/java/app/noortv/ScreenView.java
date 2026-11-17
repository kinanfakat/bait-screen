package app.noortv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The TV's screen on the phone (the phone page's «شاشة التلفزيون»): the Home app's key service takes the picture (no
 * prompt on Android 11) and sends it here; the phone asks for the next one as soon as it has shown the last. While the
 * phone watches, a small line in the TV's corner says so: nobody is watched without knowing it.
 */
final class ScreenView {
    private static volatile byte[] frame;
    private static volatile long frameAt;
    private static volatile long askedAt;
    private static BroadcastReceiver receiver, editReceiver;
    /** A text field has the focus on the TV (the phone shows its keyboard), and its text. */
    static volatile boolean editing;
    static volatile String editText = "";
    private static TextView badge;
    private static final Handler main = new Handler(Looper.getMainLooper());

    static void start(Context c) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                byte[] b = i.getByteArrayExtra("jpg");
                if (b == null || b.length == 0) return;
                frame = b;
                frameAt = SystemClock.elapsedRealtime();
                synchronized (ScreenView.class) {
                    ScreenView.class.notifyAll();
                }
            }
        };
        editReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent i) {
                editing = i.getBooleanExtra("on", false);
                String t = i.getStringExtra("text");
                editText = t == null ? "" : t;
            }
        };
        IntentFilter ef = new IntentFilter("app.noortv.EDIT");
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            c.registerReceiver(editReceiver, ef, "com.gathin.tvhome.permission.PAD", null, Context.RECEIVER_EXPORTED);
        } else {
            c.registerReceiver(editReceiver, ef, "com.gathin.tvhome.permission.PAD", null);
        }
        IntentFilter f = new IntentFilter("app.noortv.SCREEN_FRAME");
        // only the Home app (it holds the permission) may send the screen
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            c.registerReceiver(receiver, f, "com.gathin.tvhome.permission.PAD", null, Context.RECEIVER_EXPORTED);
        } else {
            c.registerReceiver(receiver, f, "com.gathin.tvhome.permission.PAD", null);
        }
    }

    /** A picture of the screen, newer than this request when it comes in time; the last one otherwise (or null). */
    static synchronized byte[] next(Context c) {
        // Android 11 allows one picture a second: one asked sooner would be refused, so wait for it
        long since = SystemClock.elapsedRealtime() - askedAt;
        if (since < 1030) SystemClock.sleep(1030 - since);
        long asked = SystemClock.elapsedRealtime();
        askedAt = asked;
        showBadge(c);
        Adhan.home(c, "e");   // and whether a text field has the focus, for the phone's keyboard
        Adhan.home(c, "p");
        long until = asked + 2500;
        boolean again = false;
        synchronized (ScreenView.class) {
            while (frameAt < asked - 50 && SystemClock.elapsedRealtime() < until) {
                // a picture asked for too soon is refused: once more after a moment
                if (!again && SystemClock.elapsedRealtime() - asked > 1100) {
                    again = true;
                    Adhan.home(c, "p");
                }
                try {
                    ScreenView.class.wait(Math.max(1, Math.min(100, until - SystemClock.elapsedRealtime())));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return frame;
    }

    /** The small line in the TV's top corner while a phone looks at the screen; gone six seconds after it stops. */
    private static void showBadge(Context c) {
        Context app = c.getApplicationContext();
        main.post(() -> {
            if (badge == null && Settings.canDrawOverlays(app)) {
                badge = new TextView(app);
                badge.setText("📱 الشاشة معروضة على جوال");
                badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                badge.setTextColor(0xFFFFFFFF);
                float d = app.getResources().getDisplayMetrics().density;
                badge.setPadding((int) (12 * d), (int) (5 * d), (int) (12 * d), (int) (5 * d));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(0xB3000000);
                bg.setCornerRadius(14 * d);
                badge.setBackground(bg);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = (int) (8 * d);
                try {
                    app.getSystemService(WindowManager.class).addView(badge, lp);
                } catch (Exception e) {
                    badge = null;
                }
            }
            main.removeCallbacks(hide);
            main.postDelayed(hide, 6000);
        });
    }

    private static final Runnable hide = () -> {
        if (badge == null) return;
        try {
            badge.getContext().getSystemService(WindowManager.class).removeView(badge);
        } catch (Exception ignored) {
        }
        badge = null;
    };

    private ScreenView() {
    }
}
