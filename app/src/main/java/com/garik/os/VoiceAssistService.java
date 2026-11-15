package com.garik.os;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.Locale;

public class VoiceAssistService extends AccessibilityService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanScheduled = false;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null ||
            !"com.openai.chatgpt".contentEquals(event.getPackageName())) return;
        SharedPreferences prefs = getSharedPreferences("garik", MODE_PRIVATE);
        if (!prefs.getBoolean("voice_pending", false) || scanScheduled) return;
        scanScheduled = true;
        handler.postDelayed(this::tryVoiceButton, 650);
    }

    private void tryVoiceButton() {
        SharedPreferences prefs = getSharedPreferences("garik", MODE_PRIVATE);
        if (!prefs.getBoolean("voice_pending", false)) {
            scanScheduled = false;
            return;
        }
        int attempts = prefs.getInt("voice_attempts", 0);
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && clickVoice(root)) {
            prefs.edit().putBoolean("voice_pending", false).putInt("voice_attempts", 0).apply();
            scanScheduled = false;
            return;
        }
        attempts++;
        prefs.edit().putInt("voice_attempts", attempts).apply();
        if (attempts >= 12) {
            prefs.edit().putBoolean("voice_pending", false).putInt("voice_attempts", 0).apply();
            scanScheduled = false;
            return;
        }
        handler.postDelayed(this::tryVoiceButton, 550);
    }

    private boolean clickVoice(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = ((text == null ? "" : text.toString()) + " " +
            (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT);
        boolean candidate = label.contains("голос") || label.contains("voice") ||
            label.contains("start voice") || label.contains("начать голос") ||
            label.contains("голосовой режим") || label.contains("голосовой чат");
        if (candidate && node.isEnabled()) {
            AccessibilityNodeInfo clickable = node;
            for (int depth = 0; clickable != null && depth < 5; depth++) {
                if (clickable.isClickable()) return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                clickable = clickable.getParent();
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (clickVoice(node.getChild(i))) return true;
        }
        return false;
    }

    @Override public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
        scanScheduled = false;
    }
}