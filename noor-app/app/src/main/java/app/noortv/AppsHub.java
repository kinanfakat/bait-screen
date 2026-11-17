package app.noortv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Every app on the TV, for «مدير التطبيقات» on the TV and the phone page's «التطبيقات»: what it is, and what can be
 * done with it. Removing an app always goes through Android's own question on the TV; nothing is removed silently.
 */
final class AppsHub {

    /** One app: its package, name, version, install date, size, and whether it is the family's own, off, or openable. */
    static final class App {
        String pkg, label, version;
        long installed, updated, size;
        boolean system, enabled, openable, tv;
        /** A system app with updates on top: removing it takes the updates off, the TV's own version stays. */
        boolean updatedSystem;
    }

    static List<App> list(Context c) {
        PackageManager pm = c.getPackageManager();
        List<App> out = new ArrayList<>();
        for (PackageInfo p : pm.getInstalledPackages(PackageManager.MATCH_DISABLED_COMPONENTS)) {
            ApplicationInfo ai = p.applicationInfo;
            if (ai == null) continue;
            App a = new App();
            a.pkg = p.packageName;
            a.label = String.valueOf(ai.loadLabel(pm));
            a.version = p.versionName == null ? "" : p.versionName;
            a.installed = p.firstInstallTime;
            a.updated = p.lastUpdateTime;
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            a.enabled = ai.enabled;
            a.updatedSystem = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            a.tv = pm.getLeanbackLaunchIntentForPackage(p.packageName) != null;
            a.openable = a.tv || pm.getLaunchIntentForPackage(p.packageName) != null;
            try {
                a.size = new File(ai.sourceDir).length();
            } catch (Exception ignored) {
            }
            out.add(a);
        }
        Collections.sort(out, (x, y) -> {
            if (x.system != y.system) return x.system ? 1 : -1;
            if (!x.system) return Long.compare(y.installed, x.installed);   // the newest of the family's own first
            return x.label.compareToIgnoreCase(y.label);
        });
        return out;
    }

    static String json(Context c) {
        JSONArray a = new JSONArray();
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        for (App x : list(c)) {
            try {
                JSONObject o = new JSONObject();
                o.put("p", x.pkg).put("n", x.label).put("v", x.version).put("d", f.format(new Date(x.installed)))
                        .put("s", x.size).put("sys", x.system).put("on", x.enabled).put("open", x.openable)
                        .put("upd", x.updatedSystem);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        return a.toString();
    }

    /** The app's icon, 96 px, as PNG (null when there is none). */
    static byte[] icon(Context c, String pkg) {
        try {
            Drawable d = c.getPackageManager().getApplicationIcon(pkg);
            Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(b);
            d.setBounds(0, 0, 96, 96);
            d.draw(cv);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, o);
            b.recycle();
            return o.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    static boolean exists(Context c, String pkg) {
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** "ok|..." or "no|..." for the phone. */
    static String open(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return "no|هذا التطبيق لا يُفتح من الشاشة (خدمة أو جزء من النظام)";
        Adhan.home(c, "w");
        c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "ok|فُتح على التلفزيون";
    }

    static String uninstall(Context c, String pkg) {
        if (!exists(c, pkg)) return "no|التطبيق غير موجود";
        if (pkg.equals(c.getPackageName()) || pkg.equals(Adhan.HOME))
            return "no|هذا من تطبيقات شاشة البيت الأساسية (الأذان والواجهة) · لا يُحذف من هنا";
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)
                return "no|تطبيق من النظام لا يُحذف · يمكن إيقافه من «معلومات التطبيق»";
        } catch (Exception ignored) {
        }
        boolean updates = false;
        try {
            updates = (c.getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
        } catch (Exception ignored) {
        }
        Adhan.home(c, "w");
        if (updates) {
            c.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "ok|هذا تطبيق من الشركة: تُحذف تحديثاته فقط ويرجع لنسخته الأصلية · أكّد على التلفزيون";
        }
        c.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "ok|على التلفزيون الآن سؤال الحذف · اضغط «حسنًا» بالريموت أو من «شاشة التلفزيون»";
    }

    static String info(Context c, String pkg) {
        Adhan.home(c, "w");
        c.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        return "ok|معلومات التطبيق على التلفزيون: الإيقاف الإجباري، مسح البيانات، التفعيل والتعطيل";
    }

    /** The app's page in the store, or a search there when q is not a package name. */
    static String store(Context c, String q) {
        q = q.trim();
        if (q.isEmpty()) return "no|اكتب اسم التطبيق";
        boolean pkg = q.matches("[a-zA-Z][\\w]*(\\.[\\w]+)+");
        Uri u = Uri.parse(pkg ? "market://details?id=" + q : "market://search?q=" + Uri.encode(q));
        Adhan.home(c, "w");
        try {
            // Google Play itself, not Android's question "which store?" (Uptodown answers market links too)
            Intent i = new Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (exists(c, "com.android.vending")) i.setPackage("com.android.vending");
            c.startActivity(i);
            return "ok|المتجر مفتوح على التلفزيون";
        } catch (Exception e) {
            return "no|لا يوجد متجر على التلفزيون";
        }
    }

    static String size(long b) {
        if (b <= 0) return "";
        if (b < 1024 * 1024) return (b / 1024) + " ك.ب";
        return String.format(Locale.US, "%.1f م.ب", b / 1048576f);
    }

    private AppsHub() {
    }
}
