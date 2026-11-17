package app.noortv;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The games' content files in assets ("field|field|…" lines, "#" for comments), and drawing from them without
 * repeats: what was drawn is remembered on the TV per file, and comes back only once the whole file was used.
 */
final class Deck {
    private static final Map<String, List<String[]>> files = new HashMap<>();
    private static final Random random = new Random();

    private Deck() {
    }

    /** The lines of an asset with exactly this many fields (cached). */
    static synchronized List<String[]> lines(Context c, String asset, int fields) {
        String key = asset + "#" + fields;
        List<String[]> l = files.get(key);
        if (l != null) return l;
        l = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open(asset), StandardCharsets.UTF_8))) {
            String line, section = "";
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#")) {
                    // "# --- السيرة والصحابة": the subject of the lines under it (the format line has a "|")
                    if (!line.contains("|")) section = line.replaceAll("^[#\\s-]+", "").trim();
                    continue;
                }
                if (line.isEmpty()) continue;
                String[] f = line.split("\\|", -1);
                if (f.length < fields) continue;   // more is fine: an optional learning note at the end
                for (int i = 0; i < f.length; i++) f[i] = f[i].trim();
                l.add(f);
                sections.put(f, section);
            }
        } catch (Exception ignored) {
        }
        files.put(key, l);
        return l;
    }

    private static final Map<String[], String> sections = new java.util.IdentityHashMap<>();

    /** The heading a line was under in its file ("" when none). */
    static synchronized String section(String[] line) {
        String s = sections.get(line);
        return s == null ? "" : s;
    }

    /** The quiz's subjects to choose from, each with the words its file headings contain. */
    static final String[][] SUBJECTS = {
            {"دين وقرآن وسيرة", "القرآن", "السيرة", "الفقه", "الدين"},
            {"تاريخ", "تاريخ", "التاريخ"},
            {"بلدان وجغرافيا وسوريا", "البلدان", "جغرافيا", "سوريا"},
            {"علوم وفضاء وطبيعة", "العلوم", "علوم", "فضاء", "اختراعات", "حيوانات"},
            {"لغة عربية وأدب", "لغة", "أدب"},
            {"رياضيات ومنطق", "رياضيات", "حساب"},
            {"عامة", "عامة"},
    };

    /** Lines whose heading belongs to subject i of SUBJECTS; -1 lets every line through. */
    static Filter subject(int i) {
        if (i < 0 || i >= SUBJECTS.length) return null;
        return line -> {
            String s = section(line);
            for (int k = 1; k < SUBJECTS[i].length; k++) if (s.contains(SUBJECTS[i][k])) return true;
            return false;
        };
    }

    /** n lines not drawn yet (the first field identifies a line), the memory starting over when the file runs out. */
    static synchronized List<String[]> draw(Context c, String asset, int fields, int n) {
        return draw(c, asset, fields, n, null);
    }

    interface Filter {
        boolean ok(String[] line);
    }

    static synchronized List<String[]> draw(Context c, String asset, int fields, int n, Filter filter) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("decks", Context.MODE_PRIVATE);
        Set<String> seen = new HashSet<>(p.getStringSet(asset, new HashSet<>()));
        List<String[]> fresh = new ArrayList<>(), old = new ArrayList<>();
        for (String[] l : lines(c, asset, fields)) {
            if (filter != null && !filter.ok(l)) continue;
            (seen.contains(id(l)) ? old : fresh).add(l);
        }
        Collections.shuffle(fresh, random);
        Collections.shuffle(old, random);
        if (fresh.size() < n) {
            for (String[] l : old) seen.remove(id(l));
        }
        List<String[]> out = new ArrayList<>(fresh.subList(0, Math.min(n, fresh.size())));
        for (int i = 0; out.size() < n && i < old.size(); i++) out.add(old.get(i));
        for (String[] l : out) seen.add(id(l));
        p.edit().putStringSet(asset, seen).apply();
        return out;
    }

    /** The field that tells lines apart: the second when the first is a category. */
    private static String id(String[] l) {
        return l.length > 1 ? l[0] + "|" + l[1] : l[0];
    }
}
