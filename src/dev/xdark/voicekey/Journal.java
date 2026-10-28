package dev.xdark.voicekey;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * What VoiceKey heard and did, in a file: the firmware runs no logd, so logcat is empty. adb
 * can't read the app's folder on Android 11, so the service prints it:
 * `adb shell dumpsys activity service dev.xdark.voicekey/.KeyService`.
 */
final class Journal {
    private static final long MAX_BYTES = 256 * 1024;
    private static File file;

    private Journal() {}

    /** The last [lines] lines, for the service's dump. */
    static synchronized void tail(Context context, java.io.PrintWriter out, int lines) {
        File dir = context.getApplicationContext().getExternalFilesDir(null);
        File f = dir == null ? null : new File(dir, "journal.txt");
        if (f == null || !f.exists()) {
            out.println("journal is empty");
            return;
        }
        java.util.ArrayDeque<String> last = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.FileReader(f))) {
            for (String line; (line = in.readLine()) != null; ) {
                last.add(line);
                if (last.size() > lines) last.removeFirst();
            }
        } catch (IOException e) {
            out.println("can't read the journal: " + e);
        }
        for (String line : last) out.println(line);
    }

    static synchronized void log(Context context, String line) {
        Log.i(KeyService.TAG, line);
        try {
            if (file == null) {
                File dir = context.getApplicationContext().getExternalFilesDir(null);
                if (dir == null) return;
                file = new File(dir, "journal.txt");
            }
            if (file.length() > MAX_BYTES) file.renameTo(new File(file.getParent(), "journal.old.txt"));
            String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
            try (FileWriter out = new FileWriter(file, true)) {
                out.write(time + " " + line + "\n");
            }
        } catch (IOException e) {
            Log.w(KeyService.TAG, "journal", e);
        }
    }
}
