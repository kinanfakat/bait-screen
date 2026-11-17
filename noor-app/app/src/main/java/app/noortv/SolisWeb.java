package app.noortv;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SolisCloud's own website inside the app, for accounts that have no API key: the owner signs in once in this
 * WebView (its cookies stay in the app), and the answers the site's own pages fetch are kept here as they arrive,
 * so EnergyActivity can draw them its own way. Nothing leaves the TV: the page talks only to soliscloud.com.
 */
final class SolisWeb {
    static final String HOME = "https://www.soliscloud.com/";
    /** The site's answers by request path, newest of each. */
    static final Map<String, String> got = new ConcurrentHashMap<>();
    static volatile long gotAt;
    static volatile String url = "";

    /** Hooks the page's own requests; runs once per document. */
    private static final String HOOK = "(function(){if(window.__noor)return;window.__noor=1;"
            + "var O=XMLHttpRequest.prototype.open,S=XMLHttpRequest.prototype.send;"
            + "XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return O.apply(this,arguments)};"
            + "XMLHttpRequest.prototype.send=function(){var x=this;x.addEventListener('load',function(){try{"
            + "var t=(x.responseType===''||x.responseType==='text')?x.responseText:(x.responseType==='json'?JSON.stringify(x.response):'');"
            + "if(t&&t.length<600000)Noor.got(String(x.__u),t)}catch(e){}});return S.apply(this,arguments)};"
            + "var F=window.fetch;if(F)window.fetch=function(){var a=arguments,u=(a[0]&&a[0].url)||a[0];"
            + "return F.apply(this,a).then(function(r){try{r.clone().text().then(function(t){if(t.length<600000)Noor.got(String(u),t)})}catch(e){}return r})};"
            + "})();";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    static WebView create(Context c) {
        WebView w = new WebView(c);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        // the station's page opens as a new window: here it loads in this one
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void got(String u, String body) {
                String path = u;
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                int h = path.indexOf("soliscloud.com");
                if (h >= 0) path = path.substring(h + 14);
                // only the station's own numbers are kept: never the sign-in or the account's answers
                if (!path.startsWith("/api/station/") || !body.startsWith("{")) return;
                SolisWeb.got.put(path, body);
                gotAt = System.currentTimeMillis();
                if (path.endsWith("/station/list")) openStation(w, body);
                if (path.endsWith("/station/detailMix")) detail(body);
            }
        }, "Noor");
        w.setWebChromeClient(new WebChromeClient());
        w.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String u, android.graphics.Bitmap icon) {
                url = u;
                v.evaluateJavascript(HOOK, null);
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                url = u;
                v.evaluateJavascript(HOOK, null);
                CookieManager.getInstance().flush();
            }

            @Override
            public void doUpdateVisitedHistory(WebView v, String u, boolean reload) {
                url = u;
                v.evaluateJavascript(HOOK, null);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest r) {
                // only the Solis site in here
                String host = r.getUrl().getHost();
                return host == null || !host.endsWith("soliscloud.com");
            }
        });
        return w;
    }

    static volatile long loadedAt;
    private static String station = "";

    /** The station's page if it is known, else the site's first page (sign-in, then the list). */
    static void start(WebView w) {
        loadedAt = System.currentTimeMillis();
        if (station.isEmpty()) {
            station = w.getContext().getSharedPreferences("solis", Context.MODE_PRIVATE).getString("station", "");
        }
        w.loadUrl(station.isEmpty() ? HOME : HOME + "overview/plantStation/details/overview/" + station);
    }

    /** The list of stations arrived: straight on to the first one's page. */
    private static void openStation(WebView w, String body) {
        try {
            JSONObject d = new JSONObject(body).getJSONObject("data");
            org.json.JSONArray recs = d.has("page") ? d.getJSONObject("page").getJSONArray("records") : d.getJSONArray("records");
            String id = recs.getJSONObject(0).getString("id");
            if (id.isEmpty() || id.equals(station)) return;
            station = id;
            w.getContext().getSharedPreferences("solis", Context.MODE_PRIVATE).edit().putString("station", id).apply();
            w.post(() -> start(w));
        } catch (Exception ignored) {
        }
    }

    /** The station's detail, under the names the API's inverter detail uses, for EnergyActivity's sheet. */
    private static void detail(String body) {
        try {
            JSONObject d = new JSONObject(body).getJSONObject("data");
            alias(d, "dcPac", "power");
            alias(d, "batteryCapacitySoc", "batteryPercent");
            alias(d, "eToday", "dayEnergy");
            alias(d, "gridPurchasedTodayEnergy", "gridPurchasedDayEnergy");
            SolisCloud.raw = d.toString();
            SolisCloud.data = d;
            SolisCloud.error = null;
            long t = d.optLong("dataTimestamp", 0);
            SolisCloud.at = t > 0 ? t : System.currentTimeMillis();
        } catch (Exception ignored) {
        }
    }

    private static void alias(JSONObject d, String to, String from) throws org.json.JSONException {
        if (!d.has(from)) return;
        d.put(to, d.get(from));
        if (d.has(from + "Str")) d.put(to + "Str", d.get(from + "Str"));
    }

    static boolean atLogin() {
        return url.contains("/login");
    }

    /** Everything kept, for the phone page's debug route: {"url":..., "paths": {path: body}}. */
    static String dump(String only) {
        try {
            JSONObject o = new JSONObject().put("url", url).put("at", gotAt);
            JSONObject paths = new JSONObject();
            for (Map.Entry<String, String> e : got.entrySet()) {
                if (only == null || only.isEmpty()) paths.put(e.getKey(), e.getValue().length());
                else if (e.getKey().contains(only)) paths.put(e.getKey(), e.getValue());
            }
            return o.put("paths", paths).toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** A touch at x,y of the view: how the remote's pointer clicks. */
    static void tap(View v, float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0);
        d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(d);
        v.dispatchTouchEvent(u);
        d.recycle();
        u.recycle();
    }

    /** The pointer the remote's arrows move over the site. */
    static final class Pointer extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        float px = 960, py = 540;

        Pointer(Context c) {
            super(c);
            fill.setColor(0xFFFFFFFF);
            edge.setColor(0xFF10201A);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(3);
            edge.setStrokeJoin(Paint.Join.ROUND);
            shape.moveTo(0, 0);
            shape.lineTo(0, 36);
            shape.lineTo(11, 26);
            shape.lineTo(19, 42);
            shape.lineTo(25, 39);
            shape.lineTo(18, 24);
            shape.lineTo(31, 24);
            shape.close();
        }

        void move(float dx, float dy) {
            px = Math.max(0, Math.min(getWidth() - 2, px + dx));
            py = Math.max(0, Math.min(getHeight() - 2, py + dy));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            c.translate(px, py);
            c.drawPath(shape, fill);
            c.drawPath(shape, edge);
        }
    }

    private SolisWeb() {
    }
}
