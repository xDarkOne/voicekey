package dev.xdark.voicekey;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

/**
 * Speech to text on the projector itself: the remote's mic ([RemoteMic]) through Vosk. Speaks to
 * the voice panel the way Android's SpeechRecognizer does, through a [RecognitionListener], so the
 * panel needn't care which is listening.
 */
final class OfflineEngine {
    /** Silence before the first word that counts as "nothing said". */
    private static final long NO_SPEECH_MILLIS = 6_000;
    /** The longest phrase listened to. */
    private static final long MAX_MILLIS = 12_000;

    /**
     * Loaded on first use and kept while in use: loading takes a second or two. Dropped after
     * [IDLE_MILLIS] unused: it holds some 150 MB, a lot on a projector with about 1 GB free.
     */
    private static Model model;
    private static final long IDLE_MILLIS = 3 * 60_000;
    private static final Handler idle = new Handler(Looper.getMainLooper());
    private static final Runnable unload = () -> {
        synchronized (OfflineEngine.class) {
            if (model != null) {
                model.close();
                model = null;
            }
        }
    };

    private final Context context;
    private final RecognitionListener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean stopRequested;
    private volatile boolean cancelled;
    private Thread thread;

    OfflineEngine(Context context, RecognitionListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    void start() {
        idle.removeCallbacks(unload);
        thread = new Thread(this::run, "vosk");
        thread.start();
    }

    /** "I'm done": recognize what was said so far. */
    void stopListening() {
        stopRequested = true;
    }

    void destroy() {
        cancelled = true;
        stopRequested = true;
    }

    private static synchronized Model model(Context c) throws java.io.IOException {
        if (model == null) {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            model = new Model(ModelStore.dir(c).getAbsolutePath());
        }
        return model;
    }

    private void run() {
        try {
            listen();
        } finally {
            idle.removeCallbacks(unload);
            idle.postDelayed(unload, IDLE_MILLIS);
        }
    }

    private void listen() {
        if (!ModelStore.ready(context)) {
            post(() -> listener.onError(SpeechRecognizer.ERROR_CLIENT));
            return;
        }
        try (Recognizer rec = new Recognizer(model(context), RemoteMic.SAMPLE_RATE);
             RemoteMic mic = new RemoteMic()) {
            rec.setMaxAlternatives(5);
            post(() -> listener.onReadyForSpeech(new Bundle()));
            byte[] frame = new byte[RemoteMic.FRAME_BYTES];
            long started = System.currentTimeMillis();
            boolean spoke = false;
            String lastPartial = "";
            String finished = null; // Vosk's result at a pause after words.
            while (!stopRequested) {
                long now = System.currentTimeMillis();
                if (now - started > MAX_MILLIS) break;
                if (!spoke && now - started > NO_SPEECH_MILLIS) {
                    post(() -> listener.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT));
                    return;
                }
                int n = mic.read(frame);
                if (n < 0) {
                    post(() -> listener.onError(SpeechRecognizer.ERROR_AUDIO));
                    return;
                }
                if (n == 0) {
                    Thread.sleep(10);
                    continue;
                }
                float rms = rmsDb(frame, n);
                post(() -> listener.onRmsChanged(rms));
                if (rec.acceptWaveForm(frame, n)) {
                    // A pause. After words, the phrase is over; a short word may come only now,
                    // with no partial before it, so the result itself is looked at too.
                    String result = rec.getResult();
                    if (spoke || !alternatives(result).isEmpty()) {
                        finished = result;
                        break;
                    }
                } else {
                    String partial = new JSONObject(rec.getPartialResult()).optString("partial", "").trim();
                    if (!partial.isEmpty() && !partial.equals(lastPartial)) {
                        spoke = true;
                        lastPartial = partial;
                        Bundle b = new Bundle();
                        b.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(java.util.Collections.singletonList(partial)));
                        post(() -> listener.onPartialResults(b));
                    }
                }
            }
            if (cancelled) return;
            post(listener::onEndOfSpeech);
            ArrayList<String> heard = alternatives(finished != null ? finished : rec.getFinalResult());
            if (heard.isEmpty()) {
                post(() -> listener.onError(SpeechRecognizer.ERROR_NO_MATCH));
                return;
            }
            Bundle results = new Bundle();
            results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, heard);
            post(() -> listener.onResults(results));
        } catch (Exception e) {
            Journal.log(context, "offline recognizer failed: " + e);
            post(() -> listener.onError(e instanceof java.io.IOException ? SpeechRecognizer.ERROR_AUDIO : SpeechRecognizer.ERROR_CLIENT));
        }
    }

    /** Vosk's alternatives ({"alternatives": [{"text": …}, …]}), or its single "text". */
    private static ArrayList<String> alternatives(String json) throws org.json.JSONException {
        ArrayList<String> out = new ArrayList<>();
        JSONObject o = new JSONObject(json);
        JSONArray alts = o.optJSONArray("alternatives");
        if (alts != null) {
            for (int i = 0; i < alts.length(); i++) {
                String t = alts.getJSONObject(i).optString("text", "").trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        } else {
            String t = o.optString("text", "").trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Loudness on the scale Google's recognizer reports (roughly -2..10), for the panel's dot. */
    private static float rmsDb(byte[] pcm, int n) {
        long sum = 0;
        int samples = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            sum += (long) s * s;
        }
        double rms = Math.sqrt(sum / (double) Math.max(1, samples));
        return (float) (20 * Math.log10(Math.max(1, rms)) - 50) / 3f;
    }

    private void post(Runnable r) {
        if (!cancelled) main.post(r);
    }
}
