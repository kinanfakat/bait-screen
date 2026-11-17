package app.noortv;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * الورد اليومي: the mushaf page by page (the 604 pages of the Madinah mushaf), text from api.alquran.cloud
 * (quran-simple), each page kept on the TV once read.
 *
 * Up to five readers in the family, each with a name and a symbol, and each with their own page, today's wird
 * (20 pages: a khatma a month) and khatmas. It opens on «مين بدو يقرأ؟»; OK on a name opens their page, holding OK
 * (or Menu) renames, changes the symbol or removes a reader.
 *
 * The whole page fits on the screen. Down (or Right, OK) turns to the next page like a book, Up (or Left) back;
 * Back returns to the readers.
 */
public class WirdActivity extends Activity {
    private static final int PAGES = 604;
    private static final int DAILY = 20;
    private static final int MAX_READERS = 5;
    private static final int INK = 0xFF2B2116;
    private static final int GOLD = 0xFFB0893A;
    private static final int LIGHT_GOLD = 0xFFE3BE6E;
    private static final int PAPER = 0xFFF8F1DE;
    private static final String BASMALA = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ";
    private static final String[] ICONS = {"🌙", "⭐", "🌿", "🕌", "🌸", "🦁", "🐎", "🌻", "🕊️", "📖", "🌴", "💎", "🍀", "☀️"};

    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Typeface naskh;

    // the reader
    private FrameLayout readerRoot;
    private LinearLayout sheet;
    private ImageView flip;
    private ScrollView scroll;
    private TextView header, body, footer;
    private int rid, page;
    private boolean reading, turning;

    // the ayahs of the page on screen: {start, end in the text, surah, ayah}, and choosing one of them
    private final List<int[]> ranges = new ArrayList<>();
    private final List<String> ayahText = new ArrayList<>();
    private SpannableStringBuilder pageText;
    private String pageSurahs = "";
    private int sel = -1;
    private android.text.style.BackgroundColorSpan selSpan;
    // the tafsir / translation panel over the page, and an ayah's recitation
    private FrameLayout panel;
    private ScrollView panelScroll;
    private TextView panelText;
    private android.media.MediaPlayer audio;

