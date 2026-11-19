package com.qureo.premium;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
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
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences("QureoPrefs", Context.MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                startActivity(intent);
            }
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(params);
        }
        
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Qureo::MainWakeLock");
            wakeLock.acquire();
        }

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
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setRenderPriority(WebSettings.RenderPriority.HIGH);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true);
        }

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient()); 

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.resumeTimers();
            // حل مشكلة الشاشة السوداء (لو الـ WebView قعد كتير ووقف)
            if (webView.getUrl() == null || webView.getUrl().equals("about:blank")) {
                webView.loadUrl("file:///android_asset/index.html");
            } else {
                webView.evaluateJavascript("if(document.body.innerHTML.trim() === '') { location.reload(); }", null);
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    public class Bridge {
        @JavascriptInterface
        public void saveSession() {
            prefs.edit().putString("cookies", serializeCookies()).apply();
        }

        @JavascriptInterface
        public void clearCookies() { 
            cookies.clear(); 
            prefs.edit().remove("cookies").apply();
        }

        @JavascriptInterface
        public String getSavedCookies() { return serializeCookies(); }

        @JavascriptInterface
        public void setCookie(String name, String value) {
            if (name != null && !name.isEmpty()) cookies.put(name, value == null ? "" : value);
        }

        @JavascriptInterface
        public void sendNotification(String title, String message) {
            NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            String channelId = "qureo_status";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(channelId, "Qureo Notifications", NotificationManager.IMPORTANCE_HIGH);
                mNotificationManager.createNotificationChannel(channel);
            }
            NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(MainActivity.this, channelId)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(message)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH);
            mNotificationManager.notify((int) System.currentTimeMillis(), mBuilder.build());
        }

        @JavascriptInterface
        public void startBackgroundService() {
            Intent serviceIntent = new Intent(MainActivity.this, SolverService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

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
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(15000);

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
