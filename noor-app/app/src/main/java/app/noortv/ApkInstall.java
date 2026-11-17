package app.noortv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * An app sent from the phone page (apk): handed to Android's own installer, which asks on the TV before anything is
 * installed («تثبيت» with the remote). The first time, Android asks to allow this app to install apps: that switch
 * is the owner's, in the TV's settings. The phone page reads state() to say how it went.
 */
public class ApkInstall extends BroadcastReceiver {
    /** What the phone page shows: empty when nothing was sent. */
    static volatile String state = "";

    static void install(Context c, File apk) {
        Context app = c.getApplicationContext();
        try {
            PackageInstaller pi = app.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            sp.setSize(apk.length());
            int id = pi.createSession(sp);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (InputStream in = new FileInputStream(apk); OutputStream out = s.openWrite("app.apk", 0, apk.length())) {
                    byte[] buf = new byte[65536];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    s.fsync(out);
                }
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                PendingIntent done = PendingIntent.getBroadcast(app, id, new Intent(app, ApkInstall.class), flags);
                s.commit(done.getIntentSender());
            }
            state = "wait|التلفزيون يعرض الآن نافذة التثبيت · اضغط «تثبيت» بالريموت";
        } catch (Exception e) {
            state = "fail|تعذّر تجهيز التثبيت: " + e.getMessage();
            apk.delete();
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String pkg = i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android's own window on the TV: it asks whoever holds the remote
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                Adhan.home(c, "w");
                c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return;
        }
        new File(c.getCacheDir(), "upload.apk").delete();
        if (status == PackageInstaller.STATUS_SUCCESS) {
            state = "ok|تم التثبيت ✅" + (pkg == null ? "" : " · " + pkg);
        } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
            state = "fail|أُلغي التثبيت على التلفزيون";
        } else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
            state = "fail|لم يُثبّت: نسخة موجودة على التلفزيون موقّعة بتوقيع آخر · احذفها أولاً ثم أعد المحاولة";
        } else if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
            state = "fail|لم يُثبّت: التطبيق لا يناسب هذا التلفزيون";
        } else if (status == PackageInstaller.STATUS_FAILURE_INVALID) {
            state = "fail|لم يُثبّت: الملف ليس تطبيقاً صالحاً أو لم يكتمل رفعه";
        } else if (status == PackageInstaller.STATUS_FAILURE_STORAGE) {
            state = "fail|لم يُثبّت: لا توجد مساحة كافية على التلفزيون";
        } else {
            String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            state = "fail|لم يُثبّت" + (m == null ? "" : ": " + m);
        }
        Adhan.log(c, "apk from the phone: " + state);
    }
}
