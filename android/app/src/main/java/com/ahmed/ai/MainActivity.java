package com.ahmed.ai;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
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

public class MainActivity extends Activity {
    private WebView webView;
    private String currentBase64Image = "";
    private String currentMimeType = "image/jpeg";
    private static final int PICK_IMAGE_REQUEST = 1;

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

        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.parseColor("#05050a"));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient()); 

        webView.loadUrl("file:///android_asset/index.html");
    }

    // استقبال الصورة من المستخدم
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                currentMimeType = getContentResolver().getType(uri);
                InputStream inputStream = getContentResolver().openInputStream(uri);
                byte[] bytes = new byte[inputStream.available()];
                inputStream.read(bytes);
                currentBase64Image = Base64.encodeToString(bytes, Base64.DEFAULT);
                inputStream.close();

                // إرسال الصورة للواجهة عشان تظهر كـ Preview
                String js = "onImageSelected('data:" + currentMimeType + ";base64," + currentBase64Image + "')";
                webView.post(() -> webView.evaluateJavascript(js, null));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public class Bridge {
        @JavascriptInterface
        public void openFilePicker() {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            startActivityForResult(intent, PICK_IMAGE_REQUEST);
        }

        @JavascriptInterface
        public void clearImage() {
            currentBase64Image = "";
        }

        @JavascriptInterface
        public String requestAI(String reqStr) {
            try {
                JSONObject req = new JSONObject(reqStr);
                URL url = new URL(req.getString("url"));
                String apiKey = req.getString("apiKey");
                JSONObject bodyObj = req.getJSONObject("body");
                
                // لو في صورة، نضيفها للـ payload
                if (!currentBase64Image.isEmpty()) {
                    String base64Url = "data:" + currentMimeType + ";base64," + currentBase64Image;
                    org.json.JSONArray contentArr = new org.json.JSONArray();
                    
                    // النص
                    org.json.JSONObject textPart = new org.json.JSONObject();
                    textPart.put("type", "text");
                    textPart.put("text", bodyObj.getJSONArray("messages").getJSONObject(0).getString("content"));
                    contentArr.put(textPart);
                    
                    // الصورة
                    org.json.JSONObject imagePart = new org.json.JSONObject();
                    imagePart.put("type", "image_url");
                    org.json.JSONObject imgObj = new org.json.JSONObject();
                    imgObj.put("url", base64Url);
                    imagePart.put("image_url", imgObj);
                    contentArr.put(imagePart);

                    // تحديث الرسالة لتحتوي على النص والصورة
                    org.json.JSONArray msgs = new org.json.JSONArray();
                    org.json.JSONObject msg = new org.json.JSONObject();
                    msg.put("role", "user");
                    msg.put("content", contentArr);
                    msgs.put(msg);
                    bodyObj.put("messages", msgs);
                    
                    currentBase64Image = ""; // تصفير الصورة بعد الإرسال
                }

                String body = bodyObj.toString();
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(120000); // وقت كافي للـ AI
                conn.setReadTimeout(120000);
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
                    String reasoning = message.optString("reasoning_content", ""); 
                    return "{\"content\":" + JSONObject.quote(content) + ",\"reasoning_content\":" + JSONObject.quote(reasoning) + "}";
                } else {
                    return "{\"error\":" + JSONObject.quote("API Error " + code + ": " + sb.toString()) + "}";
                }
            } catch (Exception e) {
                return "{\"error\":" + JSONObject.quote(e.getMessage()) + "}";
            }
        }
    }
}
