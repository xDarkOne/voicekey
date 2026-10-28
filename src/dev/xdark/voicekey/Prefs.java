package dev.xdark.voicekey;

import android.content.Context;
import android.content.SharedPreferences;

/** VoiceKey's settings, read by the service, the voice panel and the settings screen alike. */
final class Prefs {
    /** What a press or a hold of the mic button does. */
    enum Action {
        COMMAND("Голосовая команда"),
        APP_SEARCH("Поиск в текущем приложении"),
        GOOGLE_PANEL("Панель поиска Google"),
        NOTHING("Ничего");

        final String title;

        Action(String title) {
            this.title = title;
        }
    }

    /** Who turns speech into text. */
    enum Engine {
        ONLINE("Онлайн — Google", "точнее, нужен интернет"),
        OFFLINE("Офлайн — Vosk", "без интернета, проще");

        final String title;
        final String hint;

        Engine(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }
    }

    static final long[] HOLD_CHOICES = {350, 500, 700, 1000};

    private static final String FILE = "voicekey";

    private Prefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Whether VoiceKey takes the mic button at all; off, it is the firmware's or another app's. */
    static boolean micOn(Context c) {
        return prefs(c).getBoolean("mic", true);
    }

    static void setMicOn(Context c, boolean on) {
        prefs(c).edit().putBoolean("mic", on).apply();
    }

    static Action tap(Context c) {
        return action(c, "tap", Action.COMMAND);
    }

    static Action hold(Context c) {
        return action(c, "hold", Action.APP_SEARCH);
    }

    static void setTap(Context c, Action a) {
        prefs(c).edit().putString("tap", a.name()).apply();
    }

    static void setHold(Context c, Action a) {
        prefs(c).edit().putString("hold", a.name()).apply();
    }

    private static Action action(Context c, String key, Action fallback) {
        try {
            return Action.valueOf(prefs(c).getString(key, fallback.name()));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /** How long the button is held before it counts as a hold. */
    static long holdMillis(Context c) {
        return prefs(c).getLong("hold_ms", 500);
    }

    static void setHoldMillis(Context c, long ms) {
        prefs(c).edit().putLong("hold_ms", ms).apply();
    }

    static Engine engine(Context c) {
        try {
            return Engine.valueOf(prefs(c).getString("engine", Engine.ONLINE.name()));
        } catch (IllegalArgumentException e) {
            return Engine.ONLINE;
        }
    }

    static void setEngine(Context c, Engine e) {
        prefs(c).edit().putString("engine", e.name()).apply();
    }

    static boolean translate(Context c) {
        return prefs(c).getBoolean("translate", true);
    }

    static void setTranslate(Context c, boolean on) {
        prefs(c).edit().putBoolean("translate", on).apply();
    }

    /** Earcons when listening starts, succeeds or fails. */
    static boolean sounds(Context c) {
        return prefs(c).getBoolean("sounds", true);
    }

    static void setSounds(Context c, boolean on) {
        prefs(c).edit().putBoolean("sounds", on).apply();
    }

    /** Look for a newer release now and then, without being asked. */
    static boolean autoUpdate(Context c) {
        return prefs(c).getBoolean("auto_update", true);
    }

    static void setAutoUpdate(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto_update", on).apply();
    }

    static long lastUpdateCheck(Context c) {
        return prefs(c).getLong("update_checked", 0);
    }

    static void setLastUpdateCheck(Context c, long millis) {
        prefs(c).edit().putLong("update_checked", millis).apply();
    }

    /** The newer release found last time: its version name and the APK's address; null if none. */
    static String[] foundUpdate(Context c) {
        String v = prefs(c).getString("update_version", null);
        String u = prefs(c).getString("update_url", null);
        return v == null || u == null ? null : new String[] {v, u, prefs(c).getString("update_sha256", "")};
    }

    static void setFoundUpdate(Context c, String version, String url, String sha256) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (version == null) e.remove("update_version").remove("update_url").remove("update_sha256");
        else e.putString("update_version", version).putString("update_url", url).putString("update_sha256", sha256);
        e.apply();
    }
}
