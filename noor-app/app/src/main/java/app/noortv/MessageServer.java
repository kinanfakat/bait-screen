package app.noortv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * A tiny web page on port 8080: open http://<tv-ip>:8080 on a phone on the same Wi-Fi, then write a message
 * (shown in the middle of the TV for 20 seconds) or draw with a finger (drawn on the TV live, gone 15 seconds
 * after the last line). Only reachable from the home network.
 */
public class MessageServer extends Service {
    /** 8080, or the next free one when another app (a media server) already holds it. */
    static volatile int PORT = 8080;
    // Secret part of the link: the page and its actions only answer under /KEY/, anything else gets 404.
    // Random for each TV (kept in prefs), and a new one from «ألعاب العيلة» ← «صفحة الجوال» cuts off every old link.
    static volatile String KEY = "";

    /** This TV's key, made the first time. */
    static synchronized String key(Context c) {
        if (KEY.isEmpty()) {
            android.content.SharedPreferences p = c.getApplicationContext().getSharedPreferences("phone", Context.MODE_PRIVATE);
            String k = p.getString("key", null);
            if (k == null) {
                k = randomKey();
                p.edit().putString("key", k).apply();
            }
            KEY = k;
        }
        return KEY;
    }

    /** The phones in the quiz and in «مين أنا؟»: a private token for each, so no one plays under another's name. */
    private static final java.util.Map<String, String> quizPlayers = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> whoPlayers = new java.util.concurrent.ConcurrentHashMap<>();

    /** The token this phone keeps for the name (the one it had, after a reconnect), or a new one. */
    private static String token(java.util.Map<String, String> players, String name, String had) {
        if (!had.isEmpty() && name.equals(players.get(had))) return had;
        String t = randomKey() + randomKey();
        players.values().removeIf(name::equals);   // one phone per name: an older token for it stops working
        players.put(t, name);
        return t;
    }

    /** A new key: the old link stops working at once. */
    static synchronized String newKey(Context c) {
        KEY = randomKey();
        c.getApplicationContext().getSharedPreferences("phone", Context.MODE_PRIVATE).edit().putString("key", KEY).apply();
        return KEY;
    }

