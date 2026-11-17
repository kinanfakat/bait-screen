package app.noortv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * «المباريات»: yesterday's, today's and tomorrow's football, the leagues the family follows, names in Arabic.
 * From FotMob's public match list (and its Arabic names, kept a week). Right/left change the day, up/down scroll,
 * OK refreshes; today's list refreshes itself every minute while a match is on.
 */
public class MatchesActivity extends Activity {

    /** The competitions shown, in this order: FotMob's league id and its Arabic name. */
    private static final Object[][] LEAGUES = {
            {77, "كأس العالم"}, {10242, "كأس العرب"}, {329, "كأس الخليج العربي"}, {290, "كأس آسيا"},
            {289, "كأس أمم أفريقيا"}, {10197, "تصفيات كأس العالم - آسيا"}, {10196, "تصفيات كأس العالم - أفريقيا"},
            {11156, "تصفيات كأس العرب"}, {10609, "تصفيات كأس آسيا"}, {10608, "تصفيات كأس أمم أفريقيا"},
            {42, "دوري أبطال أوروبا"}, {525, "دوري أبطال آسيا للنخبة"}, {526, "دوري أبطال أفريقيا"},
            {10474, "كأس العرب للأندية الأبطال"}, {78, "كأس العالم للأندية"}, {74, "كأس السوبر الأوروبي"},
            {536, "الدوري السعودي"}, {47, "الدوري الإنجليزي"}, {87, "الدوري الإسباني"}, {55, "الدوري الإيطالي"},
            {54, "الدوري الألماني"}, {53, "الدوري الفرنسي"}, {73, "الدوري الأوروبي"}, {10216, "دوري المؤتمر الأوروبي"},
            {9806, "دوري الأمم الأوروبية"}, {50, "كأس أمم أوروبا"}, {44, "كوبا أمريكا"}, {10195, "تصفيات كأس العالم - أوروبا"},
            {10199, "تصفيات كأس العالم - أمريكا الجنوبية"}, {10201, "الملحق المؤهل لكأس العالم"},
            {519, "الدوري المصري"}, {535, "الدوري القطري"}, {538, "الدوري الإماراتي"}, {530, "الدوري المغربي"},
            {524, "الدوري العراقي"}, {529, "الدوري الكويتي"}, {544, "الدوري التونسي"}, {516, "الدوري الجزائري"},
            {9942, "كأس الملك السعودي"}, {9469, "دوري أبطال آسيا 2"}, {9468, "كأس الكونفدرالية الأفريقية"},
            {138, "كأس ملك إسبانيا"}, {132, "كأس الاتحاد الإنجليزي"}, {141, "كأس إيطاليا"}, {209, "كأس ألمانيا"},
            {114, "مباريات ودية - منتخبات"},
    };
    private static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/120 Safari/537.36";
    private static final String[] DAYS = {"أمس", "اليوم", "غداً"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, JSONObject> fetched = new HashMap<>();
    private final Map<String, Long> fetchedAt = new HashMap<>();
    private Map<String, String> names = new HashMap<>();
    private int day = 1;   // 0 yesterday, 1 today, 2 tomorrow
    private int loading;
    /** The next list shown starts from its top (a new day), not where the last one was scrolled to. */
    private boolean top = true;
    private LinearLayout list;
    private ScrollView scroll;
    private TextView[] tabs = new TextView[3];
    private TextView state;
    private float d;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0B2A1C, 0xFF0B1A24, 0xFF06080E}));
        root.setPadding(px(48), px(24), px(48), px(12));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        // laid out left to right (the app does not declare RTL): from the left غداً, اليوم, أمس, then the title
        TextView title = text("⚽  المباريات", 34, 0xFFFFFFFF, true);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        for (int i = 2; i >= 0; i--) {
            tabs[i] = text(DAYS[i], 26, 0xFFFFFFFF, true);
            tabs[i].setPadding(px(26), px(8), px(26), px(8));
            // a touch from the phone's view of the screen picks the day too
            final int which = i;
            tabs[i].setOnClickListener(v -> {
                if (day == which) return;
                day = which;
                tabsShow();
                MatchesActivity.this.top = true;
                load(false);
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.setMarginStart(px(10));
            top.addView(tabs[i], tp);
        }
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        state = text("", 20, 0xFFB8C4CC, false);
        state.setGravity(Gravity.RIGHT);
        state.setPadding(0, px(6), 0, px(6));
        root.addView(state);

        scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView help = text("◀ ▶ لتغيير اليوم   ·   ▲ ▼ للتمرير   ·   OK للتحديث", 18, 0xFF8899A6, false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, px(8), 0, 0);
        root.addView(help);
        setContentView(root);

        tabsShow();
        new Thread(() -> {
            Map<String, String> n = loadNames();
            main.post(() -> {
                names = n;
                load(false);
            });
        }, "matches-names").start();
        main.postDelayed(tick, 60_000);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (isFinishing()) return;
            if (day == 1) load(true);
            main.postDelayed(this, 60_000);
        }
    };

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        // the screen reads right to left: right is back to yesterday, left on to tomorrow
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT && day > 0) {
            day--;
        } else if (k == KeyEvent.KEYCODE_DPAD_LEFT && day < 2) {
            day++;
        } else if (k == KeyEvent.KEYCODE_DPAD_DOWN) {
            scroll.smoothScrollBy(0, px(260));
            return true;
        } else if (k == KeyEvent.KEYCODE_DPAD_UP) {
            scroll.smoothScrollBy(0, -px(260));
            return true;
        } else if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) {
            load(true);
            return true;
        } else {
            return super.onKeyDown(k, e);
        }
        tabsShow();
        top = true;
        load(false);
        return true;
    }

    private void tabsShow() {
        for (int i = 0; i < 3; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(22));
            if (i == day) {
                g.setColor(0xFF1F8A4C);
                g.setStroke(px(2), 0xFFFFD27A);
            } else {
                g.setColor(0x33FFFFFF);
            }
            tabs[i].setBackground(g);
            tabs[i].setTextColor(i == day ? 0xFFFFFFFF : 0xFFB8C4CC);
        }
    }

    // ------------------------------------------------------------------ data

    /** The local day shown (0 yesterday, 1 today, 2 tomorrow) as yyyyMMdd, shifted by more days. */
    private static String ymd(int which, int shift) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_MONTH, which - 1 + shift);
        return new SimpleDateFormat("yyyyMMdd", Locale.US).format(c.getTime());
    }

    /**
     * FotMob's lists go by Central European days: a day here takes its matches from that list and the next one,
     * keeping those that fall on this day by the TV's own clock.
     */
    private void load(boolean fresh) {
        final int which = day;
        final String[] dates = {ymd(which, -1), ymd(which, 0), ymd(which, 1)};
        if (list.getChildCount() == 0 || !fresh) state.setText("جاري التحميل…");
        final int mine = ++loading;
        new Thread(() -> {
            boolean failed = false;
            for (String date : dates) {
                Long at = fetchedAt.get(date);
                // today's lists a minute old, other days' half an hour
                long age = which == 1 ? 55_000 : 30 * 60_000L;
                if (!fresh && at != null && System.currentTimeMillis() - at < age) continue;
                if (fresh && at != null && System.currentTimeMillis() - at < 20_000) continue;
                try {
                    JSONObject j = new JSONObject(get("https://www.fotmob.com/api/data/matches?date=" + date
                            + "&timezone=" + java.net.URLEncoder.encode(TimeZone.getDefault().getID(), "UTF-8")
                            + "&ccode3=SYR"));
                    synchronized (fetched) {
                        fetched.put(date, j);
                        fetchedAt.put(date, System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    if (!fetched.containsKey(date)) failed = true;
                }
            }
            List<Object[]> groups = build(which, dates);
            boolean bad = failed;
            main.post(() -> {
                if (mine != loading || which != day || isFinishing()) return;
                show(groups, bad);
            });
        }, "matches").start();
    }

    /** The day's matches: [league name, List of match rows], in LEAGUES' order. */
    private List<Object[]> build(int which, String[] dates) {
        String dayKey = ymd(which, 0);
        SimpleDateFormat local = new SimpleDateFormat("yyyyMMdd", Locale.US);
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        Map<Integer, List<JSONObject>> by = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        synchronized (fetched) {
            for (String date : dates) {
                JSONObject j = fetched.get(date);
                if (j == null) continue;
                JSONArray leagues = j.optJSONArray("leagues");
                if (leagues == null) continue;
                for (int i = 0; i < leagues.length(); i++) {
                    JSONObject l = leagues.optJSONObject(i);
                    int id = l.optInt("primaryId", l.optInt("id"));
                    JSONArray ms = l.optJSONArray("matches");
                    if (ms == null || index(id) < 0) continue;
                    for (int k = 0; k < ms.length(); k++) {
                        JSONObject m = ms.optJSONObject(k);
                        long mid = m.optLong("id");
                        if (!seen.add(mid)) continue;
                        long t = when(m, utc);
                        if (t <= 0 || !local.format(new Date(t)).equals(dayKey)) continue;
                        m = copyWithTime(m, t);
                        List<JSONObject> g = by.get(id);
                        if (g == null) by.put(id, g = new ArrayList<>());
                        g.add(m);
                    }
                }
            }
        }
        List<Object[]> out = new ArrayList<>();
        for (Object[] l : LEAGUES) {
            List<JSONObject> g = by.get((Integer) l[0]);
            if (g == null) continue;
            g.sort((a, b) -> Long.compare(a.optLong("_t"), b.optLong("_t")));
            out.add(new Object[]{l[1], g});
        }
        return out;
    }

    private static JSONObject copyWithTime(JSONObject m, long t) {
        try {
            m.put("_t", t);
        } catch (Exception ignored) {
        }
        return m;
    }

    private static long when(JSONObject m, SimpleDateFormat utc) {
        long ts = m.optLong("timeTS", 0);
        if (ts > 0) return ts;
        try {
            String s = m.getJSONObject("status").getString("utcTime");
            return utc.parse(s.substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    private static int index(int id) {
        for (int i = 0; i < LEAGUES.length; i++) if ((Integer) LEAGUES[i][0] == id) return i;
        return -1;
    }

    // ------------------------------------------------------------------ the list

    private void show(List<Object[]> groups, boolean failed) {
        list.removeAllViews();
        int count = 0, live = 0;
        for (Object[] g : groups) {
            TextView h = text(String.valueOf(g[0]), 24, 0xFFFFD27A, true);
            h.setGravity(Gravity.RIGHT);
            h.setPadding(px(6), px(14), px(6), px(4));
            list.addView(h);
            @SuppressWarnings("unchecked") List<JSONObject> ms = (List<JSONObject>) g[1];
            for (JSONObject m : ms) {
                if (row(m)) live++;
                count++;
            }
        }
        if (top) {
            top = false;
            scroll.post(() -> scroll.scrollTo(0, 0));
        }
        String dayName = DAYS[day];
        if (count == 0) {
            TextView none = text(failed ? "تعذّر تحميل المباريات · تحقق من الإنترنت ثم اضغط OK"
                    : "لا توجد مباريات مهمة " + (day == 1 ? "اليوم" : day == 0 ? "أمس" : "غداً"), 26, 0xFFFFFFFF, false);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, px(80), 0, 0);
            list.addView(none);
            state.setText("");
            return;
        }
        String date = new SimpleDateFormat("EEEE d MMMM", Locale.forLanguageTag("ar-u-nu-latn"))
                .format(new Date(System.currentTimeMillis() + (day - 1) * 86_400_000L));
        state.setText(dayName + " · " + date + " · " + count + " مباراة"
                + (live > 0 ? " · " + live + " مباشرة الآن" : "")
                + (failed ? " · (بعض القوائم لم تُحمّل)" : ""));
    }

    /** One match: home on the right, the score or the time in the middle, away on the left. True if it is on now. */
    private boolean row(JSONObject m) {
        JSONObject st = m.optJSONObject("status");
        if (st == null) st = new JSONObject();
        boolean started = st.optBoolean("started"), finished = st.optBoolean("finished"),
                cancelled = st.optBoolean("cancelled");
        JSONObject reason = st.optJSONObject("reason");
        String rShort = reason == null ? "" : reason.optString("short");
        String rKey = reason == null ? "" : reason.optString("longKey");
        JSONObject home = m.optJSONObject("home"), away = m.optJSONObject("away");
        if (home == null || away == null) return false;

        String mid, note = "";
        int noteColor = 0xFF9FB0BC;
        boolean live = false;
        String score = home.optInt("score") + " - " + away.optInt("score");
        String time = new SimpleDateFormat("HH:mm", Locale.US).format(new Date(m.optLong("_t")));
        if (cancelled || "Can".equals(rShort) || rKey.contains("cancel")) {
            mid = time;
            note = "أُلغيت";
        } else if ("PP".equals(rShort) || rKey.contains("postpone")) {
            mid = time;
            note = "تأجلت";
        } else if (finished) {
            mid = score;
            note = rKey.contains("pen") || "Pen".equals(rShort) ? "انتهت · بركلات الترجيح"
                    : rKey.contains("extra") || "AET".equals(rShort) ? "انتهت · بعد التمديد" : "انتهت";
        } else if (started) {
            mid = score;
            live = true;
            JSONObject lt = st.optJSONObject("liveTime");
            String t = lt == null ? "" : lt.optString("short");
            note = "HT".equals(t) ? "مباشر · استراحة" : t.isEmpty() ? "مباشر" : "مباشر · " + t.replace("‎", "");
            noteColor = 0xFFFF6B6B;
        } else {
            mid = time;
        }

        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(18), px(5), px(18), px(5));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(14));
        bg.setColor(live ? 0x33FF6B6B : 0x1FFFFFFF);
        r.setBackground(bg);

        // left to right: away, the score, home (home on the right, as Arabic reads)
        TextView a = text(name(away), 24, 0xFFFFFFFF, false);
        a.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        r.addView(a, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        TextView s = text(mid, 27, live ? 0xFFFFFFFF : finished ? 0xFFFFFFFF : 0xFF7FE0A8, true);
        s.setGravity(Gravity.CENTER);
        s.setTextDirection(View.TEXT_DIRECTION_LTR);
        if (finished || started) {
            // the home team's goals on the right, by its name
            s.setText(away.optInt("score") + " - " + home.optInt("score"));
        }
        c.addView(s);
        if (!note.isEmpty()) {
            TextView n = text(note, 17, noteColor, live);
            n.setGravity(Gravity.CENTER);
            c.addView(n);
        }
        r.addView(c, new LinearLayout.LayoutParams(px(250), -2));

        TextView h = text(name(home), 24, 0xFFFFFFFF, false);
        h.setGravity(Gravity.CENTER_VERTICAL | Gravity.LEFT);
        r.addView(h, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(5);
        list.addView(r, lp);
        return live;
    }

    private String name(JSONObject team) {
        String n = names.get(String.valueOf(team.optLong("id")));
        return n != null && !n.isEmpty() ? n : team.optString("name");
    }

    // ------------------------------------------------------------------ the Arabic names, kept a week

    private Map<String, String> loadNames() {
        File f = new File(getFilesDir(), "teams_ar.json");
        String body = null;
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 86_400_000L) {
            try {
                body = get("https://www.fotmob.com/api/translationmapping?locale=ar");
                JSONObject p = new JSONObject(body).getJSONObject("Participants");
                body = p.toString();
                java.nio.file.Files.write(f.toPath(), body.getBytes("UTF-8"));
            } catch (Exception e) {
                body = null;
            }
        }
        Map<String, String> out = new HashMap<>();
        try {
            if (body == null) body = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
            JSONObject p = new JSONObject(body);
            JSONArray keys = p.names();
            if (keys != null) for (int i = 0; i < keys.length(); i++) {
                String k = keys.getString(i);
                out.put(k, p.optString(k));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static String get(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(30000);
        h.setRequestProperty("User-Agent", UA);
        h.setRequestProperty("Accept", "application/json");
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) o.write(buf, 0, n);
            if (h.getResponseCode() != 200) throw new Exception("http " + h.getResponseCode());
            return o.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }

    // ------------------------------------------------------------------ helpers

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(2);
        return t;
    }

    private int px(float dp) {
        return (int) (dp * d);
    }
}
