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
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * مين أنا؟ on the TV: the lobby with the link, then each round the category, the twenty-question meter, the host's
 * answers and the wrong guesses as they come, the hints given, and the reveal. OK starts and moves on; Back closes.
 */
public class WhoActivity extends Activity {
    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF, GREEN = 0xFF2E9E6A, RED = 0xFFE05252,
            AMBER = 0xFFE5A23A, GREY = 0xFF8A94A6;

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout body;
    private TextView top;
    private long shownVersion = -1;
    private int heardLog;
    private String heardPhase = "";
    private ToneGenerator tone;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MessageServer.start(this);
        WhoGame.open(this);
        WhoGame.own(this);
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (Exception ignored) {
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(46), dp(22), dp(46), dp(18));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF2B2140, 0xFF13233A, 0xFF090B12}));
        top = text(22, GOLD, true, "");
        root.addView(top);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        h.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!WhoGame.owns(WhoActivity.this)) {
                finish();
                return;
            }
            if (WhoGame.version() != shownVersion) {
                shownVersion = WhoGame.version();
                sounds();
                draw();
            }
            h.postDelayed(this, 300);
        }
    };

    /** A short sound for each answer, a buzz for a wrong guess, a chime when the card is guessed. */
    private void sounds() {
        if (tone == null) return;
        String phase = WhoGame.phase();
        List<String[]> log = WhoGame.log();
        if (WhoGame.ASK.equals(phase) && log.size() > heardLog) {
            String kind = log.get(log.size() - 1)[2];
            tone.startTone(kind.equals("wrong") ? ToneGenerator.TONE_SUP_ERROR
                    : kind.equals("yes") ? ToneGenerator.TONE_PROP_ACK : ToneGenerator.TONE_PROP_BEEP, 250);
        }
        if (WhoGame.REVEAL.equals(phase) && !heardPhase.equals(phase)) {
            tone.startTone(WhoGame.winner().isEmpty() ? ToneGenerator.TONE_SUP_ERROR : ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
        }
        heardLog = WhoGame.ASK.equals(phase) ? log.size() : 0;
        heardPhase = phase;
    }

    private void draw() {
        body.removeAllViews();
        String phase = WhoGame.phase();
        if (WhoGame.LOBBY.equals(phase)) lobby();
        else if (WhoGame.ASK.equals(phase)) asking();
        else if (WhoGame.REVEAL.equals(phase)) reveal();
        else podium();
    }

    private void lobby() {
        top.setText("من أنا؟");
        body.addView(text(19, TEXT, false, "لاعب واحد يعرف الشخصية السرية من هاتفه، والباقون يسألونه أسئلة جوابها نعم أو لا، ويحاولون معرفتها في أقل من 20 سؤالاً"), wrap(dp(10)));
        body.addView(text(22, GOLD, false, "افتحوا من هواتفكم: " + MessageServer.link(this) + "\nتبويب «من أنا؟» ← اكتب اسمك"), wrap(dp(24)));
        List<String> players = WhoGame.players();
        body.addView(text(22, TEXT, false, players.isEmpty() ? "بانتظار اللاعبين..." : "اللاعبون (" + players.size() + "):"), wrap(dp(24)));
        StringBuilder names = new StringBuilder();
        for (String n : players) names.append("").append(n).append("     ");
        body.addView(text(30, GOLD, true, names.toString()), wrap(dp(8)));
        body.addView(text(19, 0x99F4EEDF, false, players.size() < 2 ? "يجب وجود لاعبَين على الأقل"
                : "كل لاعب يكون المضيف مرة · عند اكتمال اللاعبين اضغط OK"), wrap(dp(22)));
    }

    private void asking() {
        top.setText("من أنا؟   ·   الجولة " + WhoGame.round() + " من " + WhoGame.rounds()
                + "   ·   المضيف: " + WhoGame.host());
        // the category and the question meter
        TextView cat = text(34, TEXT, true, "الفئة: " + WhoGame.category());
        cat.setBackground(pill(0x33E3BE6E, GOLD));
        cat.setPadding(dp(30), dp(8), dp(30), dp(8));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.CENTER_HORIZONTAL;
        cp.topMargin = dp(10);
        body.addView(cat, cp);
        int q = WhoGame.questions();
        body.addView(text(20, q >= 15 ? RED : TEXT, false, "سؤال " + q + " من " + WhoGame.MAX_QUESTIONS), wrap(dp(12)));
        body.addView(meter(q), wrap(dp(6)));
        List<String> hints = WhoGame.shownHints();
        if (!hints.isEmpty()) {
            body.addView(text(20, AMBER, false, "تلميح: " + String.join("   ·   ", hints)), wrap(dp(12)));
        }
        // the answers, newest first
        List<String[]> log = WhoGame.log();
        Collections.reverse(log);
        if (log.isEmpty()) {
            body.addView(text(22, 0x99F4EEDF, false, "\nاسألوا " + WhoGame.host() + " أسئلة جوابها نعم أو لا…\nمثلاً: هل هو إنسان؟ هل عاش قبل الإسلام؟ هل هو في سوريا؟"), wrap(dp(16)));
        }
        for (int i = 0; i < Math.min(7, log.size()); i++) body.addView(logLine(log.get(i), i == 0), wrap(dp(i == 0 ? 16 : 6)));
    }

    private View logLine(String[] l, boolean newest) {
        String who = l[0], q = l[1], kind = l[2];
        String answer;
        int color;
        switch (kind) {
            case "yes": answer = "✓ نعم"; color = GREEN; break;
            case "no": answer = "✗ لا"; color = RED; break;
            case "maybe": answer = "أحياناً / نوعاً ما"; color = AMBER; break;
            case "idk": answer = "لا أعرف"; color = GREY; break;
            default: answer = "✗ خطأ"; color = RED;
        }
        String text = kind.equals("wrong") ? who + " خمّن: «" + q + "»   " + answer
                : (q.isEmpty() ? "سؤال" : who + ": " + q) + "   ←   " + answer;
        TextView t = text(newest ? 28 : 21, newest ? TEXT : 0xCCF4EEDF, newest, text);
        t.setBackground(pill(newest ? (color & 0x00FFFFFF) | 0x33000000 : 0x14FFFFFF, newest ? color : 0x22FFFFFF));
        t.setPadding(dp(24), dp(newest ? 10 : 5), dp(24), dp(newest ? 10 : 5));
        return t;
    }

    /** Twenty dots, the used ones lit. */
    private View meter(int used) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        for (int i = WhoGame.MAX_QUESTIONS - 1; i >= 0; i--) {   // filling from the right
            View d = new View(this);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(i < used ? (used >= 15 ? RED : GOLD) : 0x33FFFFFF);
            d.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(18), dp(18));
            lp.setMargins(dp(4), 0, dp(4), 0);
            row.addView(d, lp);
        }
        return row;
    }

    private void reveal() {
        top.setText("من أنا؟   ·   الجولة " + WhoGame.round() + " من " + WhoGame.rounds());
        String w = WhoGame.winner();
        body.addView(text(30, w.isEmpty() ? AMBER : GREEN, true, w.isEmpty() ? "لم يعرفها أحد!"
                : "عرفها " + w + "!   +" + WhoGame.lastPoints() + "   (و" + WhoGame.host() + " +5)"), wrap(dp(14)));
        body.addView(text(64, GOLD, true, WhoGame.name()), wrap(dp(10)));
        body.addView(text(20, 0xCCF4EEDF, false, String.join("   ·   ", WhoGame.facts())), wrap(dp(10)));
        body.addView(text(19, TEXT, false, "بعد " + WhoGame.questions() + " سؤال"), wrap(dp(10)));
        body.addView(scoresLine(), wrap(dp(22)));
        boolean last = WhoGame.round() >= WhoGame.rounds();
        body.addView(text(19, 0x99F4EEDF, false, last ? "\u200FOK: النتيجة النهائية" : "\u200FOK: الجولة التالية (المضيف القادم: "
                + WhoGame.players().get(WhoGame.round() % WhoGame.rounds()) + ")"), wrap(dp(18)));
    }

    private void podium() {
        top.setText("من أنا؟");
        body.addView(text(40, GOLD, true, "النتيجة"), wrap(dp(20)));
        List<Map.Entry<String, Integer>> list = new ArrayList<>(WhoGame.scores().entrySet());
        Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        String[] medals = {"الأول", "الثاني", "الثالث"};
        for (int i = 0; i < list.size(); i++) {
            body.addView(text(i == 0 ? 40 : 28, i == 0 ? GOLD : TEXT, i == 0,
                    (i < 3 ? medals[i] : "   ") + "  " + list.get(i).getKey() + "   ·   " + list.get(i).getValue() + " نقطة"), wrap(dp(8)));
        }
        body.addView(text(19, 0x99F4EEDF, false, "\u200FOK: لعبة جديدة بنفس اللاعبين   ·   رجوع: خروج"), wrap(dp(26)));
    }

    private View scoresLine() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, Integer> e : WhoGame.scores().entrySet()) {
            if (b.length() > 0) b.append("     ");
            b.append(e.getKey()).append(" ").append(e.getValue());
        }
        return text(22, TEXT, false, b.toString());
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            String phase = WhoGame.phase();
            if (WhoGame.END.equals(phase)) {
                // a new game with the same players
                List<String> players = WhoGame.players();
                WhoGame.open(this);
                for (String p : players) WhoGame.join(p);
            } else {
                WhoGame.next();
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tick);
        if (WhoGame.owns(this)) WhoGame.close();
        if (tone != null) tone.release();
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(fill);
        g.setStroke(dp(2), stroke);
        return g;
    }

    private LinearLayout.LayoutParams wrap(int topMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = topMargin;
        return lp;
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
