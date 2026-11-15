package com.garik.os;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.provider.Settings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.os.Build;
import java.util.Collections;

public class MainActivity extends Activity {
    private WebView web;
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(8,19,33));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(8,19,33));
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        requestHomeShortcut();
    }

    private void requestHomeShortcut() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            ShortcutManager manager = getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return;
            boolean alreadyPinned = false;
            for (android.content.pm.ShortcutInfo item : manager.getPinnedShortcuts()) {
                if ("garik_home".equals(item.getId())) { alreadyPinned = true; break; }
            }
            if (alreadyPinned) return;
            Intent launch = new Intent(this, MainActivity.class);
            launch.setAction(Intent.ACTION_MAIN);
            ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "garik_home")
                .setShortLabel("Гарик")
                .setLongLabel("GARIK OS")
                .setIcon(Icon.createWithResource(this, R.drawable.ic_launcher))
                .setIntent(launch)
                .build();
            manager.requestPinShortcut(shortcut, null);
        } catch (Exception ignored) {}
    }

    public class Bridge {
        @JavascriptInterface public void openChatGPT() {
            runOnUiThread(() -> {
                getSharedPreferences("garik", MODE_PRIVATE).edit()
                    .putBoolean("voice_pending", true).putInt("voice_attempts", 0).apply();
                Intent launch = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(launch);
                } else {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.openai.chatgpt"))); }
                    catch (Exception ignored) {}
                }
            });
        }
        @JavascriptInterface public boolean isVoiceServiceEnabled() {
            try {
                String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (enabled == null) return false;
                String component = new android.content.ComponentName(MainActivity.this, VoiceAssistService.class).flattenToString();
                return enabled.toLowerCase(java.util.Locale.ROOT).contains(component.toLowerCase(java.util.Locale.ROOT));
            } catch (Exception ignored) { return false; }
        }
        @JavascriptInterface public void openAccessibilitySettings() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        }
    }
    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}