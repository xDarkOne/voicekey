package dev.xdark.voicekey;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Russian over the apps that show Chinese or English: Hisense's own apps can't be overlaid on
 * this firmware (Android 11 lets no unsigned resource overlay at them), so their labels are
 * covered on screen instead. Each label the dictionary knows gets a plate with the Russian text,
 * in the label's text size, on the colour beside it. Dictionaries are assets/translate/<package>.json,
 * built by tools/build-dict.py. Main thread, except the screenshot callback's executor.
 */
final class Translator {
    private static final long DEBOUNCE_MILLIS = 120;
    /** Android 11 refuses screenshots taken closer together than this. */
    private static final long SCREENSHOT_GAP_MILLIS = 1_100;
    /**
     * A screenshot once the screen has been still this long: the one taken while a menu slides
     * in has the wrong colours, and with no more changes nothing would take another.
     */
    private static final long SETTLE_MILLIS = 800;

    /**
     * Apps whose screens are confirmations that must be read as they are (allow USB debugging
     * with its key fingerprint, install this app, grant that permission): never covered.
     */
    private static final java.util.Set<String> UNTOUCHED = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.systemui", "com.android.packageinstaller", "com.android.permissioncontroller"));

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Dict> dicts = new HashMap<>();
    /** Never lets the translator take the key service down with it: errors go to the journal. */
    private final Runnable refresh;

    private FrameLayout layer;
    /** Pixels of the last screenshot, for the colours beside the labels. */
    private Bitmap shot;
    private long lastShot;
    private boolean shotPending;
    private boolean shotScheduled;

    Translator(AccessibilityService service) {
        this.service = service;
        this.refresh = () -> {
            try {
                refresh();
            } catch (RuntimeException e) {
                Journal.log(service, "translator failed: " + e);
            }
        };
        load();
    }

    boolean enabled() {
        return !dicts.isEmpty() && Settings.translate(service);
    }

    void onEvent(AccessibilityEvent event) {
        if (!enabled()) return;
        CharSequence pkg = event.getPackageName();
        int type = event.getEventType();
        if ((pkg != null && dicts.containsKey(pkg.toString())) || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                || (layer != null && layer.getChildCount() > 0)) {
            handler.removeCallbacks(refresh);
            handler.postDelayed(refresh, DEBOUNCE_MILLIS);
        }
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        if (layer != null) {
            try {
                service.getSystemService(WindowManager.class).removeView(layer);
            } catch (IllegalArgumentException ignored) {
            }
            layer = null;
        }
    }

    private String lastSeen = "";

    private void refresh() {
        List<Label> labels = new ArrayList<>();
        StringBuilder seen = new StringBuilder();
        if (enabled()) {
            for (AccessibilityWindowInfo window : service.getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                seen.append(window.getType()).append(':').append(root == null ? "null" : root.getPackageName()).append(' ');
                if (root == null || root.getPackageName() == null) continue;
                Dict dict = dicts.get(root.getPackageName().toString());
                if (dict != null) collect(root, dict, labels);
            }
        }
        String now = seen + "-> " + labels.size();
        if (!now.equals(lastSeen)) Journal.log(service, "translator: " + now);
        lastSeen = now;
        draw(labels);
        if (!labels.isEmpty()) {
            requestShot();
            handler.removeCallbacks(settled);
            handler.postDelayed(settled, SETTLE_MILLIS);
        }
    }

    private final Runnable settled = this::requestShot;

