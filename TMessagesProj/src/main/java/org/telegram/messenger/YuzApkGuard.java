package org.telegram.messenger;

import android.content.SharedPreferences;

/**
 * YuzGram #100: APK himoyasi — yoqilganda chatdan kelgan .apk fayllarni
 * ochish (o'rnatish) to'siladi. Holat alohida SharedPreferences'da saqlanadi:
 * [AndroidUtilities.openForView] ham shu yerdan o'qiydi.
 */
public class YuzApkGuard {

    private static final String FILE = "yuz_apk_guard";
    private static final String KEY = "enabled";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(FILE, 0);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY, false);
    }

    public static void setEnabled(boolean value) {
        prefs().edit().putBoolean(KEY, value).apply();
    }
}
