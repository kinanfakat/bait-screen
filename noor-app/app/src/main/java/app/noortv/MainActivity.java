package app.noortv;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Opens this shortcut's video in the YouTube TV app, then gets out of the way. */
public class MainActivity extends Activity {
    /** The home PC as Sunshine names it, and its Desktop app's id (from Moonlight's View Details). */
    // Your own PC in Moonlight (its UUID, name and the Desktop app id, from Moonlight's shortcut): fill in to use the "pc" card
    private static final String PC_UUID = "", PC_NAME = "", PC_DESKTOP = "";
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (BuildConfig.REMINDERS) {
            Reminder.schedule(this);
            Adhan.schedule(this);
            MessageServer.start(this);
        }
        // «شاشة الكمبيوتر»: Moonlight straight into the PC's desktop; its PC list if the PC changed
        if ("pc".equals(BuildConfig.TARGET)) {
            try {
                startActivity(new Intent().setClassName("com.limelight", "com.limelight.ShortcutTrampoline")
                        .putExtra("UUID", PC_UUID).putExtra("Name", PC_NAME)
                        .putExtra("AppId", PC_DESKTOP).putExtra("AppName", "Desktop"));
            } catch (Exception e) {
                try {
                    startActivity(new Intent().setClassName("com.limelight", "com.limelight.PcView"));
                } catch (Exception ignored) {
                }
            }
            finish();
            return;
        }
        // «مركز التحكم»: the launcher's Noor control center
        if (!BuildConfig.TARGET.isEmpty()) {
            try {
                startActivity(new Intent().setComponent(android.content.ComponentName.unflattenFromString(BuildConfig.TARGET)));
            } catch (Exception ignored) {
            }
            finish();
            return;
        }
        // «الورد اليومي»: the mushaf reader with the daily wird
        if (BuildConfig.WIRD) {
            startActivity(new Intent(this, WirdActivity.class));
            finish();
            return;
        }
        // «إذاعة القرآن»: our own player screen, which stops the moment you leave it
        if (!BuildConfig.STREAM.isEmpty()) {
            startActivity(new Intent(this, RadioActivity.class));
            finish();
            return;
        }
        Uri video = Uri.parse("https://www.youtube.com/watch?v=" + BuildConfig.VIDEO);
        // YouTube first, then SmartTube (beta, stable), so a TV with stock YouTube disabled skips the app chooser.
        String[] players = {"com.google.android.youtube.tv", "org.smarttube.beta", "org.smarttube.stable"};
        boolean opened = false;
        for (String pkg : players) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, video).setPackage(pkg));
                opened = true;
                break;
            } catch (Exception ignored) {
            }
        }
        if (!opened) startActivity(new Intent(Intent.ACTION_VIEW, video));
        finish();
    }
}
