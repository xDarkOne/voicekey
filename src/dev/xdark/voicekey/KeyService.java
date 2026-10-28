package dev.xdark.voicekey;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Takes the remote's mic button from the firmware's assistant. A press listens for a command
 * ("открой Лампу", "найди на ютубе …"); a hold searches in the app on screen, if it takes a
 * search from outside (TizenTube, LAMPA, Kinopoisk, VK Video), or anywhere else Google's search
 * panel, which searches all apps.
 */
public class KeyService extends AccessibilityService {
    static final String TAG = "VoiceKey";

    /** Vidda remote: scan code 166 is mapped to HISENSE_VOICE_NANOSIC, vendor keycode 4381. */
    private static final int SCAN_MIC = 166;
    private static final int KEYCODE_HISENSE_VOICE = 4381;

    static final String SEARCH_PACKAGE = "org.vidda.googlevoicesearch";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable onHold = this::onMicHeld;
    private boolean held;

    /** The last app that brought up a window: a fallback when the windows can't be read. */
    private String lastPackage;

    /** Apps seen on screen since the service started: one not seen yet is likely not running. */
    private static final Set<String> seen = Collections.synchronizedSet(new HashSet<>());

    static boolean seenSinceStart(String pkg) {
        return seen.contains(pkg);
    }

