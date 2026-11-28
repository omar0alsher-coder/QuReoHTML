package com.qureo.premium;

// ... [الاستيرادات كما هي] ...
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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;
import com.google.android.gms.ads.rewarded.RewardedAd;
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback;
import com.google.android.gms.ads.appopen.AppOpenAd;

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
    // ... [المتغيرات كما هي] ...
    private WebView webView;
    private final Map<String, String> cookies = new LinkedHashMap<>();
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;
    
    private AdView bannerAdView;
    private InterstitialAd interstitialAd;
    private RewardedAd rewardedAd;
    private AppOpenAd appOpenAd;
    private boolean isAppOpenAdLoading = false;

    private final String BANNER_ID = "ca-app-pub-4776915813805093/2834244871";
    private final String INTERSTITIAL_ID = "ca-app-pub-4776915813805093/2257987818";
    private final String REWARDED_ID = "ca-app-pub-4776915813805093/9806847330";
    private final String APP_OPEN_ID = "ca-app-pub-4776915813805093/3524189218";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("QureoPrefs", Context.MODE_PRIVATE);
        
        // ... [إعدادات الشاشة والأذونات كما هي] ...
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            getWindow().setAttributes(params);
        }
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
        );

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

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Qureo::MainWakeLock");
            wakeLock.acquire();
        }

        MobileAds.initialize(this, initializationStatus -> {
            loadAppOpenAd();
            loadInterstitialAd();
            loadRewardedAd();
        });

        setupWebView();
    }

    private void setupWebView() {
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        
        bannerAdView = new AdView(this);
        bannerAdView.setAdUnitId(BANNER_ID);
        bannerAdView.setAdSize(AdSize.BANNER);
        LinearLayout.LayoutParams bannerParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mainLayout.addView(bannerAdView, bannerParams);
        bannerAdView.loadAd(new AdRequest.Builder().build());

        webView = new WebView(this);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setBackgroundColor(android.graphics.Color.parseColor("#050914"));
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        mainLayout.addView(webView, webParams);

        setContentView(mainLayout);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("whatsapp://") || url.startsWith("https://wa.me") || url.startsWith("https://api.whatsapp.com") || url.startsWith("https://chat.whatsapp.com")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        intent.setPackage("com.whatsapp");
                        startActivity(intent);
                        return true;
                    } catch (Exception e) { return true; }
                }
                return false;
            }
        });
        webView.setWebChromeClient(new WebChromeClient()); 
        webView.loadUrl("file:///android_asset/index.html");
    }

    // ... [دوال الإعلانات loadAppOpenAd, loadInterstitialAd, loadRewardedAd كما هي] ...
    private void loadAppOpenAd() {
        if (isAppOpenAdLoading) return;
        isAppOpenAdLoading = true;
        AppOpenAd.load(this, APP_OPEN_ID, new AdRequest.Builder().build(), new AppOpenAd.AppOpenAdLoadCallback() {
            @Override public void onAdLoaded(AppOpenAd ad) { appOpenAd = ad; appOpenAd.show(MainActivity.this); isAppOpenAdLoading = false; }
            @Override public void onAdFailedToLoad(LoadAdError error) { isAppOpenAdLoading = false; }
        });
    }
    private void loadInterstitialAd() {
        InterstitialAd.load(this, INTERSTITIAL_ID, new AdRequest.Builder().build(), new InterstitialAdLoadCallback() {
            @Override public void onAdLoaded(InterstitialAd ad) { interstitialAd = ad; }
            @Override public void onAdFailedToLoad(LoadAdError error) {}
        });
    }
    private void loadRewardedAd() {
        RewardedAd.load(this, REWARDED_ID, new AdRequest.Builder().build(), new RewardedAdLoadCallback() {
            @Override public void onAdLoaded(RewardedAd ad) { rewardedAd = ad; }
            @Override public void onAdFailedToLoad(LoadAdError error) {}
        });
    }

    @Override protected void onResume() { super.onResume(); if (webView != null) webView.resumeTimers(); }
    @Override protected void onDestroy() { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); super.onDestroy(); }

    public class Bridge {
        // ... [إعلانات وأوامر كما هي] ...
        @JavascriptInterface public void showInterstitialAd() { runOnUiThread(() -> { if (interstitialAd != null) { interstitialAd.show(MainActivity.this); loadInterstitialAd(); } }); }
        @JavascriptInterface public void showRewardedAd() { runOnUiThread(() -> { if (rewardedAd != null) { rewardedAd.show(MainActivity.this, rewardItem -> { webView.evaluateJavascript("onRewardEarned()", null); }); loadRewardedAd(); } else { webView.evaluateJavascript("onRewardEarned()", null); } }); }
        @JavascriptInterface public void saveSession() { prefs.edit().putString("cookies", serializeCookies()).apply(); }
        @JavascriptInterface public void clearCookies() { cookies.clear(); prefs.edit().remove("cookies").apply(); }
        @JavascriptInterface public String getSavedCookies() { return serializeCookies(); }
        @JavascriptInterface public void setCookie(String name, String value) { if (name != null && !name.isEmpty()) cookies.put(name, value == null ? "" : value); }
        @JavascriptInterface public void sendNotification(String title, String message) { /* ... */ }
        @JavascriptInterface public void startBackgroundService() { /* ... */ }
        @JavascriptInterface public void stopBackgroundService() { /* ... */ }

        // ⭐ دالة الـ AI الجديدة اللي بتدعم الهيدرز
        @JavascriptInterface
        public String requestAI(String reqStr) {
            try {
                JSONObject req = new JSONObject(reqStr);
                URL url = new URL(req.getString("url"));
                String apiKey = req.getString("apiKey");
                String body = req.getJSONObject("body").toString();

                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(60000); // وقت أطول للـ AI
                conn.setReadTimeout(60000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setDoOutput(true);

                OutputStream os = conn.getOutputStream();
                os.write(body.getBytes("UTF-8"));
                os.flush(); os.close();

                int code = conn.getResponseCode();
                InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
                StringBuilder sb = new StringBuilder();
                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                    String line; while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                }

                if (code >= 200 && code < 400) {
                    JSONObject response = new JSONObject(sb.toString());
                    JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
                    String content = message.optString("content", "");
                    String reasoning = message.optString("reasoning_content", ""); // DeepSeek style
                    return "{\"content\":" + JSONObject.quote(content) + ",\"reasoning_content\":" + JSONObject.quote(reasoning) + "}";
                } else {
                    return "{\"error\":" + JSONObject.quote("API Error " + code + ": " + sb.toString()) + "}";
                }
            } catch (Exception e) {
                return "{\"error\":" + JSONObject.quote(e.getMessage()) + "}";
            }
        }

        // ... [request, mergeCookie, serializeCookies كما هي] ...
        @JavascriptInterface public String request(String method, String urlStr, String body) { /* ... */ return ""; }
    }
    
    // ... [mergeCookie, serializeCookies] ...
}
