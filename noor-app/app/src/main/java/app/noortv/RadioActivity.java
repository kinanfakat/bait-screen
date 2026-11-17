package app.noortv;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The live Quran radio inside the app: a calm full screen with the station and its state. OK pauses and plays,
 * Back closes it, and leaving the screen any other way (Home, another app, the TV going to standby) stops the
 * sound too, so nothing keeps playing in the background.
 */
public class RadioActivity extends Activity {
    private static final int GOLD = 0xFFE3BE6E;

    /**
     * The stations, the card's own first: reciters, tafsir, seerah and adhkar from mp3quran.net's radio list, and two
     * news radios. Left and right change the station; the last one played is the one the card opens next time.
     */
    private static final String[][] STATIONS = {
            {"إذاعة القرآن الكريم · السعودية", BuildConfig.STREAM},
            {"مشاري العفاسي", "https://backup.qurango.net/radio/mishary_alafasi"},
            {"ماهر المعيقلي", "https://backup.qurango.net/radio/maher"},
            {"عبد الباسط عبد الصمد · مجوّد", "https://backup.qurango.net/radio/abdulbasit_abdulsamad_mojawwad"},
            {"محمد صدّيق المنشاوي", "https://backup.qurango.net/radio/mohammed_siddiq_alminshawi"},
            {"محمود خليل الحصري", "https://backup.qurango.net/radio/mahmoud_khalil_alhussary"},
            {"ياسر الدوسري", "https://backup.qurango.net/radio/yasser_aldosari"},
            {"سعود الشريم", "https://backup.qurango.net/radio/saud_alshuraim"},
            {"تفسير القرآن", "https://backup.qurango.net/radio/tafseer"},
            {"المختصر في التفسير", "https://backup.qurango.net/radio/mukhtasartafsir"},
            {"قصص الصحابة", "https://backup.qurango.net/radio/sahabah"},
            {"المختصر في السيرة النبوية", "https://backup.qurango.net/radio/almukhtasar_fi_alsiyra"},
            {"أذكار الصباح", "https://backup.qurango.net/radio/athkar_sabah"},
            {"أذكار المساء", "https://backup.qurango.net/radio/athkar_masa"},
            {"أخبار · بي بي سي عربي", "https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"},
            {"أخبار · مونت كارلو الدولية", "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3"},
    };
    private int station;
    private TextView name;

    private void tune(int to) {
        station = (to + STATIONS.length) % STATIONS.length;
        getSharedPreferences("radio", MODE_PRIVATE).edit().putInt("station", station).apply();
        name.setText("◀   " + STATIONS[station][0] + "   ▶");
        play();
    }

