package app.noortv;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The phone page asks for a code «الظاهر على التلفزيون»: as soon as a phone opens the page without a session (or types
 * a wrong code), the code is shown large on the TV for a minute, like pairing a device; it goes as soon as the phone
 * is in. The family's link shows the family's code, the bare address the guests' code. Only someone in front of the
 * screen can read it.
 */
final class CodeOnScreen {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static LinearLayout shown;
    private static long lastAt;
    private static boolean lastGuest;

    static void show(Context c, boolean guest) {
        Context app = c.getApplicationContext();
        if (!app.getSystemService(PowerManager.class).isInteractive() || !Settings.canDrawOverlays(app)) return;
        long now = SystemClock.elapsedRealtime();
        // a page that reloads often shows it once, not over and over
        if (shown != null && guest == lastGuest && now - lastAt < 15_000) return;
        lastAt = now;
        lastGuest = guest;
        String code = guest ? Access.guestCode(app) : Access.code(app);
        main.post(() -> {
            remove();
            Adhan.home(app, "w");
            float d = app.getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(app);
            // a small pill in the top corner, over what plays, hiding nothing: one line, gone in 15 seconds
            box.setOrientation(LinearLayout.HORIZONTAL);
            box.setGravity(Gravity.CENTER_VERTICAL);
            box.setPadding((int) (16 * d), (int) (6 * d), (int) (16 * d), (int) (6 * d));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0x8C000000);
            bg.setCornerRadius(18 * d);
            bg.setStroke((int) d, 0x66E3BE6E);
            box.setBackground(bg);
            TextView digits = new TextView(app);
            digits.setText(code.substring(0, 3) + " " + code.substring(3));
            digits.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            digits.setTypeface(Typeface.DEFAULT_BOLD);
            digits.setLetterSpacing(0.08f);
            digits.setTextColor(0xFFFFFFFF);
            box.addView(digits);
            TextView title = new TextView(app);
            title.setText(guest ? "  🔑 رمز الضيوف" : "  🔑 رمز صفحة الجوال");
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            title.setTextColor(0xFFFFE6A8);
            box.addView(title);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = (int) (24 * d);
            lp.y = (int) (18 * d);
            try {
                app.getSystemService(WindowManager.class).addView(box, lp);
                shown = box;
            } catch (Exception ignored) {
            }
            main.removeCallbacks(timeout);
            main.postDelayed(timeout, 15_000);
        });
    }

    static void hide() {
        main.post(CodeOnScreen::remove);
    }

    private static final Runnable timeout = CodeOnScreen::remove;

    private static void remove() {
        if (shown == null) return;
        try {
            shown.getContext().getSystemService(WindowManager.class).removeView(shown);
        } catch (Exception ignored) {
        }
        shown = null;
    }

    private CodeOnScreen() {
    }
}
