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
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;

public class MainActivity extends Activity {
    private WebView webView;
    private String lastSetCookies = "";
    private String currentSessionCookies = "";

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

        // تخطي حماية SSL
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {}

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
            currentSessionCookies = cookies;
        }

        @JavascriptInterface
        public String getLastCookies() {
            return lastSetCookies;
        }

        @JavascriptInterface
        public void clearCookies() {
            currentSessionCookies = "";
            lastSetCookies = "";
        }

        @JavascriptInterface
        public String request(String method, String urlStr, String body) {
            try {
                URL url = new URL(urlStr);
                String host = url.getHost();
                String origin = "https://" + host;
                String referer = origin + "/";
                if (host.contains("portal")) referer = origin + "/login";

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setInstanceFollowRedirects(false); // إيقاف اللف ورا السيرفر
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Requested-With", "mark.via.gp");
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                conn.setRequestProperty("Origin", origin);
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Accept", "*/*");
                
                if (!currentSessionCookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", currentSessionCookies);
                }

                if (body != null && !body.isEmpty() && (method.equals("POST") || method.equals("PUT"))) {
                    conn.setDoOutput(true);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }

                int responseCode = conn.getResponseCode();
                
                // التقاط الكوكيز
                Map<String, List<String>> headerFields = conn.getHeaderFields();
                List<String> cookiesHeader = headerFields.get("Set-Cookie");
                if (cookiesHeader == null) cookiesHeader = headerFields.get("set-cookie");
                
                if (cookiesHeader != null) {
                    StringBuilder sb = new StringBuilder();
                    for (String cookie : cookiesHeader) {
                        sb.append(cookie.split(";")[0]).append("; ");
                    }
                    lastSetCookies = sb.toString();
                }

                // لو السيرفر عمل Redirect، بنرجع 302 فوراً ونكمل في الجافاسكريبت
                if (responseCode == 301 || responseCode == 302 || responseCode == 303 || responseCode == 307 || responseCode == 308) {
                    return "{\"status\":" + responseCode + ",\"body\":\"\"}";
                }

                InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                if (is == null) return "{\"status\":" + responseCode + ",\"body\":\"\"}";
                
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) response.append(line);
                br.close();
                
                return "{\"status\":" + responseCode + ",\"body\":" + JSONObject.quote(response.toString()) + "}";

            } catch (Exception e) {
                return "{\"status\":0,\"body\":" + JSONObject.quote("{\"error\":\"" + e.getClass().getSimpleName() + ": " + e.getMessage().replace("\"", "'") + "\"}") + "}";
            }
        }
    }
}
