package app.noortv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/**
 * مُحفّظ القرآن: pick a surah and a reciter; each verse plays the chosen number of times with its text large on
 * screen, then the next. The place in each surah is kept. Verse audio from everyayah.com, text from
 * api.alquran.cloud (quran-simple), both kept on the TV once fetched.
 *
 * Picker: Up/Down move, OK opens. Reading: OK pause/play, Right next verse, Left previous, Up/Down the repeats,
 * Back to the list.
 */
public class HifzActivity extends Activity {
    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF;
    private static final String[] RECITERS = {"Husary_Muallim_128kbps", "Alafasy_128kbps", "Minshawy_Murattal_128kbps",
            "Abdul_Basit_Murattal_192kbps", "Maher_AlMuaiqly_64kbps"};
    private static final String[] RECITER_NAMES = {"الحصري المعلّم (للتعليم)", "مشاري العفاسي", "المنشاوي (مرتّل)",
            "عبد الباسط (مرتّل)", "ماهر المعيقلي"};
    private static final int[] REPEATS = {1, 3, 5, 10};

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private Typeface naskh;
    private JSONArray surahs;      // from the API: number, name, numberOfAyahs
    private JSONArray ayahs;       // the open surah's verses
    private int surah = -1, ayah, played;
    private MediaPlayer player;
    private boolean paused, reading;
    private TextView verseView, infoView, stateView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("hifz", MODE_PRIVATE);
        File f = new File("/system/fonts/NotoNaskhArabic-Regular.ttf");
        naskh = f.exists() ? Typeface.createFromFile(f) : Typeface.SERIF;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF10261E, 0xFF0B1A24, 0xFF05090C}));
        setContentView(root);
        root.addView(text("…", 22, TEXT));
        new Thread(() -> {
            JSONArray s = null;
            try {
                s = new JSONObject(fetch("https://api.alquran.cloud/v1/surah", "surahs.json")).getJSONArray("data");
            } catch (Exception ignored) {
            }
            JSONArray got = s;
            h.post(() -> {
                surahs = got;
                showPicker();
            });
        }, "hifz").start();
    }

    // ---- the picker ---------------------------------------------------------------------------------------------

    private void showPicker() {
        stop();
        reading = false;
        root.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        TextView title = text("✦  مُحفّظ القرآن  ✦", 30, GOLD);
        title.setPadding(0, (int) (24 * d), 0, (int) (6 * d));
        root.addView(title);
        if (surahs == null) {
            root.addView(text("ما قدرت أجيب قائمة السور · تأكد من النت وارجع افتحه", 20, TEXT));
            return;
        }
        root.addView(text("القارئ: " + RECITER_NAMES[reciter()] + "   ·   التكرار: " + repeats()
                + " مرات   (يمين ويسار لتغيير القارئ)", 16, 0x99F4EEDF));
        ScrollView sv = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (getResources().getDisplayMetrics().widthPixels * 0.25f);
        list.setPadding(side, (int) (12 * d), side, (int) (30 * d));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        View focus = null;
        int last = prefs.getInt("last", 0);
        if (last > 0) {
            int at = prefs.getInt("ayah_" + last, 0);
            View cont = row("▶  كمّل: " + name(last) + " · آية " + (at + 1), () -> open(last));
            list.addView(cont);
            focus = cont;
        }
        // the short surahs first (Juz Amma backwards), which is how memorising usually starts
        for (int n = 114; n >= 1; n--) {
            int num = n;
            JSONObject s = surahs.optJSONObject(n - 1);
            if (s == null) continue;
            int done = prefs.getInt("ayah_" + n, 0);
            String mark = done > 0 ? "   ·   وصلت آية " + (done + 1) : "";
            View r = row(n + ".  " + name(n) + "   (" + s.optInt("numberOfAyahs") + " آية)" + mark, () -> open(num));
            list.addView(r);
            if (focus == null) focus = r;
        }
        if (focus != null) focus.requestFocus();
    }

    private String name(int n) {
        JSONObject s = surahs == null ? null : surahs.optJSONObject(n - 1);
        return s == null ? "سورة " + n : s.optString("name").replaceAll("[ۖ-ۭـ]", "");
    }

    private View row(String label, Runnable run) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = text(label, 20, TEXT);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setPadding((int) (20 * d), (int) (12 * d), (int) (20 * d), (int) (12 * d));
        t.setFocusable(true);
        t.setOnFocusChangeListener((v, f) -> {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(14 * d);
            g.setColor(f ? 0x40E3BE6E : 0x14FFFFFF);
            g.setStroke((int) ((f ? 2 : 1) * d), f ? GOLD : 0x22FFFFFF);
            v.setBackground(g);
        });
        t.getOnFocusChangeListener().onFocusChange(t, false);
        t.setOnClickListener(v -> run.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (6 * d);
        t.setLayoutParams(lp);
        return t;
    }

    private int reciter() { return Math.min(prefs.getInt("reciter", 0), RECITERS.length - 1); }
    private int repeats() { return prefs.getInt("repeats", 3); }

    // ---- reading a surah ----------------------------------------------------------------------------------------

    /** A surah is loading: Back returns to the list instead of closing. */
    private boolean opening, wasPlaying;

    private void open(int n) {
        surah = n;
        ayah = prefs.getInt("ayah_" + n, 0);
        played = 0;      // a new surah starts its first verse's repeats from one
        paused = false;
        opening = true;
        prefs.edit().putInt("last", n).apply();
        root.removeAllViews();
        root.addView(text("…", 22, TEXT));
        new Thread(() -> {
            JSONArray a = null;
            try {
                a = new JSONObject(fetch("https://api.alquran.cloud/v1/surah/" + n + "/quran-simple", "surah_" + n + ".json"))
                        .getJSONObject("data").getJSONArray("ayahs");
            } catch (Exception ignored) {
            }
            JSONArray got = a;
            h.post(() -> {
                if (!opening) return;   // Back was pressed while it loaded
                opening = false;
                if (got == null) {
                    root.removeAllViews();
                    root.addView(text("ما قدرت أجيب السورة · تأكد من النت", 22, TEXT));
                    return;
                }
                ayahs = got;
                if (ayah >= ayahs.length()) ayah = 0;
                showReader();
                play();
            });
        }, "hifz").start();
    }

    private void showReader() {
        reading = true;
        root.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        TextView head = text(name(surah) + "   ·   " + RECITER_NAMES[reciter()], 22, GOLD);
        head.setPadding(0, (int) (26 * d), 0, 0);
        root.addView(head);
        infoView = text("", 17, 0xB3F4EEDF);
        root.addView(infoView);
        verseView = text("", 44, TEXT);
        verseView.setTypeface(naskh);
        verseView.setLineSpacing(0, 1.35f);
        verseView.setPadding((int) (80 * d), 0, (int) (80 * d), 0);
        LinearLayout mid = new LinearLayout(this);
        mid.setGravity(Gravity.CENTER);
        mid.addView(verseView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        stateView = text("", 18, GOLD);
        root.addView(stateView);
        TextView help = text("OK: إيقاف/تشغيل   ·   يمين: الآية الجاية   ·   يسار: اللي قبلها   ·   فوق وتحت: عدد التكرار   ·   رجوع: السور",
                14, 0x80F4EEDF);
        help.setPadding(0, (int) (6 * d), 0, (int) (18 * d));
        root.addView(help);
        update();
    }

    private void update() {
        if (verseView == null || ayahs == null) return;
        String t = ayahs.optJSONObject(ayah).optString("text").replace("﻿", "").replace("۞", "").trim();
        if (ayah == 0 && surah != 1 && surah != 9 && t.startsWith("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ")) {
            t = t.substring("بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ".length()).trim();
        }
        verseView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 44);
        verseView.setText("﴿ " + t + " ﴾");
        verseView.post(this::fitVerse);
        infoView.setText("آية " + (ayah + 1) + " من " + ayahs.length());
        stateView.setText((paused ? "⏸ متوقف" : "🔁 التكرار " + Math.min(played + 1, repeats()) + " من " + repeats()));
    }

    /** The largest size (44sp down to 18sp) at which the whole verse fits between the title and the help line. */
    private void fitVerse() {
        if (verseView == null || !(verseView.getParent() instanceof View)) return;
        View box = (View) verseView.getParent();
        int w = box.getWidth() - verseView.getPaddingLeft() - verseView.getPaddingRight();
        int ht = box.getHeight();
        if (w <= 0 || ht <= 0) return;
        float sd = getResources().getDisplayMetrics().scaledDensity;
        android.text.TextPaint tp = new android.text.TextPaint(verseView.getPaint());
        CharSequence t = verseView.getText();
        float size = 44;
        while (size > 18) {
            tp.setTextSize(size * sd);
            android.text.StaticLayout l = android.text.StaticLayout.Builder.obtain(t, 0, t.length(), tp, w)
                    .setLineSpacing(0, 1.35f).build();
            if (l.getHeight() <= ht) break;
            size -= 2;
        }
        verseView.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
    }

    private void play() {
        stop();
        if (!reading || paused || ayahs == null) return;
        String url = String.format(Locale.US, "https://everyayah.com/data/%s/%03d%03d.mp3", RECITERS[reciter()], surah, ayah + 1);
        update();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        try {
            player.setDataSource(url);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> h.postDelayed(this::afterVerse, 900));
            player.setOnErrorListener((mp, w, x) -> {
                stateView.setText("ما قدرت أشغّل الصوت · تأكد من النت · OK للمحاولة");
                paused = true;
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            stateView.setText("ما قدرت أشغّل الصوت");
        }
    }

    private void afterVerse() {
        if (!reading || paused) return;
        played++;
        if (played < repeats()) {
            play();
            return;
        }
        played = 0;
        if (ayah + 1 < ayahs.length()) {
            ayah++;
            prefs.edit().putInt("ayah_" + surah, ayah).apply();
            play();
        } else {
            stop();
            prefs.edit().putInt("ayah_" + surah, 0).apply();
            stateView.setText("✅ خلّصت السورة · تقبّل الله · رجوع للسور");
        }
    }

    private void stop() {
        h.removeCallbacksAndMessages(null);
        if (player != null) {
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (!reading) {
            if (code == KeyEvent.KEYCODE_BACK && opening) {
                opening = false;
                showPicker();
                return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                int r = (reciter() + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : RECITERS.length - 1)) % RECITERS.length;
                prefs.edit().putInt("reciter", r).apply();
                showPicker();
                return true;
            }
            return super.onKeyDown(code, e);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                paused = !paused;
                if (paused) stop();
                else play();
                update();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_LEFT:
                int to = ayah + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                if (to >= 0 && to < ayahs.length()) {
                    ayah = to;
                    played = 0;
                    prefs.edit().putInt("ayah_" + surah, ayah).apply();
                    play();
                    update();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                int i = 0;
                for (int k = 0; k < REPEATS.length; k++) if (REPEATS[k] == repeats()) i = k;
                i = (i + (code == KeyEvent.KEYCODE_DPAD_UP ? 1 : REPEATS.length - 1)) % REPEATS.length;
                prefs.edit().putInt("repeats", REPEATS[i]).apply();
                played = Math.min(played, REPEATS[i] - 1);
                update();
                return true;
            case KeyEvent.KEYCODE_BACK:
                showPicker();
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onStop() {
        super.onStop();
        wasPlaying = reading && !paused;
        stop();
        paused = true;
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // back to it: plays on only if it was playing (a pause stays a pause)
        if (reading && wasPlaying) {
            paused = false;
            play();
        } else if (reading) {
            update();
        }
    }

    /** From the TV's cache, or the network (kept for next time). */
    private String fetch(String url, String cacheName) throws Exception {
        File cache = new File(getFilesDir(), cacheName);
        if (cache.exists()) return new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String s = b.toString("UTF-8");
            try (FileOutputStream o = new FileOutputStream(cache)) {
                o.write(s.getBytes(StandardCharsets.UTF_8));
            }
            return s;
        } finally {
            c.disconnect();
        }
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }
}
