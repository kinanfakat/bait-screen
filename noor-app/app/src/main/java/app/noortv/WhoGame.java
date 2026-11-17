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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * مين أنا؟ — twenty questions for the family. The players join from the phone page; each round one of them hosts:
 * their phone shows a secret card (a character, an animal, a place or a thing, from assets/whoami.txt) with facts to
 * answer from and hints. The others ask yes/no questions aloud or from their phones; the host answers نعم / لا /
 * أحياناً / ما بعرف and everything shows on the TV. A guess typed on a phone is checked by itself; a wrong one costs a
 * question. Twenty questions, then the card is shown. Right guess: 25 points less one per question and five per hint,
 * at least 5; the host gets 5 when the card is guessed.
 */
final class WhoGame {
    static final String LOBBY = "lobby", ASK = "ask", REVEAL = "reveal", END = "end";
    static final int MAX_QUESTIONS = 20;

    /** {category, name, aliases, clues}: seven clues from the hardest to the easiest. */
    private static List<String[]> bank;
    private static SharedPreferences prefs;
    private static final Random random = new Random();

    private static String phase = END;
    private static final Map<String, Integer> scores = new LinkedHashMap<>();
    private static final List<String> order = new ArrayList<>();   // hosts, in turn
    private static int round, questions, hints, lastPoints;
    private static String[] card;
    private static String host = "", winner = "";
    private static final List<String[]> log = new ArrayList<>();          // {who, text, kind}: kind yes/no/maybe/idk/wrong
    private static final List<String[]> pending = new ArrayList<>();      // typed questions waiting for the host: {who, text}
    private static long version;   // grows with every change, so the TV knows when to play a sound

    private WhoGame() {
    }

    private static Context appContext;

