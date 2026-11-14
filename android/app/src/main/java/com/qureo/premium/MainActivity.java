package com.qureo.premium;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView webView;
    private final Map<String, String> cookies = new LinkedHashMap<>();

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );

        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((h, s) -> true);
        } catch (Exception ignored) {}

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/index.html");
    }

    public class Bridge {
        @JavascriptInterface
        public void clearCookies() { cookies.clear(); }

        @JavascriptInterface
        public void setCookie(String name, String value) {
            if (name != null && !name.isEmpty()) cookies.put(name, value == null ? "" : value);
        }

        @JavascriptInterface
        public String getLastCookies() { return serializeCookies(); }

        // تشغيل الخلفية
        @JavascriptInterface
        public void startBackgroundService() {
            Intent serviceIntent = new Intent(MainActivity.this, SolverService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        // إيقاف الخلفية
        @JavascriptInterface
        public void stopBackgroundService() {
            Intent serviceIntent = new Intent(MainActivity.this, SolverService.class);
            stopService(serviceIntent);
        }

        @JavascriptInterface
        public String request(String method, String urlStr, String body) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                int redirects = 0;
                int maxRedirects = 10;
                int code = 0;
                String finalMethod = method;
                String finalBody = body;

                while (redirects <= maxRedirects) {
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(finalMethod);
                    conn.setInstanceFollowRedirects(false);
                    conn.setConnectTimeout(30000);
                    conn.setReadTimeout(30000);

                    String origin = "https://" + url.getHost();
                    String referer = url.getHost().contains("portal") ? origin + "/login" : origin + "/";

                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("X-Requested-With", "mark.via.gp");
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
                    conn.setRequestProperty("Origin", origin);
                    conn.setRequestProperty("Referer", referer);
                    conn.setRequestProperty("Accept", "*/*");

                    String cookieStr = serializeCookies();
                    if (!cookieStr.isEmpty()) conn.setRequestProperty("Cookie", cookieStr);

                    if (finalBody != null && !finalBody.isEmpty() && (finalMethod.equals("POST") || finalMethod.equals("PUT") || finalMethod.equals("PATCH"))) {
                        conn.setDoOutput(true);
                        OutputStream os = conn.getOutputStream();
                        os.write(finalBody.getBytes("UTF-8"));
                        os.flush();
                        os.close();
                    }

                    code = conn.getResponseCode();

                    Map<String, List<String>> headers = conn.getHeaderFields();
                    List<String> setCookies = headers.get("Set-Cookie");
                    if (setCookies == null) setCookies = headers.get("set-cookie");
                    if (setCookies != null) {
                        for (String c : setCookies) mergeCookie(c);
                    }

                    if (code >= 300 && code < 400) {
                        String location = conn.getHeaderField("Location");
                        if (location != null && !location.isEmpty()) {
                            url = new URL(url, location);
                            if (code == 303 || code == 302 || code == 301) {
                                finalMethod = "GET"; 
                                finalBody = null;
                            }
                            redirects++;
                            conn.disconnect();
                            continue; 
                        }
                    }
                    break; 
                }

                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                }
                return "{\"status\":" + code + ",\"body\":" + JSONObject.quote(sb.toString()) + "}";
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                return "{\"status\":0,\"body\":" + JSONObject.quote("{\"error\":" + JSONObject.quote(msg) + "}") + "}";
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
    }

    private void mergeCookie(String setCookie) {
        if (setCookie == null) return;
        String[] parts = setCookie.split(";");
        String pair = parts[0].trim();
        int eq = pair.indexOf('=');
        if (eq <= 0) return;
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (name.isEmpty()) return;
        cookies.put(name, value);
    }

    private String serializeCookies() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : cookies.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        return sb.toString();
    }
}
