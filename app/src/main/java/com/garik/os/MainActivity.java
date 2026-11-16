package com.garik.os;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.util.Log;
import android.os.Build;
import android.content.pm.PackageManager;
import java.util.Locale;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_WAKE_MIC = 7103;
    private static final int REQUEST_WAKE_NOTIFICATIONS = 7104;
    private WebView web;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(android.graphics.Color.rgb(8,19,33));
        getWindow().setNavigationBarColor(android.graphics.Color.rgb(8,19,33));
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (getIntent() != null && getIntent().getBooleanExtra("launch_chat", false)) {
                    getIntent().removeExtra("launch_chat");
                    view.evaluateJavascript("openAssistant()", null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        requestHomeShortcut();

        // Launch ChatGPT immediately after the notification tap.
        // Do not wait for WebView page load and JavaScript bridge startup.
        if (getIntent() != null && getIntent().getBooleanExtra("launch_chat", false)) {
            getIntent().removeExtra("launch_chat");
            launchChatGPT();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("launch_chat", false)) {
            intent.removeExtra("launch_chat");
            launchChatGPT();
        }
    }

    private void requestHomeShortcut() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            ShortcutManager manager = getSystemService(ShortcutManager.class);
            if (manager == null || !manager.isRequestPinShortcutSupported()) return;
            boolean alreadyPinned = false;
            for (ShortcutInfo item : manager.getPinnedShortcuts()) {
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
                String failure = "Неизвестная ошибка";
                for (int attempt = 1; attempt <= 2; attempt++) {
                    HttpURLConnection connection = null;
                    try {
                        URL url = new URL("https://api.open-meteo.com/v1/forecast?latitude=57.212&longitude=41.930&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m&daily=temperature_2m_max,temperature_2m_min&forecast_days=3&timezone=Europe%2FMoscow");
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        connection.setConnectTimeout(20000);
                        connection.setReadTimeout(20000);
                        connection.setInstanceFollowRedirects(true);
                        connection.setRequestProperty("Accept", "application/json");
                        connection.setRequestProperty("User-Agent", "GARIK-OS/2.1");
                        int status = connection.getResponseCode();
                        if (status < 200 || status >= 300) {
                            throw new java.io.IOException("HTTP " + status);
                        }
                        StringBuilder body = new StringBuilder();
                        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"))) {
                            String line;
                            while ((line = reader.readLine()) != null) body.append(line);
                        }
                        final String json = body.toString();
                        runOnUiThread(() -> {
                            if (web != null) web.evaluateJavascript("renderWeather(" + JSONObject.quote(json) + ")", null);
                        });
                        return;
                    } catch (Exception error) {
                        failure = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
                        Log.e("GARIK_WEATHER", "Weather request attempt " + attempt + " failed: " + failure, error);
                        if (attempt < 2) {
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        }
                    } finally {
                        if (connection != null) connection.disconnect();
                    }
                }
                final String message = failure;
                runOnUiThread(() -> {
                    if (web != null) web.evaluateJavascript("renderWeatherError(" + JSONObject.quote(message) + ")", null);
                });
            }).start();
        }

        @JavascriptInterface public void openChatGPT() {
            runOnUiThread(() -> launchChatGPT());
        }

        @JavascriptInterface public void openApp(String name) {
            runOnUiThread(() -> {
                String wanted = name == null ? "" : name.toLowerCase(new Locale("ru"));
                Intent query = new Intent(Intent.ACTION_MAIN, null);
                query.addCategory(Intent.CATEGORY_LAUNCHER);
                java.util.List<android.content.pm.ResolveInfo> apps = getPackageManager().queryIntentActivities(query, 0);
                for (android.content.pm.ResolveInfo app : apps) {
                    String label = app.loadLabel(getPackageManager()).toString().toLowerCase(new Locale("ru"));
                    if (label.equals(wanted) || label.contains(wanted) || wanted.contains(label)) {
                        Intent launch = getPackageManager().getLaunchIntentForPackage(app.activityInfo.packageName);
                        if (launch != null) { startActivity(launch); return; }
                    }
                }
                if (web != null) web.evaluateJavascript("commandResult(" + JSONObject.quote("Не нашёл приложение: " + name) + ")", null);
            });
        }

        @JavascriptInterface public void toggleWakeWord() {
            runOnUiThread(() -> {
                try {
                    if (WakeWordService.isRunning()) {
                        stopService(new Intent(MainActivity.this, WakeWordService.class));
                        if (web != null) web.evaluateJavascript("wakeWordResult(false,'Режим пробуждения выключен')", null);
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= 23 &&
                            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_WAKE_MIC);
                    } else {
                        requestWakeNotificationPermission();
                    }
                } catch (Throwable error) {
                    Log.e("GARIK_WAKE", "toggleWakeWord failed", error);
                    showWakeError(error);
                }
            });
        }
        @JavascriptInterface public void startWakeWord() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT >= 23 &&
                        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_WAKE_MIC);
                } else requestWakeNotificationPermission();
            });
        }
        @JavascriptInterface public void stopWakeWord() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, WakeWordService.class));
                if (web != null) web.evaluateJavascript("wakeWordResult(false,'Режим пробуждения выключен')", null);
            });
        }
        @JavascriptInterface public boolean isWakeWordActive() { return WakeWordService.isRunning(); }
    }

    private void requestWakeNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_WAKE_NOTIFICATIONS
            );
        } else {
            startWakeWordService();
        }
    }

    private void startWakeWordService() {
        try {
            Intent service = new Intent(this, WakeWordService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            Log.i("GARIK_WAKE", "WakeWordService start request sent");
            if (web != null) web.evaluateJavascript("wakeWordResult(true,'Запрос отправлен. Запускаю микрофон и распознавание…')", null);
        } catch (Throwable error) {
            Log.e("GARIK_WAKE", "Unable to start WakeWordService", error);
            showWakeError(error);
        }
    }

    private void showWakeError(Throwable error) {
        String detail = error == null ? "Неизвестная ошибка" :
            error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
        if (web != null) web.evaluateJavascript(
            "wakeWordResult(false," + JSONObject.quote("Не удалось включить пробуждение: " + detail) + ")", null);
    }

    private void launchChatGPT() {
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
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_WAKE_MIC) {
            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestWakeNotificationPermission();
            } else if (web != null) {
                web.evaluateJavascript(
                    "wakeWordResult(false,'Без разрешения на микрофон фраза пробуждения не работает.')",
                    null
                );
            }
        } else if (requestCode == REQUEST_WAKE_NOTIFICATIONS) {
            if (Build.VERSION.SDK_INT < 33 ||
                    (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                startWakeWordService();
            } else if (web != null) {
                web.evaluateJavascript(
                    "wakeWordResult(false,'Разреши уведомления, чтобы GARIK OS мог работать в фоне.')",
                    null
                );
            }
        }
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack(); else super.onBackPressed();
    }
}