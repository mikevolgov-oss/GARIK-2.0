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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class WakeWordService extends Service implements RecognitionListener {
    private static final String CHANNEL = "garik_wake_word";
    private static final int NOTIFICATION_ID = 2201;
    private static final String MODEL_DIR_NAME = "vosk-model-small-ru-0.22";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_DIR_NAME + ".zip";
    private static volatile boolean running = false;

    private volatile boolean triggered = false;
    private volatile boolean destroyed = false;
    private volatile Thread setupThread;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speechService;
    private PowerManager.WakeLock wakeLock;

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
            Notification n = buildNotification("Подготавливаю распознавание речи…", false);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
            if (speechService == null && (setupThread == null || !setupThread.isAlive())) {
                running = false;
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
        File modelDir = new File(getFilesDir(), MODEL_DIR_NAME);
        File zip = new File(getFilesDir(), "vosk-ru.zip");
        File staging = new File(getFilesDir(), "vosk-model-staging");
        try {
            if (!isModelReady(modelDir)) {
                updateNotification("Проверяю и загружаю русскую модель…", false);
                deleteRecursively(staging);
                if (!staging.mkdirs() && !staging.isDirectory()) {
                    throw new IOException("Не удалось создать временную папку модели");
                }

                downloadModel(zip);
                unzipModel(zip, staging);

                File extractedModel = findExtractedModel(staging);
                if (extractedModel == null) {
                    throw new IOException("В архиве не найдена полная модель Vosk");
                }

                replaceModelDirectory(extractedModel, modelDir);
                deleteRecursively(staging);
                if (!isModelReady(modelDir)) {
                    throw new IOException("Проверка распакованной модели не пройдена");
                }
            }

            // Проверяем файлы каждый запуск. Полная модель остаётся в filesDir
            // и повторно скачиваться не должна, пока данные приложения не очищены.
            model = new Model(modelDir.getAbsolutePath());
            recognizer = new Recognizer(model, 16000.0f);
            speechService = new SpeechService(recognizer, 16000.0f);
            speechService.startListening(this);
            running = true;
            updateNotification("Слушаю фразу «Гарик, не спи»", true);
            if (zip.exists()) zip.delete();
        } catch (Exception e) {
            running = false;
            if (!destroyed) {
                updateNotification("Ошибка запуска: " + e.getMessage(), false);
                stopSelf();
            }
        } finally {
            setupThread = null;
        }
    }

    private boolean isModelReady(File directory) {
        return directory != null
            && new File(directory, "conf/model.conf").isFile()
            && new File(directory, "am/final.mdl").isFile();
    }

    private File findExtractedModel(File staging) {
        File expected = new File(staging, MODEL_DIR_NAME);
        if (isModelReady(expected)) return expected;
        if (isModelReady(staging)) return staging;

        File[] children = staging.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && isModelReady(child)) return child;
            }
        }
        return null;
    }

    private void replaceModelDirectory(File source, File target) throws IOException {
        deleteRecursively(target);
        if (source.renameTo(target)) return;

        copyRecursively(source, target);
        if (!isModelReady(target)) {
            throw new IOException("Не удалось сохранить распакованную модель");
        }
        deleteRecursively(source);
    }

    private void downloadModel(File target) throws Exception {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        if (part.exists() && !part.delete()) {
            throw new IOException("Не удалось удалить незавершённую загрузку модели");
        }

        HttpURLConnection connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(120000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "GARIK-OS/2.1");
        try {
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("Сервер модели ответил HTTP " + response);
            }

            long total = 0;
            try (InputStream in = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream out = new FileOutputStream(part, false)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    ensureNotStopped();
                    out.write(buffer, 0, count);
                    total += count;
                }
                out.getFD().sync();
            }

            if (total < 1024 || !part.isFile()) {
                throw new IOException("Скачанный архив модели пустой или неполный");
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("Не удалось заменить старый архив модели");
            }
            if (!part.renameTo(target)) {
                throw new IOException("Не удалось сохранить скачанный архив модели");
            }
        } finally {
            connection.disconnect();
            if (part.exists()) part.delete();
        }
    }

    private void unzipModel(File zipFile, File destination) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream input = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)))) {
            ZipEntry entry;
            byte[] buffer = new byte[32768];
            while ((entry = input.getNextEntry()) != null) {
                ensureNotStopped();
                File output = new File(destination, entry.getName());
                String canonical = output.getCanonicalPath();
                if (!canonical.startsWith(root)) {
                    throw new SecurityException("Недопустимый путь в архиве модели");
                }

                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) {
                        throw new IOException("Не удалось создать папку модели");
                    }
                } else {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
                        throw new IOException("Не удалось создать папку файла модели");
                    }
                    try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(output))) {
                        int count;
                        while ((count = input.read(buffer)) != -1) {
                            ensureNotStopped();
                            out.write(buffer, 0, count);
                        }
                    }
                }
                input.closeEntry();
            }
        }
    }

    private void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.mkdirs() && !target.isDirectory()) {
                throw new IOException("Не удалось создать папку " + target.getName());
            }
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Не удалось прочитать папку модели");
            for (File child : children) {
                copyRecursively(child, new File(target, child.getName()));
            }
            return;
        }

        File parent = target.getParentFile();
        if (parent != null && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("Не удалось создать папку файла модели");
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
    }

    private void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Не удалось очистить временную папку модели");
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Не удалось удалить временный файл модели");
    }

    private void ensureNotStopped() throws InterruptedException {
        if (destroyed || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Загрузка модели остановлена");
        }
    }

    @Override public void onResult(String hypothesis) { inspect(hypothesis, "text"); }
    @Override public void onFinalResult(String hypothesis) { inspect(hypothesis, "text"); }
    @Override public void onPartialResult(String hypothesis) { inspect(hypothesis, "partial"); }
    @Override public void onTimeout() { }

    @Override public void onError(Exception e) {
        running = false;
        if (!destroyed) {
            updateNotification("Ошибка микрофона. Проверь разрешение и перезапусти режим.", false);
            stopSelf();
        }
    }

    private void inspect(String json, String key) {
        if (triggered || json == null) return;
        try {
            String phrase = new JSONObject(json).optString(key, "")
                .toLowerCase(new Locale("ru")).replace('ё', 'е');
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
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("GARIK OS · пробуждение")
            .setContentText(message)
            .setContentIntent(content)
            .setOngoing(listening)
            .setAutoCancel(!listening)
            .addAction(android.R.drawable.ic_media_pause, "Выключить", stopPending);
        return builder.build();
    }

    private void updateNotification(String message, boolean listening) {
        if (destroyed) return;
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(message, listening));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Голосовое пробуждение Гарика", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Показывает, когда GARIK OS слушает фразу пробуждения.");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(channel);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
        else stopForeground(false);
    }

    @Override public void onDestroy() {
        destroyed = true;
        running = false;
        Thread worker = setupThread;
        if (worker != null) worker.interrupt();

        if (speechService != null) {
            try { speechService.cancel(); speechService.shutdown(); } catch (Exception ignored) { }
            speechService = null;
        }
        if (recognizer != null) {
            try { recognizer.close(); } catch (Exception ignored) { }
            recognizer = null;
        }
        if (model != null) {
            try { model.close(); } catch (Exception ignored) { }
            model = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
