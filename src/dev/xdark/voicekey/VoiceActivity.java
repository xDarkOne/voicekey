package dev.xdark.voicekey;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Listens, then runs the phrase as a command or searches for it in the app it was asked for.
 * Online through Google's recognition service (the service, not its voice-input screen: the
 * patched recognizer feeds the remote's mic only to the service, and its screen closes at once
 * when opened from outside), or offline through Vosk on the projector ([OfflineEngine]).
 *
 * Shows what it hears on a card at the bottom: a mic whose ring swells with the voice, the words
 * as they come, and what it is about to do, held a moment before the app takes over.
 */
public class VoiceActivity extends Activity implements RecognitionListener {
    /** The app to search in (a [Command.Target] name); none: listen for a command. */
    static final String EXTRA_TARGET = "target";
    static final String EXTRA_FOREGROUND = "foreground";
    /** Test entry only: alternatives as if heard, split by "|". */
    static final String EXTRA_PHRASE = "phrase";
    /** Test entry only: listen and show what was heard, but do nothing with it. */
    static final String EXTRA_DRY = "dry";

    private static final ComponentName RECOGNIZER = new ComponentName(KeyService.SEARCH_PACKAGE,
            "com.google.android.katniss.search.serviceapi.KatnissRecognitionService");

    /** How long to wait for the recognizer to say it is ready before calling it dead. */
    private static final long CONNECT_MILLIS = 12_000;
    private boolean heardReady;

    /** The Vidda voice service that turns the remote's audio into Google's recognizer is installed and may record. */
    static boolean voiceServiceReady(android.content.Context context) {
        android.content.pm.PackageManager pm = context.getPackageManager();
        try {
            pm.getPackageInfo(KeyService.SEARCH_PACKAGE, 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
        return pm.checkPermission(android.Manifest.permission.RECORD_AUDIO, KeyService.SEARCH_PACKAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private static final String HINT = "Скажите: «открой Кинопоиск», «найди на ютубе …», «найди в Лампе …»";

    private static final long SHOW_PHRASE_MILLIS = 900;
    private static final long SHOW_ERROR_MILLIS = 1800;

    /** What the card shows, by its mic's colour. */
    private enum State { CONNECTING, LISTENING, WORKING, FAILED }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private OfflineEngine offline;
    private TextView status;
    private TextView words;
    private View mic;
    private View ring;
    private boolean done;
    /** Null: a command. */
    private Command.Target target;
    private String foreground;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String named = getIntent().getStringExtra(EXTRA_TARGET);
        target = named == null ? null : Command.Target.valueOf(named);
        foreground = getIntent().getStringExtra(EXTRA_FOREGROUND);
        setContentView(buildPanel());

        String typed = getIntent().getStringExtra(EXTRA_PHRASE);
        if (typed != null) { // adb's test entry: skip the recognizer.
            Bundle results = new Bundle();
            results.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, new ArrayList<>(Arrays.asList(typed.split("\\|"))));
            handler.post(() -> onResults(results));
            return;
        }
        current = this;
        boolean wantsOffline = Prefs.engine(this) == Prefs.Engine.OFFLINE;
        if (!wantsOffline && !voiceServiceReady(this)) {
            // Without Google's voice service the request would hang on "connecting" for ever.
            Journal.log(this, "online recognizer missing: " + (ModelStore.ready(this) ? "offline instead" : "no fallback"));
            if (!ModelStore.ready(this)) {
                fail("Нет голосового сервиса Google. Настройки VoiceKey → «Настройка: голосовой сервис» или офлайн-модель");
                return;
            }
            wantsOffline = true;
        }
        if (wantsOffline && ModelStore.ready(this)) {
            show(State.CONNECTING, "Загружаю офлайн-распознавание…");
            offline = new OfflineEngine(this, this);
            offline.start();
            return;
        }
        show(State.CONNECTING, wantsOffline ? "Офлайн-модель не скачана — слушаю онлайн" : "Подключаюсь…");
        handler.postDelayed(() -> {
            if (!heardReady && !done) {
                Journal.log(this, "recognizer silent for " + CONNECT_MILLIS + " ms");
                onError(SpeechRecognizer.ERROR_NETWORK_TIMEOUT);
            }
        }, CONNECT_MILLIS);
        recognizer = SpeechRecognizer.createSpeechRecognizer(this, RECOGNIZER);
        recognizer.setRecognitionListener(this);
        recognizer.startListening(new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName())
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5));
    }

    /** The one listening now, for the mic button: pressed again, it means "I'm done". */
    private static VoiceActivity current;

    static boolean finishListening() {
        VoiceActivity voice = current;
        if (voice == null || voice.done) return false;
        if (voice.recognizer != null) voice.recognizer.stopListening();
        if (voice.offline != null) voice.offline.stopListening();
        return true;
    }

