package dev.xdark.voicekey;

import android.content.res.Configuration;
import android.os.LocaleList;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Sets the system language the way a locale-changer app does: asks the activity manager to update
 * and persist the configuration. Runs as the adb shell user, which holds CHANGE_CONFIGURATION:
 * `CLASSPATH=<apk> app_process /system/bin dev.xdark.voicekey.LocaleTool ru`. The fallback for
 * when `setprop persist.sys.locale` does not take.
 */
public final class LocaleTool {
    private LocaleTool() {}

    public static void main(String[] args) throws Exception {
        Locale locale = Locale.forLanguageTag(args[0]);
        Class<?> managerClass = Class.forName("android.app.ActivityManager");
        Object manager = managerClass.getMethod("getService").invoke(null);
        Class<?> iface = Class.forName("android.app.IActivityManager");
        Configuration config = (Configuration) iface.getMethod("getConfiguration").invoke(manager);
        config.setLocales(new LocaleList(locale));
        // "userSetLocale" keeps the system from replacing the choice with the region's default.
        config.getClass().getField("userSetLocale").setBoolean(config, true);
        Method update = iface.getMethod("updatePersistentConfiguration", Configuration.class);
        update.invoke(manager, config);
        System.out.println("locale -> " + locale.toLanguageTag());
    }
}
