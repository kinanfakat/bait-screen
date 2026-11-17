package app.noortv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What can silently break the adhan, watched from MessageServer's clock and said on the screen:
 * the TV's clock wrong after a power cut (checked against the internet's time, and against the last time seen),
 * the Home app's key service stopped (the adhan cannot come out of the screensaver without it),
 * and an adhan whose time passed while the TV was in deep standby.
 */
final class Health {
    private static final long MIN = 60_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** The TV's clock is not to be trusted: no adhan until it is right again. */
    static volatile boolean clockBad;
    /** The Home app's key service is not running. */
    static volatile boolean serviceDown;
    private static int serviceMisses;
    private static long lastSaved, lastServiceCheck, lastClockNotice, lastServiceNotice;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("health", Context.MODE_PRIVATE);
    }

    /** The time an internet server gave (HomeStatus's check, once a minute while there is internet). */
    static void netTime(Context c, long server) {
        if (server <= 0) return;
        long now = System.currentTimeMillis();
        boolean wrong = Math.abs(server - now) > 3 * MIN;
        if (wrong && !clockBad) {
            Adhan.log(c, "clock WRONG: the TV says " + stamp(now) + ", the internet says " + stamp(server));
        } else if (!wrong && clockBad) {
            Adhan.log(c, "clock right again");
            main.post(() -> Adhan.schedule(c.getApplicationContext()));
        }
        clockBad = wrong;
        p(c).edit().putBoolean("clockBad", wrong).putLong("netOkAt", wrong ? 0 : now).apply();
        if (!wrong) save(c, now);
    }

    private static void save(Context c, long now) {
        lastSaved = SystemClock.elapsedRealtime();
        p(c).edit().putLong("lastSeen", now).apply();
    }

    /** Every few seconds, on the main thread. */
    static void tick(Context c) {
        long now = System.currentTimeMillis(), up = SystemClock.elapsedRealtime();
        SharedPreferences p = p(c);
        if (lastSaved == 0) {
            // the app has just started (a boot, an update): what was true before still is until checked
            clockBad = p.getBoolean("clockBad", false);
            lastSaved = 1;
        }
        long last = p.getLong("lastSeen", 0);
        if (last > 0 && now + 6 * 60 * MIN < last) {
            // hours before the last time this TV was seen running: the clock was reset (a power cut)
            if (!clockBad) {
                Adhan.log(c, "clock WRONG: it went back to " + stamp(now) + ", last seen " + stamp(last));
                clockBad = true;
                p.edit().putBoolean("clockBad", true).apply();
            }
        } else if (!clockBad && up - lastSaved > MIN) {
            save(c, now);
        }

        // the Home app's key service, every five minutes; two misses in a row before it is said
        if (up - lastServiceCheck > 5 * MIN || lastServiceCheck == 0) {
            lastServiceCheck = up;
            boolean running = serviceRunning(c);
            serviceMisses = running ? 0 : serviceMisses + 1;
            boolean down = serviceMisses >= 2;
            if (down != serviceDown) Adhan.log(c, down ? "Home key service is NOT running" : "Home key service runs again");
            serviceDown = down;
        }

        // said on the screen, only while someone can see it
        if (!c.getSystemService(PowerManager.class).isInteractive()) return;
        String missed = p.getString("missed", "");
        if (!missed.isEmpty()) {
            p.edit().remove("missed").apply();
            say(c, "⚠ فات " + missed + " لأن التلفزيون كان في نوم عميق");
            return;
        }
        if (clockBad && (lastClockNotice == 0 || up - lastClockNotice > 30 * MIN)) {
            lastClockNotice = up;
            say(c, "⚠ ساعة التلفزيون غير مضبوطة · الأذان متوقف حتى تُضبط الساعة أو يعود الإنترنت");
            return;
        }
        if (serviceDown && (lastServiceNotice == 0 || up - lastServiceNotice > 6 * 60 * MIN)) {
            lastServiceNotice = up;
            say(c, "⚠ خدمة «زر الرئيسية» متوقفة · الأذان قد لا يظهر فوق شاشة الساعة" + (char) 10
                    + "فعّلها من الإعدادات ← إمكانية الوصول");
        }
    }

    private static void say(Context c, String text) {
        Context app = c.getApplicationContext();
        main.post(() -> {
            try {
                Reminder.show(app, text);
            } catch (Exception ignored) {
            }
        });
    }

    private static boolean serviceRunning(Context c) {
        try {
            AccessibilityManager am = c.getSystemService(AccessibilityManager.class);
            List<AccessibilityServiceInfo> on = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo s : on) {
                String id = s.getId();
                if (id != null && id.startsWith(Adhan.HOME + "/") && id.endsWith("HomeKeyService")) return true;
            }
            return false;
        } catch (Exception e) {
            return true;   // cannot tell: say nothing
        }
    }

    /** An adhan that did not play: kept to be said when the TV is next on, and for the status screen. */
    static void missed(Context c, int prayer, long at, String why) {
        String what = "أذان " + Adhan.NAMES[prayer] + " (" + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(at)) + ")";
        SharedPreferences p = p(c);
        String m = p.getString("missed", "");
        p.edit().putString("missed", m.isEmpty() ? what : m + " و" + what)
                .putString("lastMissed", what + " · " + new SimpleDateFormat("MM-dd", Locale.US).format(new Date(at)) + " · " + why)
                .apply();
    }

    /** An adhan that played. */
    static void played(Context c, int prayer, long at) {
        p(c).edit().putString("lastPlayed", "أذان " + Adhan.NAMES[prayer] + " · "
                + new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(at))).apply();
    }

    /** For the status screen: the adhan's state in a few lines. */
    static String report(Context c) {
        SharedPreferences a = c.getSharedPreferences("adhan", Context.MODE_PRIVATE), p = p(c);
        StringBuilder b = new StringBuilder();
        long at = a.getLong("nextAt", 0);
        b.append("الأذان القادم: ");
        if (at > System.currentTimeMillis()) {
            b.append(Adhan.NAMES[Math.max(0, Math.min(4, a.getInt("nextP", 1)))]).append(" ")
                    .append(new SimpleDateFormat("HH:mm", Locale.US).format(new Date(at)));
        } else b.append("غير مجدول ⚠");
        String played = p.getString("lastPlayed", "");
        if (!played.isEmpty()) b.append((char) 10).append("آخر أذان: ").append(played);
        b.append((char) 10);
        b.append(clockBad ? "الساعة: غير مضبوطة ⚠ (الأذان متوقف)" : p.getLong("netOkAt", 0) > 0 ? "الساعة: مطابقة لوقت الإنترنت ✅" : "الساعة: لم تُطابق بعد");
        b.append((char) 10).append(serviceDown ? "خدمة زر الرئيسية: متوقفة ⚠" : "خدمة زر الرئيسية: تعمل ✅");
        String missed = p.getString("lastMissed", "");
        if (!missed.isEmpty()) b.append((char) 10).append("آخر أذان فات: ").append(missed);
        return b.toString();
    }

    private static String stamp(long t) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(t));
    }

    private Health() {
    }
}