    // the readers' screen
    private FrameLayout pickerRoot;
    private LinearLayout cards;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("wird", MODE_PRIVATE);
        migrate();
        File f = new File("/system/fonts/NotoNaskhArabic-Regular.ttf");
        naskh = f.exists() ? Typeface.createFromFile(f) : Typeface.SERIF;
        buildReader();
        buildPicker();
        showPicker();
    }

    // ------------------------------------------------------------------ readers

    /** The single reader of older builds becomes reader 1, with the page and progress they had. */
    private void migrate() {
        if (prefs.contains("readers")) return;
        prefs.edit()
                .putString("readers", "1").putInt("nextId", 2)
                .putString("r1_name", "1").putString("r1_icon", "📖")
                .putInt("r1_page", prefs.getInt("page", 1))
                .putString("r1_day", prefs.getString("day", ""))
                .putInt("r1_dayStart", prefs.getInt("dayStart", prefs.getInt("page", 1)))
                .putInt("r1_khatmat", prefs.getInt("khatmat", 0))
                .apply();
    }

    private List<Integer> readers() {
        List<Integer> ids = new ArrayList<>();
        for (String s : prefs.getString("readers", "").split(",")) {
            try {
                ids.add(Integer.parseInt(s.trim()));
            } catch (Exception ignored) {
            }
        }
        return ids;
    }

    private void saveReaders(List<Integer> ids) {
        StringBuilder b = new StringBuilder();
        for (int id : ids) b.append(b.length() == 0 ? "" : ",").append(id);
        prefs.edit().putString("readers", b.toString()).apply();
    }

    private String key(int id, String k) {
        return "r" + id + "_" + k;
    }

    private String name(int id) {
        return prefs.getString(key(id, "name"), String.valueOf(id));
    }

    private String icon(int id) {
        return prefs.getString(key(id, "icon"), "📖");
    }

    /**
     * Pages read today by this reader: only pages turned one by one forward count, so a jump of ten pages or «روح
     * لسورة» is not reading. A new day starts again from zero.
     */
    private int doneToday(int id) {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        if (!today.equals(prefs.getString(key(id, "day"), ""))) {
            prefs.edit().putString(key(id, "day"), today).putInt(key(id, "read"), 0).apply();
        } else if (!prefs.contains(key(id, "read"))) {
            // older builds counted from the day's first page: keep today's count once
            int p = prefs.getInt(key(id, "page"), 1);
            prefs.edit().putInt(key(id, "read"), Math.max(0, Math.min(DAILY, p - prefs.getInt(key(id, "dayStart"), p)))).apply();
        }
        return prefs.getInt(key(id, "read"), 0);
    }

    private void countRead() {
        doneToday(rid);
        prefs.edit().putInt(key(rid, "read"), prefs.getInt(key(rid, "read"), 0) + 1).apply();
    }

    // ------------------------------------------------------------------ the readers' screen

    private void buildPicker() {
        pickerRoot = new FrameLayout(this);
        pickerRoot.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0F2A22, 0xFF102036, 0xFF080C14}));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.addView(plain(38, LIGHT_GOLD, true, "📖 الورد اليومي · مين بدو يقرأ؟"));
        TextView sub = plain(17, 0xAAF4EEDF, false,
                "كل واحد إله صفحته وورده · اضغط مطوّل على الاسم (أو زر القائمة) للتعديل");
        sub.setPadding(0, dp(6), 0, dp(34));
        col.addView(sub);
        cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        cards.setGravity(Gravity.CENTER);
        cards.setClipChildren(false);
        cards.setPadding(0, dp(16), 0, dp(16));
        col.setClipChildren(false);
        col.addView(cards);
        pickerRoot.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void showPicker() {
        reading = false;
        cards.removeAllViews();
        List<Integer> ids = readers();
        int last = prefs.getInt("lastReader", ids.isEmpty() ? 0 : ids.get(0));
        View focus = null;
        for (int id : ids) {
            int done = doneToday(id);
            String progress = done >= DAILY ? "ورد اليوم ✓" : "اليوم " + done + " / " + DAILY;
            View c = card(icon(id), name(id), "صفحة " + prefs.getInt(key(id, "page"), 1) + "\n" + progress);
            c.setOnClickListener(v -> openReader(id));
            c.setOnLongClickListener(v -> {
                manage(id);
                return true;
            });
            c.setTag(id);
            cards.addView(c, 0);   // each one left of the one before: the first reader on the right
            if (id == last || focus == null) focus = c;
        }
        if (ids.size() < MAX_READERS) {
            View add = card("➕", "قارئ جديد", "حتى " + MAX_READERS + " أشخاص");
            add.setOnClickListener(v -> addReader());
            cards.addView(add, 0);
            if (focus == null) focus = add;
        }
        setContentView(pickerRoot);
        View f = focus;
        main.post(f::requestFocus);
    }

    private View card(String icon, String name, String info) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(18), dp(16), dp(18), dp(16));
        c.setFocusable(true);
        c.setClickable(true);
        c.setLongClickable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x22FFFFFF);
        bg.setStroke(dp(2), 0x33FFFFFF);
        c.setBackground(bg);
        c.addView(plain(54, Color.WHITE, false, icon));
        TextView n = plain(24, LIGHT_GOLD, true, name);
        n.setSingleLine(true);
        c.addView(n);
        c.addView(plain(15, 0xCCF4EEDF, false, info));
        c.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x55E3BE6E : 0x22FFFFFF);
            bg.setStroke(dp(has ? 3 : 2), has ? LIGHT_GOLD : 0x33FFFFFF);
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).setDuration(150).start();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(170), dp(210));
        lp.setMargins(dp(14), 0, dp(14), 0);
        c.setLayoutParams(lp);
        return c;
    }

    private void addReader() {
        List<Integer> ids = readers();
        if (ids.size() >= MAX_READERS) return;
        int id = prefs.getInt("nextId", ids.size() + 1);
        // the name starts as the reader's number; OK keeps it, or type a name
        askName(String.valueOf(ids.size() + 1), name -> askIcon(icon -> {
            ids.add(id);
            saveReaders(ids);
            prefs.edit().putInt("nextId", id + 1)
                    .putString(key(id, "name"), name).putString(key(id, "icon"), icon)
                    .putInt(key(id, "page"), 1).putInt("lastReader", id).apply();
            showPicker();
        }));
    }

    private void manage(int id) {
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(icon(id) + "  " + name(id))
                .setItems(new String[]{"✏️ تغيير الاسم", "🎨 تغيير الشكل", "🗑 حذف القارئ"}, (d, which) -> {
                    if (which == 0) {
                        askName(name(id), n -> {
                            prefs.edit().putString(key(id, "name"), n).apply();
                            showPicker();
                        });
                    } else if (which == 1) {
                        askIcon(i -> {
                            prefs.edit().putString(key(id, "icon"), i).apply();
                            showPicker();
                        });
                    } else {
                        List<Integer> ids = readers();
                        if (ids.size() <= 1) {
                            Toast.makeText(this, "لازم يضل قارئ واحد على الأقل", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setTitle("حذف " + name(id) + " وصفحته؟")
                                .setPositiveButton("حذف", (d2, w2) -> {
                                    ids.remove(Integer.valueOf(id));
                                    saveReaders(ids);
                                    SharedPreferences.Editor e = prefs.edit();
                                    for (String k : new String[]{"name", "icon", "page", "day", "dayStart", "read", "khatmat", "marks"}) {
                                        e.remove(key(id, k));
                                    }
                                    e.apply();
                                    showPicker();
                                })
                                .setNegativeButton("لا", null)
                                .show();
                    }
                })
                .show();
    }

    private interface Got<T> {
        void got(T t);
    }

    private void askName(String start, Got<String> then) {
        EditText e = new EditText(this);
        e.setText(start);
        e.setSelectAllOnFocus(true);
        e.setSingleLine(true);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        e.setTextDirection(View.TEXT_DIRECTION_RTL);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(e);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("الاسم (أو خلّيه رقم)")
                .setView(box)
                .setPositiveButton("تم", (d, w) -> {
                    String n = e.getText().toString().trim();
                    then.got(n.isEmpty() ? start : n.length() > 14 ? n.substring(0, 14) : n);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void askIcon(Got<String> then) {
        String[] items = new String[ICONS.length + 1];
        items[0] = "🎲  عشوائي";
        System.arraycopy(ICONS, 0, items, 1, ICONS.length);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("اختر شكلك")
                .setItems(items, (d, which) -> then.got(which == 0 ? ICONS[new Random().nextInt(ICONS.length)] : ICONS[which - 1]))
                .show();
    }

    // ------------------------------------------------------------------ the mushaf page

    private void buildReader() {
        readerRoot = new FrameLayout(this);
        readerRoot.setBackgroundColor(0xFF0E1A14);

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable paper = new GradientDrawable();
        paper.setColor(PAPER);
        paper.setCornerRadius(dp(10));
        paper.setStroke(dp(3), GOLD);
        sheet.setBackground(paper);
        sheet.setPadding(dp(30), dp(10), dp(30), dp(8));
        int w = getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (w * 0.92f), ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(10);
        readerRoot.addView(sheet, lp);

        header = text(15, GOLD);
        header.setGravity(Gravity.CENTER);
        sheet.addView(header);
        View rule = new View(this);
        rule.setBackgroundColor(GOLD);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        rp.topMargin = dp(4);
        rp.bottomMargin = dp(4);
        sheet.addView(rule, rp);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        body = text(24, INK);
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0, 1.32f);
        body.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD);
        scroll.addView(body, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL));
        sheet.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        footer = text(14, 0xFF6B5A3A);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(4), 0, 0);
        sheet.addView(footer);

        // the turning page: a picture of the page that was, folding away over the new one
        flip = new ImageView(this);
        flip.setVisibility(View.GONE);
        flip.setCameraDistance(12000 * getResources().getDisplayMetrics().density);
        readerRoot.addView(flip, new FrameLayout.LayoutParams(lp));

        // the tafsir / translation panel
        panel = new FrameLayout(this);
        panel.setBackgroundColor(0xB3000000);
        panel.setVisibility(View.GONE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cg = new GradientDrawable();
        cg.setColor(0xF2122A20);
        cg.setCornerRadius(dp(16));
        cg.setStroke(dp(2), LIGHT_GOLD);
        card.setBackground(cg);
        card.setPadding(dp(32), dp(20), dp(32), dp(12));
        panelScroll = new ScrollView(this);
        panelScroll.setVerticalScrollBarEnabled(true);
        panelText = text(22, 0xFFF4EEDF);
        panelText.setLineSpacing(0, 1.3f);
        panelScroll.addView(panelText);
        card.addView(panelScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView hint = plain(14, 0x99F4EEDF, false, "⬆⬇ للقراءة   ·   OK أو رجوع: إغلاق");
        hint.setPadding(0, dp(8), 0, 0);
        card.addView(hint);
        panel.addView(card, new FrameLayout.LayoutParams((int) (w * 0.72f), (int) (getResources().getDisplayMetrics().heightPixels * 0.82f),
                Gravity.CENTER));
        readerRoot.addView(panel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void openReader(int id) {
        rid = id;
        prefs.edit().putInt("lastReader", id).apply();
        page = prefs.getInt(key(id, "page"), 1);
        reading = true;
        setContentView(readerRoot);
        show();
    }

    private void show() {
        doneToday(rid);
        prefs.edit().putInt(key(rid, "page"), page).apply();
        updateFooter();
        int p = page;
        JSONObject cached = cached(p);
        if (cached != null) {
            render(cached);
            prefetch(p);
            return;
        }
        body.setText("…");
        header.setText("");
        new Thread(() -> {
            JSONObject data = load(p);
            main.post(() -> {
                if (p != page) return;
                if (data == null) {
                    body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                    body.setText("ما قدرت أجيب الصفحة " + p + "\nتأكد من النت واضغط OK للمحاولة");
                    return;
                }
                render(data);
                prefetch(p);
            });
        }, "wird").start();
    }

    /** The pages around this one, fetched quietly so the next turn is instant. */
    private void prefetch(int p) {
        new Thread(() -> {
            for (int q : new int[]{p + 1, p + 2, p - 1}) {
                if (q >= 1 && q <= PAGES && cached(q) == null) load(q);
            }
        }, "wird-next").start();
    }

    private void render(JSONObject data) {
        try {
            JSONArray ayahs = data.getJSONArray("ayahs");
            SpannableStringBuilder s = new SpannableStringBuilder();
            ranges.clear();
            ayahText.clear();
            sel = -1;
            selSpan = null;
            JSONObject first = ayahs.getJSONObject(0);
            String surahs = "";
            for (int i = 0; i < ayahs.length(); i++) {
                JSONObject a = ayahs.getJSONObject(i);
                String text = a.getString("text").replace("﻿", "").trim();
                int n = a.getInt("numberInSurah");
                JSONObject surah = a.getJSONObject("surah");
                String name = surah.getString("name").replaceAll("[ۖ-ۭـ]", "");
                int surahNo = surah.getInt("number");
                if (!surahs.contains(name)) surahs += (surahs.isEmpty() ? "" : " · ") + name;
                if (n == 1) {
                    // a new surah: its name as a heading, then the basmala on its own line (not for At-Tawbah)
                    if (s.length() > 0) s.append("\n");
                    int st = s.length();
                    s.append("❁ ").append(name).append(" ❁");
                    s.setSpan(new ForegroundColorSpan(GOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.setSpan(new StyleSpan(Typeface.BOLD), st, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    s.append("\n");
                    if (surahNo != 9 && surahNo != 1 && text.startsWith(BASMALA)) {
                        text = text.substring(BASMALA.length()).trim();
                        int bs = s.length();
                        s.append(BASMALA).append("\n");
                        s.setSpan(new RelativeSizeSpan(0.9f), bs, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if (s.length() > 0) {
                    s.append(' ');
                }
                int as = s.length();
                String clean = text.replace("۞", "").trim();
                s.append(clean).append(' ');
                int ms = s.length();
                s.append("﴿").append(ayahDigits(n)).append("﴾");
                s.setSpan(new ForegroundColorSpan(GOLD), ms, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new RelativeSizeSpan(0.8f), ms, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                ranges.add(new int[]{as, s.length(), surahNo, n});
                ayahText.add(clean);
            }
            header.setText("الجزء " + first.getInt("juz") + "   ·   " + surahs);
            pageSurahs = surahs;
            pageText = s;
            body.setText(s);
            fit();
        } catch (Exception e) {
            body.setText("صار خطأ بقراءة الصفحة");
        }
    }

    /** The largest text size at which the whole page fits on the screen, so nothing has to scroll. */
    private void fit() {
        if (scroll.getHeight() == 0) {
            scroll.post(this::fit);
            return;
        }
        int w = scroll.getWidth() - body.getPaddingLeft() - body.getPaddingRight();
        int h = scroll.getHeight() - dp(4);
        float px = getResources().getDisplayMetrics().scaledDensity;
        TextPaint tp = new TextPaint(body.getPaint());
        CharSequence t = body.getText();
        float lo = 14 * px, hi = 48 * px;
        for (int i = 0; i < 14; i++) {
            float mid = (lo + hi) / 2;
            tp.setTextSize(mid);
            StaticLayout l = StaticLayout.Builder.obtain(t, 0, t.length(), tp, w)
                    .setLineSpacing(0, 1.32f)
                    .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
                    .build();
            if (l.getHeight() <= h) lo = mid;
            else hi = mid;
        }
        body.setTextSize(TypedValue.COMPLEX_UNIT_PX, lo);
        scroll.scrollTo(0, 0);
    }

    private void updateFooter() {
        int done = doneToday(rid);
        String today = done >= DAILY ? "أتممت ورد اليوم ✓ تقبّل الله" : "ورد اليوم: " + done + " من " + DAILY + " صفحة";
        footer.setText(icon(rid) + " " + name(rid) + "     ·     صفحة " + page + " من " + PAGES + "     ·     " + today
                + "     ·     ⬇⬆ صفحة   ◀▶ 10 صفحات   OK خيارات");
    }

    /** Next (dir 1) or previous (-1) page, turned like a book's page. */
    private void turn(int dir) {
        if (turning) return;
        int to = page + dir;
        boolean khatma = to > PAGES;
        if (khatma) to = 1;
        if (to < 1) return;
        if (dir > 0) countRead();   // a page read: the only thing today's wird counts
        goTo(to, dir, khatma);
    }

    /** Ten pages (or any number) forward or back, stopping at the first and last page. */
    private void jump(int by) {
        int to = Math.max(1, Math.min(PAGES, page + by));
        if (to != page) goTo(to, by > 0 ? 1 : -1, false);
    }

    private void goTo(int to, int dir, boolean khatma) {
        if (turning || to < 1 || to > PAGES) return;
        stopAudio();
        Bitmap shot = null;
        if (sheet.getWidth() > 0) {
            try {
                shot = Bitmap.createBitmap(sheet.getWidth(), sheet.getHeight(), Bitmap.Config.ARGB_8888);
                sheet.draw(new Canvas(shot));
            } catch (Throwable t) {
                shot = null;
            }
        }
        page = to;
        if (khatma) {
            // khatma done: start again from the first page, and the day counts from there
            prefs.edit().putInt(key(rid, "dayStart"), 1).putInt(key(rid, "page"), 1)
                    .putInt(key(rid, "khatmat"), prefs.getInt(key(rid, "khatmat"), 0) + 1).apply();
        }
        show();
        if (khatma) footer.setText("ختمت القرآن 🤍 تقبّل الله منك · بدأت ختمة جديدة");
        if (shot == null) return;
        Bitmap old = shot;
        flip.setImageBitmap(old);
        flip.setVisibility(View.VISIBLE);
        flip.setAlpha(1f);
        flip.setRotationY(0);
        // an Arabic book: the next page lifts from the left edge and folds over to the right
        flip.setPivotX(dir > 0 ? sheet.getWidth() : 0);
        flip.setPivotY(sheet.getHeight() / 2f);
        turning = true;
        flip.animate().rotationY(dir > 0 ? -90 : 90).alpha(0.55f).setDuration(520)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        flip.setVisibility(View.GONE);
                        flip.setImageBitmap(null);
                        flip.setRotationY(0);
                        old.recycle();
                        turning = false;
                    }
                }).start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (!reading) {
            if (code == KeyEvent.KEYCODE_MENU) {
                View f = getCurrentFocus();
                if (f != null && f.getTag() instanceof Integer) manage((Integer) f.getTag());
                return true;
            }
            return super.onKeyDown(code, e);
        }
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        // Back always reaches onBackPressed: it closes the panel, then the ayah choice, then the page
        if (code == KeyEvent.KEYCODE_BACK) return super.onKeyDown(code, e);
        if (panel.getVisibility() == View.VISIBLE) {
            // the tafsir panel: Up/Down read on, OK closes it
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) panelScroll.smoothScrollBy(0, dp(140));
            else if (code == KeyEvent.KEYCODE_DPAD_UP) panelScroll.smoothScrollBy(0, -dp(140));
            else if (ok) closePanel();
            return true;
        }
        if (sel >= 0) {
            // choosing an ayah: the arrows move along the page, OK opens what can be done with it
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT) select(Math.min(ranges.size() - 1, sel + 1));
            else if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT) select(Math.max(0, sel - 1));
            else if (ok) ayahMenu();
            return true;
        }
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                // a page too long to fit (never on a Madinah page) scrolls first
                if (scroll.canScrollVertically(1)) scroll.smoothScrollBy(0, dp(160));
                else turn(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (scroll.canScrollVertically(-1)) scroll.smoothScrollBy(0, -dp(160));
                else turn(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                jump(10);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                jump(-10);
                return true;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                turn(1);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                turn(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MENU:
                if (body.getText().toString().startsWith("ما قدرت")) show();
                else menu();
                return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() {
        if (reading && panel.getVisibility() == View.VISIBLE) closePanel();
        else if (reading && sel >= 0) select(-1);
        else if (reading) {
            stopAudio();
            showPicker();
        } else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAudio();
    }

    // ------------------------------------------------------------------ OK: the options

    private void menu() {
        List<String> marks = marks();
        String[] items = {
                "📖 تفسير · ترجمة · استماع لآية من هالصفحة",
                "🔖 حط علامة على هالصفحة",
                "📑 علاماتي (" + marks.size() + ")",
                "🕌 روح لسورة",
                "📚 روح لجزء",
                "🔢 روح لصفحة",
                "👤 تغيير القارئ"};
        dialog().setTitle("صفحة " + page + " · " + pageSurahs)
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0:
                            if (!ranges.isEmpty()) select(0);
                            break;
                        case 1:
                            addMark(page, pageSurahs + " · صفحة " + page);
                            break;
                        case 2:
                            showMarks();
                            break;
                        case 3:
                            pickSurah();
                            break;
                        case 4:
                            pickJuz();
                            break;
                        case 5:
                            askPage();
                            break;
                        default:
                            stopAudio();
                            showPicker();
                    }
                }).show();
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void pickSurah() {
        String[] items = new String[114];
        for (int i = 0; i < 114; i++) {
            items[i] = (i + 1) + ".  " + QuranIndex.NAMES[i] + "   ·   " + QuranIndex.AYAHS[i] + " آية   ·   ص " + QuranIndex.SURAH_PAGE[i];
        }
        AlertDialog d = dialog().setTitle("🕌 السور").setItems(items, (x, w) -> goTo(QuranIndex.SURAH_PAGE[w], 1, false)).create();
        d.show();
        // start the list at the surah you are reading
        int cur = 0;
        for (int i = 0; i < 114; i++) if (QuranIndex.SURAH_PAGE[i] <= page) cur = i;
        int at = cur;
        d.getListView().post(() -> d.getListView().setSelection(at));
    }

    private void pickJuz() {
        String[] items = new String[30];
        for (int i = 0; i < 30; i++) items[i] = "الجزء " + (i + 1) + "   ·   ص " + QuranIndex.JUZ_PAGE[i];
        AlertDialog d = dialog().setTitle("📚 الأجزاء").setItems(items, (x, w) -> goTo(QuranIndex.JUZ_PAGE[w], 1, false)).create();
        d.show();
        int cur = 0;
        for (int i = 0; i < 30; i++) if (QuranIndex.JUZ_PAGE[i] <= page) cur = i;
        int at = cur;
        d.getListView().post(() -> d.getListView().setSelection(at));
    }

    private void askPage() {
        EditText e = new EditText(this);
        e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        e.setHint("1 - " + PAGES);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(e);
        dialog().setTitle("🔢 رقم الصفحة").setView(box)
                .setPositiveButton("روح", (d, w) -> {
                    try {
                        int p = Integer.parseInt(e.getText().toString().trim());
                        if (p >= 1 && p <= PAGES && p != page) goTo(p, p > page ? 1 : -1, false);
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("إلغاء", null).show();
    }

    // ------------------------------------------------------------------ bookmarks (each reader has their own)

    private List<String> marks() {
        List<String> l = new ArrayList<>();
        for (String s : prefs.getString(key(rid, "marks"), "").split("\n")) if (s.contains("|")) l.add(s);
        return l;
    }

    private void saveMarks(List<String> l) {
        prefs.edit().putString(key(rid, "marks"), String.join("\n", l)).apply();
    }

    private void addMark(int p, String label) {
        List<String> l = marks();
        l.add(0, p + "|" + label.replace("\n", " ").replace("|", " "));
        while (l.size() > 40) l.remove(l.size() - 1);
        saveMarks(l);
        Toast.makeText(this, "🔖 انحفظت العلامة: " + label, Toast.LENGTH_SHORT).show();
    }

    private void showMarks() {
        List<String> l = marks();
        if (l.isEmpty()) {
            Toast.makeText(this, "ما في علامات لسا · OK ← «حط علامة»", Toast.LENGTH_LONG).show();
            return;
        }
        String[] items = new String[l.size()];
        for (int i = 0; i < l.size(); i++) items[i] = "🔖 " + l.get(i).substring(l.get(i).indexOf('|') + 1);
        dialog().setTitle("📑 علامات " + name(rid)).setItems(items, (d, w) -> {
            String m = l.get(w);
            int p = Integer.parseInt(m.substring(0, m.indexOf('|')));
            dialog().setTitle(items[w]).setItems(new String[]{"↩ روح لهون", "🗑 امسح العلامة"}, (d2, w2) -> {
                if (w2 == 0) {
                    if (p != page) goTo(p, p > page ? 1 : -1, false);
                } else {
                    l.remove(w);
                    saveMarks(l);
                }
            }).show();
        }).show();
    }

    // ------------------------------------------------------------------ an ayah: tafsir, translation, recitation

    private void select(int i) {
        if (pageText == null) return;
        if (selSpan != null) pageText.removeSpan(selSpan);
        selSpan = null;
        sel = i;
        if (i >= 0 && i < ranges.size()) {
            selSpan = new android.text.style.BackgroundColorSpan(0x66E3BE6E);
            pageText.setSpan(selSpan, ranges.get(i)[0], ranges.get(i)[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            footer.setText("اختر الآية بالأسهم   ·   OK: تفسير وترجمة واستماع   ·   رجوع: إلغاء");
        } else {
            sel = -1;
            selSpan = null;
            updateFooter();
        }
        body.setText(pageText);
    }

    private void ayahMenu() {
        int[] r = ranges.get(sel);
        String title = "سورة " + QuranIndex.NAMES[r[2] - 1] + " · الآية " + r[3];
        String[] items = {"📗 التفسير الميسّر", "📘 تفسير السعدي", "🔤 معاني الكلمات (الجلالين)", "🌐 الترجمة الإنجليزية",
                "🔊 استمع للآية (العفاسي)", "🔖 حط علامة على هالآية"};
        dialog().setTitle(title).setItems(items, (d, w) -> {
            String k = r[2] + ":" + r[3];
            switch (w) {
                case 0:
                    explain(title + " · الميسّر", "muyassar_" + k, () -> alquran(k, "ar.muyassar"));
                    break;
                case 1:
                    explain(title + " · السعدي", "saadi_" + k, () -> saadi(k));
                    break;
                case 2:
                    // al-Jalalayn quotes each word or phrase «like this» and explains it: one per line
                    explain(title + " · معاني الكلمات", "jalalayn_" + k,
                            () -> alquran(k, "ar.jalalayn").replace("«", "\n\n«").trim());
                    break;
                case 3:
                    explain(title + " · English", "sahih_" + k, () -> alquran(k, "en.sahih"));
                    break;
                case 4:
                    play(r[2], r[3]);
                    break;
                default:
                    addMark(page, "سورة " + QuranIndex.NAMES[r[2] - 1] + " · آية " + r[3] + " · صفحة " + page);
            }
        }).show();
    }

    private interface Fetch {
        String get() throws Exception;
    }

    /** The panel over the page: the ayah, then its tafsir or translation (kept on the TV once fetched). */
    private void explain(String title, String cacheKey, Fetch fetch) {
        String ayah = ayahText.get(sel);
        panel.setVisibility(View.VISIBLE);
        panelText.setText(title + "\n\n﴿" + ayah + "﴾\n\n…");
        panelScroll.scrollTo(0, 0);
        new Thread(() -> {
            File f = new File(getFilesDir(), "tafsir_" + cacheKey.replace(':', '_') + ".txt");
            String text;
            try {
                if (f.exists()) {
                    text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                } else {
                    text = fetch.get();
                    try (FileOutputStream out = new FileOutputStream(f)) {
                        out.write(text.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception e) {
                text = "ما قدرت أجيبها · تأكد من النت وجرب كمان مرة";
            }
            String t = text;
            main.post(() -> {
                SpannableStringBuilder s = new SpannableStringBuilder();
                s.append(title);
                s.setSpan(new ForegroundColorSpan(LIGHT_GOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new StyleSpan(Typeface.BOLD), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.append("\n\n");
                int a = s.length();
                s.append("﴿").append(ayah).append("﴾");
                s.setSpan(new ForegroundColorSpan(0xFFF1E3B8), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.setSpan(new RelativeSizeSpan(1.1f), a, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s.append("\n\n").append(t);
                panelText.setTextDirection(title.endsWith("English") ? View.TEXT_DIRECTION_FIRST_STRONG : View.TEXT_DIRECTION_RTL);
                panelText.setText(s);
                panelScroll.scrollTo(0, 0);
            });
        }, "tafsir").start();
    }

    private void closePanel() {
        panel.setVisibility(View.GONE);
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    private static String alquran(String key, String edition) throws Exception {
        return new JSONObject(get("https://api.alquran.cloud/v1/ayah/" + key + "/" + edition)).getJSONObject("data").getString("text");
    }

    /** Tafsir as-Sa'di from quran.com (tafsir 91), its HTML made plain text. */
    private static String saadi(String key) throws Exception {
        String html = new JSONObject(get("https://api.quran.com/api/v4/tafsirs/91/by_ayah/" + key))
                .getJSONObject("tafsir").getString("text");
        return android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private void play(int surah, int ayah) {
        stopAudio();
        try {
            audio = new android.media.MediaPlayer();
            audio.setDataSource(String.format(Locale.US, "https://everyayah.com/data/Alafasy_128kbps/%03d%03d.mp3", surah, ayah));
            audio.setOnPreparedListener(android.media.MediaPlayer::start);
            audio.setOnCompletionListener(mp -> stopAudio());
            audio.setOnErrorListener((mp, w, x) -> {
                stopAudio();
                return true;
            });
            audio.prepareAsync();
            Toast.makeText(this, "🔊 سورة " + QuranIndex.NAMES[surah - 1] + " · الآية " + ayah, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            stopAudio();
        }
    }

    private void stopAudio() {
        if (audio == null) return;
        try {
            audio.release();
        } catch (Exception ignored) {
        }
        audio = null;
    }

    // ------------------------------------------------------------------ pages

    private JSONObject cached(int p) {
        File cache = new File(getFilesDir(), "page_" + p + ".json");
        try {
            if (cache.exists()) return new JSONObject(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
        return null;
    }

    /** The page from the TV's cache, or from api.alquran.cloud (kept for next time). */
    private JSONObject load(int p) {
        JSONObject c0 = cached(p);
        if (c0 != null) return c0;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://api.alquran.cloud/v1/page/" + p + "/quran-simple").openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                JSONObject data = new JSONObject(b.toString("UTF-8")).getJSONObject("data");
                File cache = new File(getFilesDir(), "page_" + p + ".json");
                File tmp = new File(getFilesDir(), "page_" + p + ".tmp" + Thread.currentThread().getId());
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(data.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(cache)) tmp.delete();
                return data;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Ayah numbers in the mushaf's own digits. */
    private static String ayahDigits(int n) {
        StringBuilder b = new StringBuilder();
        for (char ch : String.valueOf(n).toCharArray()) b.append((char) ('٠' + (ch - '0')));
        return b.toString();
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTypeface(naskh);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setShadowLayer(0, 0, 0, Color.TRANSPARENT);
        return t;
    }

    private TextView plain(float sp, int color, boolean bold, String s) {
        TextView t = new TextView(this);
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
