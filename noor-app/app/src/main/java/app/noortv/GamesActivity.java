package app.noortv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * ألعاب العيلة: every game and pastime behind one card, one under the other. A game that can be played both ways
 * asks first: with the phones, or two teams and a host with the remote only.
 */
public class GamesActivity extends Activity {
    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF;

    /** {icon, title, description, phone activity or "", remote game or ""}. */
    private static final String[][] GAMES = {
            {"", "مسابقة العائلة", "أسئلة في كل المجالات", "QuizActivity", "quiz"},
            {"", "من أنا؟", "شخصيات وأماكن وحيوانات بالتلميحات", "WhoActivity", "who"},
            {"", "بدون كلام", "تمثيل ورسم وتخمين", "CharadesActivity", "headsup"},
            {"", "الكلمات الممنوعة", "اشرح الكلمة دون الكلمات الممنوعة", "", "taboo"},
            {"", "صح أم خطأ", "جمل سريعة والأسرع يفوز", "TrueFalseActivity", "tf"},
            {"", "أكمل الآية", "بداية الآية على الشاشة، فمن يكملها؟", "", "ayah"},
            {"", "أكمل المثل", "أمثال عربية وشامية وحِكم", "", "proverb"},
            {"", "لعبة الحروف", "اسم · حيوان · نبات · جماد · بلاد", "", "letters"},
            {"", "لعبة الذاكرة", "لاعب واحد أو فريقان", "MemoryActivity", ""},
            {"", "دوري العائلة", "نقاط الأسبوع من ألعاب الجوالات", "", ""},
            {"", "صفحة الجوال", "رمز يُمسح بكاميرا الجوال", "", ""},
    };
    private static final String PHONE_PAGE = "صفحة الجوال";
    private static final String LEAGUE = "دوري العائلة";

    /** The family league: this week's table, and last week's champion. */
    private void showLeague() {
        java.util.List<String[]> now = League.table(this, 0), last = League.table(this, 1);
        StringBuilder b = new StringBuilder();
        if (now.isEmpty()) b.append("لا توجد نقاط هذا الأسبوع بعد\nالعبوا المسابقة أو «صح أم خطأ» أو «من أنا؟» من الجوالات");
        String[] medals = {"1.", "2.", "3."};
        for (int i = 0; i < now.size(); i++) {
            b.append(i < 3 ? medals[i] : "   ").append("  ").append(now.get(i)[0]).append("   ·   ").append(now.get(i)[1]).append(" نقطة\n");
        }
        if (!last.isEmpty()) b.append("\nبطل الأسبوع الماضي: ").append(last.get(0)[0]).append(" (").append(last.get(0)[1]).append(")");
        TextView t = text(22, TEXT, false, b.toString());
        t.setPadding(dp(30), dp(16), dp(30), dp(16));
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("دوري العائلة · هذا الأسبوع")
                .setView(t)
                .setPositiveButton("حسناً", null)
                .show();
    }

