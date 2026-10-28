package dev.xdark.voicekey;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.List;
import java.util.Locale;

/**
 * Rutube takes no search from outside: no link, no search intent, and its search field is a
 * label filled by its own on-screen keyboard. So the query is typed there key by key, as the
 * remote would: open the search from the sidebar, "Очистить", the letters (switching between
 * "Рус", "Eng" and "!#1" layouts), "Найти". Runs off the main thread: it waits for the screens.
 */
final class RutubeTyper {
    private static final String ID = Apps.RUTUBE + ":id/";
    private static final long STEP_MILLIS = 70;

    enum Result { DONE, NO_APP, NO_SEARCH_SCREEN }

    /** How long to wait for the user to open the search when it can't be opened for them. */
    private static final long WAIT_FOR_USER_MILLIS = 30_000;

    private final AccessibilityService service;
    private final Runnable askToOpenSearch;

    /** [askToOpenSearch] runs when the search screen can't be opened from here: Rutube's sidebar takes only the remote's keys. */
    RutubeTyper(AccessibilityService service, Runnable askToOpenSearch) {
        this.service = service;
        this.askToOpenSearch = askToOpenSearch;
    }

    Result type(String query) {
        // Started cold, Rutube shows a splash first: wait for its sidebar or its search screen.
        if (!waitFor(() -> keyboard() != null || sidebar() != null, 25_000)) {
            return isRutube(root()) ? Result.NO_SEARCH_SCREEN : Result.NO_APP;
        }
        if (keyboard() == null) {
            openSearch(); // On the chance a later Rutube gives its sidebar click semantics.
            waitFor(() -> keyboard() != null, 1_000);
        }
        if (keyboard() == null) {
            // Its sidebar is Compose without click semantics, and on Android 11 a service can't
            // press the remote's keys: the user opens the search, the query is typed then.
            askToOpenSearch.run();
            if (!waitFor(() -> keyboard() != null, WAIT_FOR_USER_MILLIS)) return Result.NO_SEARCH_SCREEN;
            SystemClock.sleep(300); // Let the screen settle before typing.
        }
        press("Очистить");
        for (char c : query.toLowerCase(Locale.forLanguageTag("ru")).toCharArray()) {
            typeChar(c);
        }
        press("Найти");
        return Result.DONE;
    }

    /** The search is the sidebar's topmost item; it may take a click, or focus and then a click. */
    private void openSearch() {
        AccessibilityNodeInfo bar = sidebar();
        if (bar == null) {
            Journal.log(service, "rutube: no sidebar");
            return;
        }
        AccessibilityNodeInfo top = topmostFocusable(bar, null);
        if (top == null) {
            Journal.log(service, "rutube: no items in the sidebar");
            return;
        }
        boolean focused = top.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        boolean clicked = top.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Journal.log(service, "rutube: sidebar search focus=" + focused + " click=" + clicked);
    }

    /** The focusable node nearest the top among [node] and its descendants (Compose nests them). */
    private static AccessibilityNodeInfo topmostFocusable(AccessibilityNodeInfo node, AccessibilityNodeInfo best) {
        if (node == null) return best;
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        // An item of the narrow strip on the left, not the whole-screen container around it.
        if (node.isFocusable() && r.width() > 0 && r.width() < 200 && r.left < 128) {
            Rect b = new Rect();
            if (best != null) best.getBoundsInScreen(b);
            if (best == null || r.top < b.top) best = node;
        }
        for (int i = 0; i < node.getChildCount(); i++) best = topmostFocusable(node.getChild(i), best);
        return best;
    }

    private void typeChar(char c) {
        if (c == ' ') {
            ensureLetters();
            pressSpace();
            return;
        }
        if (isCyrillic(c)) ensureLayout("й", "Рус");
        else if (c >= 'a' && c <= 'z') ensureLayout("q", "Eng");
        else ensureLayout("1", "!#1");
        if (!press(String.valueOf(c))) Journal.log(service, "rutube: no key for '" + c + "'");
    }

    private static boolean isCyrillic(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC;
    }

    /** Space lives on the letter layouts only. */
    private void ensureLetters() {
        if (key("й") == null && key("q") == null) press("Рус");
    }

    /** Switches with [switchKey] unless [probe]'s key is already on the keyboard. */
    private void ensureLayout(String probe, String switchKey) {
        if (key(probe) == null) press(switchKey);
    }

    /** The second letter row's key with no text: the space bar ("␣" on screen). */
    private void pressSpace() {
        AccessibilityNodeInfo root = root();
        if (root == null) return;
        for (AccessibilityNodeInfo row : root.findAccessibilityNodeInfosByViewId(ID + "lettersRowLayout2")) {
            for (int i = 0; i < row.getChildCount(); i++) {
                AccessibilityNodeInfo k = row.getChild(i);
                if (k != null && k.isClickable() && (k.getText() == null || k.getText().length() == 0)) {
                    k.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    SystemClock.sleep(STEP_MILLIS);
                    return;
                }
            }
        }
    }

    private boolean press(String label) {
        AccessibilityNodeInfo k = key(label);
        if (k == null) return false;
        boolean ok = k.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        SystemClock.sleep(STEP_MILLIS);
        return ok;
    }

    /** A clickable key of the on-screen keyboard (or its buttons) whose label is [label]. */
    private AccessibilityNodeInfo key(String label) {
        AccessibilityNodeInfo kb = keyboard();
        AccessibilityNodeInfo where = kb != null ? kb : root();
        if (where == null) return null;
        for (AccessibilityNodeInfo n : where.findAccessibilityNodeInfosByText(label)) {
            CharSequence t = n.getText();
            if (n.isClickable() && t != null && t.toString().equalsIgnoreCase(label)) return n;
        }
        // "Найти" and "Очистить" sit below the keys, outside the keyboard's own layout.
        if (kb != null) {
            AccessibilityNodeInfo root = root();
            if (root != null) {
                for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
                    CharSequence t = n.getText();
                    if (n.isClickable() && t != null && t.toString().equalsIgnoreCase(label)) return n;
                }
            }
        }
        return null;
    }

    /** The sidebar, once it has its items: Compose fills it after the view appears. */
    private AccessibilityNodeInfo sidebar() {
        AccessibilityNodeInfo root = root();
        if (root == null || !isRutube(root)) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(ID + "sidebar_container");
        if (found.isEmpty() || found.get(0).getChildCount() == 0) return null;
        return found.get(0);
    }

    private AccessibilityNodeInfo keyboard() {
        AccessibilityNodeInfo root = root();
        if (root == null || !isRutube(root)) return null;
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(ID + "fsKeyboard");
        return found.isEmpty() ? null : found.get(0);
    }

    private AccessibilityNodeInfo root() {
        return service.getRootInActiveWindow();
    }

    private static boolean isRutube(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && Apps.RUTUBE.contentEquals(root.getPackageName());
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
