package dev.xdark.voicekey;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Scanner;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Updates from the project's GitHub releases: asks for the latest release, and if its version is
 * newer than ours, downloads VoiceKey.apk and hands it to the system installer. Android 11 lets
 * nobody install silently, so the system shows its own "install?" screen and OK on it finishes
 * the job; the signature must match ours, so only an update from the same key goes through.
 * The release carries VoiceKey.apk.sha256 beside the APK: the download is checked against it.
 */
final class Updater {
    static final String LATEST = "https://api.github.com/repos/xDarkOne/voicekey/releases/latest";
    private static final long CHECK_GAP_MILLIS = 12 * 3_600_000L;
    private static final String RESULT_ACTION = "dev.xdark.voicekey.UPDATE_RESULT";

    private Updater() {}

    interface Progress {
        void report(String text);
    }

    static String currentVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** True if [a] is a later version than [b]: "5.17" > "5.16", "v5.2" < "5.10". */
    static boolean newer(String a, String b) {
        String[] x = a.replaceFirst("^v", "").split("\\.");
        String[] y = b.replaceFirst("^v", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? number(x[i]) : 0, q = i < y.length ? number(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.replaceAll("\\D.*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Asks GitHub; remembers a newer release in Prefs (or forgets one we've caught up with). Blocks: run off the main thread. */
    static boolean check(Context context) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(LATEST).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(20_000);
        http.setRequestProperty("Accept", "application/vnd.github+json");
        try (InputStream in = http.getInputStream(); Scanner scan = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            JSONObject release = new JSONObject(scan.hasNext() ? scan.next() : "{}");
            Prefs.setLastUpdateCheck(context, System.currentTimeMillis());
            String tag = release.optString("tag_name", "");
            String url = null, sha = "";
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if ("VoiceKey.apk".equals(a.optString("name"))) url = a.optString("browser_download_url");
                if ("VoiceKey.apk.sha256".equals(a.optString("name"))) sha = fetchText(a.optString("browser_download_url"));
            }
            if (!tag.isEmpty() && url != null && newer(tag, currentVersion(context))) {
                Prefs.setFoundUpdate(context, tag.replaceFirst("^v", ""), url, sha.trim().split("\\s+")[0]);
                return true;
            }
            Prefs.setFoundUpdate(context, null, null, null);
            return false;
        } finally {
            http.disconnect();
        }
    }

    /** The service's own check, at most twice a day; says so on screen if there is something new. */
    static void checkInBackground(Context context) {
        if (!Prefs.autoUpdate(context)) return;
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(context) < CHECK_GAP_MILLIS) return;
        new Thread(() -> {
            try {
                boolean found = check(context);
                Journal.log(context, "update check: " + (found ? "new " + Prefs.foundUpdate(context)[0] : "up to date"));
                if (found) KeyService.flash("VoiceKey " + Prefs.foundUpdate(context)[0]
                        + " готова: Настройки VoiceKey → Обновление", 8_000);
            } catch (Exception e) {
                Journal.log(context, "update check failed: " + e);
            }
        }, "update-check").start();
    }

    private static String fetchText(String address) throws Exception {
        HttpURLConnection http = (HttpURLConnection) new URL(address).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(20_000);
        try (InputStream in = http.getInputStream(); Scanner scan = new Scanner(in, "UTF-8").useDelimiter("\\A")) {
            return scan.hasNext() ? scan.next() : "";
        } finally {
            http.disconnect();
        }
    }

    /**
     * Downloads the APK found by [check] and gives it to the system installer. Blocks until the
     * installer has taken it; the system's confirmation screen is opened by [context] itself.
     */
    static void install(Context context, Progress progress) throws Exception {
        String[] found = Prefs.foundUpdate(context);
        if (found == null) throw new IllegalStateException("no update found");
        File apk = new File(context.getCacheDir(), "update.apk");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HttpURLConnection http = (HttpURLConnection) new URL(found[1]).openConnection();
        http.setConnectTimeout(15_000);
        http.setReadTimeout(30_000);
        try (InputStream in = http.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
            long total = http.getContentLengthLong(), done = 0;
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;
                if (total > 0) progress.report("Скачиваю " + done * 100 / total + "%");
            }
        } finally {
            http.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        if (!found[2].isEmpty() && !found[2].equalsIgnoreCase(hex.toString())) {
            apk.delete();
            throw new SecurityException("checksum mismatch");
        }
        progress.report("Устанавливаю…");

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("VoiceKey.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                session.fsync(out);
            }

            // The result comes back to a receiver that lives only as long as this screen.
            BroadcastReceiver result = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) c.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } else {
                        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        Journal.log(c, "install result " + status + ": " + message);
                        progress.report(status == PackageInstaller.STATUS_SUCCESS ? "Готово"
                                : "Не вышло: " + message + " — OK, ещё раз");
                        c.getApplicationContext().unregisterReceiver(this);
                    }
                }
            };
            context.getApplicationContext().registerReceiver(result, new IntentFilter(RESULT_ACTION));
            Intent back = new Intent(RESULT_ACTION).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(context, id, back,
                    PendingIntent.FLAG_UPDATE_CURRENT);
            session.commit(pending.getIntentSender());
        } catch (Exception e) {
            installer.abandonSession(id);
            throw e;
        }
    }
}
