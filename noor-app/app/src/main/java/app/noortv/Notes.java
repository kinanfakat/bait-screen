package app.noortv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.PowerManager;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * «ذكّرني»: a note from the phone page (typed, or spoken with the phone keyboard's microphone) that comes up on the
 * TV at its time. Kept in prefs "notes" as lines of "<when in ms>\t<text>"; MessageServer's clock asks for the ones
 * that are due. A note whose time passes with the TV in standby waits and shows when the screen is next on.
 */
final class Notes {
    private static final int MAX = 30;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("notes", Context.MODE_PRIVATE);
    }

    private static List<String[]> all(Context c) {
        List<String[]> out = new ArrayList<>();
        for (String line : p(c).getString("list", "").split("\n")) {
            String[] f = line.split("\t", 2);
            if (f.length == 2 && !f[1].isEmpty()) out.add(f);
        }
        return out;
    }

    private static void save(Context c, List<String[]> notes) {
        StringBuilder b = new StringBuilder();
        for (String[] n : notes) b.append(n[0]).append('\t').append(n[1]).append('\n');
        p(c).edit().putString("list", b.toString()).commit();
    }

    /**
     * when: minutes from now ("30") or a time of day ("21:15", tomorrow if it has passed). Returns the time it is set
     * for as the phone shows it, or null when the request makes no sense.
     */
    static synchronized String add(Context c, String when, String text) {
        text = text.replace('\t', ' ').replace('\n', ' ').trim();
        if (text.isEmpty()) return null;
        if (text.length() > 200) text = text.substring(0, 200);
        long at;
        try {
            when = when.trim();
            if (when.contains(":")) {
                String[] hm = when.split(":");
                Calendar cal = Calendar.getInstance();
                cal.set(Calendar.HOUR_OF_DAY, Integer.parseInt(hm[0]));
                cal.set(Calendar.MINUTE, Integer.parseInt(hm[1]));
                cal.set(Calendar.SECOND, 0);
                cal.set(Calendar.MILLISECOND, 0);
                if (cal.getTimeInMillis() <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1);
                at = cal.getTimeInMillis();
            } else {
                int minutes = Integer.parseInt(when);
                if (minutes < 1 || minutes > 7 * 24 * 60) return null;
                at = System.currentTimeMillis() + minutes * 60_000L;
            }
        } catch (Exception e) {
            return null;
        }
        List<String[]> notes = all(c);
        if (notes.size() >= MAX) return null;
        notes.add(new String[]{String.valueOf(at), text});
        save(c, notes);
        return clock(at);
    }

    static synchronized void clear(Context c) {
        p(c).edit().remove("list").commit();
    }

    /** The notes still waiting, one a line: "9:15 م · النص". */
    static synchronized String list(Context c) {
        StringBuilder b = new StringBuilder();
        for (String[] n : all(c)) {
            try {
                b.append(clock(Long.parseLong(n[0]))).append(" · ").append(n[1]).append('\n');
            } catch (Exception ignored) {
            }
        }
        return b.toString();
    }

    /** From MessageServer's clock: the first note that is due while the TV is on, taken off the list; else null. */
    static synchronized String due(Context c) {
        if (p(c).getString("list", "").isEmpty()) return null;
        if (!c.getSystemService(PowerManager.class).isInteractive()) return null;
        List<String[]> notes = all(c);
        long now = System.currentTimeMillis();
        for (int i = 0; i < notes.size(); i++) {
            long at;
            try {
                at = Long.parseLong(notes.get(i)[0]);
            } catch (Exception e) {
                notes.remove(i);
                save(c, notes);
                return null;
            }
            if (at > now) continue;
            String text = notes.remove(i)[1];
            save(c, notes);
            // shown late (the TV was off at its time): say when it was for
            return now - at > 5 * 60_000L ? text + "\n(كان موعدها " + clock(at) + ")" : text;
        }
        return null;
    }

    private static String clock(long t) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(t);
        int hr = c.get(Calendar.HOUR);
        return String.format(Locale.US, "%d:%02d %s", hr == 0 ? 12 : hr, c.get(Calendar.MINUTE), c.get(Calendar.AM_PM) == Calendar.AM ? "ص" : "م");
    }

    private Notes() {
    }
}
