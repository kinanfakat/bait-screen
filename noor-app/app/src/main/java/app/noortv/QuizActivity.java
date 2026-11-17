package app.noortv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * مسابقة العيلة on the TV: the waiting room with the link to join from the phones, each question with its timer,
 * the right answer, and the podium. OK starts and restarts; Back closes the game.
 */
public class QuizActivity extends Activity {
    private static android.content.Context app;

    private static final int GOLD = 0xFFE3BE6E, GREEN = 0xFF2E9E6A, TEXT = 0xFFF4EEDF;
    private static final String[] LETTERS = {"أ", "ب", "ج", "د"};

    private final Handler h = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private TextView title, sub, timer;
    private LinearLayout body;
    private long revealAt;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        app = getApplicationContext();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        MessageServer.start(this);
        float d = getResources().getDisplayMetrics().density;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (60 * d), (int) (14 * d), (int) (60 * d), (int) (12 * d));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F2A22, 0xFF102036, 0xFF080C14}));
        title = text(26, GOLD, true);
        sub = text(18, TEXT, false);
        timer = text(20, GOLD, true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);
        root.addView(sub);
        root.addView(timer);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        QuizGame.open(this, trueFalse());
        QuizGame.choose(getIntent().getBooleanExtra("kids", false), getIntent().getIntExtra("subject", -1));
        QuizGame.own(this);
        h.post(tick);
    }

    /** صح ولا غلط (TrueFalseActivity) answers true. */
    protected boolean trueFalse() {
        return false;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!QuizGame.owns(QuizActivity.this)) {
                finish();   // another quiz screen took the game
                return;
            }
            String phase = QuizGame.phase();
            if (QuizGame.LOBBY.equals(phase)) lobby();
            else if (QuizGame.QUESTION.equals(phase)) {
                long left = QuizGame.questionMs() - (System.currentTimeMillis() - QuizGame.questionAt());
                if (left <= 0 || QuizGame.everyoneAnswered()) {
                    QuizGame.reveal();
                    revealAt = System.currentTimeMillis();
                }
                question(Math.max(0, left));
            } else if (QuizGame.REVEAL.equals(phase)) {
                question(0);
                // longer when there is a learning note to read
                long show = QuizGame.note().isEmpty() ? (trueFalse() ? 3000 : 5000) : (trueFalse() ? 8000 : 10000);
                if (System.currentTimeMillis() - revealAt > show) QuizGame.next();
            } else {
                podium();
            }
            h.postDelayed(this, 500);
        }
    };

    private void lobby() {
        String chosen = QuizGame.chosenName();
        title.setText(trueFalse() ? "صح أم خطأ · الأسرع يفوز" : "مسابقة العائلة" + (chosen.isEmpty() ? "" : " · " + chosen));
        sub.setText("افتح من هاتفك: " + link() + "   ←  تبويب «مسابقة»  ←  اكتب اسمك");
        timer.setText("");
        body.removeAllViews();
        Map<String, Integer> players = QuizGame.scores();
        body.addView(text(22, TEXT, false, players.isEmpty() ? "بانتظار اللاعبين..." : "اللاعبون (" + players.size() + "):"));
        StringBuilder names = new StringBuilder();
        for (String n : players.keySet()) names.append("").append(n).append("     ");
        body.addView(text(28, GOLD, true, names.toString()));
        body.addView(text(20, 0x99F4EEDF, false, players.isEmpty() ? "" : "\nعند اكتمال اللاعبين اضغط OK للبدء"));
    }

    private void question(long left) {
        String[] q = QuizGame.question();
        if (q == null) return;
        boolean reveal = QuizGame.REVEAL.equals(QuizGame.phase());
        title.setText((trueFalse() ? "عبارة " : "سؤال ") + (QuizGame.index() + 1) + " من " + QuizGame.total());
        sub.setText("");
        Map<String, Integer> answers = QuizGame.answers();
        timer.setText(reveal ? "" : "الوقت: " + (left / 1000 + 1) + "   ·   أجاب " + answers.size() + " من " + QuizGame.scores().size());
        body.removeAllViews();
        // at most three lines: a long question must not push the answers and the note off the screen
        TextView qt = text(32, TEXT, true, q[0]);
        qt.setMaxLines(3);
        qt.setEllipsize(android.text.TextUtils.TruncateAt.END);
        qt.setPadding(0, 10, 0, 14);
        body.addView(qt);
        int correct = QuizGame.correctIndex();
        if (trueFalse()) {
            // two big answers: صح on the right, غلط on the left
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER);
            for (int i = 1; i >= 0; i--) {
                boolean right = reveal && i == correct;
                TextView o = text(40, right ? 0xFF0B1A12 : TEXT, true, q[i + 1]);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(28);
                g.setColor(right ? GREEN : reveal ? 0x11FFFFFF : 0x22FFFFFF);
                g.setStroke(3, right ? GREEN : 0x44FFFFFF);
                o.setBackground(g);
                o.setPadding(30, 36, 30, 36);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(30, 10, 30, 10);
                r.addView(o, lp);
            }
            body.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        for (int row = 0; row < (trueFalse() ? 0 : 2); row++) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER);
            // the launcher-style left-to-right row: the first option of the pair on the right
            for (int col = 1; col >= 0; col--) {
                int i = row * 2 + col;
                TextView o = text(28, reveal && i == correct ? 0xFF0B1A12 : TEXT, true, LETTERS[i] + ")  " + q[i + 1]);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(24);
                g.setColor(reveal && i == correct ? GREEN : 0x22FFFFFF);
                g.setStroke(2, reveal && i == correct ? GREEN : 0x44FFFFFF);
                o.setBackground(g);
                o.setPadding(30, 16, 30, 16);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                lp.setMargins(14, 7, 14, 7);
                r.addView(o, lp);
            }
            body.addView(r, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        if (reveal) {
            List<String> right = new ArrayList<>();
            for (Map.Entry<String, Integer> e : answers.entrySet()) if (e.getValue() == correct) right.add(e.getKey());
            body.addView(text(24, GOLD, true, right.isEmpty() ? "لم يُجب أحد إجابة صحيحة" : "✓ " + String.join("، ", right)));
            body.getChildAt(body.getChildCount() - 1).setPadding(0, 14, 0, 0);
            String note = QuizGame.note();
            if (!note.isEmpty()) {
                TextView n = text(20, 0xFFF1E6C8, false, (note.startsWith("خطأ:") || note.startsWith("صح:") ? "" : "معلومة: ") + note);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(24);
                g.setColor(0x22E3BE6E);
                g.setStroke(2, 0x55E3BE6E);
                n.setBackground(g);
                n.setMaxLines(2);
                n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                n.setPadding(36, 10, 36, 10);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(60, 12, 60, 0);
                body.addView(n, lp);
            }
        }
    }

    private void podium() {
        title.setText("النتيجة");
        sub.setText("");
        timer.setText("");
        body.removeAllViews();
        List<Map.Entry<String, Integer>> list = new ArrayList<>(QuizGame.scores().entrySet());
        Collections.sort(list, (a, b) -> b.getValue() - a.getValue());
        String[] medals = {"الأول", "الثاني", "الثالث"};
        for (int i = 0; i < list.size(); i++) {
            Map.Entry<String, Integer> e = list.get(i);
            body.addView(text(i == 0 ? 40 : 30, i == 0 ? GOLD : TEXT, i == 0,
                    (i < 3 ? medals[i] : "   ") + "  " + e.getKey() + "   ·   " + e.getValue() + " نقطة"));
        }
        body.addView(text(20, 0x99F4EEDF, false, "\nOK: جولة جديدة بنفس اللاعبين   ·   رجوع: خروج"));
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            String phase = QuizGame.phase();
            if (QuizGame.LOBBY.equals(phase) || phase.startsWith(QuizGame.END)) QuizGame.start();
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tick);
        if (QuizGame.owns(this)) QuizGame.close();
    }

    /** The phone page's link (MessageServer.link). */
    static String link() {
        return MessageServer.link(app);
    }

    private TextView text(float sp, int color, boolean bold) {
        return text(sp, color, bold, "");
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
}
