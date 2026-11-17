package app.noortv;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * The adhan and reminder choices made in the launcher's Noor control center, sent here as an
 * app.noortv.SETTINGS broadcast. Until the first one arrives, the build's own values apply.
 */
final class NoorConfig {
    static final String ACTION = "app.noortv.SETTINGS";
    static final int ADHAN_SOUND = 0, ADHAN_BANNER = 1, ADHAN_OFF = 2;

    private NoorConfig() {
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("noor_config", Context.MODE_PRIVATE);
    }

    static void save(Context c, Intent i) {
        p(c).edit()
                .putInt("adhan", i.getIntExtra("adhan", ADHAN_SOUND))
                .putInt("volume", i.getIntExtra("volume", 100))
                .putInt("nightVolume", i.getIntExtra("nightVolume", 100))
                .putBoolean("fajrSound", i.getBooleanExtra("fajrSound", true))
                .putInt("remind", i.getIntExtra("remind", 45))
                .putString("lat", String.valueOf(i.getDoubleExtra("lat", BuildConfig.LAT)))
                .putString("lon", String.valueOf(i.getDoubleExtra("lon", BuildConfig.LON)))
                .putInt("method", i.getIntExtra("method", BuildConfig.METHOD))
                .putBoolean("preAdhan", i.getBooleanExtra("preAdhan", true))
                .putInt("ramadan", i.getIntExtra("ramadan", 0))
                .putInt("hijriOffset", i.getIntExtra("hijriOffset", 0))
                .putString("muezzin", i.getStringExtra("muezzin") != null ? i.getStringExtra("muezzin") : "umayyad")
                .apply();
    }

    static int adhan(Context c) { return p(c).getInt("adhan", ADHAN_SOUND); }
    static float volume(Context c) { return p(c).getInt("volume", 100) / 100f; }
    /** The Isha and Fajr adhan's share of that volume (100, 60 or 35). */
    static float nightVolume(Context c) { return p(c).getInt("nightVolume", 100) / 100f; }
    static boolean fajrSound(Context c) { return p(c).getBoolean("fajrSound", true); }
    /** Minutes between the salawat lines; 0 is off. */
    static int remind(Context c) { return p(c).getInt("remind", 45); }
    static double lat(Context c) { return Double.parseDouble(p(c).getString("lat", String.valueOf(BuildConfig.LAT))); }
    static double lon(Context c) { return Double.parseDouble(p(c).getString("lon", String.valueOf(BuildConfig.LON))); }
    static int method(Context c) { return p(c).getInt("method", BuildConfig.METHOD); }
    /** Which recording plays: res/raw/adhan_<muezzin>.mp3 */
    /** Ramadan mode: 0 on by itself through Ramadan (Umm al-Qura month), 1 on now, 2 off. */
    static boolean ramadan(Context c) {
        return ramadan(c, java.util.Calendar.getInstance());
    }

    /** Ramadan on that day: the Umm al-Qura month, moved by the control center's +/- days (as the home screen does). */
    static boolean ramadan(Context c, java.util.Calendar day) {
        int mode = p(c).getInt("ramadan", 0);
        if (mode == 1) return true;
        if (mode == 2) return false;
        java.util.Calendar d = (java.util.Calendar) day.clone();
        d.add(java.util.Calendar.DAY_OF_YEAR, p(c).getInt("hijriOffset", 0));
        android.icu.util.IslamicCalendar h = new android.icu.util.IslamicCalendar();
        h.setCalculationType(android.icu.util.IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        h.setTimeInMillis(d.getTimeInMillis());
        return h.get(android.icu.util.IslamicCalendar.MONTH) == 8;   // 0-based: 8 is Ramadan
    }

    /** The line ten minutes before each adhan. */
    static boolean preAdhan(Context c) { return p(c).getBoolean("preAdhan", true); }
    static String muezzin(Context c) { return p(c).getString("muezzin", "umayyad"); }
}
