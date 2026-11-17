package app.noortv;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * «مدير التطبيقات»: every app on the TV, the family's own first (newest at the top), the system's and the switched-off
 * ones in their own lists. OK on an app: open it, remove it (Android asks first), its info page (force stop, clear
 * data, switch on or off) or its page in the store. Left and right change the list.
 */
public class AppsActivity extends Activity {
    private static final String[] TABS = {"تطبيقاتي", "تطبيقات النظام", "المعطّلة"};
    private int tab;
    private final TextView[] tabViews = new TextView[3];
    private LinearLayout list;
    private ScrollView scroll;
    private TextView count;
    private float d;
    private boolean reload;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF14202E, 0xFF0B1220, 0xFF06080E}));
        root.setPadding(px(48), px(22), px(48), px(10));

        // left to right (the app does not declare RTL): the tabs on the left, the title on the right
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 2; i >= 0; i--) {
            tabViews[i] = text(TABS[i], 20, 0xFFFFFFFF, true);
            tabViews[i].setPadding(px(20), px(7), px(20), px(7));
            // a touch from the phone's view of the screen picks the list too
            final int which = i;
            tabViews[i].setOnClickListener(v -> {
                tab = which;
                show();
            });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.rightMargin = px(10);
            top.addView(tabViews[i], tp);
        }
        TextView title = text("🧩  مدير التطبيقات", 30, 0xFFFFFFFF, true);
        title.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top);

        count = text("", 16, 0xFF9FB0BC, false);
        count.setGravity(Gravity.RIGHT);
        count.setPadding(0, px(6), 0, px(8));
        root.addView(count);

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView help = text("◀ ▶ لتغيير القائمة   ·   OK على تطبيق: فتح، حذف، معلومات، المتجر", 16, 0xFF8899A6, false);
        help.setGravity(Gravity.CENTER);
        help.setPadding(0, px(8), 0, 0);
        root.addView(help);
        setContentView(root);
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // back from removing an app or from its info page: the list as it is now
        if (reload) show();
        reload = false;
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT && tab > 0) {
            tab--;
            show();
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_LEFT && tab < 2) {
            tab++;
            show();
            return true;
        }
        return super.onKeyDown(k, e);
    }

    private void show() {
        for (int i = 0; i < 3; i++) {
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(px(20));
            g.setColor(i == tab ? 0xFF2D6CDF : 0x26FFFFFF);
            if (i == tab) g.setStroke(px(2), 0xFFFFD27A);
            tabViews[i].setBackground(g);
            tabViews[i].setTextColor(i == tab ? 0xFFFFFFFF : 0xFFB8C4CC);
        }
        list.removeAllViews();
        int n = 0;
        List<AppsHub.App> all = AppsHub.list(this);
        for (AppsHub.App a : all) {
            boolean in = tab == 0 ? !a.system && a.enabled : tab == 1 ? a.system && a.enabled : !a.enabled;
            if (!in) continue;
            list.addView(row(a));
            n++;
        }
        count.setText(n == 0 ? "لا يوجد شيء هنا" : n + " تطبيق" + (tab == 0 ? " · الأحدث أولاً" : ""));
        scroll.scrollTo(0, 0);
        if (list.getChildCount() > 0) list.getChildAt(0).requestFocus();
    }

    private View row(AppsHub.App a) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(px(16), px(8), px(16), px(8));
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(px(12));
        bg.setColor(0x14FFFFFF);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x40FFFFFF : 0x14FFFFFF);
            bg.setStroke(has ? px(2) : 0, 0xFFFFD27A);
            v.animate().scaleX(has ? 1.01f : 1f).scaleY(has ? 1.01f : 1f).setDuration(120).start();
        });

        String date = new SimpleDateFormat("d MMM yyyy", Locale.forLanguageTag("ar-u-nu-latn")).format(new Date(a.installed));
        TextView meta = text((a.version.isEmpty() ? "" : "الإصدار " + shorten(a.version) + "\n") + date
                + (a.size > 0 ? " · " + AppsHub.size(a.size) : ""), 14, 0xFF9FB0BC, false);
        meta.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        r.addView(meta, new LinearLayout.LayoutParams(px(230), -2));

        LinearLayout names = new LinearLayout(this);
        names.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(a.label + (a.openable ? "" : "  ·  خدمة"), 20, 0xFFFFFFFF, true);
        name.setGravity(Gravity.RIGHT);
        names.addView(name);
        TextView pkg = text(a.pkg, 13, 0xFF7F8FA0, false);
        pkg.setGravity(Gravity.RIGHT);
        names.addView(pkg);
        r.addView(names, new LinearLayout.LayoutParams(0, -2, 1));

        ImageView icon = new ImageView(this);
        try {
            icon.setImageDrawable(getPackageManager().getApplicationIcon(a.pkg));
        } catch (Exception ignored) {
        }
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(px(44), px(44));
        ip.leftMargin = px(14);
        r.addView(icon, ip);

        r.setOnClickListener(v -> actions(a));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(5);
        r.setLayoutParams(lp);
        return r;
    }

    private static String shorten(String v) {
        return v.length() > 14 ? v.substring(0, 14) + "…" : v;
    }

    private void actions(AppsHub.App a) {
        String[] items = {"فتح", a.updatedSystem ? "حذف التحديثات (يرجع لنسخة الشركة)" : "حذف التطبيق",
                "معلومات التطبيق (إيقاف · مسح البيانات · تعطيل)", "صفحته في المتجر"};
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(a.label)
                .setItems(items, (dlg, which) -> {
                    String r;
                    switch (which) {
                        case 0: r = AppsHub.open(this, a.pkg); break;
                        case 1: r = AppsHub.uninstall(this, a.pkg); reload = true; break;
                        case 2: r = AppsHub.info(this, a.pkg); reload = true; break;
                        default: r = AppsHub.store(this, a.pkg); break;
                    }
                    if (r.startsWith("no|")) {
                        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                                .setMessage(r.substring(3)).setPositiveButton("حسنًا", null).show();
                    }
                })
                .show();
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setMaxLines(2);
        return t;
    }

    private int px(float dp) {
        return (int) (dp * d);
    }
}
