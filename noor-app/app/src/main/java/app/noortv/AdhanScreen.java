package app.noortv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The adhan's full screen, over whatever is on (a film, a channel, HDMI, a game, or the TV in standby): the prayer's
 * name while the adhan plays. Any key stops the adhan and gives the TV back. When the adhan ends by itself the screen
 * closes after a few seconds, or, if the adhan switched the TV on, counts down and puts it back to standby.
 */
public class AdhanScreen extends Activity {
    private static AdhanScreen shown;
    private static boolean front, dismissed;
    private final Handler h = new Handler(Looper.getMainLooper());
    private TextView title, line;
    private boolean sleepAfter, sleeping, ending;
    private int left = -1;

    /** Brings the screen up, or back in front. sleepAfter: the adhan woke the TV; ended: the adhan has just ended. */
    static void show(Context c, String text, boolean sleepAfter, boolean ended) {
        if (!ended && shown == null) dismissed = false;
        if (dismissed) return;
        try {
            c.startActivity(new Intent(c, AdhanScreen.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("text", text).putExtra("sleep", sleepAfter).putExtra("ended", ended));
        } catch (Exception ignored) {
        }
    }

    static boolean open() {
        return shown != null;
    }

    static boolean inFront() {
        return front;
    }

    /** Sent away with a key: not brought back for this adhan. */
    static boolean dismissed() {
        return dismissed;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTurnScreenOn(true);
        setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF0F2A24, 0xFF0B1424, 0xFF06080E}));
        TextView icon = new TextView(this);
        icon.setText("🕌");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 90);
        icon.setGravity(Gravity.CENTER);
        root.addView(icon);
        title = new TextView(this);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
        title.setTextColor(0xFFFFE08A);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(40, 20, 40, 0);
        root.addView(title);
        line = new TextView(this);
        line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        line.setTextColor(0xCCF4EEDF);
        line.setGravity(Gravity.CENTER);
        line.setPadding(40, 44, 40, 0);
        root.addView(line);
        setContentView(root);
        shown = this;
        take(getIntent());
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        take(i);
    }

    private void take(Intent i) {
        String text = i.getStringExtra("text");
        if (text != null) title.setText(text.replace("🕌 ", ""));
        sleepAfter |= i.getBooleanExtra("sleep", false);
        if (Adhan.playing()) {
            line.setText("اضغط أي زر لإيقاف الأذان");
        } else if (i.getBooleanExtra("ended", false) || left < 0) {
            after(i.getBooleanExtra("ended", false));
        }
    }

    /** The adhan is over (ended) or there is none (Fajr without sound, the banner-only setting). */
    private void after(boolean ended) {
        if (ending) return;
        ending = true;
        if (sleepAfter) {
            left = 20;
            tick();
        } else {
            line.setText("اضغط أي زر للرجوع");
            // a little after the adhan, a minute when there was no sound to hear
            h.postDelayed(this::finish, ended ? 6_000 : 60_000);
        }
    }

    private void tick() {
        if (isFinishing()) return;
        if (left > 0) {
            line.setText("التلفزيون رح يرجع ينطفي بعد " + left + " ثانية\nاضغط أي زر ليضل شغّال");
            if (left % 5 == 0) Adhan.hushStray(this);
            left--;
            h.postDelayed(this::tick, 1000);
            return;
        }
        // let the screen go: the shortened timeout turns it off, MessageServer sets the timeout back
        sleeping = true;
        line.setText("التلفزيون عم ينطفي… اضغط أي زر ليضل شغّال");
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Adhan.sleepTv(this);
        // still on twenty seconds later (something holds the screen up): the remote's own sleep key
        h.postDelayed(() -> {
            if (sleeping && getSystemService(android.os.PowerManager.class).isInteractive() && LocalAdb.ready()) {
                Adhan.log(this, "still on after the adhan: sleep key");
                LocalAdb.run(this, "input keyevent KEYCODE_SLEEP");
            }
        }, 20_000);
        h.postDelayed(this::finish, 60_000);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) {
            // someone is there: stop the adhan and give the TV back as it was
            dismissed = true;
            Adhan.stopNow();
            Adhan.restoreScreenTimeout(this);
            finish();
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        front = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        front = false;
    }

    @Override
    protected void onStop() {
        super.onStop();
        // the screen went off on its way back to standby: done
        if (sleeping) finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        if (shown == this) {
            shown = null;
            front = false;
        }
    }
}