    private View buildPanel() {
        float dp = Look.dp(this);

        // The mic in a circle, a ring behind it that swells with the voice.
        ring = new View(this);
        ring.setBackground(Look.circle(0x55F4B63F));
        ring.setScaleX(0.8f);
        ring.setScaleY(0.8f);
        mic = new View(this);
        mic.setBackground(Look.circle(Look.MUTED));
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_mic);
        icon.setColorFilter(Look.ON_FOCUS);
        int micSize = (int) (56 * dp);
        FrameLayout micBox = new FrameLayout(this);
        micBox.setClipChildren(false);
        micBox.addView(ring, new FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER));
        micBox.addView(mic, new FrameLayout.LayoutParams(micSize, micSize, Gravity.CENTER));
        micBox.addView(icon, new FrameLayout.LayoutParams((int) (28 * dp), (int) (28 * dp), Gravity.CENTER));

        status = Look.text(this, 17, Look.MUTED);
        words = Look.text(this, 30, Look.TEXT);
        words.setPadding(0, (int) (10 * dp), 0, 0);
        words.setVisibility(View.GONE); // Until there are words.
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(status);
        texts.addView(words);

        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipChildren(false);
        int pad = (int) (26 * dp);
        card.setPadding(pad, pad, (int) (34 * dp), pad);
        card.setBackground(Look.card(this));
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams((int) (84 * dp), (int) (84 * dp));
        boxLp.rightMargin = (int) (18 * dp);
        card.addView(micBox, boxLp);
        card.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.56f);
        root.addView(card, new FrameLayout.LayoutParams(
                width, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        root.setPadding(0, 0, 0, (int) (64 * dp));
        return root;
    }

    private void show(State state, String text) {
        status.setText(text);
        int color;
        switch (state) {
            case LISTENING: color = Look.ACCENT; break;
            case WORKING: color = Look.TEXT; break;
            default: color = Look.MUTED;
        }
        status.setTextColor(state == State.LISTENING ? Look.TEXT : Look.MUTED);
        ((android.graphics.drawable.GradientDrawable) mic.getBackground()).setColor(color);
        ring.setVisibility(state == State.LISTENING ? View.VISIBLE : View.INVISIBLE);
        if (state != State.LISTENING) setLevel(0.8f);
    }

    private void setLevel(float scale) {
        ring.animate().scaleX(scale).scaleY(scale).setDuration(90).start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish(); // Sent away: stop listening.
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (current == this) current = null;
        if (recognizer != null) recognizer.destroy();
        if (offline != null) offline.destroy();
        super.onDestroy();
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
        heardReady = true;
        show(State.LISTENING, target == null ? "Говорите команду" : "Поиск в " + Apps.locative(target) + " — говорите");
        beep(ToneGenerator.TONE_PROP_BEEP, 120);
    }

    @Override
    public void onRmsChanged(float rms) {
        // Roughly -2..10 dB: the ring grows from the mic's size to half again its size.
        setLevel(1f + Math.max(0f, Math.min(rms, 10f)) / 20f);
    }

    @Override
    public void onEndOfSpeech() {
        if (!done) show(State.WORKING, "Распознаю…");
    }

    @Override
    public void onPartialResults(Bundle partial) {
        String phrase = first(partial);
        if (phrase != null && !done) showWords(phrase);
    }

    private void showWords(String text) {
        words.setText(text);
        words.setVisibility(View.VISIBLE);
    }

    @Override
    public void onResults(Bundle results) {
        if (done) return;
        done = true;
        List<String> heard = alternatives(results);
        if (heard.isEmpty()) {
            fail("Не расслышал, попробуйте ещё раз");
            return;
        }
        Journal.log(this, "heard (" + (target == null ? "command" : target) + " over " + foreground
                + (offline != null ? ", offline" : "") + "): " + heard);
        if (getIntent().getBooleanExtra(EXTRA_DRY, false)) {
            show(State.WORKING, "Пробный режим: только показываю");
            showWords("«" + heard.get(0) + "»");
            handler.postDelayed(this::finish, 3_000);
            return;
        }
        if (target != null) {
            searchIn(target, heard.get(0));
            return;
        }
        String said = heard.get(0).toLowerCase(java.util.Locale.forLanguageTag("ru"));
        if (said.matches(".*\\b(отдай|отпусти|освободи)\\b.*(микрофон|кнопк).*")) {
            Prefs.setMicOn(this, false);
            Journal.log(this, "mic button given away");
            show(State.WORKING, "Кнопка микрофона отдана. Вернуть — в настройках VoiceKey");
            handler.postDelayed(this::finish, 3_500);
            return;
        }
        if (said.matches(".*\\b(выключи|отключи|убери)\\b.*перев.*") || said.matches(".*\\b(включи|верни)\\b.*перев.*")) {
            boolean on = !said.matches(".*\\b(выключи|отключи|убери)\\b.*");
            Prefs.setTranslate(this, on);
            show(State.WORKING, on ? "Перевод включён" : "Перевод выключен");
            later(() -> { });
            return;
        }
        // An app not named: the one on screen, else YouTube.
        Command.Target onScreen = Apps.targetOf(foreground);
        Command.Target fallback = onScreen != null ? onScreen : Command.Target.YOUTUBE;
        run(Command.best(heard, fallback));
    }

    private void run(Command command) {
        Journal.log(this, "command: " + command);
        if (command.action == Command.Action.SEARCH) {
            searchIn(command.target, command.query);
        } else if (command.action == Command.Action.OPEN) {
            show(State.WORKING, "Открываю");
            showWords(Apps.accusative(command.target));
            later(() -> Apps.open(this, command.target));
        } else {
            showWords("«" + command.query + "»");
            fail("Не понял команду. " + HINT);
        }
    }

    private void searchIn(Command.Target target, String query) {
        showWords("«" + query + "»");
        if (target == Command.Target.KINOPOISK) {
            searchKinopoisk(query);
        } else {
            show(State.WORKING, "Ищу в " + Apps.locative(target));
            later(() -> Apps.search(this, target, query, foreground));
        }
    }

    /** Asks Kinopoisk off the main thread, then opens the best match's page. */
    private void searchKinopoisk(String query) {
        show(State.WORKING, "Ищу в Кинопоиске…");
        Context app = getApplicationContext();
        new Thread(() -> {
            Apps.Found found = Apps.findInKinopoisk(app, query);
            Journal.log(app, "kinopoisk: " + query + " -> " + (found == null ? "nothing" : found.title + " / " + found.details + (found.matches ? "" : " (no match)")));
            runOnUiThread(() -> {
                if (isFinishing()) return;
                if (found == null) {
                    fail("В Кинопоиске ничего не нашлось");
                    return;
                }
                if (!found.matches) {
                    showWords("Похожее: " + found.title);
                    fail("В Кинопоиске нет «" + query + "»");
                    return;
                }
                show(State.WORKING, "Открываю в Кинопоиске");
                showWords(found.details == null || found.details.isEmpty()
                        ? found.title : found.title + " · " + found.details);
                later(() -> Apps.start(app, found.open));
            });
        }, "kinopoisk-search").start();
    }

    /** Leaves what it is about to do on screen a moment, then does it. */
    private void later(Runnable action) {
        beep(ToneGenerator.TONE_PROP_ACK, 150);
        handler.postDelayed(() -> {
            finish();
            action.run();
        }, SHOW_PHRASE_MILLIS);
    }

    @Override
    public void onError(int error) {
        if (done) return;
        done = true;
        Journal.log(this, "recognizer error " + error + " (" + (target == null ? "command" : target)
                + (offline != null ? ", offline" : "") + ")");
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                fail("Не расслышал, попробуйте ещё раз");
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                fail("Нет связи с Google — можно включить офлайн-распознавание в настройках");
                break;
            case SpeechRecognizer.ERROR_AUDIO:
                fail("Нет звука с пульта");
                break;
            default:
                fail("Ошибка распознавания (" + error + ")");
        }
    }

    private void fail(String why) {
        show(State.FAILED, why);
        beep(ToneGenerator.TONE_PROP_NACK, 200);
        handler.postDelayed(this::finish, why.contains(HINT) ? SHOW_ERROR_MILLIS * 2 : SHOW_ERROR_MILLIS);
    }

    /** Earcons: listening, done, failed. Heard without looking at the screen; off in the settings. */
    private void beep(int toneType, int millis) {
        if (!Prefs.sounds(this)) return;
        try {
            ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 40);
            tone.startTone(toneType, millis);
            new Handler(Looper.getMainLooper()).postDelayed(tone::release, millis + 300);
        } catch (RuntimeException e) {
            Log.w(KeyService.TAG, "no beep", e);
        }
    }

    /** What the recognizer heard, its likeliest first. */
    private static List<String> alternatives(Bundle bundle) {
        List<String> out = new ArrayList<>();
        ArrayList<String> found = bundle == null ? null
                : bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (found == null) return out;
        for (String s : found) {
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }

    private static String first(Bundle bundle) {
        List<String> found = alternatives(bundle);
        return found.isEmpty() ? null : found.get(0);
    }

    @Override public void onBeginningOfSpeech() {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEvent(int type, Bundle params) {}
}
