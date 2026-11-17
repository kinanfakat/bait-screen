package app.noortv;

import android.content.Context;
import android.util.Base64;

import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.concurrent.LinkedBlockingQueue;

import javax.crypto.Cipher;

/**
 * Real touches for the phone's mouse: the TV has no touch screen, so a click made through the accessibility service
 * only reaches what an app declares clickable, and phone apps (TikTok, Instagram) declare little. The TV's own
 * debugging bridge (adb over the network, already on) can type a real touch at a point, exactly like a finger. This
 * app talks to it on 127.0.0.1:5555 with its own key; the first time, the TV asks «السماح بتصحيح الأخطاء؟» once and
 * the owner allows it with the remote. Commands go one after the other on one connection kept open.
 */
final class LocalAdb {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05,
            0x00, 0x04, 0x14};

    /** "ready", "asking" (the TV shows its question), "off" (not reachable) or "" before the first try. */
    static volatile String state = "";
    private static final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>(64);
    private static Thread worker;
    private static int nextId = 1;

    static boolean ready() {
        return "ready".equals(state);
    }

    /** Runs a shell command on the TV (input tap …), in order; dropped when the bridge is not there. */
    static synchronized void run(Context c, String cmd) {
        queue.offer(cmd);
        start(c);
    }

    /**
     * For the TV's own housekeeping (the memory guard, the adhan): connects only when the TV allowed it before, so
     * that «السماح بتصحيح الأخطاء؟» never pops up by itself over a film or an adhan on a TV that has not said yes.
     * The question is asked only from something the family does (the phone page).
     */
    static synchronized void startQuiet(Context c) {
        Context app = c.getApplicationContext();
        // a TV that had the bridge before this setting existed: its key is there, it said yes back then
        boolean before = new java.io.File(app.getFilesDir(), "adbkey").exists();
        if (!app.getSharedPreferences("localadb", Context.MODE_PRIVATE).getBoolean("allowed", before)) return;
        start(c);
    }

    /** Connects (and asks for the TV's leave the first time) without a command. */
    static synchronized void start(Context c) {
        if (worker != null && worker.isAlive()) return;
        Context app = c.getApplicationContext();
        worker = new Thread(() -> loop(app), "local-adb");
        worker.start();
    }

    private static void loop(Context c) {
        for (int round = 0; round < 3; round++) {
            try (Socket s = new Socket("127.0.0.1", 5555)) {
                s.setTcpNoDelay(true);
                s.setSoTimeout(125_000);
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                if (!connect(c, in, out)) {
                    state = "off";
                    queue.clear();
                    return;
                }
                state = "ready";
                while (true) {
                    String cmd = queue.poll(60, java.util.concurrent.TimeUnit.SECONDS);
                    if (cmd == null) {
                        // an idle minute: let the connection go, it comes back with the next command
                        return;
                    }
                    shell(in, out, cmd);
                }
            } catch (Exception e) {
                state = "off";
                try {
                    Thread.sleep(800);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
        // not reachable: what waited is old now (a touch, a close), not to be done later by surprise
        queue.clear();
    }

    private static boolean connect(Context c, InputStream in, OutputStream out) throws Exception {
        send(out, CNXN, 0x01000000, 4096, "host::noortv\0".getBytes(StandardCharsets.UTF_8));
        KeyPair keys = keys(c);
        boolean signed = false;
        while (true) {
            Msg m = read(in, 0);
            if (m.cmd == CNXN) return allowed(c, true);
            if (m.cmd != AUTH || m.arg0 != 1) continue;
            if (!signed) {
                signed = true;
                send(out, AUTH, 2, 0, sign(keys.getPrivate(), m.data));
            } else {
                // not known yet: the TV asks the owner; up to two minutes for the answer
                state = "asking";
                send(out, AUTH, 3, 0, publicKey((RSAPublicKey) keys.getPublic()));
                Msg r;
                try {
                    r = read(in, 120_000);
                } catch (Exception e) {
                    return allowed(c, false);   // no answer: the next try is only from the phone page
                }
                return allowed(c, r.cmd == CNXN);
            }
        }
    }

    private static boolean allowed(Context c, boolean yes) {
        c.getSharedPreferences("localadb", Context.MODE_PRIVATE).edit().putBoolean("allowed", yes).apply();
        return yes;
    }

    private static void shell(InputStream in, OutputStream out, String cmd) throws Exception {
        int local = nextId++;
        send(out, OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        long until = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < until) {
            Msg m = read(in, 8000);
            if (m.arg1 != local) continue;
            if (m.cmd == WRTE) send(out, OKAY, local, m.arg0, new byte[0]);
            else if (m.cmd == CLSE) {
                send(out, CLSE, local, m.arg0, new byte[0]);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- the wire

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static void send(OutputStream out, int cmd, int a0, int a1, byte[] data) throws Exception {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xFF;
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt(sum).putInt(~cmd).put(data);
        out.write(b.array());
        out.flush();
    }

    private static Msg read(InputStream in, int timeout) throws Exception {
        DataInputStream d = new DataInputStream(in);
        byte[] h = new byte[24];
        d.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int len = b.getInt();
        m.data = new byte[Math.max(0, Math.min(len, 1 << 20))];
        d.readFully(m.data);
        return m;
    }

    // ---------------------------------------------------------------- the key

    private static KeyPair keys(Context c) throws Exception {
        File pri = new File(c.getFilesDir(), "adbkey"), pub = new File(c.getFilesDir(), "adbkey.pub.x509");
        KeyFactory f = KeyFactory.getInstance("RSA");
        if (pri.exists() && pub.exists()) {
            return new KeyPair(f.generatePublic(new X509EncodedKeySpec(java.nio.file.Files.readAllBytes(pub.toPath()))),
                    f.generatePrivate(new PKCS8EncodedKeySpec(java.nio.file.Files.readAllBytes(pri.toPath()))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair k = g.generateKeyPair();
        java.nio.file.Files.write(pri.toPath(), k.getPrivate().getEncoded());
        java.nio.file.Files.write(pub.toPath(), k.getPublic().getEncoded());
        return k;
    }

    /** adbd checks the token as an SHA-1 digest: PKCS#1 v1.5 over the digest's prefix and the token itself. */
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher cph = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cph.init(Cipher.ENCRYPT_MODE, key);
        cph.update(SHA1_PREFIX);
        return cph.doFinal(token);
    }

    /** The public key in Android's own form (what ~/.android/adbkey.pub holds), base64, a name, and a zero. */
    private static byte[] publicKey(RSAPublicKey k) {
        final int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32), n = k.getModulus();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        BigInteger x = n;
        for (int i = 0; i < words; i++) {
            b.putInt(x.mod(r32).intValue());
            x = x.shiftRight(32);
        }
        x = rr;
        for (int i = 0; i < words; i++) {
            b.putInt(x.mod(r32).intValue());
            x = x.shiftRight(32);
        }
        b.putInt(k.getPublicExponent().intValue());
        String s = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " noortv@tv\0";
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private LocalAdb() {
    }
}