    /** Russian plates over the apps that show Chinese or English. */
    private Translator translator;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (translator != null) {
            try {
                translator.onEvent(event);
            } catch (RuntimeException e) {
                Journal.log(this, "translator failed: " + e);
            }
        }
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            onWindowOf(pkg);
            lastPackage = pkg;
            seen.add(lastPackage);
        }
        // LAMPA coming back from Vimu sends no window event of its own: the list of windows tells.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED && Apps.VIMU.equals(lastApp)) {
            String now = foregroundPackage();
            if (now != null) onWindowOf(now);
        }
    }

    @Override
    public void onInterrupt() {}

    /** Vimu on screen, opened by LAMPA: [WatchNext] keeps a card for its film if it's left with Home. */
    private boolean vimuFromLampa;
    /** The last app window, overlays aside. */
    private String lastApp;

    private void onWindowOf(String pkg) {
        if (isOverlay(pkg) || pkg.equals(lastApp)) return;
        if (Apps.VIMU.equals(pkg)) {
            vimuFromLampa = Apps.LAMPA.equals(lastApp);
        } else if (Apps.VIMU.equals(lastApp) && vimuFromLampa) {
            vimuFromLampa = false;
            Journal.log(this, "Vimu left for " + pkg);
            WatchNext.vimuLeft(Apps.LAMPA.equals(pkg));
        }
        lastApp = pkg;
    }

    /**
     * Windows that come over an app without replacing it: the volume bar, toasts, ours, and
     * Hisense's assistant, which shows a window of its own on every Back and Home.
     */
    private boolean isOverlay(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.equals(getPackageName()) || pkg.equals("android")
                || pkg.equals("com.hisense.tv.aivirtualassistant");
    }

    /** `adb shell dumpsys activity service dev.xdark.voicekey/.KeyService [lines]`: the journal's tail. */
    @Override
    protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter out, String[] args) {
        int lines = 40;
        try {
            if (args != null && args.length > 0) lines = Integer.parseInt(args[0]);
        } catch (NumberFormatException ignored) {
        }
        Journal.tail(this, out, lines);
    }

    /** The running service, for what only it can do: reading and pressing other apps' views. */
    private static volatile KeyService running;

    @Override
    protected void onServiceConnected() {
        running = this;
        try {
            translator = new Translator(this);
        } catch (RuntimeException e) {
            Journal.log(this, "translator failed to start: " + e);
        }
        Log.i(TAG, "service connected");
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        if (translator != null) translator.stop();
        super.onDestroy();
    }

    /**
     * A line at the top of the screen over any app, that takes no focus: the remote keeps
     * working the app below. Toasts don't show on this firmware.
     */
    private TextView hint;

    void showHint(String text) {
        hideHint();
        float dp = getResources().getDisplayMetrics().density;
        hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        int pad = (int) (18 * dp);
        hint.setPadding(pad * 2, pad, pad * 2, pad);
        GradientDrawable back = new GradientDrawable();
        back.setColor(0xF0202124);
        back.setCornerRadius(16 * dp);
        hint.setBackground(back);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = (int) (24 * dp);
        getSystemService(WindowManager.class).addView(hint, lp);
    }

    void hideHint() {
        if (hint == null) return;
        try {
            getSystemService(WindowManager.class).removeView(hint);
        } catch (IllegalArgumentException ignored) {
        }
        hint = null;
    }

    static boolean ownsMic(Context context) {
        return Prefs.micOn(context);
    }

    static void setOwnsMic(Context context, boolean mine) {
        Prefs.setMicOn(context, mine);
        Journal.log(context, "mic button " + (mine ? "taken back" : "given away"));
    }

    /** A hint at the top of the screen for a few seconds: toasts don't show on this firmware. */
    static void flash(String text, long millis) {
        KeyService service = running;
        if (service == null) return;
        service.handler.post(() -> {
            service.showHint(text);
            service.handler.postDelayed(service::hideHint, millis);
        });
    }

    /** Puts [query] into Futuba's search, off the main thread. False if the service isn't running. */
    static boolean typeInFutuba(Context context, String query, boolean wasOnScreen) {
        KeyService service = running;
        if (service == null) return false;
        new Thread(() -> Journal.log(service, "futuba: «" + query + "»: "
                + new FutubaTyper(service).type(query, wasOnScreen)), "futuba-typer").start();
        return true;
    }

    /** Types [query] into Rutube's search, off the main thread. False if the service isn't running. */
    static boolean typeInRutube(Context context, String query) {
        KeyService service = running;
        if (service == null) return false;
        new Thread(() -> {
            Runnable ask = () -> service.handler.post(() -> service.showHint(
                    "Rutube: откройте поиск (лупа слева) — «" + query + "» впишу сам"));
            RutubeTyper.Result result = new RutubeTyper(service, ask).type(query);
            service.handler.post(service::hideHint);
            Journal.log(service, "rutube: typed «" + query + "»: " + result);
        }, "rutube-typer").start();
        return true;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getScanCode() != SCAN_MIC && event.getKeyCode() != KEYCODE_HISENSE_VOICE) {
            return false;
        }
        // Off in the settings (or "отдай микрофон"): the button is the firmware's or another app's.
        if (!Prefs.micOn(this)) return false;
        // Down, repeats and up are all ours, so the assistant never sees the button.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            held = false;
            if (Prefs.hold(this) == Prefs.Action.NOTHING) {
                // Nothing to tell a hold from: act at once, not on the button's release.
                held = true;
                onMicPressed();
            } else {
                handler.postDelayed(onHold, Prefs.holdMillis(this));
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(onHold);
            if (!held) onMicPressed();
        }
        return true;
    }

    private void onMicPressed() {
        // Pressed again while listening: "I'm done", recognize what was said so far.
        if (VoiceActivity.finishListening()) {
            Journal.log(this, "mic pressed: finish listening");
            return;
        }
        String app = foregroundPackage();
        Journal.log(this, "mic pressed over " + app);
        perform(Prefs.tap(this), app);
    }

    private void onMicHeld() {
        held = true;
        String app = foregroundPackage();
        Journal.log(this, "mic held over " + app);
        perform(Prefs.hold(this), app);
    }

    /** What the settings give a press or a hold; [app] is the one on screen. */
    private void perform(Prefs.Action action, String app) {
        if (isListening(app)) return;
        switch (action) {
            case COMMAND:
                startVoice(null, app);
                break;
            case APP_SEARCH:
                Command.Target onScreen = Apps.targetOf(app);
                if (onScreen != null) {
                    startVoice(onScreen, app);
                } else {
                    openGooglePanel();
                }
                break;
            case GOOGLE_PANEL:
                openGooglePanel();
                break;
            default:
                break;
        }
    }

    /** Google's search panel: listens on its own and searches all apps. */
    private void openGooglePanel() {
        start(new Intent(Intent.ACTION_ASSIST).setComponent(new ComponentName(
                SEARCH_PACKAGE, "com.google.android.katniss.search.SearchActivity")));
    }

    private boolean isListening(String app) {
        return SEARCH_PACKAGE.equals(app) || getPackageName().equals(app);
    }

    /** [target] null: listen for a command. */
    private void startVoice(Command.Target target, String foreground) {
        Intent voice = new Intent(this, VoiceActivity.class).putExtra(VoiceActivity.EXTRA_FOREGROUND, foreground);
        if (target != null) voice.putExtra(VoiceActivity.EXTRA_TARGET, target.name());
        start(voice);
    }

    private void start(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "can't open " + intent, e);
        }
    }

    /** The app of the focused application window. */
    private String foregroundPackage() {
        List<AccessibilityWindowInfo> windows = getWindows();
        for (AccessibilityWindowInfo window : windows) {
            if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            if (!window.isFocused() && !window.isActive()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        }
        return lastPackage;
    }
}
