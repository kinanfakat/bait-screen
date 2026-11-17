package app.noortv;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Switches the TV on from standby for a notice (ten minutes before the adhan) and says it large over the home screen
 * for half a minute. A wake lock alone does not wake this TV: an activity that turns the screen on does.
 */
public class WakeScreen extends Activity {
    /** Shown and not yet sent away or timed out; and whether it is in front now. */
    static volatile boolean open, inFront;
    private static long until;

    static void show(Context c, String text) {
        if (!open) until = android.os.SystemClock.uptimeMillis() + 30_000;
        open = true;
        c.startActivity(new Intent(c, WakeScreen.class).putExtra("text", text)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setTurnScreenOn(true);
        setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        TextView t = new TextView(this);
        t.setText(getIntent().getStringExtra("text"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        t.setTextColor(0xFFFFE6A8);
        t.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;
        t.setPadding((int) (48 * d), (int) (28 * d), (int) (48 * d), (int) (28 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE60B1424);
        bg.setCornerRadius(24 * d);
        bg.setStroke((int) (2 * d), 0xFFD4A64A);
        t.setBackground(bg);
        root.addView(t, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(root);
        root.postDelayed(this::done, Math.max(1000, until - android.os.SystemClock.uptimeMillis()));
    }

    private void done() {
        open = false;
        finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        inFront = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        inFront = false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // someone is at the TV: the adhan after this notice does not put it back to standby
        getSharedPreferences("adhan", MODE_PRIVATE).edit().remove("preWokeAt").apply();
        done();
        return true;
    }
}