    static synchronized void open(Context c) {
        onTv = true;
        appContext = c.getApplicationContext();
        prefs = c.getApplicationContext().getSharedPreferences("whoami", Context.MODE_PRIVATE);
        if (bank == null) {
            bank = new ArrayList<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open("whoami.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    String[] f = line.trim().split("\\|");
                    if (f.length == 4) bank.add(f);
                }
            } catch (Exception ignored) {
            }
        }
        scores.clear();
        order.clear();
        log.clear();
        pending.clear();
        round = 0;
        winner = host = "";
        card = null;
        phase = LOBBY;
        version++;
    }

    /** The game screen running this game: an older screen left in the background stops instead of playing along. */
    private static Object owner;
    static synchronized void own(Object o) { owner = o; }
    static synchronized boolean owns(Object o) { return owner == o; }

    static synchronized void close() {
        onTv = false;
        phase = END + "_closed";
        scores.clear();
    }

    /** The game is on the TV (its screen is open), whatever its phase. */
    private static boolean onTv;

    static synchronized boolean join(String name) {
        if (!onTv) return false;
        name = name.trim();
        if (name.isEmpty() || name.length() > 16) return false;
        if (!scores.containsKey(name)) {
            scores.put(name, 0);
            order.add(name);
            version++;
        }
        return true;
    }

    /** The first round from the lobby, or the next one after a reveal; false when it can't start. */
    static synchronized boolean next() {
        if (order.size() < 2 || bank == null || bank.isEmpty()) return false;
        if (!LOBBY.equals(phase) && !REVEAL.equals(phase)) return false;
        if (REVEAL.equals(phase) && round >= order.size()) {
            League.add(appContext, scores);   // the game is over: its points go to the family league
            phase = END;
            version++;
            return false;
        }
        host = order.get(round % order.size());
        round++;
        card = pick();
        questions = hints = lastPoints = 0;
        winner = "";
        log.clear();
        pending.clear();
        phase = ASK;
        version++;
        return true;
    }

    /** A card not played yet on this TV; the memory starts over once all were played. */
    private static String[] pick() {
        Set<String> seen = new HashSet<>(prefs.getStringSet("seen", new HashSet<>()));
        List<String[]> fresh = new ArrayList<>();
        for (String[] b : bank) if (!seen.contains(b[1])) fresh.add(b);
        if (fresh.isEmpty()) {
            seen.clear();
            fresh.addAll(bank);
        }
        String[] c = fresh.get(random.nextInt(fresh.size()));
        seen.add(c[1]);
        prefs.edit().putStringSet("seen", seen).apply();
        return c;
    }

    /** A question typed on a guesser's phone, for the host to answer. */
    static synchronized boolean ask(String who, String text) {
        text = text.trim();
        if (!ASK.equals(phase) || !scores.containsKey(who) || who.equals(host) || text.isEmpty()) return false;
        if (text.length() > 80) text = text.substring(0, 80);
        if (pending.size() >= 6) pending.remove(0);
        pending.add(new String[]{who, text});
        version++;
        return true;
    }

    /** The host's answer: yes, no, maybe or idk; it goes with the oldest typed question, if there is one. */
    static synchronized boolean answer(String who, String kind) {
        if (!ASK.equals(phase) || !who.equals(host)) return false;
        if (!kind.equals("yes") && !kind.equals("no") && !kind.equals("maybe") && !kind.equals("idk")) return false;
        String[] q = pending.isEmpty() ? null : pending.remove(0);
        questions++;
        log.add(new String[]{q == null ? "" : q[0], q == null ? "" : q[1], kind});
        if (questions >= MAX_QUESTIONS) reveal("");
        version++;
        return true;
    }

    static synchronized boolean hint(String who) {
        if (!ASK.equals(phase) || !who.equals(host)) return false;
        if (hints >= hintList().length) return false;
        hints++;
        version++;
        return true;
    }

    static synchronized boolean giveUp(String who) {
        if (!ASK.equals(phase) || !who.equals(host)) return false;
        reveal("");
        version++;
        return true;
    }

    /** "right" or "wrong", or "no" when this player can't guess now. */
    static synchronized String guess(String who, String text) {
        if (!ASK.equals(phase) || !scores.containsKey(who) || who.equals(host) || text.trim().isEmpty()) return "no";
        if (matches(text)) {
            reveal(who);
            version++;
            return "right";
        }
        questions++;
        log.add(new String[]{who, text.trim(), "wrong"});
        if (questions >= MAX_QUESTIONS) reveal("");
        version++;
        return "wrong";
    }

    private static void reveal(String by) {
        winner = by;
        lastPoints = 0;
        if (!by.isEmpty()) {
            lastPoints = Math.max(5, 25 - questions - 5 * hints);
            scores.put(by, scores.get(by) + lastPoints);
            if (scores.containsKey(host)) scores.put(host, scores.get(host) + 5);
        }
        pending.clear();
        phase = REVEAL;
    }

    private static boolean matches(String guess) {
        String g = norm(guess);
        if (g.length() < 2) return false;
        List<String> names = new ArrayList<>();
        names.add(card[1]);
        for (String a : card[2].split(",")) if (!a.trim().isEmpty()) names.add(a.trim());
        for (String n : names) {
            String m = norm(n);
            if (m.equals(g)) return true;
            // «ابن سينا» for «ابن سينا رحمه الله», or «صلاح الدين» for the full name: a good part of it
            if (g.length() >= 4 && (m.contains(g) && g.length() * 2 >= m.length() || g.contains(m) && m.length() >= 3)) return true;
        }
        return false;
    }

    /** Arabic written the same way: no diacritics or tatweel, one alef, ه for ة, ي for ى, no «ال», no spaces. */
    static String norm(String s) {
        s = s.replaceAll("[\\u064B-\\u0652\\u0670\\u0640]", "")
                .replaceAll("[أإآٱ]", "ا").replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
                .replaceAll("عليه السلام", "").replaceAll("[^\\p{L}\\p{N} ]", " ").trim();
        StringBuilder b = new StringBuilder();
        for (String w : s.split("\\s+")) {
            if (w.startsWith("ال") && w.length() > 3) w = w.substring(2);
            b.append(w);
        }
        return b.toString().toLowerCase();
    }

    private static String[] hintList() {
        // the host may give the three easier clues (the fifth, sixth and seventh)
        if (card == null) return new String[0];
        String[] c = card[3].split(";");
        return c.length <= 3 ? c : java.util.Arrays.copyOfRange(c, c.length - 3, c.length);
    }

    static synchronized boolean hasPlayer(String name) { return scores.containsKey(name.trim()); }
    static synchronized String phase() { return phase.startsWith(END) ? END : phase; }
    static synchronized long version() { return version; }
    static synchronized String host() { return host; }
    static synchronized int round() { return round; }
    static synchronized int rounds() { return order.size(); }
    static synchronized int questions() { return questions; }
    static synchronized String winner() { return winner; }
    static synchronized int lastPoints() { return lastPoints; }
    static synchronized String category() { return card == null ? "" : card[0]; }
    static synchronized String name() { return card == null ? "" : card[1]; }
    static synchronized String[] facts() { return card == null ? new String[0] : card[3].split(";"); }
    static synchronized List<String> shownHints() {
        List<String> l = new ArrayList<>();
        String[] h = hintList();
        for (int i = 0; i < hints && i < h.length; i++) l.add(h[i]);
        return l;
    }
    static synchronized List<String[]> log() { return new ArrayList<>(log); }
    static synchronized Map<String, Integer> scores() { return new LinkedHashMap<>(scores); }
    static synchronized List<String> players() { return new ArrayList<>(order); }

    /** What a phone sees; the card only for the host while they host, and for everyone once revealed. */
    static synchronized String state(String who) {
        try {
            JSONObject o = new JSONObject();
            o.put("phase", phase());
            o.put("players", new JSONArray(order));
            JSONObject sc = new JSONObject();
            for (Map.Entry<String, Integer> e : scores.entrySet()) sc.put(e.getKey(), e.getValue());
            o.put("scores", sc);
            o.put("joined", who != null && scores.containsKey(who));
            o.put("host", host);
            o.put("isHost", who != null && who.equals(host) && ASK.equals(phase));
            o.put("round", round);
            o.put("rounds", order.size());
            o.put("category", category());
            o.put("questions", questions);
            o.put("max", MAX_QUESTIONS);
            o.put("hints", new JSONArray(shownHints()));
            o.put("hintsLeft", hintList().length - hints);
            JSONArray lg = new JSONArray();
            for (String[] l : log) lg.put(new JSONArray(l));
            o.put("log", lg);
            if (who != null && who.equals(host) && ASK.equals(phase)) {
                o.put("name", card[1]);
                o.put("facts", new JSONArray(facts()));
                o.put("allHints", new JSONArray(hintList()));
                JSONArray pq = new JSONArray();
                for (String[] p : pending) pq.put(new JSONArray(p));
                o.put("pending", pq);
            }
            if (REVEAL.equals(phase)) {
                o.put("name", card[1]);
                o.put("facts", new JSONArray(facts()));
                o.put("winner", winner);
                o.put("points", lastPoints);
            }
            return o.toString();
        } catch (Exception e) {
            return "{\"phase\":\"end\"}";
        }
    }
}
