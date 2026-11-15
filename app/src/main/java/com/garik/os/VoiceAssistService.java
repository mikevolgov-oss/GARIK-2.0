package com.garik.os;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.content.SharedPreferences;
import java.util.Locale;

public class VoiceAssistService extends AccessibilityService {
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null || !"com.openai.chatgpt".contentEquals(event.getPackageName())) return;
        SharedPreferences prefs = getSharedPreferences("garik", MODE_PRIVATE);
        if (!prefs.getBoolean("voice_pending", false)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        if (clickVoice(root)) prefs.edit().putBoolean("voice_pending", false).apply();
    }
    private boolean clickVoice(AccessibilityNodeInfo node) {
        if (node == null) return false;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = ((text == null ? "" : text.toString()) + " " + (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT);
        boolean candidate = label.contains("голос") || label.contains("voice") || label.contains("start voice");
        if (candidate && node.isClickable() && node.isEnabled()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        for (int i = 0; i < node.getChildCount(); i++) if (clickVoice(node.getChild(i))) return true;
        return false;
    }
    @Override public void onInterrupt() {}
}
