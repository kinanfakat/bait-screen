package app.noortv;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * لعبة الذاكرة: cards face down, OK turns one over, a second one: the same picture stays open, different pictures
 * turn back. Three levels (12, 16 and 24 cards); the moves and the time are counted and each level keeps its best.
 * Back on the board returns to the levels, Back there closes the game.
 */
public class MemoryActivity extends Activity {
    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF, GREEN = 0xFF2E9E6A;
    private static final String[] PICS = {"🕌", "🌙", "⭐", "📖", "🌴", "🐪", "🍇", "🍉", "🌸", "🦁", "🐟", "🌻",
            "🍎", "🚀", "⚽", "🎈", "🐢", "🦋", "🌈", "🍰"};
    private static final String[] LEVELS = {"سهل", "متوسط", "صعب"};
    private static final int[][] SIZES = {{4, 3}, {4, 4}, {6, 4}};   // columns, rows

    private final Handler h = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView status;
    private final List<TextView> cards = new ArrayList<>();
    private final List<String> faces = new ArrayList<>();
    private boolean[] open;
    private int level, first = -1, second = -1, moves, matched;
    private long startedAt;
    private boolean onBoard, onLevels, busy;
    // two teams take turns: a pair found is a point and another go, a miss passes the turn
    private static final String[] TEAMS = {"الأخضر", "البرتقالي"};
    private boolean teams;
    private int turn;
    private final int[] teamScore = new int[2];

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("memory", MODE_PRIVATE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setClipChildren(false);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF10302A, 0xFF12233A, 0xFF0A0C14}));
        setContentView(root);
        showLevels();
    }

    // ------------------------------------------------------------------ levels

    private void showLevels() {
        h.removeCallbacksAndMessages(null);   // a pending flip-back or win from the board just left
        onBoard = false;
        onLevels = true;
        h.removeCallbacks(clock);
        root.removeAllViews();
        root.addView(text(40, GOLD, true, "لعبة الذاكرة"));
        TextView sub = text(18, 0xAAF4EEDF, false, "اقلب البطاقات وابحث عن كل صورتين متشابهتين بأقل عدد من الحركات");
        sub.setPadding(0, dp(6), 0, dp(30));
        root.addView(sub);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setClipChildren(false);
        View focus = null;
        for (int i = LEVELS.length - 1; i >= 0; i--) {   // the easy level on the right
            int lv = i;
            int best = prefs.getInt("best" + lv, 0);
            TextView t = button(LEVELS[lv] + "\n" + SIZES[lv][0] * SIZES[lv][1] + " بطاقة"
                    + (best > 0 ? "\nأفضل نتيجة: " + best + " حركة" : "\n "));
            t.setOnClickListener(v -> startGame(lv));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(200), dp(150));
            lp.setMargins(dp(16), 0, dp(16), 0);
            row.addView(t, lp);
            if (lv == prefs.getInt("lastLevel", 0)) focus = t;
        }
        root.addView(row);
        teams = prefs.getBoolean("teams", false);
        TextView who = button(teams ? "فريقان بالتناوب" : "لاعب واحد");
        who.setOnClickListener(v -> {
            teams = !teams;
            prefs.edit().putBoolean("teams", teams).apply();
            ((TextView) v).setText(teams ? "فريقان بالتناوب" : "لاعب واحد");
        });
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(dp(300), dp(64));
        wp.topMargin = dp(26);
        wp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(who, wp);
        View f = focus;
        if (f != null) h.post(f::requestFocus);
    }

    // ------------------------------------------------------------------ the board

    private void startGame(int lv) {
        h.removeCallbacksAndMessages(null);
        level = lv;
        prefs.edit().putInt("lastLevel", lv).apply();
        onBoard = true;
        onLevels = false;
        int cols = SIZES[lv][0], rows = SIZES[lv][1], pairs = cols * rows / 2;
        List<String> pics = new ArrayList<>();
        Collections.addAll(pics, PICS);
        Collections.shuffle(pics);
        faces.clear();
        for (int i = 0; i < pairs; i++) {
            faces.add(pics.get(i));
            faces.add(pics.get(i));
        }
        Collections.shuffle(faces);
        open = new boolean[faces.size()];
        first = second = -1;
        moves = matched = 0;
        busy = false;
        turn = 0;
        teamScore[0] = teamScore[1] = 0;
        startedAt = System.currentTimeMillis();

        root.removeAllViews();
        status = text(20, TEXT, false, "");
        status.setPadding(0, 0, 0, dp(10));
        root.addView(status);
        cards.clear();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int size = Math.min(dp(130), (int) (screenH * 0.78f / rows) - dp(12));
        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER);
            row.setClipChildren(false);
            for (int c = cols - 1; c >= 0; c--) {
                int i = r * cols + c;
                TextView t = text(size * 0.42f / getResources().getDisplayMetrics().scaledDensity, TEXT, false, "");
                t.setFocusable(true);
                t.setOnClickListener(v -> flip(i));
                t.setOnFocusChangeListener((v, has) -> {
                    v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(120).start();
                    paint(i, has);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.setMargins(dp(6), dp(6), dp(6), dp(6));
                row.addView(t, lp);
                while (cards.size() <= i) cards.add(null);
                cards.set(i, t);
            }
            root.addView(row);
        }
        for (int i = 0; i < cards.size(); i++) paint(i, false);
        h.post(() -> cards.get(0).requestFocus());   // the first card, top right
        h.post(clock);
    }

    private final Runnable clock = new Runnable() {
        @Override
        public void run() {
            if (!onBoard) return;
            long s = (System.currentTimeMillis() - startedAt) / 1000;
            if (teams) {
                status.setText("دور " + TEAMS[turn] + "   ·   " + TEAMS[0] + " " + teamScore[0] + "  —  " + TEAMS[1] + " " + teamScore[1]
                        + "   ·   " + matched + " من " + faces.size() / 2);
                status.setTextColor(turn == 0 ? 0xFF4ADE80 : 0xFFF5A35C);
                if (matched < faces.size() / 2) h.postDelayed(this, 300);
                return;
            }
            status.setText("المستوى: " + LEVELS[level] + "   ·   الحركات: " + moves + "   ·   الوقت: " + s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60)
                    + "   ·   " + matched + " من " + faces.size() / 2);
            if (matched < faces.size() / 2) h.postDelayed(this, 500);
        }
    };

    private void paint(int i, boolean focused) {
        TextView t = cards.get(i);
        if (t == null) return;
        boolean shown = open[i] || i == first || i == second;
        t.setText(shown ? faces.get(i) : "✦");
        t.setTextColor(shown ? TEXT : 0x88E3BE6E);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(open[i] ? 0x552E9E6A : shown ? 0x33FFFFFF : 0xFF1C3A30);
        g.setStroke(dp(focused ? 4 : 2), focused ? GOLD : open[i] ? GREEN : 0x55E3BE6E);
        t.setBackground(g);
    }

    /** Turns card i over with a flip. */
    private void flip(int i) {
        if (busy || open[i] || i == first) return;
        if (first < 0) first = i;
        else second = i;
        TextView t = cards.get(i);
        t.animate().rotationY(90).setDuration(110).withEndAction(() -> {
            paint(i, t.isFocused());
            t.setRotationY(-90);
            t.animate().rotationY(0).setDuration(110).start();
        }).start();
        if (second < 0) return;
        moves++;
        int a = first, b = second;
        if (faces.get(a).equals(faces.get(b))) {
            open[a] = open[b] = true;
            matched++;
            if (teams) teamScore[turn]++;
            first = second = -1;
            h.postDelayed(() -> {
                paint(a, cards.get(a).isFocused());
                paint(b, cards.get(b).isFocused());
            }, 260);
            if (matched == faces.size() / 2) h.postDelayed(this::won, 700);
        } else {
            busy = true;
            h.postDelayed(() -> {
                first = second = -1;
                busy = false;
                if (teams) turn = 1 - turn;
                for (int k : new int[]{a, b}) {
                    TextView c = cards.get(k);
                    c.animate().rotationY(90).setDuration(110).withEndAction(() -> {
                        paint(k, c.isFocused());
                        c.setRotationY(-90);
                        c.animate().rotationY(0).setDuration(110).start();
                    }).start();
                }
            }, 950);
        }
    }

    private void won() {
        onBoard = false;
        long s = (System.currentTimeMillis() - startedAt) / 1000;
        int pairs = faces.size() / 2;
        root.removeAllViews();
        if (teams) {
            root.addView(text(54, GOLD, true, teamScore[0] == teamScore[1] ? "تعادل"
                    : "فاز " + TEAMS[teamScore[0] > teamScore[1] ? 0 : 1]));
            root.addView(text(34, TEXT, false, TEAMS[0] + " " + teamScore[0] + "  —  " + TEAMS[1] + " " + teamScore[1]));
        } else {
            int best = prefs.getInt("best" + level, 0);
            boolean record = best == 0 || moves < best;
            if (record) prefs.edit().putInt("best" + level, moves).apply();
            String stars = moves <= pairs + 2 ? "⭐⭐⭐" : moves <= pairs * 2 ? "⭐⭐" : "⭐";
            root.addView(text(54, GOLD, true, "أحسنت!"));
            root.addView(text(44, TEXT, false, stars));
            root.addView(text(24, TEXT, false, moves + " حركة   ·   " + s / 60 + ":" + String.format(java.util.Locale.US, "%02d", s % 60)
                    + (record ? "\nرقم قياسي جديد!" : "\nأفضل رقم: " + best + " حركة")));
        }
        TextView again = button("مرة أخرى");
        again.setOnClickListener(v -> startGame(level));
        TextView levels = button("المستويات");
        levels.setOnClickListener(v -> showLevels());
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, dp(30), 0, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(220), dp(80));
        lp.setMargins(dp(14), 0, dp(14), 0);
        row.addView(levels, lp);
        row.addView(again, lp);
        root.addView(row);
        h.post(again::requestFocus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacksAndMessages(null);
        onBoard = false;
    }

    @Override
    public void onBackPressed() {
        // the board or the win screen go back to the levels, the levels close the game
        if (onLevels) super.onBackPressed();
        else showLevels();
    }

    // ------------------------------------------------------------------ views

    private TextView button(String s) {
        TextView t = text(22, TEXT, true, s);
        t.setFocusable(true);
        t.setClickable(true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(18));
        g.setColor(0x22FFFFFF);
        g.setStroke(dp(2), 0x33FFFFFF);
        t.setBackground(g);
        t.setOnFocusChangeListener((v, has) -> {
            g.setColor(has ? 0x55E3BE6E : 0x22FFFFFF);
            g.setStroke(dp(has ? 3 : 2), has ? GOLD : 0x33FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f).setDuration(120).start();
        });
        return t;
    }

    private TextView text(float sp, int color, boolean bold, String s) {
        TextView t = new TextView(this);
        t.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
