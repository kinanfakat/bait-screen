package app.noortv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.List;

/**
 * بدون كلام on the TV. First the settings (Up/Down choose, Left/Right change, OK on «ابدأ»). Then the two teams'
 * points, whose turn it is, and during a round the category, the time and the words found; in a drawing round the
 * phone's drawing appears in the middle. A ding for every word found, ticks in the last ten seconds, a buzzer at the
 * end. The word itself is only on the phone. OK at the end starts a new game; Back closes it.
 */
public class CharadesActivity extends Activity {
    private static android.content.Context app;

    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF, GREEN = 0xFF2E9E6A, ORANGE = 0xFFE58A3A, RED = 0xFFE05252;
    private static final long[] TIMES = {45_000, 60_000, 90_000};
    private static final int[] ROUNDS = {2, 3, 4};
    private static final String[] MODES = {"تمثيل فقط", "رسم فقط", "منوّع: تمثيل ورسم"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout root, setupBox;
    private TextView title, teamA, teamB, big, info, foot, pop, timerBadge;
    private FrameLayout center, board;
    private DrawLayer drawing;
    private ToneGenerator tone;
    private final TextView[] setupRows = new TextView[4];
    private int setupRow, timeIdx, roundsIdx, modeIdx;
    private int heardGot, heardClear = -1;
    private long lastTickSecond = -1;
    private String heardPhase = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = getApplicationContext();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MessageServer.start(this);
        CharadesGame.open(this);
        CharadesGame.own(this);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (Exception ignored) {
        }
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(50), dp(20), dp(50), dp(16));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2A1A2E, 0xFF162238, 0xFF0A0C14}));
        title = text(22, GOLD, true);
        title.setText("بدون كلام");
        root.addView(title);

        LinearLayout teams = new LinearLayout(this);
        teams.setGravity(Gravity.CENTER);
        teamB = teamBox(ORANGE);
        teamA = teamBox(GREEN);
        teams.addView(teamB, teamParams());   // left: orange
        teams.addView(teamA, teamParams());   // right: green, the first team
        root.addView(teams);

        // the middle: the big timer or message, the drawing board, and the "+1" that pops up
        center = new FrameLayout(this);
        big = text(96, TEXT, true);
        center.addView(big, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        board = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0E1622);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(2), 0x55E3BE6E);
        board.setBackground(bg);
        drawing = new DrawLayer(this);
        board.addView(drawing, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        timerBadge = text(30, TEXT, true);
        FrameLayout.LayoutParams tb = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        tb.setMargins(0, dp(8), dp(16), 0);
        board.addView(timerBadge, tb);
        board.setVisibility(View.GONE);
        center.addView(board, new FrameLayout.LayoutParams(0, 0, Gravity.CENTER));
        pop = text(80, GREEN, true);
        pop.setAlpha(0f);
        center.addView(pop, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        cp.topMargin = dp(8);
        root.addView(center, cp);
        center.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> sizeBoard());

        info = text(22, TEXT, false);
        root.addView(info);
        foot = text(16, 0x99F4EEDF, false);
        foot.setPadding(0, dp(8), 0, 0);
        root.addView(foot);
        setContentView(root);

        timeIdx = indexOf(TIMES, CharadesGame.roundMs());
        roundsIdx = indexOf(ROUNDS, CharadesGame.roundsPerTeam());
        modeIdx = CharadesGame.mode();
        showSetup();
        h.post(tick);
    }

    /** The board keeps the phone canvas's 16:9 inside the middle area. */
    private void sizeBoard() {
        int w = center.getWidth(), ht = center.getHeight();
        if (w == 0 || ht == 0) return;
        int bw = Math.min(w, ht * 16 / 9), bh = bw * 9 / 16;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) board.getLayoutParams();
        if (lp.width != bw || lp.height != bh) {
            lp.width = bw;
            lp.height = bh;
            board.post(() -> board.setLayoutParams(lp));
        }
    }

    // ------------------------------------------------------------------ settings

    private void showSetup() {
        if (setupBox == null) {
            setupBox = new LinearLayout(this);
            setupBox.setOrientation(LinearLayout.VERTICAL);
            setupBox.setGravity(Gravity.CENTER);
            for (int i = 0; i < setupRows.length; i++) {
                setupRows[i] = text(i == 3 ? 30 : 26, TEXT, i == 3);
                setupRows[i].setPadding(dp(30), dp(12), dp(30), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, dp(8), 0, dp(8));
                setupBox.addView(setupRows[i], lp);
            }
            center.addView(setupBox, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setupBox.setVisibility(View.VISIBLE);
        big.setText("");
        drawSetup();
    }

    private void drawSetup() {
        String[] labels = {
                "وقت الجولة:   ◀  " + TIMES[timeIdx] / 1000 + " ثانية  ▶",
                "الجولات لكل فريق:   ◀  " + ROUNDS[roundsIdx] + "  ▶",
                "نوع اللعب:   ◀  " + MODES[modeIdx] + "  ▶",
                "▶  ابدأ اللعبة"};
        for (int i = 0; i < setupRows.length; i++) {
            setupRows[i].setText(labels[i]);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(18));
            boolean on = i == setupRow;
            g.setColor(on ? 0x55E3BE6E : i == 3 ? 0x332E9E6A : 0x18FFFFFF);
            g.setStroke(dp(on ? 3 : 1), on ? GOLD : 0x33FFFFFF);
            setupRows[i].setBackground(g);
        }
        info.setText("▲▼ اختر   ·   ◀▶ غيّر   ·   OK على «ابدأ»");
        foot.setText("في الرسم: يرسم اللاعب الكلمة على هاتفه فتظهر هنا على التلفزيون");
    }

    private boolean setupKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                setupRow = Math.max(0, setupRow - 1);
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                setupRow = Math.min(setupRows.length - 1, setupRow + 1);
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                // Right takes the next value, Left the previous one, as in the control center
                int d = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1;
                if (setupRow == 0) timeIdx = (timeIdx + d + TIMES.length) % TIMES.length;
                if (setupRow == 1) roundsIdx = (roundsIdx + d + ROUNDS.length) % ROUNDS.length;
                if (setupRow == 2) modeIdx = (modeIdx + d + MODES.length) % MODES.length;
                break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (setupRow == 3) {
                    CharadesGame.configure(TIMES[timeIdx], ROUNDS[roundsIdx], modeIdx);
                    setupBox.setVisibility(View.GONE);
                    return true;
                }
                setupRow = 3;
                break;
            default:
                return false;
        }
        drawSetup();
        return true;
    }

    // ------------------------------------------------------------------ the game

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!CharadesGame.owns(CharadesActivity.this)) {
                finish();
                return;
            }
            boolean ended = CharadesGame.tick();
            if (ended) beep(ToneGenerator.TONE_SUP_ERROR, 700);
            draw();
            h.postDelayed(this, 120);
        }
    };

    private void draw() {
        int[] s = CharadesGame.scores();
        int turn = CharadesGame.turn();
        String phase = CharadesGame.phase();
        teamA.setText(CharadesGame.TEAMS[0].replace("الفريق ", "") + "   " + s[0]);
        teamB.setText(CharadesGame.TEAMS[1].replace("الفريق ", "") + "   " + s[1]);
        teamA.setAlpha(CharadesGame.END.equals(phase) || turn == 0 ? 1f : 0.45f);
        teamB.setAlpha(CharadesGame.END.equals(phase) || turn == 1 ? 1f : 0.45f);
        if (CharadesGame.SETUP.equals(phase)) {
            teamA.setAlpha(1f);
            teamB.setAlpha(1f);
            board.setVisibility(View.GONE);
            return;
        }
        boolean playing = CharadesGame.PLAY.equals(phase);
        boolean drawing = playing && CharadesGame.drawRound();
        board.setVisibility(drawing ? View.VISIBLE : View.GONE);

        if (!heardPhase.equals(phase)) {
            heardPhase = phase;
            heardGot = 0;
            if (playing) beep(ToneGenerator.TONE_PROP_BEEP2, 300);
        }
        if (playing) {
            int got = CharadesGame.roundGot();
            if (got > heardGot) {
                heardGot = got;
                beep(ToneGenerator.TONE_PROP_ACK, 250);
                popUp();
            }
            int gen = CharadesGame.clearGen();
            if (gen != heardClear) {
                heardClear = gen;
                this.drawing.clear();
            }
            for (String seg : CharadesGame.takeStrokes()) {
                for (String part : seg.split("\n")) if (!part.isEmpty()) this.drawing.add(part);
            }
            long left = CharadesGame.left();
            long sec = (left + 999) / 1000;
            if (sec <= 10 && sec > 0 && sec != lastTickSecond) {
                lastTickSecond = sec;
                beep(ToneGenerator.TONE_CDMA_PIP, 80);
            }
            String time = String.valueOf(sec);
            if (drawing) {
                big.setText("");
                timerBadge.setText("الوقت: " + time);
                timerBadge.setTextColor(left < 10_000 ? RED : TEXT);
            } else {
                big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 120);
                big.setText(time);
                big.setTextColor(left < 10_000 ? RED : TEXT);
            }
            info.setText((drawing ? "رسم" : "تمثيل") + "   ·   الفئة: " + CharadesGame.category()
                    + "   ·   الكلمات المعروفة: " + CharadesGame.roundGot());
            foot.setText("دور " + CharadesGame.TEAMS[turn] + "   ·   الجولة " + CharadesGame.round() + " من "
                    + CharadesGame.rounds() + (drawing ? "   ·   ممنوع الكتابة والأرقام!" : "   ·   ممنوع الكلام!"));
        } else if (CharadesGame.WAIT.equals(phase)) {
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 52);
            big.setTextColor(turn == 0 ? GREEN : ORANGE);
            big.setText("دور " + CharadesGame.TEAMS[turn] + "\n" + (CharadesGame.nextIsDraw() ? "جولة رسم" : "جولة تمثيل"));
            List<String> last = CharadesGame.lastRound();
            info.setText(last.isEmpty() ? "من سيلعب يفتح من هاتفه: " + link() + "\nتبويب «تمثيل» ← «ابدأ الجولة»"
                    : "الجولة السابقة:  " + String.join("   ", last));
            foot.setText("الجولة " + CharadesGame.round() + " من " + CharadesGame.rounds() + "   ·   "
                    + CharadesGame.roundMs() / 1000 + " ثانية   ·   3 تخطّيات في الجولة   ·   الكلمة تظهر على هاتف اللاعب فقط");
        } else {
            big.setTextSize(TypedValue.COMPLEX_UNIT_SP, 56);
            big.setTextColor(GOLD);
            big.setText(s[0] == s[1] ? "تعادل" : "فاز " + CharadesGame.TEAMS[s[0] > s[1] ? 0 : 1]);
            info.setText(s[0] + " — " + s[1]);
            foot.setText("\u200FOK: لعبة جديدة   ·   رجوع: خروج");
        }
    }

    private void popUp() {
        pop.setText("+1");
        pop.setAlpha(1f);
        pop.setScaleX(0.6f);
        pop.setScaleY(0.6f);
        pop.setTranslationY(0);
        pop.animate().scaleX(1.4f).scaleY(1.4f).translationY(-dp(60)).alpha(0f).setDuration(800).start();
    }

    private void beep(int type, int ms) {
        try {
            if (tone != null) tone.startTone(type, ms);
        } catch (Exception ignored) {
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (CharadesGame.SETUP.equals(CharadesGame.phase()) && setupKey(code)) return true;
        if ((code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)
                && CharadesGame.END.equals(CharadesGame.phase())) {
            CharadesGame.open(this);
            setupRow = 3;
            showSetup();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tick);
        if (CharadesGame.owns(this)) CharadesGame.close();
        if (tone != null) tone.release();
    }

    private static int indexOf(long[] a, long v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 1;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return 1;
    }

    private TextView teamBox(int color) {
        // the same thin strip as the teams games: the name and the score on one line
        TextView t = text(20, TEXT, true);
        t.setSingleLine(true);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor((color & 0x00FFFFFF) | 0x33000000);
        g.setStroke(dp(2), color);
        t.setBackground(g);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private LinearLayout.LayoutParams teamParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(8), dp(4), dp(8), dp(2));
        return lp;
    }

    /** The phone page's link (MessageServer.link). */
    static String link() {
        return MessageServer.link(app);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextDirection(android.view.View.TEXT_DIRECTION_RTL);
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
