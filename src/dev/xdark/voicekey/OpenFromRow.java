package dev.xdark.voicekey;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/**
 * Opens a "Continue watching" card in TizenTube: brought back as it was if it still holds that
 * video, else its search for the title. Launchers open a card only inside the app that
 * published it (Lumbina sets the package to ours), so the card comes here first and is passed on.
 * Only YouTube links, and only to TizenTube: nothing else can be started through it. A film
 * LAMPA played in Vimu opens LAMPA's search for its title.
 */
public class OpenFromRow extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String film = getIntent().getStringExtra(WatchNext.EXTRA_LAMPA);
        if (film != null && !film.trim().isEmpty()) {
            Apps.search(this, Command.Target.LAMPA, film.trim(), null);
            finish();
            return;
        }
        Uri uri = getIntent().getData();
        String host = uri == null ? null : uri.getHost();
        if (uri == null || !"https".equals(uri.getScheme()) || host == null
                || !(host.equals("www.youtube.com") || host.equals("youtube.com"))) {
            finish();
            return;
        }
        android.content.Context app = getApplicationContext();
        Runnable search = () -> open(app, uri);
        // The video TizenTube showed last is likely still loaded in it: bring it back first.
        if (WatchNext.resume(getIntent().getStringExtra(WatchNext.EXTRA_TITLE), search)) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(Apps.TIZENTUBE);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(Apps.TIZENTUBE);
            if (launch != null) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Journal.log(this, "watch next: bringing TizenTube back");
                finish();
                return;
            }
        }
        search.run();
        finish();
    }

    private static void open(android.content.Context context, Uri uri) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(Apps.TIZENTUBE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            Journal.log(context, "watch next: opened TizenTube's search");
        } catch (ActivityNotFoundException e) {
            Journal.log(context, "watch next: no TizenTube: " + e);
        }
    }
}
