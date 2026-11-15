package com.garik.os;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WakeWordService extends Service implements RecognitionListener {
    private static final String CHANNEL = "garik_wake_word";
    private static final int NOTIFICATION_ID = 2201;
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
    private static volatile boolean running = false;
    private volatile boolean triggered = false;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private PowerManager.WakeLock wakeLock;
    private Thread setupThread;

    public static boolean isRunning() { return running; }

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GARIK:WakeWord");
        wakeLock.acquire();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            Notification n = buildNotification("Загружаю распознавание речи…", false);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            running = true;
            if (setupThread == null) {
                setupThread = new Thread(this::prepareRecognizer, "GarikVoskSetup");
                setupThread.start();
            }
        } catch (Exception e) {
            running = false;
            stopSelf();
        }
        return START_STICKY;
    }

    private void prepareRecognizer() {
        try {
            File modelDir = new File(getFilesDir(), "vosk-model-small-ru-0.22");
            File marker = new File(modelDir, "conf/model.conf");
            if (!marker.isFile()) {
                updateNotification("Скачиваю русскую модель, примерно 45 МБ…", false);
                File zip = new File(getFilesDir(), "vosk-ru.zip");
                downloadModel(zip);
                unzipModel(zip, getFilesDir());
                zip.delete();
            }
            if (!marker.isFile()) throw new IllegalStateException("Файл модели не найден после распаковки");
            model = new Model(modelDir.getAbsolutePath());
            recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            updateNotification("Слушаю фразу «Гарик, не спи»", true);
            speechService.startListening(this);
        } catch (Exception e) {
            updateNotification("Ошибка запуска: " + e.getMessage(), false);
            running = false;
            stopSelf();
        }
    }

    private void downloadModel(File target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "GARIK-OS/2.1");
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        } finally {
            c.disconnect();
        }
    }

    private void unzipModel(File zipFile, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new java.io.FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = zis.getNextEntry()) != null) {
                File outFile = new File(destination, entry.getName());
                String canonical = outFile.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new SecurityException("Недопустимый путь в архиве");
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null) parent.mkdirs();
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                        int n;
                        while ((n = zis.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                }
                zis.closeEntry();
            }
        }
    }

    @Override public void onResult(String hypothesis) { inspect(hypothesis, "text"); }
    @Override public void onFinalResult(String hypothesis) { inspect(hypothesis, "text"); }
    @Override public void onPartialResult(String hypothesis) { inspect(hypothesis, "partial"); }
    @Override public void onTimeout() { }
    @Override public void onError(Exception e) {
        updateNotification("Ошибка микрофона. Проверь разрешение и перезапусти режим.", false);
        running = false;
        stopSelf();
    }

    private void inspect(String json, String key) {
        if (triggered || json == null) return;
        try {
            String phrase = new JSONObject(json).optString(key, "").toLowerCase(new Locale("ru")).replace('ё', 'е');
            phrase = phrase.replaceAll("[^а-яa-z ]", " ").replaceAll("\\s+", " ").trim();
            if (phrase.contains("гарик не спи") || phrase.contains("гарик неспи")) {
                triggered = true;
                getSharedPreferences("garik", MODE_PRIVATE).edit()
                    .putBoolean("voice_pending", true).putInt("voice_attempts", 0).apply();
                try {
                    Intent chat = getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
                    if (chat == null) throw new IllegalStateException("ChatGPT не установлен");
                    chat.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    startActivity(chat);
                    stopSelf();
                } catch (Exception blocked) {
                    updateNotification("Фраза услышана. Нажми, чтобы открыть Гарика.", false);
                    stopForegroundCompat();
                    stopSelf();
                }
            }
        } catch (Exception ignored) { }
    }

    private Notification buildNotification(String message, boolean listening) {
        Intent open = new Intent(this, MainActivity.class);
        open.putExtra("launch_chat", true);
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(this, 2202, open,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Intent stop = new Intent(this, WakeWordService.class);
        stop.setAction("STOP");
        PendingIntent stopPending = PendingIntent.getService(this, 2203, stop,
            PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("GARIK OS · пробуждение")
            .setContentText(message)
            .setContentIntent(content)
            .setOngoing(listening)
            .setAutoCancel(!listening)
            .addAction(android.R.drawable.ic_media_pause, "Выключить", stopPending);
        return b.build();
    }

    private void updateNotification(String message, boolean listening) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(message, listening));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "Голосовое пробуждение Гарика", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Показывает, когда GARIK OS слушает фразу пробуждения.");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
    }

    @Override public void onDestroy() {
        running = false;
        if (speechService != null) {
            try { speechService.cancel(); speechService.shutdown(); } catch (Exception ignored) { }
            speechService = null;
        }
        if (recognizer != null) { try { recognizer.close(); } catch (Exception ignored) { } recognizer = null; }
        if (model != null) { try { model.close(); } catch (Exception ignored) { } model = null; }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}