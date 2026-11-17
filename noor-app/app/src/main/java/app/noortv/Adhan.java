package app.noortv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Calendar;
import java.util.Locale;

/**
 * The adhan at each prayer time for the city in gradle.properties. With the home screen up and nothing playing it plays the adhan in
 * res/raw (tools/fetch-adhan.sh) under a banner; while a film, a channel or HDMI is on it only shows the banner, so nothing is
 * cut off. Fajr is banner only: most adhan recordings lack «الصلاة خير من النوم». Only the Kahf shortcut runs it.
 */
public class Adhan extends BroadcastReceiver {
    static final String FIRE = "app.noortv.ADHAN";
    static final double LAT = BuildConfig.LAT, LON = BuildConfig.LON;
    static final String HOME = "com.gathin.tvhome";
    private static final String[] KEYS = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    static final String[] NAMES = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    private static MediaPlayer player;
    /** The app context while the adhan plays, to tell Home when it stops. */
    private static Context playingIn;
    static final String PRE = "app.noortv.PRE_ADHAN";
    static final String SUHOOR = "app.noortv.SUHOOR";
    /** Ramadan's Maghrib: the adhan of iftar plays over whatever is on, so leaving Home does not stop it. */
    private static boolean iftar;
    /** The banner on screen while the adhan plays; removed with it when the adhan is stopped. */
    private static android.view.View bannerView;

    static final String RETRY = "app.noortv.ADHAN_RETRY";
    static final String TEST_IN = "app.noortv.ADHAN_TEST_IN";
    /** This adhan switched the TV on from standby: it plays over whatever comes up, and the TV goes back off after it. */
    static boolean woke;

