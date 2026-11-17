package app.noortv;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * The phone page's password: a six-digit code beside the link. The link alone opens only a page that asks for the
 * code; the right code gives the phone a session (a part of the address), and everything else answers only under
 * that session. The family's code stays until the owner asks for a new one (the family asked for it to stay); the
 * guests' code changes by itself every 24 hours. Every session made with an old code stops at once.
 * A few wrong codes in a row lock the door for ten minutes.
 */
final class Access {
    private static final long DAY = 24 * 60 * 60_000L;
    private static int fails;
    private static long lockedUntil;

    private static SharedPreferences p(Context c) {
        return c.getApplicationContext().getSharedPreferences("phone", Context.MODE_PRIVATE);
    }

    /** The family's code: made once, then the same until the owner asks for a new one («رمز جديد»). */
    static synchronized String code(Context c) {
        String code = p(c).getString("code", null);
        if (code == null) code = newCode(c);
        return code;
    }

    static synchronized String newCode(Context c) {
        String code = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
        // the guests' code goes with it: a new one is made the next time it is asked for
        p(c).edit().putString("code", code).putLong("codeAt", System.currentTimeMillis()).remove("guest").commit();
        return code;
    }

    /** The guests' code: it opens the games only, never the TV's controls. A new one every day, and with the family's. */
    static synchronized String guestCode(Context c) {
        code(c);
        SharedPreferences p = p(c);
        String g = p.getString("guest", null);
        long at = p.getLong("guestAt", 0);
        if (g == null || g.equals(p.getString("code", "")) || System.currentTimeMillis() - at > DAY) {
            g = String.format(java.util.Locale.US, "%06d", new SecureRandom().nextInt(1_000_000));
            p.edit().putString("guest", g).putLong("guestAt", System.currentTimeMillis()).commit();
        }
        return g;
    }

    static String guestSession(Context c, String key) {
        return hash(key + ":guest:" + guestCode(c));
    }

    /** When the guests' code changes by itself (the family's does not). */
    static synchronized long changesAt(Context c) {
        guestCode(c);
        return p(c).getLong("guestAt", 0) + DAY;
    }

    /** The session the code gives: a part of the address that only someone who entered the code has. */
    static String session(Context c, String key) {
        return hash(key + ":" + code(c));
    }

    private static String hash(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 10; i++) b.append(String.format("%02x", d[i]));
            return b.toString();
        } catch (Exception e) {
            return "x" + Integer.toHexString(text.hashCode());
        }
    }

    /** The session for this code, "no" when it is wrong, "locked" after too many wrong ones. */
    static synchronized String enter(Context c, String key, String typed) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return "locked";
        if (typed.trim().equals(code(c))) {
            fails = 0;
            return session(c, key);
        }
        if (typed.trim().equals(guestCode(c))) {
            fails = 0;
            return guestSession(c, key);
        }
        if (++fails >= 8) {
            fails = 0;
            lockedUntil = now + 10 * 60_000L;
            return "locked";
        }
        return "no";
    }

    /**
     * The bare address (192.168.x.x:8080, what the family types from memory): the guests' code gives the games, and
     * the family's code gives everything, as on the whole link. "no" or "locked" otherwise.
     */
    static synchronized String enterGuest(Context c, String key, String typed) {
        long now = System.currentTimeMillis();
        if (now < lockedUntil) return "locked";
        if (typed.trim().equals(code(c))) {
            fails = 0;
            return session(c, key);
        }
        if (typed.trim().equals(guestCode(c))) {
            fails = 0;
            return guestSession(c, key);
        }
        if (++fails >= 8) {
            fails = 0;
            lockedUntil = now + 10 * 60_000L;
            return "locked";
        }
        return "no";
    }

    private Access() {
    }
}
