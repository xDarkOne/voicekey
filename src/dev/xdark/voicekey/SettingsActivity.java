package dev.xdark.voicekey;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * VoiceKey's settings, made for the remote: up and down pick a row, OK or right moves its value
 * on, left moves it back. What the mic button does on a press and on a hold, how speech is
 * recognized, the on-screen translation, sounds. Opening it also puts the service back if the
 * system dropped it.
 */
public class SettingsActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private volatile boolean downloading;
    private boolean deleteArmed;

    /** One line of the list: what it is, its value now, how the value moves. */
    private abstract class Row {
        final String title;
        final String hint;
        LinearLayout view;
        TextView titleView;
        TextView hintView;
        TextView valueView;

        Row(String title, String hint) {
            this.title = title;
            this.hint = hint;
        }

        abstract String value();

        /** [step] +1 on OK or right, -1 on left. */
        abstract void change(int step);

        String hintNow() {
            return hint;
        }
    }

    private class Toggle extends Row {
        private final java.util.function.Supplier<Boolean> get;
        private final java.util.function.Consumer<Boolean> set;

        Toggle(String title, String hint, java.util.function.Supplier<Boolean> get, java.util.function.Consumer<Boolean> set) {
            super(title, hint);
            this.get = get;
            this.set = set;
        }

        @Override String value() {
            return get.get() ? "Вкл." : "Выкл.";
        }

        @Override void change(int step) {
            set.accept(!get.get());
        }
    }

    private class Choice<T> extends Row {
        private final T[] options;
        private final java.util.function.Supplier<T> get;
        private final java.util.function.Consumer<T> set;
        private final java.util.function.Function<T, String> name;

        Choice(String title, String hint, T[] options, java.util.function.Supplier<T> get,
               java.util.function.Consumer<T> set, java.util.function.Function<T, String> name) {
            super(title, hint);
            this.options = options;
            this.get = get;
            this.set = set;
            this.name = name;
        }

        @Override String value() {
            return name.apply(get.get());
        }

        @Override void change(int step) {
            int i = 0;
            T now = get.get();
            for (int k = 0; k < options.length; k++) if (options[k].equals(now)) i = k;
            set.accept(options[(i + step + options.length) % options.length]);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> Journal.log(this, "settings opened: " + BootReceiver.heal(this)), "heal").start();

        rows.add(new Toggle("Кнопка микрофона", "Выкл. — кнопка остаётся прошивке или другому приложению",
                () -> Prefs.micOn(this), on -> Prefs.setMicOn(this, on)));
        rows.add(new Choice<>("Нажатие", "Что делает короткое нажатие кнопки микрофона",
                Prefs.Action.values(), () -> Prefs.tap(this), a -> Prefs.setTap(this, a), a -> a.title));
        rows.add(new Choice<>("Удержание", "Что делает кнопка, если её подержать",
                Prefs.Action.values(), () -> Prefs.hold(this), a -> Prefs.setHold(this, a), a -> a.title));
        Long[] holds = new Long[Prefs.HOLD_CHOICES.length];
        for (int i = 0; i < holds.length; i++) holds[i] = Prefs.HOLD_CHOICES[i];
        rows.add(new Choice<>("Время удержания", "Сколько держать кнопку, чтобы это было удержанием",
                holds, () -> Prefs.holdMillis(this), ms -> Prefs.setHoldMillis(this, ms),
                ms -> String.valueOf(ms / 1000.0).replace('.', ',').replaceAll(",0$", "") + " с"));
        rows.add(new Choice<Prefs.Engine>("Распознавание речи", null, Prefs.Engine.values(),
                () -> Prefs.engine(this), e -> Prefs.setEngine(this, e), e -> e.title) {
            @Override String hintNow() {
                return Prefs.engine(SettingsActivity.this).hint;
            }
        });
        rows.add(new Row("Офлайн-модель", null) {
            @Override String value() {
                if (downloading) return "Скачиваю…";
                if (ModelStore.ready(SettingsActivity.this)) return deleteArmed ? "OK — удалить" : "Скачана";
                return "OK — скачать";
            }

            @Override String hintNow() {
                return ModelStore.ready(SettingsActivity.this)
                        ? "Русская модель Vosk, около 90 МБ. Чтобы удалить: OK дважды"
                        : "Русская модель Vosk с сайта её авторов, 46 МБ";
            }

            @Override void change(int step) {
                if (downloading) return;
                if (ModelStore.ready(SettingsActivity.this)) {
                    if (!deleteArmed) {
                        deleteArmed = true;
                        handler.postDelayed(() -> {
                            deleteArmed = false;
                            refresh();
                        }, 4_000);
                    } else {
                        deleteArmed = false;
                        ModelStore.delete(SettingsActivity.this);
                    }
                    return;
                }
                download(this);
            }
        });
        rows.add(new Toggle("Перевод интерфейса", "Русские надписи поверх настроек и приложений Hisense",
                () -> Prefs.translate(this), on -> Prefs.setTranslate(this, on)));
        rows.add(new Toggle("Звуковые сигналы", "Короткий звук, когда можно говорить, и после",
                () -> Prefs.sounds(this), on -> Prefs.setSounds(this, on)));

        setContentView(build());
        refresh();
        if (!rows.isEmpty()) rows.get(0).view.requestFocus();
    }

    private View build() {
        float dp = Look.dp(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = (int) (96 * dp);
        list.setPadding(side, (int) (56 * dp), side, (int) (48 * dp));

        TextView title = Look.text(this, 38, Look.TEXT);
        title.setText("VoiceKey");
        TextView sub = Look.text(this, 18, Look.MUTED);
        sub.setText("Голос с кнопки пульта и русский интерфейс");
        sub.setPadding(0, (int) (12 * dp), 0, (int) (28 * dp));
        list.addView(title);
        list.addView(sub);

        for (Row row : rows) {
            LinearLayout v = new LinearLayout(this);
            v.setGravity(Gravity.CENTER_VERTICAL);
            int px = (int) (24 * dp), py = (int) (18 * dp);
            v.setPadding(px, py, px, py);
            v.setFocusable(true);
            v.setClickable(true);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            row.titleView = Look.text(this, 22, Look.TEXT);
            row.titleView.setText(row.title);
            row.hintView = Look.text(this, 15, Look.MUTED);
            row.hintView.setPadding(0, (int) (6 * dp), 0, 0);
            texts.addView(row.titleView);
            texts.addView(row.hintView);
            row.valueView = Look.text(this, 20, Look.MUTED);
            row.valueView.setGravity(Gravity.END);
            v.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            v.addView(row.valueView);
            v.setOnFocusChangeListener((view, focused) -> paint(row, focused));
            v.setOnClickListener(view -> {
                row.change(+1);
                refresh();
            });
            v.setOnKeyListener((view, code, e) -> {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    row.change(code == KeyEvent.KEYCODE_DPAD_RIGHT ? +1 : -1);
                    refresh();
                    return true;
                }
                return false;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = (int) (6 * dp);
            list.addView(v, lp);
            row.view = v;
            paint(row, false);
        }

        TextView foot = Look.text(this, 15, Look.MUTED);
        foot.setText("Голосом: «открой Лампу», «найди на ютубе …», «найди в Кинопоиске …», «выключи перевод», «отдай микрофон»");
        foot.setPadding((int) (24 * dp), (int) (24 * dp), (int) (24 * dp), 0);
        list.addView(foot);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Look.SCREEN);
        scroll.setFillViewport(true);
        scroll.addView(list);
        return scroll;
    }

    private void paint(Row row, boolean focused) {
        row.view.setBackground(focused ? Look.pill(this, Look.FOCUS) : null);
        row.titleView.setTextColor(focused ? Look.ON_FOCUS : Look.TEXT);
        row.hintView.setTextColor(focused ? 0xFF5F6368 : Look.MUTED);
        row.valueView.setTextColor(focused ? Look.ON_FOCUS : Look.ACCENT);
    }

    private void refresh() {
        for (Row row : rows) {
            row.valueView.setText(row.value());
            String h = row.hintNow();
            row.hintView.setText(h == null ? "" : h);
            row.hintView.setVisibility(h == null ? View.GONE : View.VISIBLE);
        }
    }

    private void download(Row row) {
        downloading = true;
        refresh();
        new Thread(() -> {
            try {
                ModelStore.download(this, (done, total) -> handler.post(() -> row.valueView.setText(done < 0
                        ? "Распаковываю…" : String.format(Locale.ROOT, "Скачиваю %d%%", done * 100 / total))));
                Journal.log(this, "offline model downloaded");
            } catch (Exception e) {
                Journal.log(this, "offline model download failed: " + e);
                handler.post(() -> row.valueView.setText("Не скачалась — OK, ещё раз"));
                downloading = false;
                return;
            }
            downloading = false;
            handler.post(this::refresh);
        }, "model-download").start();
    }
}
