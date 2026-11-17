package app.noortv;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.RouteInfo;
import android.os.SystemClock;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;

/**
 * «حالة البيت»: the house's internet and mains power, watched by MessageServer's clock once a minute, standby
 * included. The internet: a tiny request, and the router by ping, so "the router is up but the line is down" shows.
 * The power: the TV has no battery, so a boot later than the last minute's heartbeat is a time it had no power.
 * Both go into a short log (prefs "home") that StatusActivity shows.
 */
final class HomeStatus {
    private static final long MINUTE = 60_000L;
    private static final int KEEP = 30;
    static final char POWER = 'p', NET = 'n';

    static volatile boolean checked, online, router;
    static volatile int ms = -1;
    static volatile long checkedAt;

    private static long startedAt, lastRun;
    private static boolean bootSeen, running;
    private static int fails;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** From MessageServer's 5-second clock. */
    static void tick(Context c) {
        long up = SystemClock.elapsedRealtime();
        if (startedAt == 0) startedAt = up;
        if (running || (lastRun != 0 && up - lastRun < MINUTE)) return;
        lastRun = up;
        running = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                run(app, up);
            } catch (Exception ignored) {
            } finally {
                running = false;
            }
        }, "home-status").start();
    }

    private static void run(Context c, long up) {
        check(c);
        long now = System.currentTimeMillis();
        // a clock not yet set after a power cut would write nonsense: wait for the network time
        if (now < 1_700_000_000_000L) return;
        SharedPreferences p = p(c);
        if (!bootSeen) {
            // a little after the start, so the network has set the clock
            if (up - startedAt < 90_000 && !online) return;
            bootSeen = true;
            long boot = now - SystemClock.elapsedRealtime(), beat = p.getLong("beat", 0);
            // the TV started after the last beat: it was off in between (a power cut, or a restart when short)
            if (beat > 0 && boot > beat) add(p, POWER, beat, boot);
        }
        p.edit().putLong("beat", now).apply();
        // the internet: down after two misses in a row, so one lost request is not an outage
        long downAt = p.getLong("netDown", 0);
        if (online) {
            fails = 0;
            if (downAt > 0) {
                if (now - downAt > 2 * MINUTE) add(p, NET, downAt, now);
                p.edit().remove("netDown").apply();
            }
        } else if (++fails >= 2 && downAt == 0) {
            p.edit().putLong("netDown", now - MINUTE).apply();
        }
    }

    /** One look at the line and the router; StatusActivity asks for a fresh one when it opens. */
    static void check(Context c) {
        long t = SystemClock.elapsedRealtime();
        boolean ok = false;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL("https://www.gstatic.com/generate_204").openConnection();
            h.setConnectTimeout(6000);
            h.setReadTimeout(6000);
            h.setUseCaches(false);
            ok = h.getResponseCode() == 204;
            // the server's own time: the TV's clock is checked against it
            if (ok) Health.netTime(c, h.getDate());
            h.disconnect();
        } catch (Exception ignored) {
        }
        ms = ok ? (int) (SystemClock.elapsedRealtime() - t) : -1;
        String gw = gateway(c);
        router = gw != null && ping(gw);
        online = ok;
        checkedAt = System.currentTimeMillis();
        checked = true;
    }

    /** The router's address: the default route of the network in use. */
    static String gateway(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp == null) return null;
            for (RouteInfo r : lp.getRoutes()) {
                InetAddress g = r.getGateway();
                if (r.isDefaultRoute() && g instanceof java.net.Inet4Address && !g.isAnyLocalAddress()) return g.getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean ping(String ip) {
        try {
            Process pr = new ProcessBuilder("/system/bin/ping", "-c", "1", "-W", "1", ip).redirectErrorStream(true).start();
            try (java.io.InputStream in = pr.getInputStream()) {
                byte[] b = new byte[1024];
                while (in.read(b) > 0) {
                    // drained, so the process can end
                }
            }
            return pr.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static synchronized void add(SharedPreferences p, char kind, long from, long to) {
        String log = p.getString("log", "");
        String entry = kind + "," + from + "," + to;
        log = log.isEmpty() ? entry : entry + ";" + log;
        String[] all = log.split(";");
        if (all.length > KEEP) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < KEEP; i++) b.append(i == 0 ? "" : ";").append(all[i]);
            log = b.toString();
        }
        p.edit().putString("log", log).commit();
    }

    /** Newest first: {kind, from, to}. */
    static java.util.List<long[]> log(Context c) {
        java.util.List<long[]> out = new java.util.ArrayList<>();
        for (String e : p(c).getString("log", "").split(";")) {
            String[] f = e.split(",");
            if (f.length != 3) continue;
            try {
                out.add(new long[]{f[0].charAt(0), Long.parseLong(f[1]), Long.parseLong(f[2])});
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Since when the TV has had power without a break. */
    static long boot() {
        return System.currentTimeMillis() - SystemClock.elapsedRealtime();
    }

    /** When the line went down, 0 while it is up. */
    static long netDown(Context c) {
        return p(c).getLong("netDown", 0);
    }

    private HomeStatus() {
    }
}
