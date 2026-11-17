package app.noortv;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The TV has little memory (2.8 GB, most of it held by the system), and apps left open in the background
 * (TikTok, VPNs, a browser, a store) fill it until the screen goes black and nothing answers. This keeps it free:
 * once a day while nobody is watching (the screen off, or the small hours with Home in front), and at any time
 * the memory runs very low, the installed apps that are not in front are closed, the way «إغلاق» does it.
 * Never touched: Noor's apps, the Home app, the app in front, the keyboard, the accessibility services, VPNs,
 * and the system's own apps. Never near an adhan.
 */
final class MemGuard {
    private static final long MIN = 60_000L;
    /**
     * Below this share of the TV's memory the background apps are closed at once, even while someone watches: 220 MB
     * on the 2.8 GB TV it was tuned on, less on a 1.5 GB box, more on a 4 GB one.
     */
    private static final float LOW_SHARE = 0.08f;
    private static int lowMb = -1;
    private static long lastCheck, lastLow, offSince;
    private static volatile boolean busy;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("memguard", Context.MODE_PRIVATE);
    }

    /** MessageServer's clock, every few seconds, on the main thread: a look once a minute. */
    static void tick(Context c) {
        long up = SystemClock.elapsedRealtime();
        boolean on = c.getSystemService(PowerManager.class).isInteractive();
        if (on) offSince = 0;
        else if (offSince == 0) offSince = up;
        if (busy || up - lastCheck < MIN) return;
        lastCheck = up;
        Context app = c.getApplicationContext();
        new Thread(() -> check(app, on, up), "memguard").start();
    }

    private static void check(Context c, boolean on, long up) {
        try {
            int mb = availMb();
            if (nearAdhan(c)) return;
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
            if (!today.equals(p(c).getString("day", ""))) {
                int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
                String top = Adhan.topApp(c);
                boolean idleHome = top == null || Adhan.HOME.equals(top) || top.startsWith("app.noortv.");
                boolean offLong = !on && offSince > 0 && up - offSince > 10 * MIN;
                boolean listening = c.getSystemService(AudioManager.class).isMusicActive();
                // nobody watching: off for ten minutes, or the small hours with nothing open
                if (!listening && (offLong || (h >= 2 && h < 5 && idleHome))) {
                    p(c).edit().putString("day", today).apply();
                    clean(c, top, "daily", mb);
                    return;
                }
            }
            if (lowMb < 0) lowMb = Math.max(120, Math.round(totalMb() * LOW_SHARE));
            // not while something plays in the background (music, a cast): that is someone listening
            if (mb > 0 && mb < lowMb && up - lastLow > 15 * MIN
                    && !c.getSystemService(AudioManager.class).isMusicActive()) {
                lastLow = up;
                String top = Adhan.topApp(c);
                // without knowing what is in front, closing could take the film someone is watching
                if (top != null) clean(c, top, "low", mb);
            }
        } catch (Exception e) {
            Adhan.log(c, "memguard: " + e);
        }
    }

    /**
     * The TV woken from standby by Noor (the notice, the adhan) with the house asleep: every app left in the
     * background closed, Home kept. A player of the night left behind (SmartTube's background play) otherwise takes
     * the sound again by itself, with no picture, as soon as the adhan ends.
     */
    static void closeForWake(Context c) {
        synchronized (MemGuard.class) {
            if (busy) return;
            busy = true;
        }
        Context app = c.getApplicationContext();
        new Thread(() -> clean(app, Adhan.HOME, "wake", availMb()), "memguard").start();
    }

    /** From the phone page: now, in the background; false while a cleaning or an adhan is under way. */
    static boolean cleanNow(Context c) {
        if (busy || Adhan.playing()) return false;
        busy = true;
        Context app = c.getApplicationContext();
        new Thread(() -> clean(app, Adhan.topApp(app), "phone", availMb()), "memguard").start();
        return true;
    }

    /** Closes the installed apps that are not to be kept; says in the diary how much came free. */
    static void clean(Context c, String top, String why, int before) {
        busy = true;
        try {
            Set<String> keep = keep(c, top);
            if (!LocalAdb.ready()) {
                // after a reboot the bridge is not connected yet: a few seconds to come up (allowed long ago)
                LocalAdb.startQuiet(c);
                for (int i = 0; i < 10 && !LocalAdb.ready(); i++) Thread.sleep(500);
            }
            boolean adb = LocalAdb.ready();
            ActivityManager am = c.getSystemService(ActivityManager.class);
            StringBuilder list = new StringBuilder();
            for (ApplicationInfo a : c.getPackageManager().getInstalledApplications(0)) {
                if ((a.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) continue;
                if (keep.contains(a.packageName) || (a.flags & ApplicationInfo.FLAG_STOPPED) != 0) continue;
                if (adb) list.append(' ').append(a.packageName);
                else am.killBackgroundProcesses(a.packageName);
            }
            if (adb) {
                // only the ones actually running (an app closed for nothing loses its alarms until next opened);
                // then what the system keeps cached for nothing
                LocalAdb.run(c, "for p in" + list + "; do pidof $p >/dev/null && am force-stop $p && echo $p; done; am kill-all");
            }
            Thread.sleep(6000);
            int after = availMb();
            Adhan.log(c, "memguard " + why + (adb ? "" : " (no adb)") + ": free " + before + " → " + after + " MB");
            p(c).edit().putString("last", new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date())
                    + " · " + before + " ← " + after + " ميغا").apply();
        } catch (Exception e) {
            Adhan.log(c, "memguard clean: " + e);
        } finally {
            busy = false;
        }
    }

    private static Set<String> keep(Context c, String top) {
        Set<String> k = new HashSet<>();
        k.add(Adhan.HOME);
        if (top != null) k.add(top);
        try {
            String ime = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
            if (ime != null && ime.contains("/")) k.add(ime.substring(0, ime.indexOf('/')));
        } catch (Exception ignored) {
        }
        try {
            List<AccessibilityServiceInfo> on = c.getSystemService(AccessibilityManager.class)
                    .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo s : on) {
                String id = s.getId();
                if (id != null && id.contains("/")) k.add(id.substring(0, id.indexOf('/')));
            }
        } catch (Exception ignored) {
        }
        // every VPN, whatever its name: an app with a VPN service
        try {
            for (android.content.pm.ResolveInfo r : c.getPackageManager().queryIntentServices(
                    new android.content.Intent("android.net.VpnService"), 0)) {
                if (r.serviceInfo != null) k.add(r.serviceInfo.packageName);
            }
        } catch (Exception ignored) {
        }
        for (ApplicationInfo a : c.getPackageManager().getInstalledApplications(0)) {
            String n = a.packageName.toLowerCase(Locale.US);
            if (n.startsWith("app.noortv.") || n.contains("vpn") || n.contains("psiphon") || n.contains("windscribe")
                    || n.contains("wireguard") || n.contains("openvpn")) k.add(a.packageName);
        }
        return k;
    }

    /** Within a quarter of an hour of an adhan (or its notice), or while one plays: not now. */
    private static boolean nearAdhan(Context c) {
        if (Adhan.playing()) return true;
        SharedPreferences a = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        for (long t : new long[]{a.getLong("nextAt", 0), a.getLong("preAt", 0)}) {
            if (t > 0 && t - now < 15 * MIN && now - t < 5 * MIN) return true;
        }
        return false;
    }

    static int totalMb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemTotal:")) return (int) (Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024);
            }
        } catch (Exception ignored) {
        }
        return 2800;
    }

    static int availMb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("MemAvailable:")) return (int) (Long.parseLong(l.replaceAll("[^0-9]", "")) / 1024);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** For the status screen. */
    static String report(Context c) {
        String last = p(c).getString("last", "");
        return "الذاكرة المتاحة: " + availMb() + " ميغا" + (last.isEmpty() ? "" : (char) 10 + "آخر تنظيف: " + last);
    }

    private MemGuard() {
    }
}
