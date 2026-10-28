package dev.xdark.voicekey;

import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/** The apps commands name: opening them, and searching in them, each its own way. */
final class Apps {
    static final String TIZENTUBE = "io.gh.reisxd.tizentube.cobalt";
    static final String LAMPA = "top.rootu.lampa";
    static final String KINOPOISK = "ru.kinopoisk.tv";
    static final String VK = "com.vk.tv";
    /** Rutube's TV app is a package of its own since 2025; the old one gets no updates. */
    static final String RUTUBE = "ru.rutube.app.tv";
    private static final String RUTUBE_OLD = "ru.rutube.app";
    /** Futuba, an ad-free Rutube client for TV: when installed, "Rutube" means it. */
    static final String FUTUBA = "me.pnsrc.futuba";
    /** The player LAMPA opens films in. */
    static final String VIMU = "net.gtvbox.vimuhd";

    /** The deep link YouTube's TV app takes for a voice search (Cobalt's own, from tvOS's Siri). */
    private static final String YOUTUBE_SEARCH_URL =
            "https://www.youtube.com/tv?launch=voice&vs=11&va=search&vaa=";
    /** VK Video's own link to its search screen (TvOpenSearchLinkRedirectAction). */
    private static final String VK_SEARCH_URL = "vkvideo://video/search?query=";
    /** Kinopoisk's suggestions for Android TV's global search: the query goes as selectionArgs[0]. */
    private static final Uri KINOPOISK_SUGGEST =
            Uri.parse("content://ru.kinopoisk.tv.search.kinopoisk.tv/search_suggest_query?limit=10");

    /**
     * LAMPA started cold loses a query while it loads its plugins, so it is asked again. Only
     * then: a running LAMPA asked again starts the search over under the user's hands.
     */
    private static final long[] LAMPA_RETRY_MILLIS = {8_000, 16_000};

    private static final Handler handler = new Handler(Looper.getMainLooper());

    private Apps() {}

    static String packageOf(Command.Target target) {
        switch (target) {
            case YOUTUBE: return TIZENTUBE;
            case LAMPA: return LAMPA;
            case KINOPOISK: return KINOPOISK;
            case VK: return VK;
            default: return RUTUBE;
        }
    }

    static Command.Target targetOf(String pkg) {
        if (RUTUBE_OLD.equals(pkg) || FUTUBA.equals(pkg)) return Command.Target.RUTUBE;
        for (Command.Target t : Command.Target.values()) {
            if (packageOf(t).equals(pkg)) return t;
        }
        return null;
    }

    /** "Открываю …" */
    static String accusative(Command.Target target) {
        switch (target) {
            case YOUTUBE: return "YouTube";
            case LAMPA: return "Лампу";
            case KINOPOISK: return "Кинопоиск";
            case VK: return "VK Видео";
            default: return "Rutube";
        }
    }

    /** "Ищу в …" */
    static String locative(Command.Target target) {
        switch (target) {
            case YOUTUBE: return "YouTube";
            case LAMPA: return "Лампе";
            case KINOPOISK: return "Кинопоиске";
            case VK: return "VK Видео";
            default: return "Rutube";
        }
    }

    static boolean open(Context context, Command.Target target) {
        PackageManager pm = context.getPackageManager();
        String pkg = target == Command.Target.RUTUBE && hasFutuba(context) ? FUTUBA : packageOf(target);
        Intent launch = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
        if (launch == null && target == Command.Target.RUTUBE) launch = pm.getLeanbackLaunchIntentForPackage(RUTUBE_OLD);
        return launch != null && start(context.getApplicationContext(), launch);
    }