    private static String randomKey() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        return b.toString();
    }

    /** http://<the TV's Wi-Fi address>:8080/<key>/ */
    static String link(Context c) {
        String key = key(c);
        try {
            // the home network's address: Wi-Fi or cable first, never a VPN's tunnel or Wi-Fi Direct (the phone
            // could not reach those); any other private address only when there is nothing better
            String best = null;
            int rank = 9;
            java.util.Enumeration<java.net.NetworkInterface> ifs = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                java.net.NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(java.util.Locale.US);
                if (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("p2p") || n.startsWith("ipsec")
                        || n.startsWith("rmnet")) continue;
                java.util.Enumeration<java.net.InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    java.net.InetAddress a = as.nextElement();
                    if (!(a instanceof java.net.Inet4Address) || a.isLoopbackAddress()) continue;
                    int r = (n.startsWith("wlan") || n.startsWith("eth")) ? (a.isSiteLocalAddress() ? 0 : 1)
                            : (a.isSiteLocalAddress() ? 2 : 3);
                    if (r < rank) {
                        rank = r;
                        best = a.getHostAddress();
                    }
                }
            }
            if (best != null) return "http://" + best + ":" + PORT + "/" + key + "/#" + Access.code(c);
        } catch (Exception ignored) {
        }
        return "صفحة الجوال";
    }

    /** The link as a QR code to scan with the phone's camera. */
    private static String qrText;
    private static android.graphics.Bitmap qrBitmap;

    static synchronized android.graphics.Bitmap qr(String text, int size) {
        // drawn once: the lobbies refresh twice a second
        if (text.equals(qrText) && qrBitmap != null && qrBitmap.getWidth() == size) return qrBitmap;
        try {
            com.google.zxing.common.BitMatrix m = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.RGB_565);
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) b.setPixel(x, y, m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
            }
            qrText = text;
            qrBitmap = b;
            return b;
        } catch (Exception e) {
            return null;
        }
    }
    static final int MAX = 200;
    static final long DRAW_IDLE = 15_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ServerSocket server;
    private View shown;
    private DrawLayer drawing;
    private final Runnable hideDrawing = this::hideDrawing;

    static void start(Context c) {
        key(c);
        try {
            c.startForegroundService(new Intent(c, MessageServer.class));
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ScreenView.start(this);
        getApplication().registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(android.app.Activity a) {
                front = a;
            }

            @Override
            public void onActivityPaused(android.app.Activity a) {
                if (front == a) front = null;
            }

            @Override
            public void onActivityCreated(android.app.Activity a, android.os.Bundle b) {
            }

            @Override
            public void onActivityStarted(android.app.Activity a) {
            }

            @Override
            public void onActivityStopped(android.app.Activity a) {
            }

            @Override
            public void onActivitySaveInstanceState(android.app.Activity a, android.os.Bundle b) {
            }

            @Override
            public void onActivityDestroyed(android.app.Activity a) {
            }
        });
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("msg", "رسائل الجوال", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "msg")
                .setContentTitle("رسائل الجوال")
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .build();
        startForeground(1, n);
        new Thread(this::serve, "msg-server").start();
        keepAwakeInStandby();
    }

    private android.os.PowerManager.WakeLock standby;

    /**
     * This Philips firmware turns every wakeup alarm into a plain one, so in standby the chip sleeps and the adhan
     * waited for the TV to be switched on (Asr playing at half past seven). While the screen is off a partial wake lock
     * keeps the chip awake, so the alarm comes on time and can switch the TV on; with the screen on it is let go.
     * Screen off is also when a screen timeout the adhan shortened to put the TV back to sleep is set back.
     */
    private void keepAwakeInStandby() {
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        standby = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "noortv:standby");
        standby.setReferenceCounted(false);
        Adhan.restoreScreenTimeout(this);
        Adhan.restoreLeftover(this);
        if (!pm.isInteractive()) standby.acquire();
        // our own clock for the adhan: the chip is kept awake in standby, so this runs on time when alarms do not
        android.os.Handler clock = new android.os.Handler(android.os.Looper.getMainLooper());
        clock.post(new Runnable() {
            @Override
            public void run() {
                Adhan.due(MessageServer.this);
                Health.tick(MessageServer.this);
                MemGuard.tick(MessageServer.this);
                Reminder.tick(MessageServer.this);
                HomeStatus.tick(MessageServer.this);
                // «ذكّرني»: a note whose time has come, in the middle of the screen with a short tone
                String note = Notes.due(MessageServer.this);
                if (note != null) {
                    show("🔔 تذكير" + (char) 10 + note);
                    try {
                        new android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 70)
                                .startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 400);
                    } catch (Exception ignored) {
                    }
                }
                clock.postDelayed(this, 5000);
            }
        });
        android.content.IntentFilter f = new android.content.IntentFilter(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                Adhan.restoreScreenTimeout(c);
                if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) standby.acquire();
                else if (standby.isHeld()) standby.release();
            }
        }, f);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private void serve() {
        try {
            ServerSocket ss = null;
            // 8080 is the address the family saved: a few tries first (a restart may still hold it a moment)
            for (int attempt = 0; attempt < 14 && ss == null; attempt++) {
                int port = attempt < 5 ? 8080 : 8076 + attempt;   // 8080 ×5, then 8081…8089
                try {
                    ServerSocket t = new ServerSocket();
                    t.setReuseAddress(true);
                    t.bind(new java.net.InetSocketAddress(port));
                    ss = t;
                    PORT = port;
                } catch (java.io.IOException taken) {
                    if (attempt == 4) Adhan.log(this, "phone page: port 8080 is taken, trying the next ones");
                    if (attempt < 4) Thread.sleep(2000);
                }
            }
            if (ss == null) return;
            server = ss;
            // several phones at once (the quiz polls every second), and a phone that opens a connection and
            // sends nothing no longer holds up the others
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(8);
            while (true) {
                Socket s = server.accept();
                pool.execute(() -> {
                    try (Socket c = s) {
                        c.setSoTimeout(8000);
                        handle(c);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
            // Port taken (a second start): the first server keeps running.
        }
    }

    private void handle(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        String head = readHead(in);
        String first = head.split("\r\n", 2)[0];
        String[] parts = first.split(" ");
        String path = parts.length > 1 ? parts[1] : "";
        final String KEY = key(this);   // one key for the whole request, even if a new one is made meanwhile
        if (path.equals("/" + KEY)) {
            redirect(s.getOutputStream(), "/" + KEY + "/");
            return;
        }
        // the bare address (what a tunnel's link or QR code opens): the door, for guests only. Their code leads to
        // the games; the family's code works only with the whole private link.
        if (path.equals("/") || path.isEmpty()) {
            CodeOnScreen.show(this, false);   // the family's code: it opens everything here too
            reply(s.getOutputStream(), "text/html", DOOR);
            return;
        }
        if (path.equals("/auth") && first.startsWith("POST ")) {
            String r = Access.enterGuest(this, KEY, readBody(in, head));
            if (r.length() > 6) CodeOnScreen.hide();
            else CodeOnScreen.show(this, true);
            reply(s.getOutputStream(), "text/plain", r.length() > 6 ? KEY + "/s/" + r : r);
            return;
        }
        if (!path.startsWith("/" + KEY + "/")) {
            notFound(s.getOutputStream());
            return;
        }
        String route = path.substring(KEY.length() + 2);
        // the code's door: without a session the link gives only the page that asks for the code
        String session = Access.session(this, KEY);
        if (route.startsWith("s/")) {
            int end = route.indexOf('/', 2);
            String got = end > 0 ? route.substring(2, end) : route.substring(2);
            String rest = end > 0 ? route.substring(end + 1) : "";
            // a guest's session reaches the games it was invited to, and nothing that moves the TV
            boolean guest = !got.equals(session) && got.equals(Access.guestSession(this, KEY));
            if (guest) {
                String r = rest.contains("?") ? rest.substring(0, rest.indexOf('?')) : rest;
                boolean game = r.startsWith("quiz/") || r.startsWith("who/") || r.startsWith("charades/");
                if (!r.isEmpty() && !r.equals("ping") && !game) {
                    forbidden(s.getOutputStream());
                    return;
                }
                if (r.isEmpty() && end > 0) {
                    reply(s.getOutputStream(), "text/html", page().replace("<body>", "<body class=\"guest\">"));
                    return;
                }
            }
            if (!guest && !got.equals(session)) {
                // an old session (the code changed): the page goes back to the door, everything else is refused
                if (rest.isEmpty() && !first.startsWith("POST ")) redirect(s.getOutputStream(), "/" + KEY + "/");
                else forbidden(s.getOutputStream());
                return;
            }
            if (end < 0) {
                redirect(s.getOutputStream(), "/" + KEY + "/s/" + session + "/");
                return;
            }
            route = rest;
        } else if (route.equals("auth") && first.startsWith("POST ")) {
            String r = Access.enter(this, KEY, readBody(in, head));
            // in: the code leaves the screen; wrong: it is shown again for whoever holds the phone
            if (r.length() > 6) CodeOnScreen.hide();
            else CodeOnScreen.show(this, false);
            reply(s.getOutputStream(), "text/plain", r);
            return;
        } else {
            CodeOnScreen.show(this, false);
            reply(s.getOutputStream(), "text/html", DOOR);
            return;
        }
        int query = route.indexOf('?');
        String args = query >= 0 ? route.substring(query + 1) : "";
        if (query >= 0) route = route.substring(0, query);
        if (!first.startsWith("POST ")) {
            if (route.equals("quiz/state")) {
                String token = args.startsWith("token=") ? args.substring(6) : "";
                reply(s.getOutputStream(), "application/json", QuizGame.state(quizPlayers.get(token)));
                return;
            }
            if (route.equals("charades/state")) {
                String token = args.startsWith("token=") ? args.substring(6) : null;
                reply(s.getOutputStream(), "application/json", CharadesGame.state(token));
                return;
            }
            if (route.equals("who/state")) {
                String token = args.startsWith("token=") ? args.substring(6) : "";
                reply(s.getOutputStream(), "application/json", WhoGame.state(whoPlayers.get(token)));
                return;
            }
            if (route.equals("remind/list")) {
                reply(s.getOutputStream(), "text/plain", Notes.list(this));
                return;
            }
            if (route.equals("adhan/log")) {
                reply(s.getOutputStream(), "text/plain", Adhan.diary(this));
                return;
            }
            if (route.equals("apps/list")) {
                reply(s.getOutputStream(), "application/json", AppsHub.json(this));
                return;
            }
            if (route.equals("apps/icon")) {
                String pkg = args.startsWith("p=") ? java.net.URLDecoder.decode(args.substring(2), "UTF-8") : "";
                byte[] png = AppsHub.icon(this, pkg);
                if (png == null) notFound(s.getOutputStream());
                else replyBytes(s.getOutputStream(), "image/png", png, "max-age=86400");
                return;
            }
            if (route.equals("screen/frame")) {
                byte[] jpg = ScreenView.next(this);
                if (jpg == null) notFound(s.getOutputStream());
                // with it, whether a text field has the focus on the TV: the phone then shows its keyboard
                else replyBytes(s.getOutputStream(), "image/jpeg", jpg, "no-store\r\nX-Edit: "
                        + (ScreenView.editing ? "1" : "0") + "\r\nX-Edit-Text: " + android.net.Uri.encode(ScreenView.editText));
                return;
            }
            if (route.equals("pad/state")) {
                reply(s.getOutputStream(), "text/plain", PadMouse.state());
                return;
            }
            if (route.equals("mem/state")) {
                reply(s.getOutputStream(), "text/plain", MemGuard.report(this));
                return;
            }
            if (route.equals("mem/clean")) {
                // «تفريغ الذاكرة» from the phone: the apps in the background closed now, the one in front kept
                reply(s.getOutputStream(), "text/plain", MemGuard.cleanNow(this) ? "ok" : "busy");
                return;
            }
            if (route.equals("apk/state")) {
                reply(s.getOutputStream(), "text/plain", ApkInstall.state);
                return;
            }
            if (route.equals("ping")) {
                reply(s.getOutputStream(), "text/plain", "ok");
                return;
            }
            if (route.equals("solis/web")) {
                reply(s.getOutputStream(), "application/json", SolisWeb.dump(args));
                return;
            }
            if (route.equals("solis/raw")) {
                reply(s.getOutputStream(), "application/json", SolisCloud.raw.isEmpty() ? "{}" : SolisCloud.raw);
                return;
            }
            reply(s.getOutputStream(), "text/html", page());
            return;
        }
        if (route.equals("photo")) {
            byte[] img = readBytes(in, head, 12 * 1024 * 1024);
            main.post(() -> showPhoto(img));
            reply(s.getOutputStream(), "text/plain", "ok");
            return;
        }
        if (route.equals("apk")) {
            // an app from the phone, written straight to a file (it can be a few hundred MB), then to the installer
            long len = 0;
            for (String line : head.split("\n")) {
                if (line.toLowerCase().startsWith("content-length:")) len = Long.parseLong(line.substring(15).trim());
            }
            if (len <= 0 || len > 500L * 1024 * 1024) {
                reply(s.getOutputStream(), "text/plain", "size");
                return;
            }
            java.io.File f = new java.io.File(getCacheDir(), "upload.apk");
            long got = 0;
            ApkInstall.state = "up|جاري الاستلام…";
            try (java.io.OutputStream o = new java.io.FileOutputStream(f)) {
                byte[] buf = new byte[65536];
                while (got < len) {
                    int r = in.read(buf, 0, (int) Math.min(buf.length, len - got));
                    if (r < 0) break;
                    o.write(buf, 0, r);
                    got += r;
                }
            }
            if (got < len) {
                f.delete();
                ApkInstall.state = "fail|انقطع الرفع قبل أن يكتمل · أعد المحاولة";
                reply(s.getOutputStream(), "text/plain", "short");
                return;
            }
            Adhan.log(this, "apk from the phone: " + got / 1024 + " KB received");
            new Thread(() -> ApkInstall.install(this, f), "apk-install").start();
            reply(s.getOutputStream(), "text/plain", "ok");
            return;
        }
        String body = readBody(in, head);
        if (route.startsWith("apps/")) {
            String pkg = body.trim();
            String r;
            switch (route) {
                case "apps/open": r = AppsHub.open(this, pkg); break;
                case "apps/uninstall": r = AppsHub.uninstall(this, pkg); break;
                case "apps/info": r = AppsHub.info(this, pkg); break;
                case "apps/store": r = AppsHub.store(this, pkg); break;
                default: r = "no|";
            }
            reply(s.getOutputStream(), "text/plain", r);
            return;
        }
        if (route.equals("pad/precise")) {
            // the real touches: connect to the TV's debugging bridge (the TV asks the owner once)
            LocalAdb.start(this);
            reply(s.getOutputStream(), "text/plain", PadMouse.state());
            return;
        }
        if (route.equals("screen/swipe")) {
            // "fx fy d": a finger dragged on the phone's picture; d -1 up (next), 1 down (back)
            String[] v = body.trim().split(" ");
            if (v.length == 3 && v[0].matches("[0-9.]+") && v[1].matches("[0-9.]+") && v[2].matches("-?1")) {
                PadMouse.handle(this, "v " + v[0] + " " + v[1] + " " + v[2]);
            }
            reply(s.getOutputStream(), "text/plain", "ok");
            return;
        }
        if (route.equals("screen/tap") || route.equals("screen/hold")) {
            // "fx fy": a touch on the phone's picture of the screen, as fractions of it
            String[] xy = body.trim().split(" ");
            if (xy.length == 2 && xy[0].matches("[0-9.]+") && xy[1].matches("[0-9.]+")) {
                PadMouse.handle(this, (route.equals("screen/tap") ? "a " : "l ") + xy[0] + " " + xy[1]);
            }
            reply(s.getOutputStream(), "text/plain", "ok");
            return;
        }
        if (route.equals("open")) {
            // said back to the phone: where it opened, or why not
            reply(s.getOutputStream(), "text/plain", openLink(body));
            return;
        } else if (route.equals("vol")) {
            android.media.AudioManager am = getSystemService(android.media.AudioManager.class);
            int dir = body.trim().equals("up") ? android.media.AudioManager.ADJUST_RAISE
                    : body.trim().equals("down") ? android.media.AudioManager.ADJUST_LOWER
                    : android.media.AudioManager.ADJUST_TOGGLE_MUTE;
            am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, dir, android.media.AudioManager.FLAG_SHOW_UI);
        } else if (route.equals("pad")) {
            // the phone as mouse, keys and keyboard: where the pointer is is kept here; a click is a real touch there
            if (body.length() <= 2000) PadMouse.handle(this, body);
        } else if (route.equals("solis")) {
            // the SolisCloud API key, typed by the owner: "<KeyID>\n<KeySecret>"
            String[] ks = body.split("\n", 2);
            if (ks.length == 2 && !ks[0].trim().isEmpty() && !ks[1].trim().isEmpty()) {
                SolisCloud.setKey(this, ks[0], ks[1]);
                SolisCloud.refresh(this);
            }
        } else if (route.equals("game/open")) {
            String game = body.trim();
            main.post(() -> openGame(game));
        } else if (route.equals("game/key")) {
            String key = body.trim();
            main.post(() -> gameKey(key));
        } else if (route.equals("remind")) {
            // "<minutes from now, or HH:mm>" then a new line, then the note: "ok", its time, then the notes waiting
            int cut = body.indexOf(10);
            String at = cut > 0 ? Notes.add(this, body.substring(0, cut), body.substring(cut + 1)) : null;
            reply(s.getOutputStream(), "text/plain", at == null ? "no" : "ok" + (char) 10 + at + (char) 10 + Notes.list(this));
            return;
        } else if (route.equals("remind/clear")) {
            Notes.clear(this);
        } else if (route.equals("stopadhan")) {
            Adhan.stopNow();
        } else if (route.equals("quiz/join")) {
            // "<name>\n<token it had, if any>" → "ok\n<token>", "taken" or "closed"
            String[] nt = body.split("\n", 2);
            String name = nt[0].trim(), had = nt.length == 2 ? nt[1].trim() : "";
            String r;
            if (QuizGame.hasPlayer(name) && !name.equals(quizPlayers.get(had))) r = "taken";
            else if (!QuizGame.join(name)) {
                r = "closed";
                waiting(name, "مسابقة العائلة");
            }
            else r = "ok\n" + token(quizPlayers, name, had);
            reply(s.getOutputStream(), "text/plain", r);
            return;
        } else if (route.equals("quiz/answer")) {
            // "<token>\n<choice>": only the phone that joined under a name answers for it
            String[] na = body.split("\n", 2);
            String name = na.length == 2 ? quizPlayers.get(na[0].trim()) : null;
            boolean ok = name != null && QuizGame.answer(name, parseInt(na[1].trim()));
            reply(s.getOutputStream(), "text/plain", ok ? "ok" : "no");
            return;
        } else if (route.equals("charades/start")) {
            String token = CharadesGame.start();
            reply(s.getOutputStream(), "text/plain", token == null ? "no" : token);
            return;
        } else if (route.equals("charades/got") || route.equals("charades/skip")) {
            boolean ok = route.endsWith("got") ? CharadesGame.got(body.trim()) : CharadesGame.skip(body.trim());
            reply(s.getOutputStream(), "text/plain", ok ? "ok" : "no");
            return;
        } else if (route.equals("charades/draw")) {
            // "<token>\n<segments as for /draw>"
            String[] ts = body.split("\n", 2);
            boolean ok = ts.length == 2 && CharadesGame.draw(ts[0].trim(), ts[1]);
            reply(s.getOutputStream(), "text/plain", ok ? "ok" : "no");
            return;
        } else if (route.equals("charades/clear")) {
            reply(s.getOutputStream(), "text/plain", CharadesGame.clearDrawing(body.trim()) ? "ok" : "no");
            return;
        } else if (route.startsWith("who/")) {
            // join: "<name>\n<token it had>"; the rest: "<token>" or "<token>\n<text>"
            String[] nt = body.split("\n", 2);
            String lead = nt[0].trim(), text = nt.length == 2 ? nt[1] : "";
            String r;
            if (route.equals("who/join")) {
                if (WhoGame.hasPlayer(lead) && !lead.equals(whoPlayers.get(text.trim()))) r = "taken";
                else if (!WhoGame.join(lead)) {
                    r = "closed";
                    waiting(lead, "من أنا؟");
                }
                else r = "ok\n" + token(whoPlayers, lead, text.trim());
                reply(s.getOutputStream(), "text/plain", r);
                return;
            }
            String who = whoPlayers.get(lead);
            if (who == null) {
                reply(s.getOutputStream(), "text/plain", "no");
                return;
            }
            switch (route) {
                case "who/ask": r = WhoGame.ask(who, text) ? "ok" : "no"; break;
                case "who/answer": r = WhoGame.answer(who, text.trim()) ? "ok" : "no"; break;
                case "who/hint": r = WhoGame.hint(who) ? "ok" : "no"; break;
                case "who/giveup": r = WhoGame.giveUp(who) ? "ok" : "no"; break;
                case "who/guess": r = WhoGame.guess(who, text); break;
                default: r = "no";
            }
            reply(s.getOutputStream(), "text/plain", r);
            return;
        } else if (route.equals("send")) {
            String text = body.trim();
            if (text.length() > MAX) text = text.substring(0, MAX);
            if (!text.isEmpty()) {
                String t = text;
                main.post(() -> show(t));
            }
        } else if (route.equals("draw")) {
            String segs = body;
            main.post(() -> draw(segs));
        } else if (route.equals("clear")) {
            main.post(this::hideDrawing);
        }
        reply(s.getOutputStream(), "text/plain", "ok");
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return -1;
        }
    }

    private String cachedPage;

    /** The phone page, from assets/phone.html. */
    private String page() {
        if (cachedPage != null) return cachedPage;
        try (InputStream in = getAssets().open("phone.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            cachedPage = b.toString("UTF-8");
        } catch (Exception e) {
            cachedPage = PAGE;
        }
        return cachedPage;
    }

    private static byte[] readBytes(InputStream in, String head, int max) throws Exception {
        int len = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = new byte[Math.min(len, max)];
        int got = 0;
        while (got < body.length) {
            int r = in.read(body, got, body.length - got);
            if (r < 0) break;
            got += r;
        }
        return got == body.length ? body : java.util.Arrays.copyOf(body, got);
    }

    /** A link from the phone: YouTube in SmartTube, anything else in the browser. */
    /** YouTube's own apps on the TV, best first. */
    private static final String[] YOUTUBE = {"org.smarttube.beta", "org.smarttube.stable", "com.teamsmart.videomanager.tv",
            "com.liskovsoft.smarttubetv.beta", "com.google.android.youtube.tv", "com.google.android.youtube"};
    /** Browsers made for a TV remote, best first; Android's own choice after them. */
    private static final String[] BROWSERS = {"com.tcl.browser", "com.opera.browser", "org.mozilla.tv.firefox",
            "com.android.chrome", "org.droidtv.nettvbrowser"};

    /**
     * Any link from the phone. Whatever was pasted (a shared text with a link inside, a bare "example.com") is turned
     * into the link itself; YouTube's links open in SmartTube (or YouTube), every other link in the TV's browser.
     * Returns "ok|<what opened it>" or "no|<why not>" for the phone.
     */
    private String openLink(String text) {
        String t = text == null ? "" : text.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)https?://[^\\s<>\"']+").matcher(t);
        String url;
        if (m.find()) url = m.group();
        else {
            // a bare address somewhere in the text ("look at example.com/page")
            java.util.regex.Matcher b = java.util.regex.Pattern.compile(
                    "(?i)(?<![@\\w.-])((?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}(?:/[^\\s<>\"']*)?)").matcher(t);
            if (b.find()) url = "https://" + b.group(1);
            else return "no|هذا ليس رابطاً · انسخ الرابط كاملاً والصقه هنا";
        }
        url = url.replaceAll("[).,;!?،]+$", "");
        // Arabic letters in the address (a page's name) written the way every browser understands them
        try {
            java.net.URL j = new java.net.URL(url);
            url = new java.net.URI(j.getProtocol(), j.getUserInfo(), java.net.IDN.toASCII(j.getHost()), j.getPort(),
                    java.net.URLDecoder.decode(j.getPath().replace("+", "%2B"), "UTF-8"), j.getQuery(), j.getRef())
                    .toASCIIString();
        } catch (Exception ignored) {
        }
        android.net.Uri u = android.net.Uri.parse(url);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean youtube = host.equals("youtu.be") || host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.equals("youtube-nocookie.com") || host.endsWith(".youtube-nocookie.com");
        Adhan.home(this, "w");   // out of the screensaver first, or the link opens behind it
        android.content.pm.PackageManager pm = getPackageManager();
        for (String pkg : youtube ? YOUTUBE : BROWSERS) {
            Intent i = new Intent(Intent.ACTION_VIEW, u).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i);
                return "ok|" + label(pkg);
            } catch (Exception ignored) {
            }
        }
        if (youtube) {
            // no YouTube app answered: the browser then
            String r = openIn(u, BROWSERS, pm);
            if (r != null) return r;
        }
        // Android's own choice (it may ask on the TV which app)
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_VIEW, u), "افتح الرابط بـ")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "ok|اختر التطبيق على التلفزيون";
        } catch (Exception e) {
            return "no|لا يوجد على التلفزيون تطبيق يفتح هذا الرابط";
        }
    }

    private String openIn(android.net.Uri u, String[] pkgs, android.content.pm.PackageManager pm) {
        for (String pkg : pkgs) {
            Intent i = new Intent(Intent.ACTION_VIEW, u).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(pm) == null) continue;
            try {
                startActivity(i);
                return "ok|" + label(pkg);
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private String label(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception e) {
            return pkg;
        }
    }

    private View photoShown;

    /** A photo from the phone, large in the middle of the screen for 30 seconds. */
    private void showPhoto(byte[] img) {
        if (!Settings.canDrawOverlays(this) || img == null || img.length == 0) return;
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(img, 0, img.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return;
        int sample = 1;
        while (o.outWidth / (sample * 2) >= 1920 || o.outHeight / (sample * 2) >= 1080) sample *= 2;
        o = new android.graphics.BitmapFactory.Options();
        o.inSampleSize = sample;
        android.graphics.Bitmap bm;
        try {
            bm = android.graphics.BitmapFactory.decodeByteArray(img, 0, img.length, o);
        } catch (OutOfMemoryError e) {
            return;
        }
        if (bm == null) return;
        WindowManager wm = getSystemService(WindowManager.class);
        if (photoShown != null) {
            try {
                wm.removeView(photoShown);
            } catch (Exception ignored) {
            }
        }
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.setBackgroundColor(0xCC000000);
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageBitmap(bm);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        android.widget.FrameLayout.LayoutParams ip = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT);
        ip.setMargins(80, 60, 80, 60);
        frame.addView(iv, ip);
        frame.setAlpha(0f);
        try {
            wm.addView(frame, overlay(WindowManager.LayoutParams.MATCH_PARENT));
        } catch (Exception e) {
            return;
        }
        photoShown = frame;
        frame.animate().alpha(1f).setDuration(500).start();
        main.postDelayed(() -> frame.animate().alpha(0f).setDuration(700).withEndAction(() -> {
            try {
                wm.removeView(frame);
            } catch (Exception ignored) {
            }
            if (photoShown == frame) photoShown = null;
        }).start(), 30_000);
    }

    private static String readHead(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) != -1 && b.size() < 8192) {
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) break;
        }
        return b.toString("UTF-8");
    }

    private static String readBody(InputStream in, String head) throws Exception {
        int len = 0;
        for (String line : head.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = new byte[Math.min(len, 65536)];
        int got = 0;
        while (got < body.length) {
            int r = in.read(body, got, body.length - got);
            if (r < 0) break;
            got += r;
        }
        return new String(body, 0, got, StandardCharsets.UTF_8);
    }

    private static void redirect(OutputStream out, String to) throws Exception {
        out.write(("HTTP/1.1 302 Found\r\nLocation: " + to + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private long waitingShownAt;

    /** A player asked to join a game that is not on the TV: a line on the TV says so, at most twice a minute. */
    private void waiting(String name, String game) {
        long now = System.currentTimeMillis();
        if (name.isEmpty() || now - waitingShownAt < 30_000) return;
        waitingShownAt = now;
        String who = name.length() > 16 ? name.substring(0, 16) : name;
        main.post(() -> show(who + " ينتظر اللعب" + (char) 10 + "افتحوا «" + game + "» واختاروا «كل لاعب من جواله»"));
    }

    /** The screen of this app that is in front on the TV, null when none is. */
    private static volatile android.app.Activity front;

    /** A game chosen on the phone: "quiz", "tf", "who", "charades", "memory" by phones, "teams:<game>" by teams. */
    private void openGame(String game) {
        try {
            Intent i;
            if (game.startsWith("teams:")) {
                i = new Intent(this, TeamsActivity.class).putExtra(TeamsActivity.EXTRA, game.substring(6));
            } else {
                Class<?> c = game.equals("quiz") ? QuizActivity.class : game.equals("tf") ? TrueFalseActivity.class
                        : game.equals("who") ? WhoActivity.class : game.equals("charades") ? CharadesActivity.class
                        : game.equals("memory") ? MemoryActivity.class : null;
                if (c == null) return;
                i = new Intent(this, c);
            }
            // one game at a time: the one in front closes first
            android.app.Activity a = front;
            if (a != null && !(a instanceof GamesActivity)) a.finish();
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception ignored) {
        }
    }

    /**
     * The phone as the TV's remote for this app's own screens: the arrows, OK and Back. The key goes to whichever of
     * this app's windows has the focus, a dialog included; when another app is in front the system refuses it.
     */
    private void gameKey(String key) {
        if (front == null) return;
        final int code;
        switch (key) {
            case "up": code = android.view.KeyEvent.KEYCODE_DPAD_UP; break;
            case "down": code = android.view.KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "left": code = android.view.KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "right": code = android.view.KeyEvent.KEYCODE_DPAD_RIGHT; break;
            case "ok": code = android.view.KeyEvent.KEYCODE_DPAD_CENTER; break;
            case "back": code = android.view.KeyEvent.KEYCODE_BACK; break;
            default: return;
        }
        keys.execute(() -> {
            try {
                new android.app.Instrumentation().sendKeyDownUpSync(code);
            } catch (Exception ignored) {
            }
        });
    }

    /** One key after the other, off the main thread (sendKeyDownUpSync waits for the key to be handled). */
    private static final java.util.concurrent.ExecutorService keys = java.util.concurrent.Executors.newSingleThreadExecutor();

    private static void forbidden(OutputStream out) throws Exception {
        out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** The page that asks for the code; a code after # in the address (the TV's QR code) is tried by itself. */
    private static final String DOOR = "<!doctype html><html dir=rtl lang=ar><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1'><title>شاشة البيت</title>"
            + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0f1a17;"
            + "color:#fff;font-family:-apple-system,system-ui,sans-serif}div{width:min(88vw,380px);text-align:center}"
            + "h1{color:#e3be6e;font-size:24px}input{width:100%;box-sizing:border-box;font-size:30px;text-align:center;"
            + "letter-spacing:8px;padding:14px;border-radius:16px;border:1px solid #2f4a42;background:#16241f;color:#fff}"
            + "button{margin-top:14px;width:100%;padding:16px;font-size:19px;font-weight:700;border:0;border-radius:16px;"
            + "background:#1f6f50;color:#fff}p{opacity:.75;min-height:1.4em}</style></head><body><div>"
            + "<h1>شاشة البيت</h1><p>أدخل رمز الدخول الظاهر على التلفزيون</p>"
            + "<input id=c inputmode=numeric maxlength=6 autocomplete=off><button onclick='go(c.value)'>دخول</button><p id=m></p></div>"
            + "<script>var c=document.getElementById('c'),m=document.getElementById('m');"
            + "function go(v){v=(v||'').trim();if(!v)return;fetch('auth',{method:'POST',body:v}).then(function(r){return r.text()})"
            + ".then(function(t){if(t==='locked')m.textContent='محاولات كثيرة · انتظر عشر دقائق';"
            + "else if(t==='no')m.textContent='الرمز غير صحيح';else{try{localStorage.setItem('noorS',t)}catch(e){}"
            + "location.replace((t.indexOf('/')>0?t:'s/'+t)+'/')}}).catch(function(){m.textContent='تعذّر الاتصال بالتلفزيون'})}"
            + "c.addEventListener('keydown',function(e){if(e.key==='Enter')go(c.value)});"
            + "if(location.hash.length>1){go(location.hash.substring(1))}else{var k=null;try{k=localStorage.getItem('noorS')}catch(e){}"
            + "if(k){var u=(k.indexOf('/')>0?k:'s/'+k);fetch(u+'/ping').then(function(r){return r.status===200?r.text():''}).then(function(t){if(t==='ok')location.replace(u+'/')})}}"
            + "</script></body></html>";

    private static void notFound(OutputStream out) throws Exception {
        out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void replyBytes(OutputStream out, String type, byte[] b, String cache) throws Exception {
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nCache-Control: " + cache + "\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static void reply(OutputStream out, String type, String body) throws Exception {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static WindowManager.LayoutParams overlay(int size) {
        return new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
    }

    private void draw(String segs) {
        if (!Settings.canDrawOverlays(this)) return;
        if (drawing == null) {
            drawing = new DrawLayer(this);
            try {
                getSystemService(WindowManager.class).addView(drawing, overlay(WindowManager.LayoutParams.MATCH_PARENT));
            } catch (Exception e) {
                drawing = null;
                return;
            }
        }
        DrawLayer d = drawing;
        // The view has no size until its first layout; draw after it.
        d.post(() -> {
            for (String seg : segs.split("\n")) {
                if (!seg.isEmpty()) d.add(seg);
            }
        });
        main.removeCallbacks(hideDrawing);
        main.postDelayed(hideDrawing, DRAW_IDLE);
    }

    private void hideDrawing() {
        main.removeCallbacks(hideDrawing);
        if (drawing == null) return;
        DrawLayer d = drawing;
        drawing = null;
        d.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            try {
                getSystemService(WindowManager.class).removeView(d);
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void show(String text) {
        if (!Settings.canDrawOverlays(this)) return;
        WindowManager wm = getSystemService(WindowManager.class);
        if (shown != null) {
            try {
                wm.removeView(shown);
            } catch (Exception ignored) {
            }
        }
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(Color.WHITE);
        // 54sp for a few words, smaller for a long message, so it all stays on the screen
        v.setTextSize(text.length() <= 60 ? 54 : text.length() <= 120 ? 42 : 34);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setGravity(Gravity.CENTER);
        v.setPadding(80, 50, 80, 50);
        v.setMaxWidth(1500);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9101418);
        bg.setCornerRadius(48);
        bg.setStroke(3, 0x66FFFFFF);
        v.setBackground(bg);
        v.setAlpha(0f);
        WindowManager.LayoutParams lp = overlay(WindowManager.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER;
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            return;
        }
        shown = v;
        v.animate().alpha(1f).setDuration(400).start();
        main.postDelayed(() -> v.animate().alpha(0f).setDuration(600).withEndAction(() -> {
            try {
                wm.removeView(v);
            } catch (Exception ignored) {
            }
            if (shown == v) shown = null;
        }).start(), 20_000);
    }

    private static final String PAGE = "<!doctype html><html dir=rtl lang=ar><head><meta charset=utf-8>"
            + "<meta name=viewport content='width=device-width,initial-scale=1,user-scalable=no'><title>رسالة للتلفزيون</title><style>"
            + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
            + "background:#0f1a17;font-family:-apple-system,system-ui,sans-serif;color:#fff}"
            + ".c{width:min(94vw,560px)}h1{font-size:24px;margin:0 0 14px}"
            + ".tabs{display:flex;gap:8px;margin-bottom:12px}.tabs button{flex:1;margin:0;background:#16241f;border:1px solid #2f4a42}"
            + ".tabs button.on{background:#1f6f50;border-color:#1f6f50}"
            + "textarea{width:100%;box-sizing:border-box;height:140px;font-size:22px;padding:14px;border-radius:16px;"
            + "border:1px solid #2f4a42;background:#16241f;color:#fff;resize:none}"
            + "canvas{width:100%;aspect-ratio:16/9;background:#16241f;border-radius:16px;border:1px solid #2f4a42;touch-action:none;display:block}"
            + ".row{display:flex;gap:10px;margin-top:12px;align-items:center}.dot{width:38px;height:38px;border-radius:50%;border:3px solid transparent}"
            + ".dot.on{border-color:#fff}"
            + "button{margin-top:14px;width:100%;padding:16px;font-size:20px;font-weight:700;border:0;border-radius:16px;"
            + "background:#1f6f50;color:#fff}.row button{margin:0;width:auto;flex:1;background:#3a2a2a}"
            + "p{opacity:.7;text-align:center;min-height:1.4em}.hide{display:none}</style></head><body>"
            + "<div class=c><h1>📺 للتلفزيون</h1>"
            + "<div class=tabs><button id=bw class=on onclick=tab(0)>✍️ اكتب</button><button id=bd onclick=tab(1)>🎨 ارسم</button></div>"
            + "<div id=w><textarea id=t maxlength=200 placeholder='اكتب هني...'></textarea><button onclick=send()>أرسل</button></div>"
            + "<div id=d class=hide><canvas id=cv></canvas><div class=row id=colors></div>"
            + "<div class=row><button onclick=clr()>امسح</button></div></div>"
            + "<p id=s></p></div><script>"
            + "function $(i){return document.getElementById(i)}"
            + "function tab(n){$('w').className=n?'hide':'';$('d').className=n?'':'hide';$('bw').className=n?'':'on';$('bd').className=n?'on':'';if(n)fit()}"
            + "function send(){var t=$('t');if(!t.value.trim())return;fetch('send',{method:'POST',body:t.value})"
            + ".then(function(){$('s').textContent='وصلت ✅';t.value=''}).catch(function(){$('s').textContent='ما وصلت، جرّب مرة ثانية'})}"
            + "var cv=$('cv'),cx=cv.getContext('2d'),col='#ffffff',q=[],last=null,cont=false;"
            + "['#ffffff','#f5c542','#4ade80','#f87171','#60a5fa'].forEach(function(c,i){var b=document.createElement('div');"
            + "b.className='dot'+(i?'':' on');b.style.background=c;b.onclick=function(){col=c;"
            + "Array.prototype.forEach.call(document.querySelectorAll('.dot'),function(x){x.className='dot'});b.className='dot on'};$('colors').appendChild(b)});"
            + "function fit(){var r=cv.getBoundingClientRect();cv.width=r.width*2;cv.height=r.height*2}"
            + "function pt(e){var r=cv.getBoundingClientRect();return[(e.clientX-r.left)/r.width,(e.clientY-r.top)/r.height]}"
            + "function line(a,b){cx.strokeStyle=col;cx.lineWidth=6;cx.lineCap='round';cx.beginPath();"
            + "cx.moveTo(a[0]*cv.width,a[1]*cv.height);cx.lineTo(b[0]*cv.width,b[1]*cv.height);cx.stroke()}"
            + "cv.onpointerdown=function(e){cv.setPointerCapture(e.pointerId);last=pt(e);cont=false;q=[last]};"
            + "cv.onpointermove=function(e){if(!last)return;var p=pt(e);line(last,p);last=p;q.push(p)};"
            + "cv.onpointerup=cv.onpointercancel=function(){flush();last=null};"
            + "function flush(){if(q.length<1)return;var s=(cont?'+':'')+col+' '+q.map(function(p){return p[0].toFixed(4)+','+p[1].toFixed(4)}).join(' ');"
            + "if(last){q=[last];cont=true}else q=[];fetch('draw',{method:'POST',body:s})}"
            + "setInterval(function(){if(last&&q.length>1)flush()},80);"
            + "function clr(){cx.clearRect(0,0,cv.width,cv.height);fetch('clear',{method:'POST',body:''})}"
            + "</script></body></html>";
}
