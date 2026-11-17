package app.noortv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;

import java.util.Calendar;
import java.util.Locale;

/**
 * «الطاقة الشمسية»: the house's Solis system live, from SolisCloud. The panels, the house's load, the battery and
 * the grid around the inverter, with dots running along each line the way the power flows, and today's totals below.
 * Drawn on a 1920x1080 sheet scaled to the screen. Without an API key it says how to add one from the phone page.
 */
public class EnergyActivity extends Activity {
    private static final int GOLD = 0xFFFFE08A, WHITE = 0xFFF4EEDF, DIM = 0x99F4EEDF, CARD = 0xFF13222B, EDGE = 0xFF2B4450;
    private static final int SUN = 0xFFFFC94A, HOME = 0xFF6FC3FF, BATT = 0xFF7BE0A4, GRID = 0xFFC79BFF, RED = 0xFFFF8A80;
    private final Handler h = new Handler(Looper.getMainLooper());
    private Sheet sheet;
    private boolean alive;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // adb: am start -n app.noortv.kahf/app.noortv.EnergyActivity --ez demo true, to see the sheet with sample numbers
        demo = getIntent().getBooleanExtra("demo", false);
        if (demo) {
            try {
                SolisCloud.data = new org.json.JSONObject("{\"state\":3,\"pow1\":0,\"totalLoadPower\":0.42,\"totalLoadPowerStr\":\"kW\","
                        + "\"batteryPower\":-0.49,\"batteryPowerStr\":\"kW\",\"batteryCapacitySoc\":80,\"psum\":0,\"eToday\":12.3,"
                        + "\"homeLoadTodayEnergy\":9.8,\"batteryTodayChargeEnergy\":6.1,\"batteryTodayDischargeEnergy\":3.4,"
                        + "\"gridPurchasedTodayEnergy\":0,\"inverterTemperature\":31}");
                SolisCloud.at = System.currentTimeMillis();
            } catch (Exception ignored) {
            }
        }
        sheet = new Sheet(this);
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        // SolisCloud's site under the sheet: the owner signs in there once, and its numbers feed the sheet
        web = SolisWeb.create(this);
        pointer = new SolisWeb.Pointer(this);
        // twice the screen and shown at half size: the site lays itself out as on a 1920-wide computer screen
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        root.addView(web, new android.widget.FrameLayout.LayoutParams(dm.widthPixels * 2, dm.heightPixels * 2));
        web.setPivotX(0);
        web.setPivotY(0);
        web.setScaleX(0.5f);
        web.setScaleY(0.5f);
        root.addView(sheet, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(pointer, new android.widget.FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        if (!demo && !SolisCloud.hasKey(this)) SolisWeb.start(web);
        layers();
    }

    private android.webkit.WebView web;
    private SolisWeb.Pointer pointer;

    /** The site on top while there is nothing to draw yet (signing in, or no data), the sheet once there is. */
    private void layers() {
        boolean site = !demo && !SolisCloud.hasKey(this) && (SolisCloud.data == null || SolisWeb.atLogin());
        sheet.setVisibility(site ? View.GONE : View.VISIBLE);
        pointer.setVisibility(site ? View.VISIBLE : View.GONE);
        if (alive) h.postDelayed(this::layers, 1500);
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (pointer.getVisibility() != View.VISIBLE) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        boolean down = e.getAction() == android.view.KeyEvent.ACTION_DOWN;
        // held arrows go faster
        float step = 18 + Math.min(60, e.getRepeatCount() * 4);
        switch (code) {
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: if (down) pointer.move(-step, 0); return true;
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: if (down) pointer.move(step, 0); return true;
            case android.view.KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (pointer.py <= 2) web.scrollBy(0, -240);
                    pointer.move(0, -step);
                }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (pointer.py >= pointer.getHeight() - 4) web.scrollBy(0, 240);
                    pointer.move(0, step);
                }
                return true;
            case android.view.KeyEvent.KEYCODE_DPAD_CENTER:
            case android.view.KeyEvent.KEYCODE_ENTER:
                if (!down) SolisWeb.tap(web, pointer.px * 2, pointer.py * 2);
                return true;
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private static boolean demo;

    @Override
    protected void onResume() {
        super.onResume();
        alive = true;
        poll();
        layers();
    }

    @Override
    protected void onPause() {
        super.onPause();
        alive = false;
        h.removeCallbacksAndMessages(null);
    }

    /** The cloud has new numbers every few minutes; a minute between looks is plenty, sooner after a failure. */
    private void poll() {
        if (!alive || demo) return;
        if (!SolisCloud.hasKey(this)) {
            // no API key: the site's own page is loaded again for new numbers
            if (System.currentTimeMillis() - SolisWeb.loadedAt > 170_000) SolisWeb.start(web);
            h.postDelayed(this::poll, 20_000);
            return;
        }
        SolisCloud.refresh(this);
        h.postDelayed(this::poll, SolisCloud.data == null ? 15_000 : 60_000);
    }

    private static final class Sheet extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private Shader back;

        Sheet(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = getWidth() / 1920f;
            c.save();
            c.scale(s, s);
            if (back == null) {
                back = new LinearGradient(0, 0, 0, 1080, new int[]{0xFF0F2A24, 0xFF0B1424, 0xFF06080E}, null, Shader.TileMode.CLAMP);
            }
            p.setShader(back);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(0, 0, 1920, getHeight() / s, p);
            p.setShader(null);
            text(c, "⚡ الطاقة الشمسية", 1840, 100, 52, GOLD, true, Paint.Align.RIGHT);
            if (!demo && SolisCloud.data == null && !SolisCloud.hasKey(getContext())) {
                noKey(c);
            } else if (SolisCloud.data == null) {
                text(c, SolisCloud.error != null ? SolisCloud.error : "عم نجيب بيانات المنظومة…", 960, 540, 40,
                        SolisCloud.error != null ? RED : WHITE, false, Paint.Align.CENTER);
            } else {
                live(c);
            }
            c.restore();
            postInvalidateOnAnimation();
        }

        private void noKey(Canvas c) {
            String link = MessageServer.link(getContext());
            String[] lines = {
                    "لربط منظومة Solis بالتلفزيون بدنا مفتاح الخدمة من حسابك:",
                    "1) افتح موقع soliscloud.com من الكمبيوتر وسجّل دخول بحسابك",
                    "2) من الإعدادات (Basic Settings) افتح «API Management» وفعّلها",
                    "3) بيطلع لك رقمين: KeyID و KeySecret",
                    "4) افتح صفحة الموبايل ← تبويب «تحكّم» ← «مفتاح الطاقة الشمسية» واكتبهم",
                    link,
            };
            for (int i = 0; i < lines.length; i++) {
                text(c, lines[i], 960, 250 + i * 80, i == 0 ? 42 : 36, i == 0 || i == lines.length - 1 ? GOLD : WHITE, i == 0, Paint.Align.CENTER);
            }
            box(c, 360, 760, 1200, 150);
            text(c, "لحد ما يوصل المفتاح: اضغط OK لفتح موقع SolisCloud هون على التلفزيون", 960, 825, 34, GOLD, true, Paint.Align.CENTER);
            text(c, "بتسجّل دخول مرة وحدة · اكتب من الموبايل بتبويب «ماوس وكيبورد»", 960, 880, 28, DIM, false, Paint.Align.CENTER);
        }

        private void live(Canvas c) {
            double pv = SolisCloud.has("dcPac") ? SolisCloud.kw("dcPac") : strings();
            double load = SolisCloud.has("totalLoadPower") ? SolisCloud.kw("totalLoadPower")
                    : SolisCloud.kw("familyLoadPower") + SolisCloud.kw("bypassLoadPower");
            // battery: above zero it charges; grid: above zero the house sells, below it buys
            double batt = SolisCloud.kw("batteryPower"), grid = SolisCloud.kw("psum");
            double soc = SolisCloud.num("batteryCapacitySoc");
            int state = (int) SolisCloud.num("state");

            // the header: the state and how old the numbers are
            String st = state == 2 ? "المنظومة غير متصلة" : state == 3 ? "⚠ إنذار: الشبكة مقطوعة أو في تنبيه" : "المنظومة شغّالة";
            chip(c, st, 80, 46, state == 1 ? BATT : RED);
            long age = (System.currentTimeMillis() - SolisCloud.at) / 60_000;
            text(c, "آخر قراءة من المنظومة " + clock(SolisCloud.at) + (age > 12 ? "  ·  قديمة من " + age + " دقيقة" : ""),
                    80, 142, 26, age > 12 ? RED : DIM, false, Paint.Align.LEFT);

            float cx = 960, cy = 400;
            flow(c, 620, 265, cx, cy, pv > 0.02 ? 1 : 0, SUN);
            flow(c, 1300, 265, cx, cy, load > 0.02 ? -1 : 0, HOME);
            flow(c, 620, 535, cx, cy, batt > 0.02 ? -1 : batt < -0.02 ? 1 : 0, BATT);
            flow(c, 1300, 535, cx, cy, grid > 0.02 ? -1 : grid < -0.02 ? 1 : 0, GRID);

            card(c, 200, 175, "☀️ الألواح", pv, SUN, pv > 0.02 ? "عم تنتج هلق" : "ما في إنتاج هلق");
            card(c, 1300, 175, "🏠 صرف البيت", load, HOME, "الاستهلاك الحالي");
            String volts = SolisCloud.has("storageBatteryVoltage")
                    ? "  ·  " + String.format(Locale.US, "%.1f", SolisCloud.num("storageBatteryVoltage")) + " فولت" : "";
            card(c, 200, 445, "🔋 البطارية  " + Math.round(soc) + "%", Math.abs(batt), BATT,
                    (batt > 0.02 ? "عم تشحن" : batt < -0.02 ? "عم تفرّغ" : "واقفة") + volts);
            gauge(c, 230, 600, 360, soc);
            card(c, 1300, 445, "🔌 الشبكة", Math.abs(grid), GRID,
                    grid > 0.02 ? "عم نرجّع للشبكة" : grid < -0.02 ? "عم نسحب من الشبكة" : "ما في سحب من الشبكة");

            // the inverter in the middle
            p.setStyle(Paint.Style.FILL);
            p.setColor(CARD);
            c.drawCircle(cx, cy, 96, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(4);
            p.setColor(GOLD);
            c.drawCircle(cx, cy, 96, p);
            text(c, "الانفرتر", cx, cy + 10, 32, GOLD, true, Paint.Align.CENTER);

            // the totals: today, this month, since the system was installed
            String[] cols = {"إنتاج الألواح", "صرف البيت", "شحن البطارية", "تفريغ البطارية", "من الشبكة"};
            int[] colors = {SUN, HOME, BATT, BATT, GRID};
            String[][] keys = {
                    {"eToday", "homeLoadTodayEnergy", "batteryTodayChargeEnergy", "batteryTodayDischargeEnergy", "gridPurchasedTodayEnergy"},
                    {"monthEnergy", "homeLoadMonthEnergy", "batteryChargeMonthEnergy", "batteryDischargeMonthEnergy", "gridPurchasedMonthEnergy"},
                    {"allEnergy", "homeLoadTotalEnergy", "batteryChargeTotalEnergy", "batteryDischargeTotalEnergy", "gridPurchasedTotalEnergy"},
            };
            String[] rows = {"اليوم", "هالشهر", "من أول التركيب"};
            box(c, 80, 690, 1760, 350);
            for (int j = 0; j < 5; j++) {
                text(c, cols[j], 1530 - j * 290, 745, 28, colors[j], true, Paint.Align.CENTER);
            }
            text(c, "kWh", 150, 745, 24, DIM, false, Paint.Align.CENTER);
            boolean month = SolisCloud.has("monthEnergy");
            for (int i = 0; i < (month ? 3 : 1); i++) {
                float y = 825 + i * 82;
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1);
                p.setColor(EDGE);
                c.drawLine(110, y - 56, 1810, y - 56, p);
                text(c, rows[i], 1800, y, 30, WHITE, true, Paint.Align.RIGHT);
                for (int j = 0; j < 5; j++) {
                    double v = SolisCloud.kwh(keys[i][j]);
                    text(c, String.format(Locale.US, v >= 1000 ? "%.0f" : "%.1f", v), 1530 - j * 290, y + 4, i == 0 ? 50 : 42,
                            i == 0 ? colors[j] : WHITE, i == 0, Paint.Align.CENTER);
                }
            }
            // how much of today's use the sun and the battery covered
            double used = SolisCloud.kwh("homeLoadTodayEnergy"), bought = SolisCloud.kwh("gridPurchasedTodayEnergy");
            if (used > 0.1) {
                long self = Math.round(Math.max(0, Math.min(1, 1 - bought / used)) * 100);
                chip(c, "اليوم " + self + "% من صرف البيت من الشمس والبطارية", 620, 46, SUN);
            }
        }

        /** The panels' strings together, in kW (pow1.. are watts). */
        private static double strings() {
            double w = 0;
            for (int i = 1; i <= 8; i++) w += SolisCloud.num("pow" + i);
            return w / 1000;
        }

        private void card(Canvas c, float x, float y, String title, double kw, int color, String sub) {
            box(c, x, y, 420, 200);
            text(c, title, x + 390, y + 56, 32, WHITE, true, Paint.Align.RIGHT);
            text(c, String.format(Locale.US, "%.2f", kw), x + 250, y + 130, 66, color, true, Paint.Align.CENTER);
            text(c, "kW", x + 70, y + 130, 30, DIM, false, Paint.Align.CENTER);
            text(c, sub, x + 390, y + 178, 26, DIM, false, Paint.Align.RIGHT);
        }

        private void box(Canvas c, float x, float y, float w, float hgt) {
            r.set(x, y, x + w, y + hgt);
            p.setStyle(Paint.Style.FILL);
            p.setColor(CARD);
            c.drawRoundRect(r, 28, 28, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2);
            p.setColor(EDGE);
            c.drawRoundRect(r, 28, 28, p);
        }

        private void chip(Canvas c, String t, float x, float y, int color) {
            p.setTextSize(30);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            float w = p.measureText(t) + 56;
            r.set(x, y, x + w, y + 58);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color & 0x33FFFFFF);
            c.drawRoundRect(r, 29, 29, p);
            text(c, t, x + w / 2, y + 41, 30, color, true, Paint.Align.CENTER);
        }

