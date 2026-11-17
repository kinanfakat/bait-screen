package app.noortv;

import android.app.Activity;
import android.content.Intent;
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
 * One Home card for a family of cards: «ديني» (the radio, the wird, the hadith, the surahs and the adhkar) and
 * «البيت» (the solar system, the house's state, the control center). The same thin rows as the games list: the name
 * at the right, what it is beside it. A row opens the card's own app, or a screen of this app (a target starting
 * with a dot).
 */
public abstract class FolderActivity extends Activity {
    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF;

    /** The folder's title. */
    abstract String title();

    /** {name, what it is, target}: a package of the Noor cards, or ".Activity" in this app. */
    abstract String[][] items();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(110), dp(14), dp(110), dp(10));
        col.addView(text(26, GOLD, true, title()));
        TextView sub = text(15, 0xAAF4EEDF, false, "اختر ثم اضغط OK");
        sub.setPadding(0, 0, 0, dp(6));
        col.addView(sub);
        View first = null;
        String last = getSharedPreferences("folders", MODE_PRIVATE).getString(getClass().getSimpleName(), "");
        for (String[] it : items()) {
            View row = row(it);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(2), 0, dp(2));
            col.addView(row, lp);
            if (first == null || it[0].equals(last)) first = row;
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF14302A, 0xFF13233A, 0xFF090B12}));
        scroll.setFillViewport(true);
        scroll.addView(col);
        setContentView(scroll);
        View f = first;
        if (f != null) scroll.post(f::requestFocus);
    }

    private View row(String[] it) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(24), dp(3), dp(24), dp(3));
        r.setFocusable(true);
        r.setClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(0x14FFFFFF);
        bg.setStroke(dp(1), 0x22FFFFFF);
        r.setBackground(bg);
        // laid out left to right: what it is, then the name at the right end
        TextView d = text(16, 0xAAF4EEDF, false, it[1]);
        d.setSingleLine(true);
        d.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        r.addView(d, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = text(22, TEXT, true, it[0]);
        t.setSingleLine(true);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(28);
        r.addView(t, tl);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x55E3BE6E : 0x14FFFFFF);
            bg.setStroke(dp(has ? 2 : 1), has ? GOLD : 0x22FFFFFF);
        });
        r.setOnClickListener(v -> open(it));
        return r;
    }

    private void open(String[] it) {
        getSharedPreferences("folders", MODE_PRIVATE).edit().putString(getClass().getSimpleName(), it[0]).apply();
        try {
            if (it[2].startsWith(".")) {
                // the screens live in the main Noor app: its data (the outage log, the adhan, the solar sign-in) is there
                startActivity(new Intent().setClassName("app.noortv.kahf", "app.noortv" + it[2]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } else {
                // every Noor card's door is the same class in its own package
                startActivity(new Intent().setClassName(it[2], "app.noortv.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        } catch (Exception ignored) {
        }
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

    /** «ديني»: the Quran, the wird, the hadith and the adhkar. */
    public static class Deen extends FolderActivity {
        @Override
        String title() {
            return "القرآن والأذكار";
        }

        @Override
        String[][] items() {
            return new String[][]{
                    {"إذاعة القرآن الكريم", "بث مباشر · قرّاء وتفسير وسيرة", "app.noortv.radio"},
                    {"الورد اليومي", "ختمة كل شهر", "app.noortv.wird"},
                    {"حديث اليوم", "حديث مع شرحه وفوائده", "app.noortv.hadith"},
                    {"مُحفّظ القرآن", "حفظ ومراجعة", "app.noortv.hifz"},
                    {"سورة الكهف", "تلاوة كاملة", "app.noortv.kahf"},
                    {"سورة البقرة", "تلاوة كاملة", "app.noortv.baqara"},
                    {"سورة يس", "تلاوة كاملة", "app.noortv.yasin"},
                    {"سورة الملك", "تلاوة كاملة", "app.noortv.mulk"},
                    {"أذكار الصباح", "", "app.noortv.sabah"},
                    {"أذكار المساء", "", "app.noortv.masa"},
                    {"أذكار النوم", "", "app.noortv.nawm"},
                    {"الرقية الشرعية", "", "app.noortv.ruqya"},
                    {"مواقيت الأسبوع", "أوقات الصلاة لسبعة أيام", "app.noortv.week"},
            };
        }
    }

    /** «البيت»: the solar system, the internet and the power, and the control center. */
    public static class House extends FolderActivity {
        @Override
        String title() {
            return "البيت والتحكم";
        }

        @Override
        String[][] items() {
            return new String[][]{
                    {"الطاقة الشمسية", "الألواح والبطارية والاستهلاك الآن واليوم", ".EnergyActivity"},
                    {"حالة البيت", "الإنترنت والكهرباء وسجل الانقطاعات", ".StatusActivity"},
                    {"مركز التحكم", "إعدادات الأذان والتذكيرات", "app.noortv.control"},
            };
        }
    }
}