    /** The quiz: the kids' questions, all subjects, or one subject. */
    private void chooseQuiz(java.util.function.BiConsumer<Boolean, Integer> go) {
        String[] items = new String[Deck.SUBJECTS.length + 2];
        items[0] = "للصغار (أسئلة سهلة)";
        items[1] = "كل المجالات (للكبار)";
        for (int i = 0; i < Deck.SUBJECTS.length; i++) items[i + 2] = Deck.SUBJECTS[i][0];
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("ما نوع الأسئلة؟")
                .setItems(items, (d, w) -> go.accept(w == 0, w <= 1 ? -1 : w - 2))
                .show();
    }

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        // adb: am start -n app.noortv.kahf/app.noortv.GamesActivity --es open tf, straight into a teams game (to try it)
        String open = getIntent().getStringExtra("open");
        if (open != null) startActivity(new Intent(this, TeamsActivity.class).putExtra(TeamsActivity.EXTRA, open));
        prefs = getSharedPreferences("games", MODE_PRIVATE);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(110), dp(10), dp(110), dp(8));
        col.setClipChildren(false);
        col.setClipToPadding(false);
        col.addView(text(26, GOLD, true, "ألعاب العائلة"));
        TextView sub = text(15, 0xAAF4EEDF, false, "اختر لعبة ثم اضغط OK");
        sub.setPadding(0, 0, 0, dp(4));
        col.addView(sub);
        View first = null;
        String last = prefs.getString("last", "");
        for (String[] g : GAMES) {
            View row = row(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            col.addView(row, lp);
            if (first == null || g[1].equals(last)) first = row;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF14302A, 0xFF13233A, 0xFF090B12}));
        scroll.setFillViewport(true);
        scroll.setClipChildren(false);
        scroll.addView(col);
        setContentView(scroll);
        View f = first;
        scroll.post(f::requestFocus);
    }

    private View row(String[] g) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(2), dp(24), dp(2));
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x14FFFFFF);
        bg.setStroke(dp(1), 0x22FFFFFF);
        r.setBackground(bg);
        // one line, laid out left to right: how it is played, what it is, and the name at the right end
        boolean phones = !g[3].isEmpty() && !g[3].equals("MemoryActivity"), teams = !g[4].isEmpty() || g[3].equals("MemoryActivity");
        String modes = phones && teams ? "بالجوالات أو بالفرق" : phones ? "بالجوالات" : teams ? "بالفرق" : "";
        TextView m = text(15, GOLD, false, modes);
        m.setSingleLine(true);
        m.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        r.addView(m, new LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView d = text(16, 0xAAF4EEDF, false, g[2]);
        d.setSingleLine(true);
        d.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        r.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = text(22, TEXT, true, g[1]);
        t.setSingleLine(true);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(28);
        r.addView(t, tl);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x55E3BE6E : 0x14FFFFFF);
            bg.setStroke(dp(has ? 2 : 1), has ? GOLD : 0x22FFFFFF);
        });
        r.setOnClickListener(v -> open(g));
        return r;
    }

    /** The phone page's link and its QR code; «رابط جديد» makes a new key, and every old link stops working. */
    private void showPhonePage() {
        MessageServer.start(this);
        String link = MessageServer.link(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        android.widget.ImageView qr = new android.widget.ImageView(this);
        qr.setImageBitmap(MessageServer.qr(link, 480));
        box.addView(qr, new LinearLayout.LayoutParams(dp(220), dp(220)));
        java.util.Calendar at = java.util.Calendar.getInstance();
        at.setTimeInMillis(Access.changesAt(this));
        TextView t = text(18, TEXT, false, "\nامسحه بكاميرا الجوال، أو افتح الرابط واكتب الرمز:\n" + link.replaceAll("#.*", "")
                + "\n\nرمز العائلة (كل شيء):  " + Access.code(this)
                + "\nرمز الضيوف (الألعاب فقط):  " + Access.guestCode(this)
                + "\nرمز العائلة ثابت حتى تضغط «رمز جديد» · رمز الضيوف يتغيّر "
                + String.format(java.util.Locale.US, "الساعة %d:%02d", at.get(java.util.Calendar.HOUR_OF_DAY), at.get(java.util.Calendar.MINUTE)));
        box.addView(t);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("صفحة الجوال")
                .setView(box)
                .setPositiveButton("حسناً", null)
                .setNegativeButton("رمز جديد", (d, w) -> {
                    // everyone who entered with the old code is out until they have the new one
                    Access.newCode(this);
                    showPhonePage();
                })
                .setNeutralButton("رابط جديد", (d, w) -> new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("رابط جديد؟")
                        .setMessage("سيتوقف الرابط القديم في كل الجوالات، وتفتحون الجديد من هذا الرمز")
                        .setPositiveButton("نعم، غيّره", (d2, w2) -> {
                            MessageServer.newKey(this);
                            showPhonePage();
                        })
                        .setNegativeButton("لا", null)
                        .show())
                .show();
    }

    private void open(String[] g) {
        if (PHONE_PAGE.equals(g[1])) {
            showPhonePage();
            return;
        }
        if (LEAGUE.equals(g[1])) {
            showLeague();
            return;
        }
        prefs.edit().putString("last", g[1]).apply();
        boolean phone = !g[3].isEmpty(), remote = !g[4].isEmpty();
        if (phone && remote) {
            new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(g[0] + "  " + g[1])
                    .setItems(new String[]{"فرق ومقدّم بالريموت (بدون جوالات)", "كل لاعب من جواله"}, (d, w) -> {
                        if (w == 0) remote(g[4]);
                        else phone(g[3]);
                    }).show();
        } else if (phone) {
            phone(g[3]);
        } else {
            remote(g[4]);
        }
    }

    private void phone(String activity) {
        if (activity.equals("QuizActivity")) {
            chooseQuiz((kids, subject) -> startActivity(new Intent().setClassName(this, "app.noortv.QuizActivity")
                    .putExtra("kids", kids).putExtra("subject", subject)));
            return;
        }
        startActivity(new Intent().setClassName(this, "app.noortv." + activity));
    }

    private void remote(String game) {
        if (game.equals("quiz")) {
            chooseQuiz((kids, subject) -> startActivity(new Intent(this, TeamsActivity.class).putExtra(TeamsActivity.EXTRA, game)
                    .putExtra("kids", kids).putExtra("subject", subject)));
            return;
        }
        startActivity(new Intent(this, TeamsActivity.class).putExtra(TeamsActivity.EXTRA, game));
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
