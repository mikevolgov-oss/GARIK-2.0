package ru.garik.os;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;

    public class GarikBridge {
        @JavascriptInterface public void openChatGPT() {
            runOnUiThread(() -> {
                getSharedPreferences("garik_voice", MODE_PRIVATE)
                    .edit().putBoolean("pending_voice_click", true).apply();
                Intent launch = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } else {
                    getSharedPreferences("garik_voice", MODE_PRIVATE)
                        .edit().putBoolean("pending_voice_click", false).apply();
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/")));
                }
            });
        }

        @JavascriptInterface public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(8,19,33));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(8,19,33));
        webView = new WebView(this);
        webView.setBackgroundColor(android.graphics.Color.rgb(8,19,33));
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        webView.addJavascriptInterface(new GarikBridge(), "Android");
        webView.setWebViewClient(new WebViewClient());
        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}