    /** YouTube, LAMPA, VK Video, Rutube; Kinopoisk goes through [findInKinopoisk]. [foreground] is the app on screen. */
    static boolean search(Context context, Command.Target target, String query, String foreground) {
        Context app = context.getApplicationContext();
        switch (target) {
            case YOUTUBE:
                // Single top: TizenTube is on top of its own task, so the link reaches it as a new intent.
                return start(app, new Intent(Intent.ACTION_VIEW, Uri.parse(YOUTUBE_SEARCH_URL + Uri.encode(query)))
                        .setPackage(TIZENTUBE)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
            case VK:
                return start(app, new Intent(Intent.ACTION_VIEW, Uri.parse(VK_SEARCH_URL + Uri.encode(query)))
                        .setPackage(VK));
            case LAMPA:
                return searchLampa(app, query, foreground);
            case RUTUBE:
                if (hasFutuba(app)) {
                    // Started cold, Futuba opens its search with the query; running, it's typed in.
                    start(app, new Intent(Intent.ACTION_SEARCH)
                            .setComponent(new ComponentName(FUTUBA, FUTUBA + ".MainActivity"))
                            .putExtra(SearchManager.QUERY, query));
                    return KeyService.typeInFutuba(app, query, FUTUBA.equals(foreground));
                }
                if (!RUTUBE.equals(foreground)) open(app, target);
                return KeyService.typeInRutube(app, query);
            default:
                return false;
        }
    }

    static boolean hasFutuba(Context context) {
        return context.getPackageManager().getLeanbackLaunchIntentForPackage(FUTUBA) != null;
    }

    private static boolean searchLampa(Context app, String query, String foreground) {
        Intent search = new Intent(Intent.ACTION_SEARCH)
                .setComponent(new ComponentName(LAMPA, LAMPA + ".MainActivity"))
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        boolean cold = !LAMPA.equals(foreground) && !KeyService.seenSinceStart(LAMPA);
        Journal.log(app, "search LAMPA" + (cold ? " (cold, will repeat)" : "") + ": " + query);
        if (!start(app, search)) return false;
        if (cold) {
            for (long delay : LAMPA_RETRY_MILLIS) handler.postDelayed(() -> start(app, search), delay);
        }
        return true;
    }

    /** A title Kinopoisk found, and the intent that opens its page. */
    static final class Found {
        final String title;
        final String details;
        final Intent open;
        /** Its title has the query: else it's only what Kinopoisk ranked first, likely something else. */
        final boolean matches;

        Found(String title, String details, Intent open, boolean matches) {
            this.title = title;
            this.details = details;
            this.open = open;
            this.matches = matches;
        }
    }

    /**
     * Kinopoisk has no search screen to link to, but answers Android TV's global search. Its own
     * order puts "Долгая дорога в дюнах" before "Дюна", so the title is matched here: the same
     * title first, then one that starts with the query, then one that has it; Kinopoisk's order
     * within each. Blocks: call off the main thread.
     */
    static Found findInKinopoisk(Context context, String query) {
        Found best = null;
        int bestScore = -1;
        String wanted = comparable(query);
        try (Cursor c = context.getContentResolver().query(
                KINOPOISK_SUGGEST, null, null, new String[] {query}, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                String data = text(c, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
                if (data == null) continue;
                String title = text(c, SearchManager.SUGGEST_COLUMN_TEXT_1);
                String details = text(c, SearchManager.SUGGEST_COLUMN_TEXT_2);
                Journal.log(context, "  kinopoisk has: " + title + " / " + details);
                String have = comparable(title);
                // Whole words: "дюна" is not in "долгая дорога в дюнах".
                String h = " " + have + " ", w = " " + wanted + " ";
                // "игра престолов 2 сезон" is the title said with more. Only somewhere inside it
                // ("Золотая дюна" for "дюна") is a different title: shown, not opened.
                int score = have.equals(wanted) ? 3
                        : h.startsWith(w) || (!have.isEmpty() && w.startsWith(h)) ? 2
                        : h.contains(w) ? 1 : 0;
                if (score > bestScore) {
                    String action = text(c, SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
                    Intent open = new Intent(action != null ? action : Intent.ACTION_VIEW, Uri.parse(data))
                            .setPackage(KINOPOISK);
                    best = new Found(title, details, open, score >= 2);
                    bestScore = score;
                }
            }
        } catch (RuntimeException e) {
            Journal.log(context, "kinopoisk search failed: " + e);
            return null;
        }
        return best;
    }

    /** Lower case, "е" for "ё", letters and digits only: "Дюна: Часть вторая" ~ "дюна часть вторая". */
    private static String comparable(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.forLanguageTag("ru")).replace('ё', 'е')
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 ? null : c.getString(i);
    }

    static boolean start(Context context, Intent intent) {
        try {
            context.startActivity(new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(KeyService.TAG, "can't open " + intent, e);
            return false;
        }
    }
}
