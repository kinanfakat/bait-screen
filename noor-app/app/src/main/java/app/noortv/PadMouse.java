package app.noortv;

import android.content.Context;
import android.graphics.Point;
import android.media.AudioManager;
import android.view.WindowManager;

import java.util.Locale;

/**
 * The phone's mouse, keys and touchpad (the phone page's «ماوس وريموت» and «شاشة التلفزيون»). This app keeps where the
 * pointer is and tells the Home app's service to draw it there; a click is then a real touch at that very point
 * through the TV's own debugging bridge (LocalAdb), exact like a finger, in phone apps too. Without the bridge (not yet
 * allowed on the TV) the Home app's service clicks instead, as before.
 */
final class PadMouse {
    private static float x = -1, y = -1;
    private static final Point screen = new Point();

    private static void size(Context c) {
        c.getSystemService(WindowManager.class).getDefaultDisplay().getRealSize(screen);
        if (x < 0) {
            x = screen.x / 2f;
            y = screen.y / 2f;
        }
    }

    private static String xy() {
        return Math.round(x) + " " + Math.round(y);
    }

    /** One message from the phone page's pad: "m dx dy", "c", "r", "s d", "k key", "t text", "a fx fy" …; "ok" or why not. */
    static void handle(Context c, String cmd) {
        if (cmd.isEmpty()) return;
        size(c);
        String arg = cmd.length() > 2 ? cmd.substring(2).trim() : "";
        boolean real = LocalAdb.ready();
        switch (cmd.charAt(0)) {
            case 'm': {
                String[] d = arg.split(" ");
                if (d.length != 2) return;
                x = clamp(x + num(d[0]), screen.x - 1);
                y = clamp(y + num(d[1]), screen.y - 1);
                pointer(c);
                return;
            }
            case 'a':
            case 'l': {
                // a place on the phone's picture of the screen, as fractions of it
                String[] d = arg.split(" ");
                if (d.length != 2) return;
                x = clamp(num(d[0]) * screen.x, screen.x - 1);
                y = clamp(num(d[1]) * screen.y, screen.y - 1);
                pointer(c);
                if (cmd.charAt(0) == 'a') click(c, real);
                else hold(c, real);
                return;
            }
            case 'c':
                click(c, real);
                return;
            case 'r':
                hold(c, real);
                return;
            case 's':
                scroll(c, real, num(arg) < 0 ? -1 : 1, 380);
                return;
            case 'v': {
                // a swipe on the picture: "fx fy d"
                String[] d = arg.split(" ");
                if (d.length != 3) return;
                x = clamp(num(d[0]) * screen.x, screen.x - 1);
                y = clamp(num(d[1]) * screen.y, screen.y - 1);
                pointer(c);
                scroll(c, real, num(d[2]) < 0 ? -1 : 1, 520);
                return;
            }
            case 'k':
                key(c, real, arg);
                return;
            default:
                // the text of the field, and anything else: the Home app's service, as before
                Adhan.home(c, cmd);
        }
    }

    private static void pointer(Context c) {
        Adhan.home(c, "A " + xy());
    }

    private static void click(Context c, boolean real) {
        if (real) {
            pointer(c);
            LocalAdb.run(c, "input tap " + xy());
        } else {
            Adhan.home(c, "A " + xy());
            Adhan.home(c, "c");
        }
    }

    private static void hold(Context c, boolean real) {
        if (real) LocalAdb.run(c, "input swipe " + xy() + " " + xy() + " 700");
        else {
            Adhan.home(c, "A " + xy());
            Adhan.home(c, "r");
        }
    }

    /** d -1: the finger went up (the list goes on, the next video), 1: back. */
    private static void scroll(Context c, boolean real, int d, int len) {
        if (real) {
            int from, to;
            // the drag goes where the finger went: up for the next one
            if (d < 0) {
                from = Math.round(clamp(y + len / 2f, screen.y - 1));
                to = Math.round(clamp(y - len / 2f, screen.y - 1));
            } else {
                from = Math.round(clamp(y - len / 2f, screen.y - 1));
                to = Math.round(clamp(y + len / 2f, screen.y - 1));
            }
            LocalAdb.run(c, String.format(Locale.US, "input swipe %d %d %d %d 220", Math.round(x), from, Math.round(x), to));
        } else {
            Adhan.home(c, "A " + xy());
            Adhan.home(c, "s " + d);
        }
    }

    private static void key(Context c, boolean real, String k) {
        String code;
        switch (k) {
            case "up": code = "DPAD_UP"; break;
            case "down": code = "DPAD_DOWN"; break;
            case "left": code = "DPAD_LEFT"; break;
            case "right": code = "DPAD_RIGHT"; break;
            case "ok": code = "DPAD_CENTER"; break;
            case "enter": code = "ENTER"; break;
            case "back": code = "BACK"; break;
            case "home": code = "HOME"; break;
            case "menu": code = "MENU"; break;
            case "volup": code = "VOLUME_UP"; break;
            case "voldown": code = "VOLUME_DOWN"; break;
            case "mute": code = "VOLUME_MUTE"; break;
            case "play": code = "MEDIA_PLAY_PAUSE"; break;
            default: return;
        }
        if (real) {
            LocalAdb.run(c, "input keyevent KEYCODE_" + code);
            return;
        }
        // without the bridge: the volume here, the rest through the Home app's service
        AudioManager am = c.getSystemService(AudioManager.class);
        switch (k) {
            case "volup": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); return;
            case "voldown": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); return;
            case "mute": am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI); return;
            case "menu": case "play": return;
            default: Adhan.home(c, "k " + k);
        }
    }

    private static float clamp(float v, float max) {
        return Math.max(0, Math.min(max, v));
    }

    private static float num(String s) {
        try {
            return Float.parseFloat(s);
        } catch (Exception e) {
            return 0;
        }
    }

    /** For the phone page: what the pad does now. */
    static String state() {
        switch (LocalAdb.state) {
            case "ready": return "ready|اللمس الدقيق يعمل: النقرة لمسة حقيقية مثل الإصبع";
            case "asking": return "asking|على التلفزيون سؤال «السماح بتصحيح الأخطاء؟» · اختر «السماح دائمًا» ثم «حسنًا» بالريموت";
            case "off": return "off|اللمس الدقيق غير متاح الآن (تصحيح الأخطاء عبر الشبكة مطفأ) · النقرة العادية تعمل";
            default: return "|";
        }
    }

    private PadMouse() {
    }
}