    private MediaPlayer player;
    private TextView state;
    private View dot;
    private boolean paused;
    /** Stations in a row that did not answer. */
    private int fails;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B2A20, 0xFF061811, 0xFF020806}));

        TextView star = text("✦", 40, GOLD);
        root.addView(star);
        TextView title = text(getString(R.string.app_name), 46, 0xFFF4EEDF);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        LinearLayout live = new LinearLayout(this);
        live.setGravity(Gravity.CENTER);
        live.setPadding(0, dp(18), 0, dp(6));
        dot = new View(this);
        GradientDrawable red = new GradientDrawable();
        red.setShape(GradientDrawable.OVAL);
        red.setColor(0xFFE0534A);
        dot.setBackground(red);
        live.addView(dot, new LinearLayout.LayoutParams(dp(12), dp(12)));
        TextView liveText = text("  بث مباشر", 20, 0xFFE0534A);
        live.addView(liveText);
        root.addView(live);

        station = Math.min(STATIONS.length - 1, Math.max(0, getSharedPreferences("radio", MODE_PRIVATE).getInt("station", 0)));
        name = text("◀   " + STATIONS[station][0] + "   ▶", 30, 0xFFF4EEDF);
        name.setPadding(0, dp(14), 0, 0);
        root.addView(name);
        state = text("جاري الاتصال…", 22, GOLD);
        state.setPadding(0, dp(18), 0, dp(40));
        root.addView(state);
        root.addView(text("يمين ويسار: تغيير الإذاعة   ·   OK: إيقاف مؤقت وتشغيل   ·   رجوع: إغلاق", 16, 0x99F4EEDF));
        setContentView(root);

        ObjectAnimator pulse = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.2f);
        pulse.setDuration(900);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        play();
    }

    @Override
    protected void onStop() {
        super.onStop();
        release();
        finish();   // leaving the screen ends the radio; the card starts it again
    }

    private void play() {
        release();
        paused = false;
        state.setText("جاري الاتصال…");
        final int mine = ++attempt;
        final String url = STATIONS[station][1];
        new Thread(() -> {
            String real = resolve(url);
            runOnUiThread(() -> {
                if (mine == attempt && !isFinishing() && !paused) start(real);
            });
        }, "radio-resolve").start();
    }

    private int attempt;

    /**
     * A station's address may send on to another one (radiojar: https to a plain http server), and the TV's player
     * does not follow that. Followed here, at most four hops, and kept on https, which those servers also answer.
     */
    private static String resolve(String url) {
        try {
            for (int hop = 0; hop < 4; hop++) {
                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                h.setInstanceFollowRedirects(false);
                h.setConnectTimeout(8000);
                h.setReadTimeout(8000);
                h.setRequestProperty("Icy-MetaData", "0");
                int code = h.getResponseCode();
                String to = h.getHeaderField("Location");
                h.disconnect();
                if (code < 300 || code >= 400 || to == null) break;
                url = new java.net.URL(new java.net.URL(url), to).toString();
                if (url.startsWith("http:")) url = "https:" + url.substring(5);
            }
        } catch (Exception ignored) {
        }
        return url;
    }

    private void start(String url) {
        release();
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        try {
            player.setDataSource(url);
            player.setOnPreparedListener(mp -> {
                fails = 0;
                mp.start();
                state.setText("يعمل الآن 🎧");
            });
            // a live stream that ends by itself (the source dropped it): connect again after a moment
            player.setOnCompletionListener(mp -> {
                state.setText("انقطع البث · يعيد الاتصال…");
                state.postDelayed(() -> {
                    if (!isFinishing() && !paused) play();
                }, 2000);
            });
            player.setOnErrorListener((mp, what, extra) -> {
                release();
                // a station that does not answer: on to the next one by itself, until every one was tried once
                if (++fails < STATIONS.length && !isFinishing()) {
                    state.setText("«" + STATIONS[station][0] + "» لا تعمل الآن · الانتقال إلى الإذاعة التالية…");
                    state.postDelayed(() -> {
                        if (!isFinishing() && player == null && !paused) {
                            station = (station + 1) % STATIONS.length;
                            name.setText("◀   " + STATIONS[station][0] + "   ▶");
                            play();
                        }
                    }, 2500);
                } else {
                    fails = 0;
                    state.setText("تعذّر الاتصال بالإذاعات · تحقق من الإنترنت ثم اضغط OK");
                }
                return true;
            });
            player.prepareAsync();
            final MediaPlayer slow = player;
            state.postDelayed(() -> {
                if (player == slow && !isFinishing() && !slow.isPlaying() && !paused) state.setText("جاري الاتصال… قد يستغرق نصف دقيقة");
            }, 8_000);
            // a station that neither answers nor fails: a minute (a slow stream needs half of it to fill the player), then it counts as not working
            final MediaPlayer mine = player;
            state.postDelayed(() -> {
                if (player == mine && !isFinishing() && !mine.isPlaying() && !paused) {
                    release();
                    if (++fails < STATIONS.length) {
                        state.setText("«" + STATIONS[station][0] + "» لا تعمل الآن · الانتقال إلى الإذاعة التالية…");
                        station = (station + 1) % STATIONS.length;
                        name.setText("◀   " + STATIONS[station][0] + "   ▶");
                        state.postDelayed(() -> {
                            if (!isFinishing() && player == null && !paused) play();
                        }, 2000);
                    } else {
                        fails = 0;
                        state.setText("تعذّر الاتصال بالإذاعات · تحقق من الإنترنت ثم اضغط OK");
                    }
                }
            }, 60_000);
        } catch (Exception e) {
            state.setText("تعذّر الاتصال بالإذاعة · اضغط OK للمحاولة مرة ثانية");
        }
    }

    private void release() {
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
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (player == null) {
                play();
            } else if (paused) {
                player.start();
                paused = false;
                state.setText("يعمل الآن 🎧");
            } else if (player.isPlaying()) {
                player.pause();
                paused = true;
                state.setText("متوقف مؤقتاً · OK للتشغيل");
            }
            return true;
        }
        // the layout is right-to-left for the family: right goes on to the next station
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_CHANNEL_UP) {
            tune(station + 1);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_CHANNEL_DOWN) {
            tune(station - 1);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
