package app.noortv;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * دوري العيلة: each player's points from the phone games (the family quiz, صح ولا غلط, مين أنا؟) added up for the
 * week, Saturday to Friday. Kept on the TV under the week's first day, so last week's champion stays known.
 */
final class League {
    private League() {
    }

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("league", Context.MODE_PRIVATE);
    }

    /** "2026-11-07": the Saturday this week started on (weeksAgo 1 is last week). */
    static String week(int weeksAgo) {
        return LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)).minusWeeks(weeksAgo).toString();
    }

    /** A finished game's points, added to this week's. */
    static synchronized void add(Context c, Map<String, Integer> scores) {
        if (c == null || scores.isEmpty()) return;
        try {
            String key = week(0);
            JSONObject o = new JSONObject(p(c).getString(key, "{}"));
            for (Map.Entry<String, Integer> e : scores.entrySet()) {
                if (e.getValue() <= 0) continue;
                o.put(e.getKey(), o.optInt(e.getKey(), 0) + e.getValue());
            }
            p(c).edit().putString(key, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** The week's players, most points first: {name, points}. */
    static List<String[]> table(Context c, int weeksAgo) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(p(c).getString(week(weeksAgo), "{}"));
            Iterator<String> names = o.keys();
            while (names.hasNext()) {
                String n = names.next();
                out.add(new String[]{n, String.valueOf(o.getInt(n))});
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        return out;
    }
}
