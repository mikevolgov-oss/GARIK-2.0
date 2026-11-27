package com.garik.os;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.speech.RecognizerIntent;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
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
import java.util.ArrayList;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int REQUEST_WAKE_MIC = 7103;
    private static final int REQUEST_WAKE_NOTIFICATIONS = 7104;
    private WebView web;
    private SpeechRecognizer voiceRecognizer;
    private TextToSpeech textToSpeech;
    private boolean voiceSessionActive = false;
    private boolean voiceRequestPending = false;
    private final Handler voiceHandler = new Handler(Looper.getMainLooper());

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
                if (getIntent() != null && getIntent().getBooleanExtra("launch_voice", false)) {
                    getIntent().removeExtra("launch_voice");
                    stopService(new Intent(MainActivity.this, WakeWordService.class));
                    view.evaluateJavascript("startGarikVoice()", null);
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");
        requestHomeShortcut();

        // Voice mode starts after WebView and the Android bridge are ready.
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("launch_voice", false)) {
            intent.removeExtra("launch_voice");
            stopService(new Intent(this, WakeWordService.class));
            if (web != null) web.post(() -> web.evaluateJavascript("startGarikVoice()", null));
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


        @JavascriptInterface public boolean hasApiKey() {
            return ApiKeyStore.hasKey(MainActivity.this);
        }

        @JavascriptInterface public boolean saveApiSettings(String key, String model) {
            try {
                String safeModel = model == null || model.trim().isEmpty() ? "auto" : model.trim();
                if (safeModel.length() > 100) throw new IllegalArgumentException("Слишком длинное название модели.");
                if (key != null && !key.trim().isEmpty()) {
                    ApiKeyStore.save(MainActivity.this, key);
                } else if (!ApiKeyStore.hasKey(MainActivity.this)) {
                    throw new IllegalArgumentException("Сначала введи API-ключ HCNSEC.");
                }
                getSharedPreferences("garik_api", MODE_PRIVATE).edit()
                    .putString("model", safeModel).apply();
                return true;
            } catch (Exception error) {
                Log.e("GARIK_API", "Unable to save API settings", error);
                return false;
            }
        }

        @JavascriptInterface public void clearApiKey() {
            ApiKeyStore.clear(MainActivity.this);
            getSharedPreferences("garik_api", MODE_PRIVATE).edit().clear().apply();
        }

        @JavascriptInterface public void sendApiMessages(String messagesJson) {
            new Thread(() -> performApiRequest(messagesJson), "GarikHcnsecRequest").start();
        }

        @JavascriptInterface public void openChatGPT() {
            runOnUiThread(() -> launchChatGPT());
        }

        @JavascriptInterface public void startVoiceSession() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, WakeWordService.class));
                beginVoiceSession();
            });
        }

        @JavascriptInterface public void stopVoiceSession() {
            runOnUiThread(() -> {
                endVoiceSession();
                startWakeWordService();
            });
        }

        @JavascriptInterface public void speakText(String text) {
            runOnUiThread(() -> speakVoiceReply(text));
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



    private void beginVoiceSession() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceStatus("Распознавание речи недоступно на этом телефоне");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_WAKE_MIC);
            voiceStatus("Разреши доступ к микрофону и запусти голосовой режим ещё раз");
            return;
        }
        voiceSessionActive = true;
        if (voiceRecognizer == null) {
            voiceRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            voiceRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { voiceStatus("Слушаю тебя…"); }
                @Override public void onBeginningOfSpeech() { voiceStatus("Говори, брат…"); }
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { voiceStatus("Обрабатываю речь…"); }
                @Override public void onError(int error) {
                    if (!voiceSessionActive || voiceRequestPending) return;
                    voiceHandler.postDelayed(() -> startVoiceListening(), 900);
                }
                @Override public void onResults(Bundle results) {
                    if (!voiceSessionActive) return;
                    ArrayList<String> matches = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    if (matches != null && !matches.isEmpty()) {
                        voiceRequestPending = true;
                        if (web != null) web.evaluateJavascript("onVoiceRecognized(" + JSONObject.quote(matches.get(0)) + ")", null);
                    } else startVoiceListening();
                }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
        if (textToSpeech == null) {
            textToSpeech = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS) textToSpeech.setLanguage(new Locale("ru", "RU"));
                startVoiceListening();
            });
        } else startVoiceListening();
        if (web != null) web.evaluateJavascript("voiceUi(true)", null);
    }

    private void startVoiceListening() {
        if (!voiceSessionActive || voiceRecognizer == null || voiceRequestPending) return;
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ru-RU");
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            voiceRecognizer.startListening(intent);
        } catch (Exception error) {
            voiceStatus("Не удалось включить микрофон: " + error.getMessage());
            voiceHandler.postDelayed(() -> startVoiceListening(), 1500);
        }
    }

    private void speakVoiceReply(String text) {
        if (!voiceSessionActive || textToSpeech == null) { voiceRequestPending = false; return; }
        String safe = text == null ? "" : text.trim();
        if (safe.isEmpty()) { voiceRequestPending = false; startVoiceListening(); return; }
        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { voiceStatus("Гарик отвечает…"); }
            @Override public void onDone(String id) {
                voiceRequestPending = false;
                voiceHandler.post(() -> startVoiceListening());
            }
            @Override public void onError(String id) {
                voiceRequestPending = false;
                voiceHandler.post(() -> startVoiceListening());
            }
        });
        textToSpeech.speak(safe, TextToSpeech.QUEUE_FLUSH, null, "garik_reply_" + System.currentTimeMillis());
    }

    private void voiceStatus(String message) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("voiceStatus(" + JSONObject.quote(message) + ")", null);
        });
    }

    private void endVoiceSession() {
        voiceSessionActive = false;
        voiceRequestPending = false;
        voiceHandler.removeCallbacksAndMessages(null);
        if (voiceRecognizer != null) {
            try { voiceRecognizer.cancel(); voiceRecognizer.destroy(); } catch (Exception ignored) {}
            voiceRecognizer = null;
        }
        if (textToSpeech != null) {
            try { textToSpeech.stop(); textToSpeech.shutdown(); } catch (Exception ignored) {}
            textToSpeech = null;
        }
        if (web != null) web.evaluateJavascript("voiceUi(false)", null);
    }

    @Override protected void onDestroy() {
        endVoiceSession();
        if (web != null) { web.stopLoading(); web.destroy(); web = null; }
        super.onDestroy();
    }

    private void performApiRequest(String messagesJson) {
        HttpURLConnection connection = null;
        try {
            String apiKey = ApiKeyStore.read(this);
            if (apiKey == null || apiKey.isEmpty()) {
                throw new IllegalStateException("Сначала добавь API-ключ в настройках HCNSEC.");
            }
            JSONArray input = new JSONArray(messagesJson);
            if (input.length() < 1 || input.length() > 20) {
                throw new IllegalArgumentException("В запросе должно быть от 1 до 20 сообщений.");
            }
            JSONArray messages = new JSONArray();
            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", "Ты GARIK, личный помощник пользователя. Отвечай на русском, прямо, дружелюбно и разговорно. Не выдумывай факты и не утверждай, что выполнил действие, если это не подтверждено. Береги конфиденциальные данные и никогда не проси присылать API-ключи в чат.");
            messages.put(systemMessage);
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.getJSONObject(i);
                String role = item.optString("role", "");
                String content = item.optString("content", "");
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    throw new IllegalArgumentException("Недопустимый тип сообщения.");
                }
                if (content.trim().isEmpty() || content.length() > 6000) {
                    throw new IllegalArgumentException("Сообщение пустое или слишком длинное.");
                }
                JSONObject safe = new JSONObject();
                safe.put("role", role);
                safe.put("content", content);
                messages.put(safe);
            }

            String model = getSharedPreferences("garik_api", MODE_PRIVATE)
                .getString("model", "auto");
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("messages", messages);

            connection = (HttpURLConnection) new URL(
                "https://api.hcnsec.cn/v1/chat/completions").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "GARIK-OS/2.2");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
                output.flush();
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream();
            String response = readApiBody(stream);
            if (status < 200 || status >= 300) {
                String detail = response;
                try {
                    JSONObject error = new JSONObject(response).optJSONObject("error");
                    if (error != null) detail = error.optString("message", response);
                } catch (Exception ignored) {}
                if (status == 429) {
                    throw new IllegalStateException("HCNSEC ограничил частоту запросов (429). Подожди немного и повтори. " + detail);
                }
                throw new IllegalStateException("HCNSEC вернул HTTP " + status + ": " + detail);
            }

            JSONObject result = new JSONObject(response);
            JSONArray choices = result.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new IllegalStateException("Сервис ответил, но не прислал текст ответа.");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            String answer = message == null ? "" : message.optString("content", "");
            if (answer.trim().isEmpty()) throw new IllegalStateException("Модель вернула пустой ответ.");
            deliverApiResponse(true, answer);
        } catch (Exception error) {
            Log.e("GARIK_API", "HCNSEC request failed: " + error.getClass().getSimpleName()
                + ": " + String.valueOf(error.getMessage()));
            deliverApiResponse(false, error.getMessage() == null
                ? "Не удалось выполнить запрос к HCNSEC." : error.getMessage());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readApiBody(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }

    private void deliverApiResponse(boolean success, String message) {
        runOnUiThread(() -> {
            if (web != null) {
                web.evaluateJavascript("onApiResponse(" + success + ","
                    + JSONObject.quote(message) + ")", null);
            }
        });
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