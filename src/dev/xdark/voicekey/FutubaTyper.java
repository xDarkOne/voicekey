package dev.xdark.voicekey;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.List;

/**
 * Futuba (a Rutube client for TV) opens its search for a SEARCH intent only when it starts: a
 * running one keeps its screen. Its search field is a real EditText, so the query is set there
 * whole and sent with the IME's Enter: the sidebar's "Поиск", the text, Enter. Off the main thread.
 */
final class FutubaTyper {
    enum Result { DONE, ALREADY, NO_APP, NO_SEARCH_SCREEN }

    private final AccessibilityService service;

    FutubaTyper(AccessibilityService service) {
        this.service = service;
    }

    /**
     * [running]: Futuba was on screen, so its search button is pressed at once. Started cold,
     * it is given a moment to open its search with the query itself, as it does for the SEARCH
     * intent once it has loaded.
     */
    Result type(String query, boolean running) {
        if (!waitFor(() -> isFutuba(root()), 15_000)) return Result.NO_APP;
        long started = SystemClock.uptimeMillis();
        boolean pressed = false;
        AccessibilityNodeInfo field = null;
        while (SystemClock.uptimeMillis() - started < 8_000) {
            field = field();
            if (field != null) break;
            if (!pressed && (running || SystemClock.uptimeMillis() - started > 3_000)) {
                AccessibilityNodeInfo label = byDescription("Поиск");
                AccessibilityNodeInfo search = clickable(label);
                pressed = search != null && search.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Journal.log(service, "futuba: search button " + (label == null ? "not found" : pressed ? "pressed" : "would not press"));
                if (label != null && !pressed) pressed = true; // Asked once; don't hammer it.
            }
            SystemClock.sleep(250);
        }
        if (field == null) return Result.NO_SEARCH_SCREEN;
        SystemClock.sleep(300); // Let a freshly opened search settle before writing into it.
        field = field();
        if (field == null) return Result.NO_SEARCH_SCREEN;
        CharSequence now = field.getText();
        if (now != null && query.contentEquals(now)) {
            // Opened with the query already: its keyboard stays open over the results, Enter closes it.
            field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            return Result.ALREADY;
        }
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text);
        SystemClock.sleep(200);
        field = field();
        if (field != null) field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        return Result.DONE;
    }

    private AccessibilityNodeInfo field() {
        AccessibilityNodeInfo root = root();
        if (!isFutuba(root)) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            if (n.isEditable()) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    /** By walking the tree: Compose answers no text search, so findAccessibilityNodeInfosByText finds nothing. */
    private AccessibilityNodeInfo byDescription(String description) {
        AccessibilityNodeInfo root = root();
        if (!isFutuba(root)) return null;
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            CharSequence d = n.getContentDescription();
            if (d != null && description.contentEquals(d)) return n;
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
        }
        return null;
    }

    /** [node] or the nearest ancestor that takes a click: Compose puts the label below the button. */
    private static AccessibilityNodeInfo clickable(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo n = node; n != null; n = n.getParent()) {
            if (n.isClickable()) return n;
        }
        return null;
    }

    private AccessibilityNodeInfo root() {
        return service.getRootInActiveWindow();
    }

    private static boolean isFutuba(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && Apps.FUTUBA.contentEquals(root.getPackageName());
    }

    private interface Check { boolean ok(); }

    private static boolean waitFor(Check check, long millis) {
        long until = SystemClock.uptimeMillis() + millis;
        while (SystemClock.uptimeMillis() < until) {
            if (check.ok()) return true;
            SystemClock.sleep(200);
        }
        return check.ok();
    }
}
