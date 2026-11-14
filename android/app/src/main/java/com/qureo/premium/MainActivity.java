package com.qureo.premium;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
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
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private WebView webView;
    private String lastSetCookies = "";

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // شاشة كاملة 100%
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );

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
            lastSetCookies = ""; // تصفير الكوكيز قبل كل طلب

            for (int i = 0; i < maxRedirects; i++) {
                try {
                    URL url = new URL(currentUrl);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod(currentMethod);
                    conn.setInstanceFollowRedirects(false); // مهم جداً لمنع تحويل الصفحات
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
                    
                    // التقاط الكوكيز من الـ Headers في كل مرة
                    Map<String, List<String>> headerFields = conn.getHeaderFields();
                    List<String> cookiesHeader = headerFields.get("Set-Cookie");
                    if (cookiesHeader == null) cookiesHeader = headerFields.get("set-cookie");
                    
                    if (cookiesHeader != null) {
                        StringBuilder sb = new StringBuilder();
                        for (String cookie : cookiesHeader) {
                            sb.append(cookie.split(";")[0]).append("; ");
                        }
                        if (lastSetCookies.isEmpty() || i == 0) {
                            lastSetCookies = sb.toString();
                        } else {
                            lastSetCookies += sb.toString();
                        }
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
                    
                    // تغليف الرد بـ JSONObject.quote لمنع أي خطأ
                    return "{\"status\":" + responseCode + ",\"body\":" + JSONObject.quote(response.toString()) + "}";

                } catch (Exception e) {
                    return "{\"status\":0,\"body\":" + JSONObject.quote("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}") + "}";
                }
            }
            return "{\"status\":0,\"body\":\"{\\\"error\\\":\\\"Too many redirects\\\"}\"}";
        }
    }
}
