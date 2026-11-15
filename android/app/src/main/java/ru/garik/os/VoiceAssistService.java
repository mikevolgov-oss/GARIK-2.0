package ru.garik.os;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.Locale;

public class VoiceAssistService extends AccessibilityService {
    private static final String CHATGPT = "com.openai.chatgpt";
    private static final String[] VOICE_LABELS = {
        "start voice", "voice mode", "voice conversation", "talk", "голосовой режим",
        "начать голос", "голосовой разговор", "голос", "режим голос"
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null ||
            !CHATGPT.contentEquals(event.getPackageName())) return;

        android.content.SharedPreferences prefs =
            getSharedPreferences("garik_voice", MODE_PRIVATE);
        if (!prefs.getBoolean("pending_voice_click", false)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        AccessibilityNodeInfo target = findVoiceButton(root);
        if (target != null && target.isEnabled() && target.isClickable()) {
            boolean clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (clicked) prefs.edit().putBoolean("pending_voice_click", false).apply();
        }
    }

    private AccessibilityNodeInfo findVoiceButton(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();
        String label = ((text == null ? "" : text.toString()) + " " +
                        (desc == null ? "" : desc.toString())).toLowerCase(Locale.ROOT);

        for (String candidate : VOICE_LABELS) {
            if (label.contains(candidate)) {
                AccessibilityNodeInfo clickable = node;
                while (clickable != null && !clickable.isClickable()) {
                    AccessibilityNodeInfo parent = clickable.getParent();
                    if (clickable != node) clickable.recycle();
                    clickable = parent;
                }
                if (clickable != null && clickable.isClickable()) return clickable;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findVoiceButton(child);
            if (found != null) {
                if (child != found) child.recycle();
                return found;
            }
            child.recycle();
        }
        return null;
    }

    @Override public void onInterrupt() { }
}