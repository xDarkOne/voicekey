package dev.xdark.voicekey;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the accessibility service back after a boot or a wake from sleep: the firmware may
 * rewrite the list of enabled services, list ours without binding it, or force-stop us. Checks again a little later, as the
 * firmware's rewrite may come after us.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_HEAL = "dev.xdark.voicekey.HEAL";
    private static final long[] RECHECK_MILLIS = {20_000, 60_000, 180_000};

    @Override
    public void onReceive(Context context, Intent intent) {
        Journal.log(context, "woken by " + intent.getAction() + ": " + heal(context));
        if (!ACTION_HEAL.equals(intent.getAction())) scheduleRechecks(context);
    }

    private static void scheduleRechecks(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        for (int i = 0; i < RECHECK_MILLIS.length; i++) {
            Intent heal = new Intent(context, BootReceiver.class).setAction(ACTION_HEAL);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context, i, heal, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            alarms.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + RECHECK_MILLIS[i], pending);
        }
    }

    /** Returns what it found or did, for the hand-run screen. */
    static String heal(Context context) {
        String ours = new ComponentName(context, KeyService.class).flattenToString();
        if (isRunning(context)) return "служба работает";
        try {
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            List<String> others = new ArrayList<>();
            boolean listed = false;
            if (enabled != null) {
                for (String s : enabled.split(":")) {
                    if (s.isEmpty() || others.contains(s)) continue;
                    if (s.equals(ours)) listed = true; else others.add(s);
                }
            }
            // Listed but not bound: dropping it and adding it again makes the system bind it,
            // given a moment between, or the system sees no change.
            if (listed) {
                put(context, String.join(":", others));
                android.os.SystemClock.sleep(1_000);
            }
            others.add(ours);
            put(context, String.join(":", others));
            Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            Log.i(KeyService.TAG, "service re-enabled");
            return "служба включена";
        } catch (SecurityException e) {
            Log.w(KeyService.TAG, "no WRITE_SECURE_SETTINGS", e);
            return "нет WRITE_SECURE_SETTINGS — выдайте по adb";
        }
    }

    private static void put(Context context, String value) {
        Settings.Secure.putString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value);
    }

    static boolean isRunning(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        for (AccessibilityServiceInfo info :
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            if (info.getId().startsWith(context.getPackageName() + "/")) return true;
        }
        return false;
    }

    /** The launcher entry: heals by hand and says how it went. */
    public static class Heal extends Activity {
        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            // Also takes the mic button back if it was given away: once given, it can't be asked for by voice.
            boolean wasGiven = !KeyService.ownsMic(this);
            KeyService.setOwnsMic(this, true);
            String healed = heal(this);
            Toast.makeText(this, "VoiceKey: " + healed, Toast.LENGTH_LONG).show();
            KeyService.flash(wasGiven ? "VoiceKey: кнопка микрофона снова у VoiceKey" : "VoiceKey: " + healed, 4_000);
            finish();
        }
    }
}
