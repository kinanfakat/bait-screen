package app.noortv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * بدون كلام (charades): two teams take turns. The TV first sets the game up (round time, rounds per team, and
 * acting, drawing or both). The one playing presses «ابدأ» in the 🎭 tab of the phone page; only that phone sees the
 * word (it holds the round's token). In an acting round they act it out; in a drawing round they draw it on the phone
 * and it appears on the TV. «عرفوها» is a point and the next word, «تخطّي» the next word without one (three a round).
 * Words come from assets/charades.txt, the ones not played yet first; actions and situations are not drawn.
 */
final class CharadesGame {
    static final String SETUP = "setup", WAIT = "wait", PLAY = "play", END = "end";
    static final String[] TEAMS = {"الفريق الأخضر", "الفريق البرتقالي"};
    static final int ACT = 0, DRAW = 1, MIXED = 2;
    static final int SKIPS = 3;

    private static List<String[]> bank;
    private static SharedPreferences prefs;
    private static final Random random = new Random();

    private static String phase = END;
    private static long roundMs = 60_000;
    private static int roundsPerTeam = 3, mode = MIXED;
    private static final int[] scores = new int[2];
    private static int turn, roundsPlayed, roundGot, skipsLeft, clearGen;
    private static boolean drawRound;
    private static long roundAt;
    private static String token = "", category = "", word = "";
    private static final List<String> lastRound = new ArrayList<>();   // "✓ word" / "✗ word"
    private static final List<String> thisRound = new ArrayList<>();
    private static final List<String> strokes = new ArrayList<>();     // drawing from the phone, for the TV to take

    private CharadesGame() {
    }

    static synchronized void open(Context c) {
        prefs = c.getApplicationContext().getSharedPreferences("charades", Context.MODE_PRIVATE);
        if (bank == null) {
            bank = new ArrayList<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open("charades.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    String[] f = line.trim().split("\\|");
                    if (f.length == 2 && !line.startsWith("#")) bank.add(new String[]{f[0].trim(), f[1].trim()});
                }
            } catch (Exception ignored) {
            }
        }
        roundMs = prefs.getLong("roundMs", 60_000);
        roundsPerTeam = prefs.getInt("rounds", 3);
        mode = prefs.getInt("mode", MIXED);
        scores[0] = scores[1] = 0;
        turn = roundsPlayed = roundGot = 0;
        lastRound.clear();
        thisRound.clear();
        strokes.clear();
        token = "";
        phase = SETUP;
    }

    /** The TV's settings screen is done: the first round can start. */
    static synchronized void configure(long ms, int rounds, int m) {
        roundMs = ms;
        roundsPerTeam = rounds;
        mode = m;
        prefs.edit().putLong("roundMs", ms).putInt("rounds", rounds).putInt("mode", m).apply();
        phase = WAIT;
    }

    /** The game screen running this game: an older screen left in the background stops instead of playing along. */
    private static Object owner;
    static synchronized void own(Object o) { owner = o; }
    static synchronized boolean owns(Object o) { return owner == o; }

    static synchronized void close() {
        phase = END + "_closed";
        token = "";
    }

    /** The type of the coming round: in a mixed game two acting rounds, two drawing, and so on. */
    static synchronized boolean nextIsDraw() {
        return mode == DRAW || mode == MIXED && (roundsPlayed / 2) % 2 == 1;
    }

    /** The phone of the one playing starts the round; it gets the token that shows it the words. */
    static synchronized String start() {
        if (!WAIT.equals(phase) || bank == null || bank.isEmpty()) return null;
        token = Long.toHexString(random.nextLong());
        drawRound = nextIsDraw();
        phase = PLAY;
        roundAt = System.currentTimeMillis();
        roundGot = 0;
        skipsLeft = SKIPS;
        thisRound.clear();
        nextWord();
        return token;
    }

    private static void nextWord() {
        Set<String> seen = new HashSet<>(prefs.getStringSet("seen", new HashSet<>()));
        List<String[]> fresh = new ArrayList<>(), all = new ArrayList<>();
        for (String[] b : bank) {
            if (drawRound && (b[0].equals("حركة") || b[0].equals("موقف"))) continue;
            all.add(b);
            if (!seen.contains(b[1])) fresh.add(b);
        }
        if (fresh.isEmpty()) {
            for (String[] b : all) seen.remove(b[1]);
            fresh.addAll(all);
        }
        String[] w = fresh.get(random.nextInt(fresh.size()));
        seen.add(w[1]);
        prefs.edit().putStringSet("seen", seen).apply();
        category = w[0];
        word = w[1];
        strokes.clear();
        clearGen++;   // a new word: the TV clears the drawing
    }

    static synchronized boolean got(String t) {
        if (!PLAY.equals(phase) || !token.equals(t)) return false;
        scores[turn]++;
        roundGot++;
        thisRound.add("✓ " + word);
        nextWord();
        return true;
    }

    static synchronized boolean skip(String t) {
        if (!PLAY.equals(phase) || !token.equals(t) || skipsLeft <= 0) return false;
        skipsLeft--;
        thisRound.add("✗ " + word);
        nextWord();
        return true;
    }

    /** Strokes from the drawing phone, in DrawLayer's format. */
    static synchronized boolean draw(String t, String segs) {
        if (!PLAY.equals(phase) || !drawRound || !token.equals(t)) return false;
        if (strokes.size() < 400) strokes.add(segs);
        return true;
    }

    static synchronized boolean clearDrawing(String t) {
        if (!PLAY.equals(phase) || !drawRound || !token.equals(t)) return false;
        strokes.clear();
        clearGen++;
        return true;
    }

    /** The strokes that came since the last call. */
    static synchronized List<String> takeStrokes() {
        List<String> l = new ArrayList<>(strokes);
        strokes.clear();
        return l;
    }

    /** Called by the TV a few times a second: ends the round when the time is up. */
    static synchronized boolean tick() {
        if (!PLAY.equals(phase) || System.currentTimeMillis() - roundAt < roundMs) return false;
        thisRound.add("⏰ " + word);
        lastRound.clear();
        lastRound.addAll(thisRound);
        roundsPlayed++;
        token = "";
        turn = 1 - turn;
        phase = roundsPlayed >= 2 * roundsPerTeam ? END : WAIT;
        return true;
    }

    static synchronized String phase() { return phase.startsWith(END) ? END : phase; }
    static synchronized int turn() { return turn; }
    static synchronized int[] scores() { return scores.clone(); }
    static synchronized int round() { return roundsPlayed + 1; }
    static synchronized int rounds() { return 2 * roundsPerTeam; }
    static synchronized int roundGot() { return roundGot; }
    static synchronized boolean drawRound() { return drawRound; }
    static synchronized int clearGen() { return clearGen; }
    static synchronized String category() { return category; }
    static synchronized long left() { return Math.max(0, roundMs - (System.currentTimeMillis() - roundAt)); }
    static synchronized long roundMs() { return roundMs; }
    static synchronized int roundsPerTeam() { return roundsPerTeam; }
    static synchronized int mode() { return mode; }
    static synchronized List<String> lastRound() { return new ArrayList<>(lastRound); }

    /** What a phone sees; the word only for the phone playing this round. */
    static synchronized String state(String t) {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", phase());
            o.put("team", TEAMS[turn]);
            o.put("round", Math.min(roundsPlayed + 1, 2 * roundsPerTeam));
            o.put("rounds", 2 * roundsPerTeam);
            o.put("scores", new JSONArray(new int[]{scores[0], scores[1]}));
            o.put("teams", new JSONArray(TEAMS));
            o.put("type", PLAY.equals(phase) ? (drawRound ? "draw" : "act") : (nextIsDraw() ? "draw" : "act"));
            if (PLAY.equals(phase)) {
                o.put("left", left());
                o.put("category", category);
                o.put("got", roundGot);
                o.put("skipsLeft", skipsLeft);
                boolean mine = t != null && t.equals(token);
                o.put("mine", mine);
                if (mine) o.put("word", word);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"phase\":\"end\"}";
        }
    }
}
