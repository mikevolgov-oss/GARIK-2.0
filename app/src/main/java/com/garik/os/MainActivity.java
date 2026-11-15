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
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_COMMAND = 7102;
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
        @JavascriptInterface public void loadWeather() {
            new Thread(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=57.212&longitude=41.930&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&forecast_days=3&timezone=Europe%2FMoscow");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("Accept", "application/json");
                    StringBuilder body = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                        String line;
                        while ((line = reader.readLine()) != null) body.append(line);
                    }
                    final String json = body.toString();
                    runOnUiThread(() -> {
                        if (web != null) web.evaluateJavascript("renderWeather(" + JSONObject.quote(json) + ")", null);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> {
                        if (web != null) web.evaluateJavascript("renderWeatherError()", null);
                    });
                } finally {
                    if (connection != null) connection.disconnect();
                }
            }).start();
        }

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

        @JavascriptInterface public void startCommandRecognition() {
            runOnUiThread(() -> {
                try {
                    Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Скажите команду для Гарика");
                    i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    startActivityForResult(i, REQUEST_COMMAND);
                } catch (Exception e) {
                    if (web != null) web.evaluateJavascript("commandResult('Голосовой ввод недоступен')", null);
                }
            });
        }
        @JavascriptInterface public void openWifi() {
            runOnUiThread(() -> {
                try { if (Build.VERSION.SDK_INT >= 29) startActivity(new Intent(Settings.Panel.ACTION_WIFI)); else startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); }
            });
        }
        @JavascriptInterface public void openBluetooth() {
            runOnUiThread(() -> {
                try { if (Build.VERSION.SDK_INT >= 29) startActivity(new Intent(Settings.Panel.ACTION_BLUETOOTH)); else startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
                catch (Exception e) { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            });
        }
        @JavascriptInterface public void volume(String direction) {
            android.media.AudioManager a = (android.media.AudioManager)getSystemService(AUDIO_SERVICE);
            if (a != null) a.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                "up".equals(direction) ? android.media.AudioManager.ADJUST_RAISE : android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI);
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

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_COMMAND && web != null) {
            if (resultCode == RESULT_OK && data != null) {
                java.util.ArrayList<String> a = data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                String phrase = a != null && !a.isEmpty() ? a.get(0) : "";
                web.evaluateJavascript("onGarikCommand(" + JSONObject.quote(phrase) + ")", null);
            } else web.evaluateJavascript("commandResult('Не расслышал. Попробуй ещё раз.')", null);
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}