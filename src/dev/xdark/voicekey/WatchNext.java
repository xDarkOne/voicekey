package dev.xdark.voicekey;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.media.tv.TvContract;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.regex.Pattern;

/**
 * "Continue watching" for TizenTube, which publishes nothing to Android TV's rows itself: what it
 * plays is followed through its media session (title, channel, duration, position), and a video
 * left halfway goes to the system's Watch Next list, where launchers such as Lumbina show it.
 * Watched to the end, it is taken off again. Runs in the notification listener: only a listener
 * may read other apps' media sessions.
 *
 * Also films LAMPA plays in Vimu: left with Home, Vimu closes without giving LAMPA the position,
 * so LAMPA writes no card. Vimu's session still names the film, so a card of ours opens LAMPA's
 * search for it. Left with Back, LAMPA writes its own card and ours goes.
 */
final class WatchNext {
    private static final String TIZENTUBE = Apps.TIZENTUBE;
    /** A video ID as YouTube writes them: if the session gives one, the card opens that very video. */
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final long MIN_WATCHED_MILLIS = 5_000;
    private static final long MIN_DURATION_MILLIS = 60_000;
    private static final float DONE_SHARE = 0.95f;
    /** One card for TizenTube, one for LAMPA: what was left last. */
    private static final int KEEP = 1;
    private static final String TT_KEY = "tizentube:";
    private static final String LAMPA_KEY = "lampa:";

    private final Context context;
    private final ComponentName listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaController controller;
    private boolean loggedKeys;

    /** What is playing now. */
    private String title;
    private String channel;
    private String mediaId;
    private long duration;
    private Bitmap art;
    /** The video [art] was taken for. */
    private String artOf;
    /** The last video's picture, while a new video still shows it. */
    private Bitmap staleArt;
    private long position;
    private long positionAt;
    private boolean playing;
    /**
     * Vimu's newest session, followed for the film's title: on Back the session is gone by the
     * time Vimu is off the screen, so the title is kept from while it played.
     */
    private MediaController vimu;
    private String vimuTitle;
    private final MediaController.Callback vimuCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            String t = filmTitle(metadata);
            if (t != null) vimuTitle = t;
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::attach;