        private void gauge(Canvas c, float x, float y, float w, double soc) {
            // drawn under the battery card's text line
            r.set(x, y + 44, x + w, y + 56);
            p.setStyle(Paint.Style.FILL);
            p.setColor(EDGE);
            c.drawRoundRect(r, 6, 6, p);
            r.set(x, y + 44, x + (float) (w * Math.max(0, Math.min(100, soc)) / 100), y + 56);
            p.setColor(soc < 25 ? RED : BATT);
            c.drawRoundRect(r, 6, 6, p);
        }

        /** A line from a card to the inverter; dir 1: dots run towards the inverter, -1: away from it, 0: no flow. */
        private void flow(Canvas c, float x1, float y1, float x2, float y2, int dir, int color) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(5);
            p.setColor(dir == 0 ? EDGE : (color & 0x66FFFFFF));
            c.drawLine(x1, y1, x2, y2, p);
            if (dir == 0) return;
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            float t = (SystemClock.uptimeMillis() % 1600) / 1600f;
            for (int i = 0; i < 3; i++) {
                float k = (t + i / 3f) % 1f;
                if (dir < 0) k = 1 - k;
                c.drawCircle(x1 + (x2 - x1) * k, y1 + (y2 - y1) * k, 9, p);
            }
        }

        private void text(Canvas c, String t, float x, float y, float size, int color, boolean bold, Paint.Align a) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextSize(size);
            p.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            p.setTextAlign(a);
            c.drawText(t, x, y, p);
            p.setTextAlign(Paint.Align.LEFT);
        }

        private static String clock(long t) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(t);
            int hr = c.get(Calendar.HOUR);
            return String.format(Locale.US, "%d:%02d %s", hr == 0 ? 12 : hr, c.get(Calendar.MINUTE), c.get(Calendar.AM_PM) == Calendar.AM ? "ص" : "م");
        }
    }
}
