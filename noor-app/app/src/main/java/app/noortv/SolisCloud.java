package app.noortv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The house's Solis inverter, read from SolisCloud's own API (the Wi-Fi stick answers nothing on the home network).
 * The account's API key (KeyID and KeySecret, from soliscloud.com) is typed by the owner on the phone page and kept
 * in prefs "solis"; every request is signed with it (HMAC-SHA1) as the API asks. EnergyActivity shows the detail.
 */
final class SolisCloud {
    private static final String HOST = "https://www.soliscloud.com:13333";

    /** The inverter's detail as the API gave it, null before the first answer. */
    static volatile JSONObject data;
    static volatile String raw = "";
    /** Why the last request failed, in the family's words; null when it worked. */
    static volatile String error;
    static volatile long at;
    private static volatile boolean busy;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("solis", Context.MODE_PRIVATE);
    }

    static boolean hasKey(Context c) {
        return !p(c).getString("id", "").isEmpty() && !p(c).getString("secret", "").isEmpty();
    }

    /** From the phone page; a new key forgets the inverter found with the old one. */
    static void setKey(Context c, String id, String secret) {
        p(c).edit().putString("id", id.trim()).putString("secret", secret.trim()).remove("inv").remove("sn").commit();
        data = null;
        error = null;
        at = 0;
    }

    /** One refresh on a thread of its own; a second call while it runs does nothing. */
    static void refresh(Context c) {
        if (busy || !hasKey(c)) return;
        busy = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                load(app);
                error = null;
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                error = e instanceof java.io.IOException && !m.startsWith("solis:") ? "ما في اتصال بخدمة SolisCloud (الإنترنت مقطوع؟)"
                        : m.startsWith("solis:") ? m.substring(6) : "خطأ بقراءة بيانات المنظومة";
            } finally {
                busy = false;
            }
        }, "solis").start();
    }

    private static void load(Context c) throws Exception {
        SharedPreferences p = p(c);
        String inv = p.getString("inv", ""), sn = p.getString("sn", "");
        if (inv.isEmpty()) {
            JSONObject list = post(c, "/v1/api/inverterList", "{\"pageNo\":1,\"pageSize\":20}");
            JSONObject d = list.optJSONObject("data");
            JSONObject page = d == null ? null : d.optJSONObject("page");
            JSONArray recs = page == null ? null : page.optJSONArray("records");
            if (recs == null || recs.length() == 0) throw new Exception("solis:ما في انفرتر مسجّل على هالحساب");
            JSONObject first = recs.getJSONObject(0);
            inv = first.optString("id");
            sn = first.optString("sn");
            p.edit().putString("inv", inv).putString("sn", sn).apply();
        }
        JSONObject detail = post(c, "/v1/api/inverterDetail", new JSONObject().put("id", inv).put("sn", sn).toString());
        JSONObject d = detail.optJSONObject("data");
        if (d == null) throw new Exception("solis:الخدمة ما رجّعت بيانات الانفرتر");
        raw = d.toString();
        data = d;
        at = System.currentTimeMillis();
    }

    private static JSONObject post(Context c, String path, String body) throws Exception {
        SharedPreferences p = p(c);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String md5 = Base64.encodeToString(MessageDigest.getInstance("MD5").digest(bytes), Base64.NO_WRAP);
        SimpleDateFormat f = new SimpleDateFormat("EEE, d MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = f.format(new Date()), type = "application/json";
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(p.getString("secret", "").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        String sign = Base64.encodeToString(mac.doFinal(("POST\n" + md5 + "\n" + type + "\n" + date + "\n" + path)
                .getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        HttpURLConnection h = (HttpURLConnection) new URL(HOST + path).openConnection();
        h.setConnectTimeout(10_000);
        h.setReadTimeout(15_000);
        h.setRequestMethod("POST");
        h.setDoOutput(true);
        h.setRequestProperty("Content-MD5", md5);
        h.setRequestProperty("Content-Type", type);
        h.setRequestProperty("Date", date);
        h.setRequestProperty("Authorization", "API " + p.getString("id", "") + ":" + sign);
        try (OutputStream o = h.getOutputStream()) {
            o.write(bytes);
        }
        int code = h.getResponseCode();
        String text;
        try (InputStream in = code >= 400 ? h.getErrorStream() : h.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while (in != null && (n = in.read(buf)) > 0) b.write(buf, 0, n);
            text = b.toString("UTF-8");
        } finally {
            h.disconnect();
        }
        if (code == 401 || code == 403) throw new Exception("solis:المفتاح مرفوض · تأكد من KeyID و KeySecret");
        if (code == 429) throw new Exception("solis:طلبات كتير · رح نجرّب بعد شوي");
        JSONObject j;
        try {
            j = new JSONObject(text);
        } catch (Exception e) {
            throw new Exception("solis:جواب غير مفهوم من الخدمة (" + code + ")");
        }
        if (!"0".equals(j.optString("code")) && !j.optBoolean("success")) {
            throw new Exception("solis:الخدمة رفضت الطلب: " + j.optString("msg", String.valueOf(code)));
        }
        return j;
    }

    /** A power in kW: the API gives a number and its unit apart (pac and pacStr). */
    static double kw(String name) {
        JSONObject d = data;
        if (d == null) return 0;
        double v = d.optDouble(name, 0);
        if (Double.isNaN(v)) return 0;
        String u = d.optString(name + "Str", "kW");
        return u.equalsIgnoreCase("W") ? v / 1000 : u.equalsIgnoreCase("MW") ? v * 1000 : v;
    }

    /** An energy in kWh. */
    static double kwh(String name) {
        JSONObject d = data;
        if (d == null) return 0;
        double v = d.optDouble(name, 0);
        if (Double.isNaN(v)) return 0;
        String u = d.optString(name + "Str", "kWh");
        return u.equalsIgnoreCase("Wh") ? v / 1000 : u.equalsIgnoreCase("MWh") ? v * 1000 : u.equalsIgnoreCase("GWh") ? v * 1_000_000 : v;
    }

    static double num(String name) {
        JSONObject d = data;
        double v = d == null ? 0 : d.optDouble(name, 0);
        return Double.isNaN(v) ? 0 : v;
    }

    static boolean has(String name) {
        JSONObject d = data;
        return d != null && d.has(name) && !d.isNull(name);
    }

    private SolisCloud() {
    }
}