    /** While a video plays, its place is saved every minute: no pause needed for it to show. */
    private static final long SAVE_EVERY_MILLIS = 60_000;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (playing) commit();
            handler.postDelayed(this, SAVE_EVERY_MILLIS);
        }
    };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            String next = metadata == null ? null : metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            // TizenTube sends a new video's details in steps, the title last: an empty one changes nothing yet.
            if (next == null || next.isEmpty()) return;
            if (!next.equals(title)) commit(); // The video before this one: save where it was left.
            read(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            boolean wasPlaying = playing;
            readState(state);
            if (wasPlaying && !playing) commit();
        }

        @Override
        public void onSessionDestroyed() {
            commit();
            detach();
        }
    };

    /** The running one, for [OpenFromRow]: only this listener sees TizenTube's session. */
    private static volatile WatchNext running;

    /** The card's title in the intent, so a card for what TizenTube still holds just brings it back. */
    static final String EXTRA_TITLE = "vk_title";
    /** A film's title for LAMPA's search, on our card for a film LAMPA played in Vimu. */
    static final String EXTRA_LAMPA = "vk_lampa";

    WatchNext(Context context, ComponentName listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * The last video TizenTube showed: its session closes in the background, this stays, kept
     * across restarts of VoiceKey too.
     */
    private static String lastTitle(Context c) {
        return c.getSharedPreferences("voicekey", Context.MODE_PRIVATE).getString("tt_last", null);
    }

    private static void setLastTitle(Context c, String title) {
        c.getSharedPreferences("voicekey", Context.MODE_PRIVATE).edit().putString("tt_last", title).apply();
    }

    /** How long TizenTube is given, once brought back, to show which video it holds. */
    private static final long COME_BACK_MILLIS = 8_000;

    /**
     * Whether [cardTitle] is the video TizenTube showed last: then it is likely still loaded in
     * the background, and bringing TizenTube back is all it takes. Its session reappears with
     * the title last, so it is watched for a while; [fallback] (the search) runs only if TizenTube
     * shows another video, or plays nothing at all (it was closed and starts on its home).
     */
    static boolean resume(String cardTitle, Runnable fallback) {
        WatchNext w = running;
        if (w == null || cardTitle == null) return false;
        String last = lastTitle(w.context);
        if (last == null || !last.trim().equals(cardTitle.trim())) return false;
        long until = SystemClock.uptimeMillis() + COME_BACK_MILLIS;
        w.handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String now = w.controller != null ? w.title : null;
                if (now != null && now.trim().equals(cardTitle.trim())) {
                    if (!w.playing) w.controller.getTransportControls().play();
                    Journal.log(w.context, "watch next: TizenTube came back with its video");
                } else if (now != null && !now.isEmpty()) {
                    Journal.log(w.context, "watch next: TizenTube came back with another video, searching");
                    fallback.run();
                } else if (SystemClock.uptimeMillis() < until) {
                    w.handler.postDelayed(this, 500);
                } else {
                    Journal.log(w.context, "watch next: TizenTube came back playing nothing, searching");
                    fallback.run();
                }
            }
        }, 500);
        return true;
    }

    /**
     * Vimu, opened by LAMPA, is off the screen: [toLampa] when it went back to LAMPA (Back, which
     * gives LAMPA the position and LAMPA writes its own card), else Home or another app.
     */
    static void vimuLeft(boolean toLampa) {
        WatchNext w = running;
        if (w != null) w.handler.post(() -> w.onVimuLeft(toLampa));
    }

    private void onVimuLeft(boolean toLampa) {
        MediaController c = newestVimu();
        MediaMetadata m = c == null ? null : c.getMetadata();
        String film = vimuTitle;
        if (film == null) {
            Journal.log(context, "watch next: Vimu left, no title in its session");
            return;
        }
        String key = LAMPA_KEY + film;
        try {
            if (toLampa) {
                lampaHasCard(film, true);
                if (find(key) != null) remove(key);
                Journal.log(context, "watch next: «" + film + "» back in LAMPA, it keeps its own card");
                return;
            }
            if (m == null || !film.equals(filmTitle(m))) {
                Journal.log(context, "watch next: Vimu left, its session is not «" + film + "»");
                return;
            }
            if (lampaHasCard(film, false)) {
                // Its card opens the film's page, and Vimu starts where LAMPA last heard: ours would be a twin.
                if (find(key) != null) remove(key);
                Journal.log(context, "watch next: «" + film + "» left in Vimu, LAMPA has a card for it");
                return;
            }
            PlaybackState s = c.getPlaybackState();
            long at = s == null ? 0 : s.getPosition();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
                at += Math.max(0, SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime());
            }
            long length = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            if (at < MIN_WATCHED_MILLIS) return;
            Intent open = new Intent(Intent.ACTION_VIEW).setComponent(new ComponentName(context, OpenFromRow.class))
                    .putExtra(EXTRA_LAMPA, film);
            Bitmap poster = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (poster == null) poster = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            save(key, LAMPA_KEY, TvContract.WatchNextPrograms.TYPE_MOVIE, film, "Лампа", length, at, poster, open);
            Journal.log(context, "watch next: «" + film + "» left in Vimu at " + at / 1000 + " s");
        } catch (RuntimeException e) {
            Journal.log(context, "watch next failed: " + e);
        }
    }

    /**
     * Films LAMPA has a card for: it writes one when Vimu closes with Back, and its rows can't be
     * read from here, so they are remembered. [add] records [film] as one.
     */
    private boolean lampaHasCard(String film, boolean add) {
        android.content.SharedPreferences p = context.getSharedPreferences("voicekey", Context.MODE_PRIVATE);
        java.util.Set<String> films = new java.util.LinkedHashSet<>(p.getStringSet("lampa_cards", java.util.Collections.emptySet()));
        if (!add) return films.contains(film);
        films.add(film);
        p.edit().putStringSet("lampa_cards", films).apply();
        return true;
    }

    void start() {
        running = this;
        try {
            MediaSessionManager msm = context.getSystemService(MediaSessionManager.class);
            migrate();
            handler.removeCallbacks(tick);
            handler.postDelayed(tick, SAVE_EVERY_MILLIS);
            msm.addOnActiveSessionsChangedListener(sessions, listener, handler);
            attach(msm.getActiveSessions(listener));
        } catch (SecurityException e) {
            Journal.log(context, "watch next: no access to media sessions: " + e);
        }
    }

    void stop() {
        if (running == this) running = null;
        handler.removeCallbacks(tick);
        try {
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(sessions);
        } catch (RuntimeException ignored) {
        }
        commit();
        detach();
        if (vimu != null) vimu.unregisterCallback(vimuCallback);
        vimu = null;
    }

    private void attach(List<MediaController> controllers) {
        if (controllers == null) return;
        followVimu();
        for (MediaController c : controllers) {
            if (!TIZENTUBE.equals(c.getPackageName())) continue;
            if (controller != null && controller.getSessionToken().equals(c.getSessionToken())) return;
            detach();
            controller = c;
            controller.registerCallback(callback, handler);
            read(c.getMetadata());
            readState(c.getPlaybackState());
            return;
        }
    }

    private static String filmTitle(MediaMetadata m) {
        if (m == null) return null;
        CharSequence t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null) t = m.getDescription().getTitle();
        return t == null || t.toString().trim().isEmpty() ? null : t.toString().trim();
    }

    /** Follows the newest Vimu session for its title. */
    private void followVimu() {
        MediaController c = newestVimu();
        if (c == null || vimu != null && vimu.getSessionToken().equals(c.getSessionToken())) return;
        if (vimu != null) vimu.unregisterCallback(vimuCallback);
        vimu = c;
        vimu.registerCallback(vimuCallback, handler);
        String t = filmTitle(c.getMetadata());
        if (t != null) vimuTitle = t;
    }

    /**
     * The session of the film Vimu played last. Every film leaves one, all still "playing" after
     * Vimu closes, so the newest is the one whose playback started last.
     */
    private MediaController newestVimu() {
        MediaController best = null;
        long bestAt = Long.MIN_VALUE;
        try {
            for (MediaController c : context.getSystemService(MediaSessionManager.class).getActiveSessions(listener)) {
                if (!Apps.VIMU.equals(c.getPackageName())) continue;
                PlaybackState s = c.getPlaybackState();
                long at = s == null ? Long.MIN_VALUE + 1 : s.getLastPositionUpdateTime();
                if (at > bestAt) {
                    best = c;
                    bestAt = at;
                }
            }
        } catch (SecurityException e) {
            Journal.log(context, "watch next: no access to media sessions: " + e);
        }
        return best;
    }

    private void detach() {
        if (controller != null) controller.unregisterCallback(callback);
        controller = null;
        title = null;
    }

    private void read(MediaMetadata m) {
        if (m == null) {
            title = null;
            return;
        }
        if (!loggedKeys) {
            // Once: which fields TizenTube fills in, for whoever reads the journal.
            StringBuilder keys = new StringBuilder();
            for (String k : m.keySet()) {
                Object v = k.endsWith("_ART") || k.endsWith(".ART") || k.endsWith("ICON") ? "(image)" : m.getString(k);
                if (v == null) v = m.getLong(k);
                keys.append(k.replace("android.media.metadata.", "")).append('=').append(v).append("; ");
            }
            Journal.log(context, "watch next: TizenTube session fields: " + keys);
            loggedKeys = true;
        }
        title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null) title = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (title != null && !title.isEmpty()) setLastTitle(context, title);
        channel = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        mediaId = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        duration = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        Bitmap next = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (next == null) next = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (next == null) next = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (title != null && title.equals(artOf)) {
            if (next != null) art = next;
        } else if (next != null && (art != null && next.sameAs(art) || staleArt != null && next.sameAs(staleArt))) {
            // TizenTube names a new video before it changes the picture: this one is the last video's.
            if (art != null) staleArt = art;
            art = null;
        } else {
            staleArt = null;
            art = next;
            if (next != null) artOf = title;
        }
    }

    private void readState(PlaybackState s) {
        if (s == null) return;
        playing = s.getState() == PlaybackState.STATE_PLAYING;
        position = s.getPosition();
        positionAt = s.getLastPositionUpdateTime();
    }

    /** Where the video is now: the last reported position, run on while it plays. */
    private long positionNow() {
        if (!playing || positionAt <= 0) return position;
        return position + Math.max(0, SystemClock.elapsedRealtime() - positionAt);
    }

    /** Saves or clears the current video in Watch Next, by how far it was watched. */
    private void commit() {
        if (title == null || title.isEmpty()) return;
        long at = positionNow();
        String key = TT_KEY + (mediaId != null && !mediaId.isEmpty() ? mediaId : title);
        try {
            if (duration >= MIN_DURATION_MILLIS && at >= duration * DONE_SHARE) {
                remove(key);
                Journal.log(context, "watch next: finished «" + title + "», removed");
            } else if (at >= MIN_WATCHED_MILLIS && (duration <= 0 || duration >= MIN_DURATION_MILLIS)) {
                save(key, TT_KEY, TvContract.WatchNextPrograms.TYPE_CLIP, title, channel, duration, at, art, openIntent(at));
                Journal.log(context, "watch next: «" + title + "» at " + at / 1000 + "/" + duration / 1000 + " s");
            }
        } catch (RuntimeException e) {
            Journal.log(context, "watch next failed: " + e);
        }
    }

    /** One card; [kind] is the key's prefix, of which [KEEP] cards stay. */
    private void save(String key, String kind, int type, String title, String channel, long duration, long at,
                      Bitmap art, Intent open) {
        ContentResolver cr = context.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(TvContract.WatchNextPrograms.COLUMN_TYPE, type);
        v.put(TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE, TvContract.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE);
        v.put(TvContract.WatchNextPrograms.COLUMN_TITLE, title);
        if (channel != null) v.put(TvContract.WatchNextPrograms.COLUMN_SHORT_DESCRIPTION, channel);
        if (duration > 0) v.put(TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS, (int) Math.min(Integer.MAX_VALUE, duration));
        v.put(TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS, (int) Math.min(Integer.MAX_VALUE, at));
        v.put(TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS, System.currentTimeMillis());
        v.put(TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID, key);
        v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI, open.toUri(Intent.URI_INTENT_SCHEME));
        Uri poster = ArtProvider.save(context, key, art);
        if (poster != null) {
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI, poster.toString());
            v.put(TvContract.WatchNextPrograms.COLUMN_POSTER_ART_ASPECT_RATIO, TvContract.WatchNextPrograms.ASPECT_RATIO_16_9);
        }
        Long id = find(key);
        if (id != null) {
            cr.update(TvContract.buildWatchNextProgramUri(id), v, null, null);
        } else {
            cr.insert(TvContract.WatchNextPrograms.CONTENT_URI, v);
        }
        trim(kind);
    }

    /** That very video if the session named it, else TizenTube's search for its exact title. */
    private Intent openIntent(long at) {
        Uri uri;
        if (mediaId != null && VIDEO_ID.matcher(mediaId).matches()) {
            uri = Uri.parse("https://www.youtube.com/watch?v=" + mediaId + "&t=" + at / 1000 + "s");
        } else {
            uri = searchUri(title);
        }
        return toRow(uri, title);
    }

    private static Uri searchUri(String title) {
        return Uri.parse("https://www.youtube.com/tv?launch=voice&vs=11&va=search&vaa=" + Uri.encode(title));
    }

    /**
     * Through [OpenFromRow]: a launcher opens a card only inside the app that published it, so a
     * card pointing at TizenTube itself would open nothing.
     */
    private Intent toRow(Uri uri, String title) {
        return new Intent(Intent.ACTION_VIEW, uri).setComponent(new ComponentName(context, OpenFromRow.class))
                .putExtra(EXTRA_TITLE, title);
    }

    /** Cards written before [OpenFromRow] pointed at TizenTube and opened nothing: rewritten once. */
    private void migrate() {
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                new String[] {TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_TITLE,
                        TvContract.WatchNextPrograms.COLUMN_INTENT_URI}, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String intent = c.getString(2);
                if (intent == null || intent.contains("S." + EXTRA_TITLE + "=") || intent.contains("S." + EXTRA_LAMPA + "=")) continue;
                ContentValues v = new ContentValues();
                v.put(TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                        toRow(searchUri(c.getString(1)), c.getString(1)).toUri(Intent.URI_INTENT_SCHEME));
                context.getContentResolver().update(TvContract.buildWatchNextProgramUri(c.getLong(0)), v, null, null);
                Journal.log(context, "watch next: card «" + c.getString(1) + "» now opens through VoiceKey");
            }
        } catch (RuntimeException e) {
            Journal.log(context, "watch next: migrate failed: " + e);
        }
    }

    private void remove(String key) {
        Long id = find(key);
        if (id != null) context.getContentResolver().delete(TvContract.buildWatchNextProgramUri(id), null, null);
        ArtProvider.delete(context, key);
    }

    /** Our row for [key]: the provider shows each app only its own rows. */
    private Long find(String key) {
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                new String[] {TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, null)) {
            if (c == null) return null;
            while (c.moveToNext()) {
                if (key.equals(c.getString(1))) return c.getLong(0);
            }
        }
        return null;
    }

    /** Keeps the [KEEP] last left videos of a [kind], oldest out first. */
    private void trim(String kind) {
        try (Cursor c = context.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI,
                new String[] {TvContract.WatchNextPrograms._ID, TvContract.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID},
                null, null, TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS + " DESC")) {
            if (c == null) return;
            int n = 0;
            while (c.moveToNext()) {
                String key = c.getString(1);
                // TizenTube's cards from before LAMPA's had no other kind.
                boolean mine = key == null ? kind.equals(TT_KEY) : key.startsWith(kind) || (kind.equals(TT_KEY) && !key.startsWith(LAMPA_KEY));
                if (!mine || ++n <= KEEP) continue;
                context.getContentResolver().delete(TvContract.buildWatchNextProgramUri(c.getLong(0)), null, null);
                ArtProvider.delete(context, c.getString(1));
            }
        }
    }
}
