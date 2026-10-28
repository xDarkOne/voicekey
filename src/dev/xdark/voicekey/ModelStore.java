package dev.xdark.voicekey;

import android.content.Context;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The offline Russian model for Vosk: downloaded once from its authors (alphacephei.com) into the
 * app's own files and unpacked there. Small model: about 46 MB to fetch, 90 MB unpacked.
 */
final class ModelStore {
    static final String URL = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip";
    static final long DOWNLOAD_BYTES = 46_236_750L;

    interface Progress {
        /** [done] of [total] bytes fetched; then -1 while unpacking. */
        void onProgress(long done, long total);
    }

    private ModelStore() {}

    static File dir(Context c) {
        return new File(c.getFilesDir(), "vosk-ru");
    }

    /** Unpacked and whole: the last file the unpacking writes is the marker. */
    static boolean ready(Context c) {
        return new File(dir(c), ".ready").exists();
    }

    static void delete(Context c) {
        deleteTree(dir(c));
        new File(c.getFilesDir(), "vosk-ru.zip.part").delete();
    }

    /** Blocking: call off the main thread. Throws on any failure, leaving nothing half-done. */
    static void download(Context c, Progress progress) throws IOException {
        File part = new File(c.getFilesDir(), "vosk-ru.zip.part");
        HttpURLConnection conn = (HttpURLConnection) new URL(URL).openConnection();
        conn.setConnectTimeout(20_000);
        conn.setReadTimeout(30_000);
        try (InputStream in = new BufferedInputStream(conn.getInputStream());
             OutputStream out = new FileOutputStream(part)) {
            long total = conn.getContentLengthLong() > 0 ? conn.getContentLengthLong() : DOWNLOAD_BYTES;
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            long lastReport = 0;
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                if (done - lastReport > 512 * 1024) {
                    progress.onProgress(done, total);
                    lastReport = done;
                }
            }
        } finally {
            conn.disconnect();
        }
        progress.onProgress(-1, -1);
        File target = dir(c);
        deleteTree(target);
        File tmp = new File(c.getFilesDir(), "vosk-ru.tmp");
        deleteTree(tmp);
        unzip(part, tmp);
        part.delete();
        if (!tmp.renameTo(target)) throw new IOException("can't move the model into place");
        if (!new File(target, ".ready").createNewFile() && !ready(c)) throw new IOException("can't mark the model ready");
    }

    /** Unpacks [zip] into [into], dropping the archive's single top folder. */
    private static void unzip(File zip, File into) throws IOException {
        String root = into.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            byte[] buf = new byte[64 * 1024];
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String name = e.getName();
                int slash = name.indexOf('/');
                String inner = slash >= 0 ? name.substring(slash + 1) : name;
                if (inner.isEmpty()) continue;
                File f = new File(into, inner);
                // No entry may land outside the model's folder.
                if (!f.getCanonicalPath().startsWith(root)) throw new IOException("bad entry " + name);
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    for (int n; (n = z.read(buf)) > 0; ) out.write(buf, 0, n);
                }
            }
        }
    }

    private static void deleteTree(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        f.delete();
    }
}
