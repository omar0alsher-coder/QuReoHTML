package com.qureo.premium;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.os.PowerManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView webView;
    private String lastSetCookies = "";
    private PowerManager.WakeLock wakeLock;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new CookieBridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "QuReo::Solver");
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    public class CookieBridge {
        @JavascriptInterface
        public void setCookies(String cookies) {
            String domain = "https://me-tp.qureo.education";
            for (String pair : cookies.split(";")) {
                String trimmed = pair.trim();
                if (!trimmed.isEmpty()) {
                    CookieManager.getInstance().setCookie(domain, trimmed);
                }
            }
            CookieManager.getInstance().flush();
        }

        @JavascriptInterface
        public String getLastCookies() {
            return lastSetCookies;
        }

        @JavascriptInterface
        public String request(String method, String urlStr, String body) {
            int maxRedirects = 10;
            String currentMethod = method;
            String currentUrl = urlStr;
            String currentBody = body;
            lastSetCookies = "";

            for (int i = 0; i < maxRedirects; i++) {
                try {
                    URL url = new URL(currentUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(currentMethod);
                    conn.setInstanceFollowRedirects(false);
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Requested-With", "mark.via.gp");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                    conn.setRequestProperty("Origin", "https://me-tp.qureo.education");
                    conn.setRequestProperty("Referer", "https://me-tp.qureo.education/");
                    conn.setRequestProperty("Accept", "*/*");

                    if (currentBody != null && !currentBody.isEmpty() && (currentMethod.equals("POST") || currentMethod.equals("PUT"))) {
                        conn.setDoOutput(true);
                        OutputStream os = conn.getOutputStream();
                        os.write(currentBody.getBytes("UTF-8"));
                        os.flush();
                        os.close();
                    }

                    int responseCode = conn.getResponseCode();
                    
                    Map<String, List<String>> headerFields = conn.getHeaderFields();
                    List<String> cookiesHeader = headerFields.get("Set-Cookie");
                    if (cookiesHeader != null) {
                        StringBuilder sb = new StringBuilder();
                        for (String cookie : cookiesHeader) {
                            sb.append(cookie).append("; ");
                        }
                        lastSetCookies = sb.toString();
                    }

                    if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                        String loc = conn.getHeaderField("Location");
                        if (loc == null || loc.isEmpty()) break;
                        if (loc.startsWith("/")) {
                            loc = "https://me-tp.qureo.education" + loc;
                        } else if (!loc.startsWith("http")) {
                            loc = "https://me-tp.qureo.education/" + loc;
                        }
                        if (responseCode == 301 || responseCode == 302 || responseCode == 303) {
                            currentMethod = "GET";
                            currentBody = null;
                        }
                        currentUrl = loc;
                        continue;
                    }

                    InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                    if (is == null) return "{\"status\":" + responseCode + ",\"body\":\"\"}";
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line);
                    }
                    br.close();
                    return "{\"status\":" + responseCode + ",\"body\":" + response.toString() + "}";

                } catch (Exception e) {
                    return "{\"status\":0,\"body\":\"{\\\"error\\\":\\\"" + e.getMessage().replace("\"", "'") + "\\\"}\"}";
                }
            }
            return "{\"status\":0,\"body\":\"{\\\"error\\\":\\\"Too many redirects\\\"}\"}";
        }
    }
}
