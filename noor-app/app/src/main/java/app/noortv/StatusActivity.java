package app.noortv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * «حالة البيت»: the internet now (and the router), since when the TV has had power, the last power and internet
 * cuts (HomeStatus keeps them), how many devices answer on the home network, and a speed test on OK.
 */
public class StatusActivity extends Activity {
    private static final int GOLD = 0xFFFFE08A, WHITE = 0xFFF4EEDF, DIM = 0xB3F4EEDF, GREEN = 0xFF7BE0A4, RED = 0xFFFF8A80;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView adhan;
    private TextView net, routerLine, power, devices, speed, log;
    private volatile boolean alive, testing;
    private String deviceText = "📱 الأجهزة على الشبكة: عم نعدّ…";
    private String speedText = "⏱ اضغط OK لقياس سرعة الإنترنت";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(LinearLayout.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(64), dp(36), dp(64), dp(28));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0F2A24, 0xFF0B1424, 0xFF06080E}));
        TextView title = text(34, GOLD, true);
        title.setText("🏠 حالة البيت");
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        cols.setLayoutDirection(LinearLayout.LAYOUT_DIRECTION_RTL);
        LinearLayout now = column(), past = column();
        cols.addView(now, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.25f));
        cols.addView(past, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(18);
        root.addView(cols, lp);

        net = line(now, 24);
        routerLine = line(now, 20);
        power = line(now, 22);
        devices = line(now, 20);
        speed = line(now, 22);
        TextView head = line(past, 22);
        head.setTextColor(GOLD);
        head.setText("🧾 آخر الانقطاعات");
        log = line(past, 19);
        log.setLineSpacing(0, 1.25f);
        // the adhan's own state, under the outages
        adhan = line(past, 18);
        adhan.setLineSpacing(0, 1.25f);
        adhan.setPadding(0, dp(18), 0, 0);
        setContentView(root);
    }

    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), 0, dp(14), 0);
        return c;
    }

    private TextView line(LinearLayout into, int sp) {
        TextView t = text(sp, WHITE, false);
        t.setPadding(0, dp(9), 0, dp(9));
        into.addView(t);
        return t;
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTextDirection(TextView.TEXT_DIRECTION_RTL);
        t.setGravity(Gravity.RIGHT);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        h.post(this::refresh);
        // a fresh look now, not the one from up to a minute ago; and who answers on the network
        new Thread(() -> {
            HomeStatus.check(getApplicationContext());
            countDevices();
        }, "status-open").start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        alive = false;
        h.removeCallbacksAndMessages(null);
    }

    private void refresh() {
        if (!alive) return;
        if (!HomeStatus.checked) {
            net.setText("🌐 الإنترنت: عم نفحص…");
            net.setTextColor(WHITE);
            routerLine.setText("");
        } else if (HomeStatus.online) {
            net.setText("🌐 الإنترنت: شغّال ✅  ·  الاستجابة " + HomeStatus.ms + " م.ث");
            net.setTextColor(GREEN);
            routerLine.setText("📶 الراوتر: واصل");
            routerLine.setTextColor(DIM);
        } else {
            long down = HomeStatus.netDown(this);
            net.setText("🌐 الإنترنت: مقطوع ❌" + (down > 0 ? "  ·  من " + clock(down) : ""));
            net.setTextColor(RED);
            routerLine.setText(HomeStatus.router ? "📶 الراوتر واصل، بس الخط من الشركة مقطوع" : "📶 الراوتر مو واصل (مطفّي أو الواي فاي مقطوع)");
            routerLine.setTextColor(WHITE);
        }
        long boot = HomeStatus.boot();
        power.setText("⚡ الكهربا: ما انقطعت عن التلفزيون من " + span(System.currentTimeMillis() - boot)
                + "\n      (" + day(boot) + " " + clock(boot) + ")");
        devices.setText(deviceText);
        adhan.setText("🕌 " + Health.report(this) + "\n🧠 " + MemGuard.report(this));
        adhan.setTextColor(Health.clockBad || Health.serviceDown ? RED : DIM);
        speed.setText(speedText);
        List<long[]> entries = HomeStatus.log(this);
        if (entries.isEmpty()) {
            log.setText("ما في انقطاعات مسجّلة لسا.\nالتسجيل بلّش من هلق، وأي انقطاع كهربا أو إنترنت رح يطلع هون.");
            log.setTextColor(DIM);
        } else {
            StringBuilder b = new StringBuilder();
            int n = 0;
            for (long[] e : entries) {
                if (n++ == 9) break;
                b.append(e[0] != HomeStatus.POWER ? "🌐 إنترنت" : e[2] - e[1] < 3 * 60_000L ? "🔄 إعادة تشغيل" : "⚡ كهربا").append("  ·  ").append(day(e[1])).append(" ")
                        .append(clock(e[1])).append(" ← ").append(clock(e[2])).append("  (").append(span(e[2] - e[1])).append(")\n");
            }
            log.setText(b.toString().trim());
            log.setTextColor(WHITE);
        }
        h.postDelayed(this::refresh, 2000);
    }

    private static String clock(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int hr = c.get(Calendar.HOUR);
        return String.format(Locale.US, "%d:%02d %s", hr == 0 ? 12 : hr, c.get(Calendar.MINUTE), c.get(Calendar.AM_PM) == Calendar.AM ? "ص" : "م");
    }

    private static final String[] DAYS = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};

    private static String day(long t) {
        Calendar c = Calendar.getInstance(), today = Calendar.getInstance();
        c.setTimeInMillis(t);
        if (c.get(Calendar.YEAR) == today.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) return "اليوم";
        return DAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + "/" + (c.get(Calendar.MONTH) + 1);
    }

    private static String span(long ms) {
        long m = Math.max(1, ms / 60_000);
        if (m < 60) return m + " دقيقة";
        long hrs = m / 60;
        if (hrs < 48) return hrs + " ساعة" + (m % 60 >= 5 ? " و" + (m % 60) + " دقيقة" : "");
        return (hrs / 24) + " يوم";
    }

    /** Every address of the home network pinged once, forty at a time. */
    private void countDevices() {
        String me = myAddress();
        if (me == null) {
            deviceText = "📱 الأجهزة على الشبكة: التلفزيون مو متصل بالشبكة";
            return;
        }
        String base = me.substring(0, me.lastIndexOf('.') + 1), gw = HomeStatus.gateway(getApplicationContext());
        List<Integer> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(40);
        for (int i = 1; i < 255; i++) {
            int host = i;
            pool.execute(() -> {
                if (alive && (me.equals(base + host) || HomeStatus.ping(base + host))) found.add(host);
            });
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        if (!alive) return;
        int others = 0;
        for (int host : found) {
            String ip = base + host;
            if (!ip.equals(me) && !ip.equals(gw)) others++;
        }
        deviceText = "📱 الأجهزة على الشبكة: " + found.size() + "\n      الراوتر والتلفزيون و" + others
                + " غيرهم · الموبايل النايم ممكن ما ينعدّ";
    }

    private static String myAddress() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Download for up to six seconds (at most 8 MB) and show megabits a second. */
    private void speedTest() {
        if (testing) return;
        testing = true;
        speedText = "⏱ عم نقيس السرعة…";
        new Thread(() -> {
            String out;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=8000000").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setUseCaches(false);
                long got = 0, t0 = 0;
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[65536];
                    int n;
                    t0 = SystemClock.elapsedRealtime();
                    while (alive && (n = in.read(buf)) > 0) {
                        got += n;
                        if (SystemClock.elapsedRealtime() - t0 > 6000) break;
                    }
                } finally {
                    c.disconnect();
                }
                double secs = Math.max(0.2, (SystemClock.elapsedRealtime() - t0) / 1000.0);
                double mbps = got * 8 / 1_000_000.0 / secs;
                out = String.format(Locale.US, "⏱ سرعة التحميل: %.1f ميغابت بالثانية\n      اضغط OK لتعيد القياس", mbps);
            } catch (Exception e) {
                out = "⏱ ما قدرنا نقيس السرعة (الإنترنت مقطوع أو ضعيف)\n      اضغط OK لتعيد القياس";
            }
            speedText = out;
            testing = false;
        }, "speed-test").start();
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            speedTest();
            return true;
        }
        return super.onKeyUp(code, e);
    }
}
