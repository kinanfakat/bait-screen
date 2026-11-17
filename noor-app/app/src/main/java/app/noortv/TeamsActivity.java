package app.noortv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
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
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The family games with no phones: two teams in front of the TV and one host with the remote.
 *
 * Race games (quiz, tf, who, ayah, proverb): the TV shows a question, a statement, clues, or the start of a verse or
 * proverb; the teams answer aloud; the host gives the point: Right → the green team (on the right), Left → the
 * orange team (on the left), Down → nobody. OK shows the answer (in «مين أنا؟» the next clue: the fewer clues, the
 * more points). Up takes the last point back. After the last one, OK starts again.
 *
 * Turn games (headsup, taboo): each team in turn has a minute. In «بدون كلام» the one guessing turns their back to
 * the TV and the team acts or explains; in «ممنوع تقول» one explains without the forbidden words. Right = got it,
 * Left = skip, Down (taboo) = said a forbidden word, minus one.
 *
 * Letters («اسم حيوان نبات جماد بلاد»): OK spins a letter, a minute to write, then Right/Left give each team a point
 * for every right word, Up takes one back, OK the next letter.
 */
public class TeamsActivity extends Activity {
    static final String EXTRA = "game";
    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF, GREEN = 0xFF2E9E6A, ORANGE = 0xFFE58A3A, RED = 0xFFE05252,
            AMBER = 0xFFE5A23A;
    private static final String[] TEAMS = {"الفريق الأخضر", "الفريق البرتقالي", "الفريق الأزرق", "الفريق البنفسجي"};
    private static final String[] NAMES = {"الأخضر", "البرتقالي", "الأزرق", "البنفسجي"};
    private static final int[] COLORS = {GREEN, ORANGE, 0xFF4A90E2, 0xFF9B6BE0};
    private static final String LETTERS = "ابتجحخدرزسشصطعفقكلمنهوي";
    private static final String[] LETTER_CATS = {"اسم", "حيوان", "نبات", "جماد", "بلاد"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private String game;
    private TextView title, info, foot, verdict, turnLabel;
    private final TextView[] boxes = new TextView[4];
    private LinearLayout center, actions;
    private ToneGenerator tone;

    // the game
    private final int[] scores = new int[4];
    /** How many teams play (2 to 4), asked before the first game; sel: the team the letters game gives points to. */
    private int n = 2, sel;
    private boolean setup = true;
    private final List<int[]> awards = new ArrayList<>();   // {team, points} for undo
    private List<String[]> items = new ArrayList<>();
    private int index = -1, total, clues;
    private boolean shown, awarded, over;
    /** The team that answered first (its arrow pressed before the answer was shown), -1 when none: the answer comes up
     *  so everyone can check it, and only then does the host give the point. */
    private int claim = -1;
    private String awardText = "";
    // turn games and letters
    private static final int WAIT = 0, PLAY = 1, SCORE = 2;
    private int turnState = WAIT, turn, roundsPlayed, rounds, roundGot;
    private long roundAt, roundMs = 60_000, lastTick = -1;
    private String letter = "";
    private final List<String> roundLog = new ArrayList<>();
    // «أكمل الآية» comes from the internet
    private final List<String[]> ayahQueue = Collections.synchronizedList(new ArrayList<>());
    private boolean fetching;
    private volatile boolean ayahFailed;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        game = getIntent().getStringExtra(EXTRA);
        if (game == null) game = "quiz";
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (Exception ignored) {
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(46), dp(16), dp(46), dp(12));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF1B2A3A, 0xFF121B2E, 0xFF090B12}));
        title = text(22, GOLD, true, gameTitle());
        root.addView(title);
        LinearLayout teams = new LinearLayout(this);
        teams.setGravity(Gravity.CENTER);
        for (int i = 3; i >= 0; i--) {
            boxes[i] = teamBox(COLORS[i]);
            teams.addView(boxes[i], teamParams());
        }
        root.addView(teams);
        // under the teams: whose turn it is in the right corner and the answer's verdict in the left one, so the
        // middle of the screen is the question and, right under it, what there is to learn
        LinearLayout bar = new LinearLayout(this);
        bar.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), dp(4), dp(12), 0);
        verdict = text(24, TEXT, true, "");
        verdict.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        turnLabel = text(22, TEXT, true, "");
        turnLabel.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        bar.addView(verdict, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(turnLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));
        center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER);
        root.addView(center, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        // the row under the content: the two choices after an answer, or what was decided; always on screen
        actions = new LinearLayout(this);
        actions.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        actions.setGravity(Gravity.CENTER);
        root.addView(actions, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        info = text(20, TEXT, false, "");
        root.addView(info);
        foot = text(16, 0x99F4EEDF, false, "");
        foot.setPadding(0, dp(6), 0, 0);
        root.addView(foot);
        setContentView(root);
        n = Math.max(2, Math.min(4, getSharedPreferences("teams", MODE_PRIVATE).getInt("n", 2)));
        draw();
        h.post(tick);
    }

    private boolean race() {
        return game.equals("quiz") || game.equals("tf") || game.equals("who") || game.equals("ayah") || game.equals("proverb");
    }

    private boolean turns() {
        return game.equals("headsup") || game.equals("taboo");
    }

    private String gameTitle() {
        switch (game) {
            case "quiz": return "مسابقة العائلة";
            case "tf": return "صح أم خطأ";
            case "who": return "من أنا؟";
            case "ayah": return "أكمل الآية";
            case "proverb": return "أكمل المثل";
            case "headsup": return "بدون كلام";
            case "taboo": return "الكلمات الممنوعة";
            default: return "لعبة الحروف";
        }
    }

    // ------------------------------------------------------------------ a new game

    private void newGame() {
        java.util.Arrays.fill(scores, 0);
        sel = 0;
        awards.clear();
        over = false;
        index = -1;
        turn = roundsPlayed = 0;
        turnState = WAIT;
        roundLog.clear();
        switch (game) {
            case "quiz":
                total = fit(12);
                boolean kids = getIntent().getBooleanExtra("kids", false);
                items = Deck.draw(this, kids ? "quiz_kids.txt" : "quiz.txt", 5, total,
                        kids ? null : Deck.subject(getIntent().getIntExtra("subject", -1)));
                break;
            case "tf":
                total = fit(15);
                items = Deck.draw(this, "tf.txt", 2, total);
                break;
            case "who":
                total = fit(8);
                items = Deck.draw(this, "whoami.txt", 4, total);
                break;
            case "proverb":
                total = fit(12);
                items = Deck.draw(this, "proverbs.txt", 2, total);
                break;
            case "ayah":
                total = fit(10);
                items = new ArrayList<>();
                fetchAyahs();
                break;
            case "headsup":
            case "taboo":
                rounds = 3 * n;   // three each
                break;
            default:
                rounds = 5;   // letters
        }
        if (race()) next();
        draw();
    }

    /** The count rounded up so that every team gets as many questions as the others. */
    private int fit(int base) {
        return (base + n - 1) / n * n;
    }

    // ------------------------------------------------------------------ question games, a team at a time

    private String[] item() {
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    private void next() {
        index++;
        // each question is one team's: the next question, the next team
        if (index > 0) turn = (turn + 1) % n;
        clues = 1;
        shown = awarded = false;
        claim = -1;
        awardText = "";
        if (index >= total) over = true;
        if (game.equals("ayah")) {
            if (ayahQueue.size() > 0 && items.size() <= index) items.add(ayahQueue.remove(0));
            fetchAyahs();
        }
        if (game.equals("quiz") && item() != null && item().length >= 5 && item().length <= 6) {
            // the four options in a new order, the right one remembered as the sixth field, the note as the seventh
            String[] q = item();
            List<String> opts = new ArrayList<>();
            for (int i = 1; i <= 4; i++) opts.add(q[i]);
            Collections.shuffle(opts, random);
            items.set(index, new String[]{q[0], opts.get(0), opts.get(1), opts.get(2), opts.get(3),
                    String.valueOf(opts.indexOf(q[1])), q.length > 5 ? q[5] : ""});
        }
        draw();
    }

    /** Points for the team that answers now: in «مين أنا؟» 7 with one clue, down to 1 with all seven. */
    private int points() {
        if (game.equals("who")) return Math.max(1, 8 - clues);
        return 1;
    }

    /** The answer is up: team is the one whose turn it is when it answered right, -1 when it answered wrong. */
    private void award(int team) {
        if (over || awarded || item() == null) return;
        int p = points();
        if (team >= 0) {
            scores[team] += p;
            awards.add(new int[]{team, p});
            awardText = (p == 1 ? "نقطة" : p + " نقاط") + " ل" + TEAMS[team].substring(1);   // «نقطة للفريق الأخضر»
            beep(ToneGenerator.TONE_PROP_ACK, 250);
        } else {
            awardText = "بدون نقطة";
            beep(ToneGenerator.TONE_PROP_NACK, 250);
        }
        awarded = shown = true;
        draw();
    }

    private void undo() {
        if (awards.isEmpty()) return;
        int[] a = awards.remove(awards.size() - 1);
        scores[a[0]] -= a[1];
        if (awarded && race()) {
            awarded = false;
            awardText = "";
        }
        beep(ToneGenerator.TONE_PROP_NACK, 150);
        draw();
    }

    private void raceDraw() {
        center.removeAllViews();
        if (over) {
            endScreen();
            return;
        }
        String[] it = item();
        info.setText((index + 1) + " من " + total + (game.equals("who") ? "   ·   الإجابة الصحيحة الآن = " + points() + " نقاط" : ""));
        if (awarded) {
            foot.setText("\u200FOK: السؤال التالي");
        } else if (shown) {
            foot.setText("هل أجاب الفريق إجابة صحيحة؟");
        } else {
            foot.setText(game.equals("who") ? "\u200FOK: تلميح جديد   ·   ▶ أظهر الجواب" : "يجيب الفريق بصوت مرتفع   ·   OK: أظهر الجواب");
        }
        if (it == null) {
            center.addView(text(28, TEXT, false, !game.equals("ayah") ? "لا يوجد محتوى"
                    : ayahFailed ? "تعذّر جلب الآية · تحقق من الاتصال ثم اضغط OK" : "… جارٍ جلب الآية"));
            return;
        }
        switch (game) {
            case "quiz": {
                center.addView(lines(text(32, TEXT, true, it[0]), 3), wrap(dp(4)));
                int right = Integer.parseInt(it[5]);
                for (int row = 0; row < 2; row++) {
                    LinearLayout r = new LinearLayout(this);
                    r.setGravity(Gravity.CENTER);
                    for (int col = 1; col >= 0; col--) {
                        int i = row * 2 + col;
                        boolean ok = shown && i == right;
                        TextView o = text(26, ok ? 0xFF0B1A12 : TEXT, true, "أبجد".charAt(i) + ")  " + it[i + 1]);
                        o.setBackground(pill(ok ? GREEN : shown ? 0x11FFFFFF : 0x22FFFFFF, ok ? GREEN : 0x44FFFFFF));
                        o.setPadding(dp(16), dp(12), dp(16), dp(12));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        lp.setMargins(dp(10), dp(6), dp(10), dp(6));
                        r.addView(o, lp);
                    }
                    center.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                if (shown) note(it.length > 6 ? it[6] : "");
                break;
            }
            case "tf": {
                center.addView(lines(text(34, TEXT, true, it[0]), 3), wrap(dp(4)));
                if (shown) {
                    boolean t = "1".equals(it[1]);
                    verdict.setText(t ? "الجواب: صح" : "الجواب: خطأ");
                    verdict.setTextColor(t ? GREEN : RED);
                    note(it.length > 2 ? it[2] : "");
                } else {
                    center.addView(text(24, 0x99F4EEDF, false, "صح أم خطأ؟"), wrap(dp(16)));
                }
                break;
            }
            case "who": {
                center.addView(text(28, GOLD, true, "الفئة: " + it[0]), wrap(0));
                String[] c = it[3].split(";");
                if (shown) {
                    // the answer, then everything about it: all seven clues are what there is to learn
                    center.addView(text(38, GOLD, true, "الجواب: " + it[1]), wrap(dp(8)));
                    note(String.join("  ·  ", c));
                    break;
                }
                for (int i = 0; i < Math.min(clues, c.length); i++) {
                    TextView t = text(i == clues - 1 ? 28 : 21, i == clues - 1 ? TEXT : 0xBBF4EEDF, i == clues - 1, (i + 1) + ".  " + c[i]);
                    center.addView(t, wrap(dp(6)));
                }
                break;
            }
            case "ayah":
            case "proverb": {
                String head = game.equals("ayah") ? "﴿ " + it[0] + " …" : "«" + it[0] + " …";
                TextView q = text(36, TEXT, true, head);
                center.addView(q, wrap(0));
                if (shown) {
                    center.addView(text(34, GOLD, true, game.equals("ayah") ? "… " + it[1] + " ﴾" : "… " + it[1] + "»"), wrap(dp(16)));
                    if (game.equals("ayah")) {
                        if (it.length > 2) center.addView(text(20, 0xBBF4EEDF, false, it[2]), wrap(dp(6)));
                        if (it.length > 3) note("التفسير الميسّر: " + it[3]);
                    } else if (it.length > 2) {
                        note(it[2]);
                    }
                } else {
                    center.addView(text(22, 0x99F4EEDF, false, game.equals("ayah") ? "من يكمل الآية؟" : "من يكمل المثل؟"), wrap(dp(16)));
                }
                break;
            }
        }
        if (shown && !awarded) {
            // one press: the right arrow gives the team its point, the left arrow gives none
            actions.addView(choice("◀   بدون نقطة", RED));
            actions.addView(choice(points() == 1 ? "لهم نقطة   ▶" : "لهم " + points() + " نقاط   ▶", GREEN));
        } else if (!awardText.isEmpty()) {
            actions.addView(text(26, awardText.contains("للفريق") ? GREEN : AMBER, true, awardText));
        }
    }

    private TextView choice(String label, int color) {
        TextView t = text(24, 0xFFFFFFFF, true, label);
        t.setBackground(pill((color & 0x00FFFFFF) | 0x66000000, color));
        t.setPadding(dp(34), dp(4), dp(34), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(26), 0, dp(26), 0);
        t.setLayoutParams(lp);
        return t;
    }

    /** At most this many lines, the rest cut with dots, so nothing pushes the rest off the screen. */
    private TextView lines(TextView t, int max) {
        t.setMaxLines(max);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    /** The learning note under an answer: a soft box, «💡 تعرف؟». */
    private void note(String s) {
        if (s == null || s.trim().isEmpty()) return;
        TextView n = lines(text(20, 0xFFF1E6C8, false, s.trim()), 2);
        n.setLineSpacing(0, 1.15f);
        n.setBackground(pill(0x22E3BE6E, 0x55E3BE6E));
        n.setPadding(dp(22), dp(6), dp(22), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(60), dp(10), dp(60), 0);
        center.addView(n, lp);
    }

    private boolean raceKey(int code) {
        if (over) {
            if (ok(code)) newGame();
            return ok(code);
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (shown) {
                    award(turn);
                } else if (game.equals("who")) {
                    shown = true;   // the team is ready to answer: the answer comes up
                    draw();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (shown) award(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: return true;
            case KeyEvent.KEYCODE_DPAD_UP: undo(); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (item() == null && game.equals("ayah")) {
                    ayahFailed = false;
                    fetchAyahs();   // the verse did not come: try again
                    draw();
                    return true;
                }
                if (awarded) {
                    next();
                } else if (!shown && game.equals("who") && item() != null && clues < item()[3].split(";").length) {
                    clues++;
                    beep(ToneGenerator.TONE_PROP_BEEP, 120);
                    draw();
                } else if (!shown) {
                    shown = true;
                    draw();
                } else {
                    // the answer is up: right or wrong has to be chosen, OK alone does nothing
                    beep(ToneGenerator.TONE_PROP_BEEP, 80);
                }
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ «أكمل الآية»

    /** Keeps two ayahs ready: half of them from Juz' Amma, the rest from the whole mushaf; long enough to split. */
    private void fetchAyahs() {
        if (fetching || ayahQueue.size() >= 2) return;
        fetching = true;
        new Thread(() -> {
            try {
                while (ayahQueue.size() < 3) {
                    int n = random.nextBoolean() ? 5673 + random.nextInt(564) : 1 + random.nextInt(6236);
                    JSONObject d = new JSONObject(get("https://api.alquran.cloud/v1/ayah/" + n + "/quran-simple")).getJSONObject("data");
                    String text = d.getString("text").replace("﻿", "").trim();
                    if (d.getInt("numberInSurah") == 1 && text.startsWith("بِسْمِ ")) continue;
                    String[] w = text.split("\\s+");
                    if (w.length < 6) continue;
                    int cut = Math.max(3, w.length * 45 / 100);
                    String first = String.join(" ", java.util.Arrays.copyOfRange(w, 0, cut));
                    String rest = String.join(" ", java.util.Arrays.copyOfRange(w, cut, w.length));
                    String where = "سورة " + QuranIndex.NAMES[d.getJSONObject("surah").getInt("number") - 1] + " · " + d.getInt("numberInSurah");
                    // and what it means, from at-Tafsir al-Muyassar
                    String tafsir = "";
                    try {
                        tafsir = new JSONObject(get("https://api.alquran.cloud/v1/ayah/" + n + "/ar.muyassar")).getJSONObject("data").getString("text");
                    } catch (Exception ignored) {
                    }
                    ayahQueue.add(new String[]{first, rest, where, tafsir});
                }
            } catch (Exception ignored) {
            }
            fetching = false;
            if (ayahQueue.isEmpty()) ayahFailed = true;
            h.post(() -> {
                if (game.equals("ayah") && item() == null) draw();
                if (game.equals("ayah") && item() == null && !over && !ayahQueue.isEmpty()) {
                    while (items.size() <= index) items.add(ayahQueue.remove(0));
                    draw();
                }
            });
        }, "ayah").start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // ------------------------------------------------------------------ turn games

    private void startRound() {
        turnState = PLAY;
        roundAt = System.currentTimeMillis();
        roundGot = 0;
        roundLog.clear();
        items = game.equals("taboo") ? Deck.draw(this, "taboo.txt", 3, 40) : Deck.draw(this, "charades.txt", 2, 40);
        index = 0;
        lastTick = -1;
        beep(ToneGenerator.TONE_PROP_BEEP2, 300);
        draw();
    }

    private void endRound() {
        String[] it = item();
        if (it != null) roundLog.add("– " + it[1]);
        roundsPlayed++;
        turn = (turn + 1) % n;
        turnState = WAIT;
        if (roundsPlayed >= rounds) over = true;
        beep(ToneGenerator.TONE_SUP_ERROR, 700);
        draw();
    }

    private boolean turnKey(int code) {
        if (over) {
            if (ok(code)) newGame();
            return ok(code);
        }
        if (turnState == WAIT) {
            if (ok(code)) startRound();
            return ok(code);
        }
        String[] it = item();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                scores[turn]++;
                roundGot++;
                if (it != null) roundLog.add("✓ " + it[1]);
                beep(ToneGenerator.TONE_PROP_ACK, 200);
                index++;
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (it != null) roundLog.add("✗ " + it[1]);
                beep(ToneGenerator.TONE_PROP_NACK, 150);
                index++;
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!game.equals("taboo")) return true;
                scores[turn]--;
                if (it != null) roundLog.add("✗ " + it[1]);
                beep(ToneGenerator.TONE_SUP_ERROR, 300);
                index++;
                break;
            default:
                return ok(code);
        }
        if (index >= items.size()) {
            items.addAll(game.equals("taboo") ? Deck.draw(this, "taboo.txt", 3, 40) : Deck.draw(this, "charades.txt", 2, 40));
        }
        draw();
        return true;
    }

    private void turnDraw() {
        center.removeAllViews();
        if (over) {
            endScreen();
            return;
        }
        if (turnState == WAIT) {
            center.addView(text(40, TEXT, true, "استعدّوا للجولة"), wrap(0));
            center.addView(text(24, TEXT, false, game.equals("taboo")
                    ? "يشرح أحد أعضاء الفريق الكلمة دون أن ينطق أي كلمة ممنوعة\nوتراقبه بقية الفرق"
                    : "يدير المخمِّن ظهره للشاشة\nويمثّل فريقه الكلمة دون كلام"), wrap(dp(16)));
            if (!roundLog.isEmpty()) center.addView(lines(text(19, 0xBBF4EEDF, false, "الجولة السابقة:  " + String.join("   ", roundLog)), 2), wrap(dp(16)));
            info.setText("الجولة " + (roundsPlayed + 1) + " من " + rounds + "   ·   دقيقة لكل جولة");
            foot.setText("\u200FOK: ابدأ الجولة");
            return;
        }
        String[] it = item();
        long left = Math.max(0, roundMs - (System.currentTimeMillis() - roundAt));
        center.addView(text(30, left < 10_000 ? RED : GOLD, true, "بقي " + (left + 999) / 1000 + " ثانية"), wrap(0));
        if (it != null) {
            center.addView(text(20, 0xBBF4EEDF, false, it[0]), wrap(dp(6)));
            center.addView(text(64, TEXT, true, it[1]), wrap(dp(4)));
            if (game.equals("taboo")) {
                TextView f = lines(text(26, RED, true, "ممنوع:   " + it[2].replace(",", "   ·   ")), 2);
                f.setBackground(pill(0x22E05252, 0x66E05252));
                f.setPadding(dp(24), dp(10), dp(24), dp(10));
                center.addView(f, wrap(dp(16)));
            }
        }
        info.setText("الإجابات الصحيحة: " + roundGot);
        foot.setText(game.equals("taboo") ? "▶ إجابة صحيحة   ·   ◀ تخطٍّ   ·   ▼ نطق كلمة ممنوعة (−1)" : "▶ إجابة صحيحة   ·   ◀ تخطٍّ");
    }

    // ------------------------------------------------------------------ letters

    private boolean letterKey(int code) {
        if (over) {
            if (ok(code)) newGame();
            return ok(code);
        }
        if (turnState == WAIT) {
            if (!ok(code)) return false;
            spin(12);
            return true;
        }
        if (turnState == PLAY) {
            if (ok(code) && roundAt != Long.MAX_VALUE) {   // not while the letter still spins
                turnState = SCORE;
                draw();
            }
            return ok(code);
        }
        // SCORE: a point for each right word
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                sel = (sel + n - 1) % n;   // the first team is the box on the right
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                sel = (sel + 1) % n;
                break;
            case KeyEvent.KEYCODE_DPAD_UP:
                scores[sel]++;
                beep(ToneGenerator.TONE_PROP_ACK, 120);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (scores[sel] > 0) scores[sel]--;
                beep(ToneGenerator.TONE_PROP_NACK, 120);
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                roundsPlayed++;
                awards.clear();
                turnState = WAIT;
                if (roundsPlayed >= rounds) over = true;
                break;
            default:
                return false;
        }
        draw();
        return true;
    }

    /** The letter turns like a wheel for a moment, then the minute starts. */
    private void spin(int left) {
        letter = String.valueOf(LETTERS.charAt(random.nextInt(LETTERS.length())));
        turnState = PLAY;
        roundAt = Long.MAX_VALUE;
        draw();
        beep(ToneGenerator.TONE_PROP_BEEP, 40);
        if (left > 0) {
            h.postDelayed(() -> spin(left - 1), 70 + (12 - left) * 25L);
        } else {
            roundAt = System.currentTimeMillis();
            lastTick = -1;
            beep(ToneGenerator.TONE_PROP_BEEP2, 300);
        }
    }

    private void letterDraw() {
        center.removeAllViews();
        if (over) {
            endScreen();
            return;
        }
        if (turnState == WAIT) {
            center.addView(text(30, TEXT, true, "جهّزوا ورقة وقلماً"), wrap(0));
            center.addView(text(22, 0xCCF4EEDF, false, "يظهر حرف، ويكتب كل فريق كلمة تبدأ به في كل فئة:\n" + String.join("  ·  ", LETTER_CATS)), wrap(dp(14)));
            info.setText("الحرف " + (roundsPlayed + 1) + " من " + rounds);
            foot.setText("\u200FOK: اختر حرفاً");
            return;
        }
        TextView l = text(110, GOLD, true, letter);
        center.addView(l, wrap(0));
        center.addView(text(26, TEXT, false, String.join("   ·   ", LETTER_CATS)), wrap(dp(4)));
        if (turnState == PLAY) {
            long left = roundAt == Long.MAX_VALUE ? roundMs : Math.max(0, roundMs - (System.currentTimeMillis() - roundAt));
            info.setText("بقي " + (left + 999) / 1000 + " ثانية");
            info.setTextColor(left < 10_000 ? RED : TEXT);
            foot.setText("\u200FOK: انتهينا قبل الوقت");
        } else {
            info.setTextColor(TEXT);
            info.setText("وقت التصحيح · لكل كلمة صحيحة نقطة");
            foot.setText("◀ ▶ اختر الفريق   ·   ▲ أضف نقطة   ·   ▼ احذف نقطة   ·   OK: الحرف التالي");
        }
    }

    // ------------------------------------------------------------------ common

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean timed = turns() && turnState == PLAY || game.equals("letters") && turnState == PLAY && roundAt != Long.MAX_VALUE;
            if (timed) {
                long left = roundMs - (System.currentTimeMillis() - roundAt);
                long sec = (left + 999) / 1000;
                if (sec <= 10 && sec > 0 && sec != lastTick) {
                    lastTick = sec;
                    beep(ToneGenerator.TONE_CDMA_PIP, 80);
                }
                if (left <= 0) {
                    if (turns()) endRound();
                    else {
                        turnState = SCORE;
                        beep(ToneGenerator.TONE_SUP_ERROR, 700);
                    }
                }
                draw();
            }
            h.postDelayed(this, 250);
        }
    };

    private void draw() {
        actions.removeAllViews();
        verdict.setText("");
        turnLabel.setText("");
        boolean scoring = !race() && !turns() && turnState == SCORE;
        for (int i = 0; i < 4; i++) {
            boxes[i].setVisibility(i < n ? View.VISIBLE : View.GONE);
            boxes[i].setText(NAMES[i] + "   " + scores[i]);
            // bright: the team whose turn it is, or the one the letters game is giving points to
            boolean lit = setup || over || (race() || turns() ? turn == i : !scoring || sel == i);
            boxes[i].setAlpha(lit ? 1f : 0.4f);
        }
        if (setup) {
            center.removeAllViews();
            center.addView(text(44, TEXT, true, "كم فريقاً سيلعب؟"), wrap(0));
            center.addView(text(72, GOLD, true, "◀   " + n + "   ▶"), wrap(dp(6)));
            center.addView(text(22, 0xCCF4EEDF, false, "يجيب كل فريق في دوره، ثم تقرّرون هل يستحق النقطة"), wrap(dp(10)));
            info.setText("");
            foot.setText("◀ ▶ غيّر عدد الفرق   ·   OK: ابدأ اللعب");
            return;
        }
        if (!over && (race() || turns())) {
            turnLabel.setText("دور " + TEAMS[turn]);
            turnLabel.setTextColor(COLORS[turn]);
        }
        if (race()) raceDraw();
        else if (turns()) turnDraw();
        else letterDraw();
    }

    private void endScreen() {
        int best = 0;
        for (int i = 0; i < n; i++) best = Math.max(best, scores[i]);
        List<String> winners = new ArrayList<>();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (scores[i] == best) winners.add(TEAMS[i]);
            all.add(NAMES[i] + "  " + scores[i]);
        }
        center.addView(text(52, GOLD, true, winners.size() > 1 ? "تعادل" : "فاز " + winners.get(0)), wrap(0));
        if (winners.size() > 1) center.addView(text(30, GOLD, false, String.join("  و  ", winners)), wrap(dp(8)));
        center.addView(text(30, TEXT, false, String.join("      ", all)), wrap(dp(14)));
        info.setTextColor(TEXT);
        info.setText("");
        foot.setText("\u200FOK: لعبة جديدة   ·   رجوع: خروج");
        if (!over) return;
        beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) return super.onKeyDown(code, e);
        if (setup) {
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_UP) {
                n = n < 4 ? n + 1 : 2;
            } else if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_DOWN) {
                n = n > 2 ? n - 1 : 4;
            } else if (ok(code)) {
                getSharedPreferences("teams", MODE_PRIVATE).edit().putInt("n", n).apply();
                setup = false;
                newGame();
                return true;
            } else {
                return super.onKeyDown(code, e);
            }
            draw();
            return true;
        }
        boolean used = race() ? raceKey(code) : turns() ? turnKey(code) : letterKey(code);
        return used || super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (tone != null) tone.release();
    }

    private static boolean ok(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
    }

    private void beep(int type, int ms) {
        try {
            if (tone != null) tone.startTone(type, ms);
        } catch (Exception ignored) {
        }
    }

    private TextView teamBox(int color) {
        TextView t = text(20, TEXT, true, "");
        t.setSingleLine(true);
        t.setBackground(pill((color & 0x00FFFFFF) | 0x33000000, color));
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private LinearLayout.LayoutParams teamParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(8), dp(4), dp(8), dp(2));
        return lp;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(fill);
        g.setStroke(dp(2), stroke);
        return g;
    }

    private LinearLayout.LayoutParams wrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = topMargin;
        return lp;
    }

    private TextView text(float sp, int color, boolean bold, String s) {
        TextView t = new TextView(this);
        t.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