    private void collect(AccessibilityNodeInfo root, Dict dict, List<Label> out) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) queue.add(c);
            }
            CharSequence text = n.getText();
            if (text == null || text.length() == 0 || !n.isVisibleToUser()) continue;
            // A checkbox's or a switch's node is the box and its label together: a plate would hide the box.
            if (n.isCheckable()) continue;
            String ru = dict.translate(text.toString());
            if (ru == null) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() <= 0 || r.height() <= 0) continue;
            // Russian runs longer: the plate may grow right, up to what sits beside the label.
            Rect box = container(n, r);
            r.right = Math.max(r.right, roomRight(n, r, service.getResources().getDisplayMetrics().widthPixels));
            float size = 0;
            if (n.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_RENDERING_INFO_KEY, new android.os.Bundle())
                    && n.getExtraRenderingInfo() != null) {
                size = n.getExtraRenderingInfo().getTextSizeInPx();
            }
            out.add(new Label(r, ru, size, box));
        }
    }

    /**
     * How far right a label may grow: to the nearest thing on its right in the same row (an
     * arrow, a value like "Off", a switch), else to its row's edge; a little short of either.
     * The label's own container may hug the text, so the row is looked for a level or two up;
     * but never as far as a container spanning the whole screen: a panel's window often does,
     * and its edge is the screen's, not the panel's (a strip across the launcher behind it).
     */
    private static int roomRight(AccessibilityNodeInfo node, Rect r, int screenWidth) {
        int limit = r.right;
        AccessibilityNodeInfo child = node;
        for (int level = 0; level < 3; level++) {
            AccessibilityNodeInfo parent = child.getParent();
            if (parent == null) break;
            Rect pr = new Rect();
            parent.getBoundsInScreen(pr);
            if (pr.width() >= screenWidth * 0.9f) break;
            int edge = pr.right;
            boolean blocked = false;
            for (int i = 0; i < parent.getChildCount(); i++) {
                AccessibilityNodeInfo s = parent.getChild(i);
                if (s == null || s.equals(child) || !s.isVisibleToUser()) continue;
                Rect sr = new Rect();
                s.getBoundsInScreen(sr);
                boolean sameRow = sr.top < r.bottom && sr.bottom > r.top;
                if (sameRow && sr.width() > 0 && sr.left >= r.right - 2 && sr.left < edge) {
                    edge = sr.left;
                    blocked = true;
                }
            }
            limit = Math.max(limit, edge - 8);
            if (blocked) break;
            child = parent;
        }
        return limit;
    }

    /** The nearest ancestor that leaves room left of the label: where its own background shows. */
    private static Rect container(AccessibilityNodeInfo node, Rect r) {
        for (AccessibilityNodeInfo p = node.getParent(); p != null; p = p.getParent()) {
            Rect pr = new Rect();
            p.getBoundsInScreen(pr);
            if (pr.left < r.left - 2 && pr.contains(r.left, r.centerY())) return pr;
        }
        return null;
    }

    private void draw(List<Label> labels) {
        if (labels.isEmpty()) {
            if (layer != null) layer.removeAllViews();
            return;
        }
        if (layer == null) {
            layer = new FrameLayout(service);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            service.getSystemService(WindowManager.class).addView(layer, lp);
        }
        layer.removeAllViews();
        for (Label l : labels) {
            TextView tv = new TextView(service);
            tv.setText(l.ru);
            int[] column = columnBeside(l.bounds, l.box);
            int background = average(column);
            if (column.length > 1) {
                Bitmap strip = Bitmap.createBitmap(column, 1, column.length, Bitmap.Config.ARGB_8888);
                tv.setBackground(new android.graphics.drawable.BitmapDrawable(service.getResources(), strip));
            } else {
                tv.setBackgroundColor(background);
            }
            tv.setTextColor(luminance(background) > 0.5 ? 0xFF202124 : Color.WHITE);
            float size = l.textSize > 0 ? l.textSize : l.bounds.height() * 0.62f;
            boolean oneLine = l.bounds.height() < size * 2.2f;
            tv.setMaxLines(oneLine ? 1 : Math.max(1, (int) (l.bounds.height() / (size * 1.15f))));
            tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            tv.setIncludeFontPadding(false);
            // Russian runs longer: shrink to fit the label's box, never below 55%.
            tv.setAutoSizeTextTypeUniformWithConfiguration(
                    Math.max(8, (int) (size * 0.55f)), Math.max(9, (int) size), 1, TypedValue.COMPLEX_UNIT_PX);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(l.bounds.width(), l.bounds.height());
            p.leftMargin = l.bounds.left;
            p.topMargin = l.bounds.top;
            layer.addView(tv, p);
        }
    }

    /**
     * The column of pixels beside the label, top to bottom, inside what it sits in (a tooltip's
     * dark box, not the screen behind it), and left of it: outside our plate, so not ours.
     * Stretched across the plate it carries a gradient or a shade under the text, which a single
     * colour flattens into a visible box.
     */
    private int[] columnBeside(Rect r, Rect box) {
        Bitmap b = shot;
        if (b == null) return new int[] {0xFF2B2B2B};
        int x;
        if (box != null) x = Math.max(box.left + 2, r.left - 6);
        else x = r.left - 3 >= 0 ? r.left - 3 : r.right + 2;
        x = Math.min(Math.max(x, 0), b.getWidth() - 1);
        int top = Math.max(r.top, 0), bottom = Math.min(r.bottom, b.getHeight());
        if (bottom - top < 2) {
            int y = Math.min(Math.max(r.centerY(), 0), b.getHeight() - 1);
            return new int[] {b.getPixel(x, y) | 0xFF000000};
        }
        int[] column = new int[r.height()];
        for (int i = 0; i < column.length; i++) {
            int y = Math.min(Math.max(r.top + i, top), bottom - 1);
            column[i] = b.getPixel(x, y) | 0xFF000000;
        }
        return column;
    }

    private static int average(int[] pixels) {
        long red = 0, green = 0, blue = 0;
        for (int c : pixels) {
            red += Color.red(c);
            green += Color.green(c);
            blue += Color.blue(c);
        }
        int n = pixels.length;
        return Color.rgb((int) (red / n), (int) (green / n), (int) (blue / n));
    }

    private static double luminance(int c) {
        return (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0;
    }

    /** A fresh screenshot for the colours, then the plates again with them. */
    private void requestShot() {
        long now = SystemClock.uptimeMillis();
        if (shotPending || shotScheduled) return;
        long wait = lastShot + SCREENSHOT_GAP_MILLIS - now;
        if (wait > 0) {
            shotScheduled = true;
            handler.postDelayed(() -> {
                shotScheduled = false;
                requestShot();
            }, wait);
            return;
        }
        shotPending = true;
        lastShot = now;
        service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        shotPending = false;
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        Bitmap previous = shot;
                        shot = hw == null ? null : hw.copy(Bitmap.Config.ARGB_8888, false);
                        buffer.close();
                        if (previous != null) previous.recycle();
                        handler.removeCallbacks(refresh);
                        handler.post(() -> {
                            try {
                                draw(currentLabels());
                            } catch (RuntimeException e) {
                                Journal.log(service, "translator failed: " + e);
                            }
                        });
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        shotPending = false;
                    }
                });
    }

    private List<Label> currentLabels() {
        List<Label> labels = new ArrayList<>();
        if (!enabled()) return labels;
        for (AccessibilityWindowInfo window : service.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null || root.getPackageName() == null) continue;
            Dict dict = dicts.get(root.getPackageName().toString());
            if (dict != null) collect(root, dict, labels);
        }
        return labels;
    }

    private void load() {
        try {
            String[] files = service.getAssets().list("translate");
            if (files == null) return;
            for (String f : files) {
                if (!f.endsWith(".json")) continue;
                if (UNTOUCHED.contains(f.substring(0, f.length() - 5))) continue;
                try (InputStream in = service.getAssets().open("translate/" + f)) {
                    byte[] bytes = new byte[in.available()];
                    int read = 0;
                    while (read < bytes.length) {
                        int n = in.read(bytes, read, bytes.length - read);
                        if (n < 0) break;
                        read += n;
                    }
                    dicts.put(f.substring(0, f.length() - 5), new Dict(new JSONObject(new String(bytes, StandardCharsets.UTF_8))));
                }
            }
            Journal.log(service, "translator: dictionaries for " + dicts.keySet());
        } catch (Exception e) {
            Journal.log(service, "translator: can't load dictionaries: " + e);
        }
    }

    private static final class Label {
        final Rect bounds;
        final String ru;
        final float textSize;
        /** What the label sits in, for its background colour; null if nothing leaves room. */
        final Rect box;

        Label(Rect bounds, String ru, float textSize, Rect box) {
            this.bounds = bounds;
            this.ru = ru;
            this.textSize = textSize;
            this.box = box;
        }
    }

    /** One app's phrases: whole labels, then labels with values in them ("Всего: 12"). */
    private static final class Dict {
        final Map<String, String> exact = new HashMap<>();
        final List<Pattern> patterns = new ArrayList<>();
        final List<String> templates = new ArrayList<>();

        Dict(JSONObject json) throws org.json.JSONException {
            JSONObject e = json.getJSONObject("exact");
            for (java.util.Iterator<String> it = e.keys(); it.hasNext(); ) {
                String k = it.next();
                exact.put(k, e.getString(k));
            }
            JSONArray p = json.getJSONArray("patterns");
            for (int i = 0; i < p.length(); i++) {
                JSONArray pair = p.getJSONArray(i);
                try {
                    patterns.add(Pattern.compile(pair.getString(0), Pattern.DOTALL));
                    templates.add(javaTemplate(pair.getString(1)));
                } catch (RuntimeException ignored) {
                }
            }
        }

        /**
         * build-dict.py writes Python-style groups (\\1); Java's replaceFirst wants $1, and a
         * literal $ escaped. Left as is, every value came out as its group's number ("1: 2").
         */
        static String javaTemplate(String python) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < python.length(); i++) {
                char c = python.charAt(i);
                if (c == '\\' && i + 1 < python.length()) {
                    char next = python.charAt(++i);
                    if (Character.isDigit(next)) out.append('$').append(next);
                    else out.append('\\').append(next);
                } else if (c == '$') {
                    out.append("\\$");
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        String translate(String text) {
            String t = text.trim();
            String ru = exact.get(t);
            if (ru != null) return ru;
            for (int i = 0; i < patterns.size(); i++) {
                Matcher m = patterns.get(i).matcher(t);
                if (m.matches()) {
                    try {
                        return m.replaceFirst(templates.get(i));
                    } catch (RuntimeException ignored) {
                        return null;
                    }
                }
            }
            return null;
        }
    }

    /** The on/off switch: [Prefs]. */
    static final class Settings {
        static boolean translate(Context context) {
            return Prefs.translate(context);
        }

        static void setTranslate(Context context, boolean on) {
            Prefs.setTranslate(context, on);
        }
    }
}
