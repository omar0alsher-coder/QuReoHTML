package com.qureo.premium;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.os.Bundle;
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

public class MainActivity extends Activity {
    private WebView webView;
    private String currentCookies = "";

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

    public class CookieBridge {
        @JavascriptInterface
        public void setCookies(String cookies) {
            currentCookies = cookies.replace("\n", "").replace("\r", "").trim();
            String domain = "https://me-tp.qureo.education";
            for (String pair : currentCookies.split(";")) {
                String trimmed = pair.trim();
                if (!trimmed.isEmpty()) {
                    CookieManager.getInstance().setCookie(domain, trimmed);
                }
            }
            CookieManager.getInstance().flush();
        }

        @JavascriptInterface
        public String request(String method, String urlStr, String body) {
            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setInstanceFollowRedirects(false); // منع تحويل الصفحات
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", "mark.via.gp");
                conn.setRequestProperty("X-Requested-With", "mark.via.gp");
                conn.setRequestProperty("Origin", "https://me-tp.qureo.education");
                conn.setRequestProperty("Referer", "https://me-tp.qureo.education/");
                conn.setRequestProperty("Accept", "*/*");
                if (currentCookies != null && !currentCookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", currentCookies);
                }

                if (body != null && !body.isEmpty() && (method.equals("POST") || method.equals("PUT"))) {
                    conn.setDoOutput(true);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.flush();
                    os.close();
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == 302 || responseCode == 401) {
                    return "{\"error\":\"Unauthorized\"}";
                }

                InputStream is = (responseCode >= 200 && responseCode < 300) ? conn.getInputStream() : conn.getErrorStream();
                BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
                br.close();
                return response.toString();
            } catch (Exception e) {
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        }
    }
}