    /**
     * Sets the alarm for the next prayer; fetches today's and tomorrow's times in the background. When no times can
     * be had (no internet, the site down) it tries again in ten minutes, so a short cut never silences the adhans.
     * Each success also keeps the coming month's times on the TV, for days without internet.
     */
    static void schedule(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (!scheduleNow(app)) retryLater(app);
                else prefetchMonth(app);
            } catch (Exception e) {
                // never let a scheduling error take the whole app (and the phone page) down with it
                log(app, "schedule: " + e);
                try { retryLater(app); } catch (Exception ignored) { }
            }
        }, "adhan").start();
    }

    /**
     * An exact alarm where the system allows one (Android 12+ asks for the alarm permission; without it, the
     * nearest the system gives). MessageServer's clock (due) still calls the adhan on the minute either way.
     */
    static void exact(AlarmManager am, long when, PendingIntent pi) {
        try {
            if (android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
                return;
            }
        } catch (SecurityException ignored) {
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pi);
    }

    private static boolean scheduleNow(Context app) {
        {
            Calendar now = Calendar.getInstance();
            for (int dayOffset = 0; dayOffset < 2; dayOffset++) {
                Calendar day = (Calendar) now.clone();
                day.add(Calendar.DAY_OF_YEAR, dayOffset);
                String[] times = times(app, day);
                if (times == null) continue;
                for (int i = 0; i < KEYS.length; i++) {
                    String[] hm = times[i].split(":");
                    Calendar at = (Calendar) day.clone();
                    at.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                    at.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                    at.set(Calendar.SECOND, 0);
                    at.set(Calendar.MILLISECOND, 0);
                    if (at.getTimeInMillis() > now.getTimeInMillis() + 30_000) {
                        set(app, at.getTimeInMillis(), i);
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static void retryLater(Context c) {
        PendingIntent pi = PendingIntent.getBroadcast(c, 4, new Intent(c, Adhan.class).setAction(RETRY),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        c.getSystemService(AlarmManager.class).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + 10 * 60_000L, pi);
    }

    /** This month's and next month's times in two requests, kept like the daily ones (when the 25th day ahead is missing). */
    private static void prefetchMonth(Context c) {
        Calendar ahead = Calendar.getInstance();
        ahead.add(Calendar.DAY_OF_YEAR, 25);
        SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        if (p.contains(key(c, ahead))) return;
        Calendar m = Calendar.getInstance();
        SharedPreferences.Editor e = p.edit();
        for (int k = 0; k < 2; k++) {
            try {
                String url = "https://api.aladhan.com/v1/calendar/" + m.get(Calendar.YEAR) + "/" + (m.get(Calendar.MONTH) + 1)
                        + "?latitude=" + NoorConfig.lat(c) + "&longitude=" + NoorConfig.lon(c) + "&method=" + NoorConfig.method(c)
                        + "&timezonestring=" + java.net.URLEncoder.encode(java.util.TimeZone.getDefault().getID(), "UTF-8");
                org.json.JSONArray days = new JSONObject(get(url)).getJSONArray("data");
                for (int i = 0; i < days.length(); i++) {
                    JSONObject day = days.getJSONObject(i);
                    String[] dmy = day.getJSONObject("date").getJSONObject("gregorian").getString("date").split("-");
                    Calendar at = Calendar.getInstance();
                    at.set(Integer.parseInt(dmy[2]), Integer.parseInt(dmy[1]) - 1, Integer.parseInt(dmy[0]));
                    e.putString(key(c, at), parse(day.getJSONObject("timings")));
                }
            } catch (Exception ignored) {
            }
            m.add(Calendar.MONTH, 1);
        }
        e.apply();
    }

    /** A day's cache key: the date, the place and method, and the TV's time zone (a corrected zone fetches again). */
    private static String key(Context c, Calendar day) {
        return String.format(Locale.US, "%02d-%02d-%d", day.get(Calendar.DAY_OF_MONTH), day.get(Calendar.MONTH) + 1,
                day.get(Calendar.YEAR)) + "@" + NoorConfig.lat(c) + "," + NoorConfig.lon(c) + "," + NoorConfig.method(c)
                + "," + java.util.TimeZone.getDefault().getID();
    }

    /** "HH:mm,..." for Fajr..Isha from Aladhan's timings ("05:02 (+03)"). */
    private static String parse(JSONObject t) throws Exception {
        String[] out = new String[KEYS.length];
        for (int i = 0; i < KEYS.length; i++) {
            String[] hm = t.getString(KEYS[i]).substring(0, 5).split(":");
            int m = Integer.parseInt(hm[0]) * 60 + Integer.parseInt(hm[1]);
            out[i] = String.format(Locale.US, "%02d:%02d", m / 60, m % 60);
        }
        return String.join(",", out);
    }

    private static void set(Context c, long when, int prayer) {
        Intent fire = new Intent(c, Adhan.class).setAction(FIRE).putExtra("p", prayer).putExtra("at", when);
        PendingIntent pi = PendingIntent.getBroadcast(c, 1, fire,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager am = c.getSystemService(AlarmManager.class);
        exact(am, when, pi);
        // and for MessageServer's own clock (due): with the screen off the system holds plain alarms back for up to an hour
        c.getSharedPreferences("adhan", Context.MODE_PRIVATE).edit().putLong("nextAt", when).putInt("nextP", prayer).apply();
        log(c, "set: p=" + prayer + " at " + clock(when));
        // a time still to come cannot have been called: such a mark is left from a stale alarm, and would skip it
        SharedPreferences sp = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        if (sp.getLong("lastFired", 0) == when && when > System.currentTimeMillis()) {
            sp.edit().remove("lastFired").commit();
            log(c, "cleared a wrong done-mark for " + clock(when));
        }
        // and a quiet line ten minutes before it (the control center can turn it off)
        Intent pre = new Intent(c, Adhan.class).setAction(PRE).putExtra("p", prayer).putExtra("at", when - 10 * 60_000L);
        PendingIntent ppi = PendingIntent.getBroadcast(c, 2, pre,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long preAt = when - 10 * 60_000L;
        boolean preOn = NoorConfig.preAdhan(c) && preAt > System.currentTimeMillis() + 30_000;
        if (preOn) {
            exact(am, preAt, ppi);
        } else {
            am.cancel(ppi);
        }
        // for MessageServer's clock too (due): the firmware may hold the alarm back
        c.getSharedPreferences("adhan", Context.MODE_PRIVATE).edit().putLong("preAt", preOn ? preAt : 0).apply();
        // Ramadan: a suhoor line 45 minutes before Fajr
        Intent suhoor = new Intent(c, Adhan.class).setAction(SUHOOR);
        PendingIntent spi = PendingIntent.getBroadcast(c, 3, suhoor,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        long suhoorAt = when - 45 * 60_000L;
        // the Hijri date of that Fajr's own day: not the evening it was scheduled (Sha'ban's last night, Eid's eve)
        Calendar fajrDay = Calendar.getInstance();
        fajrDay.setTimeInMillis(when);
        if (prayer == 0 && NoorConfig.ramadan(c, fajrDay) && suhoorAt > System.currentTimeMillis() + 30_000) {
            exact(am, suhoorAt, spi);
        } else {
            am.cancel(spi);
        }
    }

    /** "HH:mm" for Fajr..Isha on that day, from Aladhan (noor.method), cached per day for offline days. */
    private static String[] times(Context c, Calendar day) {
        String date = String.format(Locale.US, "%02d-%02d-%d", day.get(Calendar.DAY_OF_MONTH),
                day.get(Calendar.MONTH) + 1, day.get(Calendar.YEAR));
        SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        double lat = NoorConfig.lat(c), lon = NoorConfig.lon(c);
        int method = NoorConfig.method(c);
        String key = key(c, day);
        String cached = p.getString(key, null);
        if (cached != null) return cached.split(",");
        try {
            JSONObject data = new JSONObject(get("https://api.aladhan.com/v1/timings/" + date + "?latitude=" + lat
                    + "&longitude=" + lon + "&method=" + method
                    // in the TV's own time zone: the site's zone data is current, the TV's may not be
                    + "&timezonestring=" + java.net.URLEncoder.encode(java.util.TimeZone.getDefault().getID(), "UTF-8"))).getJSONObject("data");
            String out = parse(data.getJSONObject("timings"));
            p.edit().putString(key, out).apply();
            return out.split(",");
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!BuildConfig.REMINDERS) return;
        Context app = c.getApplicationContext();
        // an alarm held back while the chip slept (switching the TV on hours later) is past: no late adhan
        long at = i.getLongExtra("at", 0);
        boolean late = at > 0 && System.currentTimeMillis() - at > 5 * 60_000L;
        if (TEST_IN.equals(i.getAction())) {
            // a real adhan alarm in s seconds (adb), to try the TV in standby: am broadcast -a app.noortv.ADHAN_TEST_IN --ei s 90
            set(app, System.currentTimeMillis() + i.getIntExtra("s", 60) * 1000L, i.getIntExtra("p", 1));
            // --ei pre 30: the ten-minute notice too, in that many seconds (MessageServer's clock brings it)
            if (i.hasExtra("pre")) app.getSharedPreferences("adhan", Context.MODE_PRIVATE).edit()
                    .putLong("preAt", System.currentTimeMillis() + i.getIntExtra("pre", 30) * 1000L).commit();
            return;
        }
        // An alarm that arrives before its time is a stale one: the system held the last prayer's alarm back in
        // standby and delivers it when the adhan wakes the TV, by then carrying the NEXT prayer's time (the pending
        // intent was updated). Taking it would play that prayer now and mark it done, so its real time is skipped:
        // this is how Fajr was lost after Isha's adhan woke the TV.
        if (at > System.currentTimeMillis() + 20_000 && (FIRE.equals(i.getAction()) || PRE.equals(i.getAction()))) {
            log(app, "ignored: a stale alarm for " + clock(at) + " arrived early");
            return;
        }
        if (late && !FIRE.equals(i.getAction())) return;
        if (PRE.equals(i.getAction())) {
            int p = i.getIntExtra("p", 1);
            // once: the alarm and MessageServer's clock may both bring it
            SharedPreferences pp = app.getSharedPreferences("adhan", Context.MODE_PRIVATE);
            if (at > 0 && pp.getLong("lastPre", 0) == at) return;
            if (at > 0) pp.edit().putLong("lastPre", at).commit();
            home(app, "w");
            int mode = NoorConfig.adhan(app);
            if (mode == NoorConfig.ADHAN_OFF || Health.clockBad) return;
            boolean imsak = p == 0 && NoorConfig.ramadan(app);
            String say = imsak ? "⏳ الإمساك: باقي 10 دقائق على الفجر" : "⏳ باقي 10 دقائق على أذان " + NAMES[p];
            if (app.getSystemService(PowerManager.class).isInteractive()) {
                Reminder.show(app, say);
                return;
            }
            // the TV in standby: on now for the notice, and kept on until the adhan, which then puts it back to standby
            if (mode != NoorConfig.ADHAN_SOUND || (p == 0 && !NoorConfig.fajrSound(app))) return;
            try {
                PowerManager pm = app.getSystemService(PowerManager.class);
                if (wake == null) {
                    wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE, "noortv:adhan");
                }
                if (wake.isHeld()) wake.release();
                wake.acquire(13 * 60_000L);
            } catch (Exception ignored) {
            }
            pp.edit().putLong("preWokeAt", System.currentTimeMillis()).commit();
            log(app, "pre: the TV switched on from standby for the notice");
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                home(app, "w");
                quiet(app);
                WakeScreen.show(app, say);
            }, 1500);
            // the Home app comes up over everything a few seconds after the TV wakes: the notice back over it
            for (long d : new long[]{6000, 11000, 17000}) {
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (WakeScreen.open && !WakeScreen.inFront) WakeScreen.show(app, say);
                }, d);
            }
            return;
        }
        if (RETRY.equals(i.getAction())) {
            schedule(app);
            return;
        }
        if (SUHOOR.equals(i.getAction())) {
            if (app.getSystemService(PowerManager.class).isInteractive()) {
                Reminder.show(app, "🌙 وقت السحور · باقي 45 دقيقة على الفجر · تسحّروا فإن في السحور بركة");
            }
            return;
        }
        if (!FIRE.equals(i.getAction())) return;
        int prayer = i.getIntExtra("p", 1);
        log(app, "fire arrives: p=" + prayer + " for " + clock(at) + (late ? " LATE" : "")
                + " screen=" + (app.getSystemService(PowerManager.class).isInteractive() ? "on" : "off")
                + " mode=" + NoorConfig.adhan(app) + " fajrSound=" + NoorConfig.fajrSound(app));
        // once per adhan: the alarm and MessageServer's clock may both bring it
        SharedPreferences ap = app.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        if (at > 0 && ap.getLong("lastFired", 0) == at) return;
        if (at > 0) ap.edit().putLong("lastFired", at).commit();
        schedule(app);
        if (late) {
            Health.missed(app, prayer, at, "وصل المنبّه متأخراً");
            return;
        }
        if (Health.clockBad) {
            log(app, "skipped: the TV's clock is not trusted");
            return;
        }
        int mode = NoorConfig.adhan(app);
        if (mode == NoorConfig.ADHAN_OFF) return;
        // the screensaver counts as "on" but covers every screen: out of it first
        home(app, "w");
        long preWoke = ap.getLong("preWokeAt", 0);
        ap.edit().remove("preWokeAt").commit();
        if (preWoke > 0 && System.currentTimeMillis() - preWoke < 20 * 60_000L
                && app.getSystemService(PowerManager.class).isInteractive()) {
            String top = topApp(app);
            if (top == null || HOME.equals(top) || top.startsWith("app.noortv.")) {
                // the notice woke it ten minutes ago and it is still on Home: the adhan as from standby
                log(app, "the notice woke the TV: the adhan returns it to standby");
                woke = true;
                fire(app, prayer, mode == NoorConfig.ADHAN_SOUND && (prayer != 0 || NoorConfig.fajrSound(app)),
                        prayer == 3 && NoorConfig.ramadan(app));
                return;
            }
        }
        if (!app.getSystemService(PowerManager.class).isInteractive()) {
            // the TV in standby: switch it on for the adhan, then back off (Fajr only when its sound is on)
            if (mode != NoorConfig.ADHAN_SOUND || (prayer == 0 && !NoorConfig.fajrSound(app))) return;
            woke = true;
            fire(app, prayer, true, prayer == 3 && NoorConfig.ramadan(app));
            return;
        }
        // The adhan comes first: whatever is on (a film, a channel, HDMI, a game) its full screen comes up over it and
        // the film pauses; whoever is watching sends it away with a key. Fajr's sound as the control center says.
        boolean ramadanIftar = prayer == 3 && NoorConfig.ramadan(app);
        fire(app, prayer, mode == NoorConfig.ADHAN_SOUND && (prayer != 0 || NoorConfig.fajrSound(app)), ramadanIftar);
        if (ramadanIftar) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> Reminder.show(app,
                    "🤲 ذهب الظمأ وابتلّت العروق وثبت الأجر إن شاء الله"), 4000);
        }
    }

    /** «جرّب الأذان»: the chosen muezzin now, with a banner that says it is a test and how to stop it. */
    static void test(Context app) {
        fire(app, -1, true, false);
    }

    /** Stops the adhan and takes its banner away (Back on Home, the control center, or a second press). */
    static void stopNow() {
        new Handler(Looper.getMainLooper()).post(() -> {
            stop();
            removeBanner();
        });
    }

    /** The adhan's sound being made ready (off the main thread), and which adhan it is for. */
    private static volatile boolean starting;
    private static volatile int generation;

    static boolean playing() {
        return player != null || starting;
    }

    /**
     * The adhan's full screen over whatever is on, and the adhan itself when play is true. prayer -1 is the control
     * center's test. The screen stays in front for the whole adhan (the TV's own apps and the launcher come back over
     * it now and then) until a key sends it away; after the adhan it closes, or puts the TV back to standby if it woke it.
     */
    static void fire(Context app, int prayer, boolean play, boolean isIftar) {
        String text = prayer < 0 ? "🕌 تجربة صوت الأذان"
                : prayer == 3 && isIftar ? "🌙 حان موعد الإفطار · أذان المغرب" : "🕌 حان الآن موعد أذان " + NAMES[prayer];
        // the muezzin chosen in the control center, then the Umayyad recording, then a plain "adhan" from older builds
        int raw = app.getResources().getIdentifier("adhan_" + NoorConfig.muezzin(app), "raw", app.getPackageName());
        if (raw == 0) raw = app.getResources().getIdentifier("adhan_umayyad", "raw", app.getPackageName());
        if (raw == 0) raw = app.getResources().getIdentifier("adhan", "raw", app.getPackageName());
        boolean fromStandby = woke;
        stop();
        iftar = isIftar;
        woke = fromStandby;
        boolean withSound = play && raw != 0;
        if (fromStandby) {
            quiet(app);
            // again once the screen is up: the TV brings its last app back a few seconds after waking
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                quiet(app);
                AdhanScreen.show(app, text, true, !playing());
            }, 5000);
        }
        if (withSound) {
            // first the screen on, the film's sound paused (audio focus), the TV unmuted and at the adhan's volume
            wakeAndUnmute(app, prayer == 0 || prayer == 4 ? 20 / 60f : 25 / 60f);
            log(app, "adhan starts: p=" + prayer + " fromStandby=" + fromStandby);
            Health.played(app, prayer, System.currentTimeMillis());
            playingIn = app;
            state(app, true);
            starting = true;
            int mine = ++generation, sound = raw;
            float vol = NoorConfig.volume(app) * (prayer == 0 || prayer == 4 ? NoorConfig.nightVolume(app) : 1f);
            Handler main = new Handler(Looper.getMainLooper());
            // off the main thread, and again if it fails: as the TV goes to standby its sound engine stops answering
            // for a while (Asr, half a minute after the TV switched itself off, never played and held everything up)
            new Thread(() -> {
                MediaPlayer mp = null;
                for (int tries = 0; tries < 8 && mp == null && mine == generation; tries++) {
                    try {
                        mp = MediaPlayer.create(app, sound, new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                                app.getSystemService(AudioManager.class).generateAudioSessionId());
                    } catch (Exception ignored) {
                    }
                    if (mp == null) android.os.SystemClock.sleep(2500);
                }
                MediaPlayer made = mp;
                main.post(() -> {
                    if (mine != generation) {
                        if (made != null) made.release();
                        return;
                    }
                    starting = false;
                    if (made == null) {
                        stop();
                        AdhanScreen.show(app, text, fromStandby, true);
                        return;
                    }
                    player = made;
                    player.setVolume(vol, vol);
                    player.setOnCompletionListener(done -> {
                        stop();
                        removeBanner();
                        AdhanScreen.show(app, text, fromStandby, true);
                    });
                    player.start();
                });
            }, "adhan-sound").start();
        }
        AdhanScreen.show(app, text, fromStandby, false);
        // back over whatever comes up while it plays (the launcher sends a returning live channel to Home a little
        // after waking; some players bring themselves back); a key on the screen stops the adhan and ends this
        Handler h = new Handler(Looper.getMainLooper());
        long until = android.os.SystemClock.uptimeMillis() + 90_000;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean on = withSound ? playing() : AdhanScreen.open() && android.os.SystemClock.uptimeMillis() < until;
                if (!on || AdhanScreen.dismissed()) return;
                if (!AdhanScreen.inFront()) AdhanScreen.show(app, text, fromStandby, false);
                h.postDelayed(this, 2500);
            }
        }, 2500);
    }

    /**
     * The TV woken from standby by Noor (the notice, the adhan): it comes back with the last app of the night, and a
     * film or a channel left playing goes on by itself, loud, as soon as the adhan gives the sound back (a Fajr woke
     * the whole house that way). So: the player paused, and Home put in front of it (under the adhan's screen).
     */
    static void quiet(Context app) {
        try {
            String top = topApp(app);
            if (top != null && !HOME.equals(top) && !top.startsWith("app.noortv.")) {
                log(app, "woke with " + top + " in front: paused, Home in front of it");
                if (LocalAdb.ready()) LocalAdb.run(app, "input keyevent KEYCODE_MEDIA_PAUSE");
                else app.getSystemService(AudioManager.class).dispatchMediaKeyEvent(
                        new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE));
            }
            app.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        // and every app of the night closed, those playing in the background without a picture too
        MemGuard.closeForWake(app);
    }

    /**
     * After an adhan that woke the TV, while it counts down to standby: a sound that is not the adhan is a player
     * that came back by itself. Paused, and the background apps closed again.
     */
    static void hushStray(Context app) {
        try {
            if (playing() || !app.getSystemService(AudioManager.class).isMusicActive()) return;
            log(app, "a sound after the adhan that is not the adhan: paused, apps closed");
            if (LocalAdb.ready()) LocalAdb.run(app, "input keyevent KEYCODE_MEDIA_PAUSE");
            MemGuard.closeForWake(app);
        } catch (Exception ignored) {
        }
    }

    /** Stops the adhan as soon as an app is opened over the home screen. */
    private static void watch(Context app) {
        Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (player == null) return;
                String top = topApp(app);
                if (top != null && !HOME.equals(top) && !top.startsWith("app.noortv.") && !iftar && !woke) {
                    stop();
                    removeBanner();
                    return;
                }
                h.postDelayed(this, 1500);
            }
        }, 1500);
    }

    /** Home listens: while the adhan plays, any key on the remote stops it. */
    private static void state(Context c, boolean playing) {
        try {
            c.sendBroadcast(new Intent("app.noortv.ADHAN_STATE").setPackage(HOME).putExtra("playing", playing));
        } catch (Exception ignored) {
        }
    }

    private static PowerManager.WakeLock wake;
    private static android.media.AudioFocusRequest focus;
    private static boolean unmuted;
    private static int volBefore = -1, volSet = -1;

    /**
     * A muted or silent TV played the adhan without a sound until a key unmuted it halfway through. Wake the screen,
     * take the audio focus, unmute the media stream and give it an audible level; restoreAudio puts it all back.
     */
    private static void wakeAndUnmute(Context app, float share) {
        try {
            PowerManager pm = app.getSystemService(PowerManager.class);
            if (wake == null) {
                wake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "noortv:adhan");
            }
            if (!wake.isHeld()) wake.acquire(10 * 60_000L);
        } catch (Exception ignored) {
        }
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            // the TV woken from standby: the focus for good, so that no player of the night takes the sound back after
            focus = new android.media.AudioFocusRequest.Builder(woke ? AudioManager.AUDIOFOCUS_GAIN : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).build();
            am.requestAudioFocus(focus);
            unmuted = am.isStreamMute(AudioManager.STREAM_MUSIC);
            if (unmuted) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            // the adhan at a share of the TV's scale (25 of 60 on the TV it was tuned on, Fajr and Isha 20 of 60: the
            // house may be asleep), whatever it was left at: the same loudness on a TV with 15 steps or with 100
            int level = Math.max(1, Math.min(max, Math.round(max * share)));
            volBefore = volSet = -1;
            if (cur != level) {
                volBefore = cur;
                volSet = level;
                am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
            }
            app.getSharedPreferences("adhan", Context.MODE_PRIVATE).edit().putInt("volBefore", volBefore)
                    .putInt("volSet", volSet).putBoolean("volMuted", unmuted).commit();
        } catch (Exception ignored) {
        }
    }

    /** The app has just started: a volume an adhan changed and never gave back (it was cut short) goes back now. */
    static void restoreLeftover(Context c) {
        SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        if (!p.contains("volSet") || starting) return;
        volBefore = p.getInt("volBefore", -1);
        volSet = p.getInt("volSet", -1);
        unmuted = p.getBoolean("volMuted", false);
        log(c, "an adhan was cut short: the volume goes back to " + volBefore);
        restoreAudio(c.getApplicationContext());
    }

    /** After the adhan: the sound as it was, unless someone changed the volume meanwhile. */
    private static void restoreAudio(Context app) {
        try {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (focus != null) am.abandonAudioFocusRequest(focus);
            boolean untouched = volSet < 0 || am.getStreamVolume(AudioManager.STREAM_MUSIC) == volSet;
            if (volBefore >= 0 && untouched) am.setStreamVolume(AudioManager.STREAM_MUSIC, volBefore, 0);
            if (unmuted && untouched && !am.isStreamMute(AudioManager.STREAM_MUSIC)) {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
            }
        } catch (Exception ignored) {
        }
        focus = null;
        unmuted = false;
        volBefore = volSet = -1;
        app.getSharedPreferences("adhan", Context.MODE_PRIVATE).edit().remove("volBefore").remove("volSet")
                .remove("volMuted").commit();
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
    }

    /**
     * Back to standby after an adhan that switched the TV on: a short screen timeout (WRITE_SETTINGS, granted over adb),
     * which MessageServer sets back as soon as the screen is off, or on any key in AdhanScreen.
     */
    /** A word to the Home app's service: "w" ends the screensaver, "z" puts the TV in standby. */
    static void home(Context c, String cmd) {
        try {
            c.sendBroadcast(new Intent("com.gathin.tvhome.PAD").setPackage(HOME).putExtra("c", cmd)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND));
        } catch (Exception ignored) {
        }
    }

    static void sleepTv(Context c) {
        // standby through the Home app; the short timeout below is the fallback, set back a minute and a half later
        home(c, "z");
        try {
            SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
            p.edit().putLong("timeoutAt", System.currentTimeMillis()).commit();
            if (!Settings.System.canWrite(c)) return;   // no leave to change settings: the Home app's standby is the way
            if (!p.contains("timeoutBefore")) {
                int before = Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 1800000);
                p.edit().putInt("timeoutBefore", before).commit();
            }
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 10_000);
        } catch (Exception ignored) {
        }
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
    }

    /** MessageServer's clock, every few seconds: the adhan whose time has come, if the alarm has not brought it yet. */
    static void due(Context c) {
        SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
        long at = p.getLong("nextAt", 0), now = System.currentTimeMillis();
        // the diary: a gap between two ticks means the TV stopped this app for that long (deep standby)
        long up = android.os.SystemClock.elapsedRealtime();
        if (lastTick != 0 && up - lastTick > 30_000) log(c, "gap: the clock did not run for " + (up - lastTick) / 1000 + " s");
        lastTick = up;
        if (up - lastBeat > 20 * 60_000L) {
            lastBeat = up;
            log(c, "beat: next p=" + p.getInt("nextP", -1) + " at " + clock(at)
                    + " screen=" + (c.getSystemService(PowerManager.class).isInteractive() ? "on" : "off"));
        }
        // a timeout sleepTv shortened and nothing set back (the screensaver came instead of standby)
        if (p.contains("timeoutBefore") && now - p.getLong("timeoutAt", 0) > 90_000L) {
            restoreScreenTimeout(c);
            log(c, "screen timeout set back");
        }
        long preWoke = p.getLong("preWokeAt", 0);
        if (preWoke > 0 && now - preWoke > 20 * 60_000L) {
            p.edit().remove("preWokeAt").commit();
            String top = topApp(c);
            if (!playing() && c.getSystemService(PowerManager.class).isInteractive()
                    && (top == null || HOME.equals(top) || top.startsWith("app.noortv."))) {
                log(c, "the notice woke the TV and no adhan came: back to standby");
                sleepTv(c);
            }
        }
        long pre = p.getLong("preAt", 0);
        if (pre > 0 && now >= pre && now - pre < 3 * 60_000L && p.getLong("lastPre", 0) != pre) {
            new Adhan().onReceive(c, new Intent(c, Adhan.class).setAction(PRE).putExtra("p", p.getInt("nextP", 1)).putExtra("at", pre));
        }
        if (at <= 0 || now < at || p.getLong("lastFired", 0) == at) return;
        if (now - at > 5 * 60_000L) {
            // its time passed while the clock was not running: say so once, and go on to the next prayer
            log(c, "missed: p=" + p.getInt("nextP", -1) + " at " + clock(at) + ", seen " + (now - at) / 60_000 + " min late");
            p.edit().putLong("lastFired", at).commit();
            Health.missed(c, Math.max(0, Math.min(4, p.getInt("nextP", 1))), at, "التلفزيون في نوم عميق");
            schedule(c);
            return;
        }
        log(c, "clock: time for p=" + p.getInt("nextP", 1) + " (" + clock(at) + ")");
        // straight into the receiver: a broadcast to ourselves can sit in the system's queue while the TV is in standby
        new Adhan().onReceive(c, new Intent(c, Adhan.class).setAction(FIRE).putExtra("p", p.getInt("nextP", 1)).putExtra("at", at));
    }

    private static long lastTick, lastBeat;

    private static String clock(long t) {
        return new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new java.util.Date(t));
    }

    /** The adhan's diary (files/adhan.log, the last few hundred lines): read from the phone page's adhan/log. */
    static synchronized void log(Context c, String line) {
        try {
            java.io.File f = new java.io.File(c.getApplicationContext().getFilesDir(), "adhan.log");
            if (f.length() > 60_000) {
                // keep the newer half
                byte[] all = java.nio.file.Files.readAllBytes(f.toPath());
                try (java.io.FileOutputStream o = new java.io.FileOutputStream(f)) {
                    o.write(all, all.length / 2, all.length - all.length / 2);
                }
            }
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(f, true)) {
                o.write((clock(System.currentTimeMillis()) + "  " + line + "\n").getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    static String diary(Context c) {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(c.getApplicationContext().getFilesDir(), "adhan.log").toPath()), "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** The screen timeout as it was before sleepTv, if it is still shortened. */
    static void restoreScreenTimeout(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences("adhan", Context.MODE_PRIVATE);
            if (!p.contains("timeoutBefore")) return;
            int before = p.getInt("timeoutBefore", 1800000);
            p.edit().remove("timeoutBefore").commit();
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, before);
        } catch (Exception ignored) {
        }
    }

    private static void stop() {
        starting = false;
        generation++;
        if (playingIn != null) restoreAudio(playingIn);
        iftar = false;
        woke = false;
        if (playingIn != null) {
            state(playingIn, false);
            playingIn = null;
        }
        if (player == null) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        player.release();
        player = null;
    }

    /** The app in front, from usage events (GET_USAGE_STATS is granted over adb), or null if unknown. */
    static String topApp(Context c) {
        UsageStatsManager usm = c.getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(now - 24 * 3600_000L, now);
        UsageEvents.Event e = new UsageEvents.Event();
        String top = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) top = e.getPackageName();
        }
        return top;
    }

    private static void removeBanner() {
        android.view.View v = bannerView;
        bannerView = null;
        if (v == null) return;
        try {
            v.getContext().getSystemService(WindowManager.class).removeView(v);
        } catch (Exception ignored) {
        }
    }

    private static void banner(Context c, String text, int ms) {
        if (!Settings.canDrawOverlays(c)) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            removeBanner();
            WindowManager wm = c.getSystemService(WindowManager.class);
            TextView v = new TextView(c);
            v.setText(text);
            v.setTextColor(Color.WHITE);
            v.setTextSize(30);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setPadding(56, 24, 56, 24);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xE60F3D2E);
            bg.setCornerRadius(48);
            bg.setStroke(3, 0xFFFFE08A);
            v.setBackground(bg);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = 215; // below the clock block, so the weather and next prayer stay readable
            try {
                wm.addView(v, lp);
            } catch (Exception e) {
                return;
            }
            bannerView = v;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (bannerView == v) removeBanner();
            }, ms);
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
