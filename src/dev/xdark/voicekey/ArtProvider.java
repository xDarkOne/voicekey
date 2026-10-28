package dev.xdark.voicekey;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Posters for the Watch Next cards VoiceKey writes for TizenTube: the session gives a bitmap, the
 * provider wants a URI, so the bitmap is kept as a file and served read-only from here. Only
 * those posters: names are hashes, nothing else of the app can be read through it.
 */
public class ArtProvider extends ContentProvider {
    static final String AUTHORITY = "dev.xdark.voicekey.art";

    static File dir(Context c) {
        return new File(c.getFilesDir(), "art");
    }

    private static String name(String key) {
        return Integer.toHexString(key.hashCode()) + ".jpg";
    }

    /** Stores [art] for [key] and returns its URI; null if there is no art. */
    static Uri save(Context c, String key, Bitmap art) {
        if (art == null) return null;
        File d = dir(c);
        d.mkdirs();
        File f = new File(d, name(key));
        try (FileOutputStream out = new FileOutputStream(f)) {
            art.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            return null;
        }
        return Uri.parse("content://" + AUTHORITY + "/" + f.getName());
    }

    static void delete(Context c, String key) {
        if (key != null) new File(dir(c), name(key)).delete();
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String n = uri.getLastPathSegment();
        if (n == null || !n.matches("[0-9a-f-]+\\.jpg") || !"r".equals(mode)) throw new FileNotFoundException(String.valueOf(uri));
        return ParcelFileDescriptor.open(new File(dir(getContext()), n), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
