package app.noortv;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;

/**
 * حديث اليوم: one hadith a day from the Encyclopedia of Translated Prophetic Hadiths (hadeethenc.com), which only
 * holds hadiths graded sahih or hasan, each with its source, its grade, a scholar's explanation, the meanings of
 * hard words and the lessons drawn from it. Days go round manners and virtues, the Quran, the sira and belief.
 * Right / Left: the next or the previous day's; Up / Down: read on; Back closes. Each one is kept on the TV.
 */
public class HadithActivity extends Activity {
    private static final int GOLD = 0xFFE3BE6E, TEXT = 0xFFF4EEDF, SOFT = 0xCCF4EEDF;
    /** الفضائل والآداب, القرآن الكريم وعلومه, السيرة والتاريخ, العقيدة. */
    private static final int[] CATEGORIES = {5, 1, 5, 7, 5, 3};
    private static final int PER_PAGE = 50;

    private final Handler h = new Handler(Looper.getMainLooper());
    private ScrollView scroll;
    private TextView title, body;
    private int dayOffset;
    private Typeface naskh;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        File f = new File("/system/fonts/NotoNaskhArabic-Regular.ttf");
        naskh = f.exists() ? Typeface.createFromFile(f) : Typeface.SERIF;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(110), dp(24), dp(110), dp(14));
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF14302A, 0xFF13233A, 0xFF090B12}));
        title = text(26, GOLD, true);
        root.addView(title);
        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.setFocusable(false);
        body = text(26, TEXT, false);
        body.setTypeface(naskh);
        body.setLineSpacing(0, 1.45f);
        body.setGravity(Gravity.START);
        body.setTextDirection(View.TEXT_DIRECTION_RTL);
        body.setPadding(0, dp(12), 0, dp(20));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView help = text(15, 0x80F4EEDF, false);
        help.setText("⬆⬇ للقراءة   ·   ◀ ▶ حديث يوم تاني   ·   المصدر: الموسوعة الحديثية المترجمة (hadeethenc.com)");
        root.addView(help);
        setContentView(root);
        show();
    }

    private void show() {
        int day = (int) LocalDate.now().toEpochDay() + dayOffset;
        title.setText("📜 حديث اليوم" + (dayOffset == 0 ? "" : dayOffset < 0 ? "  ·  من " + (-dayOffset) + " يوم" : "  ·  بعد " + dayOffset + " يوم"));
        body.setText("…");
        scroll.scrollTo(0, 0);
        new Thread(() -> {
            JSONObject hadith = forDay(day);
            h.post(() -> {
                if (day != (int) LocalDate.now().toEpochDay() + dayOffset) return;
                if (hadith == null) body.setText("ما قدرت جيب الحديث · تأكد من النت وارجع افتحه");
                else render(hadith);
            });
        }, "hadith").start();
    }

    /** The day's hadith: a category by the day, then a place in it spread over the year; sahih or hasan only. */
    private JSONObject forDay(int day) {
        int cat = CATEGORIES[Math.floorMod(day, CATEGORIES.length)];
        int round = Math.floorDiv(day, CATEGORIES.length);
        try {
            JSONObject first = json("list_" + cat + "_1.json", "https://hadeethenc.com/api/v1/hadeeths/list/?language=ar&category_id="
                    + cat + "&page=1&per_page=" + PER_PAGE);
            int total = first.getJSONObject("meta").getInt("total_items");
            if (total <= 0) return null;
            for (int tries = 0; tries < 5; tries++) {
                int i = Math.floorMod(round * 37 + tries, total);
                int page = i / PER_PAGE + 1;
                JSONObject list = page == 1 ? first : json("list_" + cat + "_" + page + ".json",
                        "https://hadeethenc.com/api/v1/hadeeths/list/?language=ar&category_id=" + cat + "&page=" + page + "&per_page=" + PER_PAGE);
                JSONArray data = list.getJSONArray("data");
                if (i % PER_PAGE >= data.length()) continue;
                String id = data.getJSONObject(i % PER_PAGE).getString("id");
                JSONObject one = json("hadith_" + id + ".json", "https://hadeethenc.com/api/v1/hadeeths/one/?language=ar&id=" + id);
                String grade = one.optString("grade");
                if (grade.contains("صحيح") || grade.contains("حسن")) return one;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void render(JSONObject o) {
        SpannableStringBuilder s = new SpannableStringBuilder();
        section(s, o.optString("title"), GOLD, 1.0f, true);
        s.append("\n\n");
        int a = s.length();
        s.append(o.optString("hadeeth").trim());
        s.setSpan(new RelativeSizeSpan(1.12f), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.append("\n");
        section(s, "📚 " + o.optString("attribution") + "   ·   الدرجة: " + o.optString("grade"), SOFT, 0.7f, false);
        String expl = o.optString("explanation").trim();
        if (!expl.isEmpty()) {
            s.append("\n\n");
            section(s, "💡 الشرح", GOLD, 0.85f, true);
            s.append("\n");
            int e = s.length();
            s.append(expl);
            s.setSpan(new RelativeSizeSpan(0.85f), e, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        JSONArray words = o.optJSONArray("words_meanings");
        if (words != null && words.length() > 0) {
            s.append("\n\n");
            section(s, "🔤 معاني الكلمات", GOLD, 0.85f, true);
            for (int i = 0; i < words.length(); i++) {
                JSONObject w = words.optJSONObject(i);
                if (w == null) continue;
                s.append("\n");
                int k = s.length();
                s.append("• ").append(w.optString("word")).append(": ").append(w.optString("meaning"));
                s.setSpan(new RelativeSizeSpan(0.8f), k, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        JSONArray hints = o.optJSONArray("hints");
        if (hints != null && hints.length() > 0) {
            s.append("\n\n");
            section(s, "🌱 من فوائد الحديث", GOLD, 0.85f, true);
            for (int i = 0; i < hints.length(); i++) {
                s.append("\n");
                int k = s.length();
                s.append("• ").append(hints.optString(i).trim());
                s.setSpan(new RelativeSizeSpan(0.8f), k, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        body.setText(s);
        scroll.scrollTo(0, 0);
    }

    private static void section(SpannableStringBuilder s, String text, int color, float size, boolean bold) {
        int a = s.length();
        s.append(text);
        s.setSpan(new ForegroundColorSpan(color), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(size), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) s.setSpan(new StyleSpan(Typeface.BOLD), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** From the TV's copy, or from the site (then kept). */
    private JSONObject json(String file, String url) throws Exception {
        File cache = new File(getFilesDir(), file);
        if (cache.exists()) return new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String s = b.toString("UTF-8");
            JSONObject o = new JSONObject(s);
            try (FileOutputStream out = new FileOutputStream(cache)) {
                out.write(s.getBytes(StandardCharsets.UTF_8));
            }
            return o;
        } finally {
            c.disconnect();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                scroll.smoothScrollBy(0, dp(160));
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                scroll.smoothScrollBy(0, -dp(160));
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                dayOffset++;
                show();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                dayOffset--;
                show();
                return true;
        }
        return super.onKeyDown(code, e);
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
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
