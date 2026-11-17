package app.noortv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Calendar;

/**
 * Every 45 minutes, a small line under the top of the screen for 10 seconds: the salawat line, and on Friday
 * before maghrib the Surat Al-Kahf reminder first and the salawat line right after it. Only the Kahf shortcut schedules it.
 */
public class Reminder extends BroadcastReceiver {
    static final String SHOW = "app.noortv.SHOW";
    static final long EVERY = 45 * 60 * 1000L;
    static final long SHOWN = 15_000;

    static void schedule(Context c) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        Intent show = new Intent(c, Reminder.class).setAction(SHOW);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, show,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        int minutes = NoorConfig.remind(c);   // the control center's choice; 0 turns the line off
        if (minutes <= 0) {
            am.cancel(pi);
            return;
        }
        long every = minutes * 60_000L;
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 60_000, every, pi);
    }

    /**
     * MessageServer's clock, every few seconds: the line on time, also while a film or a channel plays (it sits in the
     * corner above any app). Not while the screen is off, nor while the adhan plays.
     */
    static void tick(Context c) {
        if (!BuildConfig.REMINDERS || Adhan.playing()) return;
        long ms = NoorConfig.remind(c) * 60_000L;
        if (ms <= 0) return;
        if (!c.getSystemService(android.os.PowerManager.class).isInteractive()) return;
        long last = c.getSharedPreferences("reminder", Context.MODE_PRIVATE).getLong("shownAt", 0), now = System.currentTimeMillis();
        if (last == 0) {
            // the first time: counted from now
            c.getSharedPreferences("reminder", Context.MODE_PRIVATE).edit().putLong("shownAt", now).apply();
            return;
        }
        if (now - last >= ms) new Reminder().onReceive(c, new Intent(c, Reminder.class).setAction(SHOW));
    }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!BuildConfig.REMINDERS) return;
        if ("app.noortv.TEST_ADHAN".equals(i.getAction())) {
            // «جرّب الأذان» in the control center: the chosen muezzin, now, whatever is on screen
            Adhan.test(c.getApplicationContext());
            return;
        }
        if ("app.noortv.STOP_ADHAN".equals(i.getAction())) {
            Adhan.stopNow();
            return;
        }
        if (NoorConfig.ACTION.equals(i.getAction())) {
            // from the launcher's Noor control center: keep the choices, then schedule again with them
            NoorConfig.save(c, i);
            schedule(c);
            Adhan.schedule(c);
            return;
        }
        if (!SHOW.equals(i.getAction())) {
            schedule(c);
            Adhan.schedule(c);
            MessageServer.start(c);
            return;
        }
        if (!Settings.canDrawOverlays(c)) return;
        // the system's alarm can come up to an hour late on this TV: MessageServer's clock (tick) is the real one,
        // and whichever comes first shows the line, once
        android.content.SharedPreferences p = c.getSharedPreferences("reminder", Context.MODE_PRIVATE);
        long ms = NoorConfig.remind(c) * 60_000L, at = System.currentTimeMillis();
        if (ms <= 0 || at - p.getLong("shownAt", 0) < ms - 5 * 60_000L) return;
        p.edit().putLong("shownAt", at).apply();
        Calendar now = Calendar.getInstance();
        boolean friday = now.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY && now.get(Calendar.HOUR_OF_DAY) < 17;
        Context app = c.getApplicationContext();
        if (!friday) {
            show(app, "صلّ على النبي ﷺ");
            return;
        }
        // On Friday the Kahf line first, then the salawat line as soon as it clears.
        show(app, "اليوم الجمعة... لا تنسى سورة الكهف");
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> show(app, "لا تنسى الصلاة على الحبيب محمد ﷺ"), SHOWN + 500);
    }

    /**
     * The salawat line: a small gold-edged pill in the bottom-right corner (clear of subtitles in the middle), that
     * fades in, breathes gently while it is up, and fades away on its own after SHOWN.
     */
    static void show(Context c, String text) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        TextView v = new TextView(c);
        v.setText(text);
        v.setTextColor(0xFFFFF4D6);
        v.setTextSize(20);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setPadding(34, 14, 34, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD90F3D2E);
        bg.setCornerRadius(40);
        bg.setStroke(2, 0xCCE3BE6E);
        v.setBackground(bg);
        v.setAlpha(0f);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.x = 60;
        lp.y = 70;
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            return;
        }
        v.animate().alpha(1f).setDuration(700).start();
        android.animation.ObjectAnimator sx = android.animation.ObjectAnimator.ofFloat(v, "scaleX", 1f, 1.06f);
        android.animation.ObjectAnimator sy = android.animation.ObjectAnimator.ofFloat(v, "scaleY", 1f, 1.06f);
        for (android.animation.ObjectAnimator o : new android.animation.ObjectAnimator[]{sx, sy}) {
            o.setDuration(1100);
            o.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            o.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            o.start();
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> v.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            sx.cancel();
            sy.cancel();
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
        }).start(), SHOWN);
    }
}
