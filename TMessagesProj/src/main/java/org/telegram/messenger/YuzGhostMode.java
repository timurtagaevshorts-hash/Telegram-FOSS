package org.telegram.messenger;

import android.content.SharedPreferences;

/**
 * YuzGram r63: "sharpa rejim" — yoqilganda foydalanuvchi hech kimga onlayn
 * ko'rinmaydi: account.updateStatus(online) yuborilmaydi, "yozmoqda…" va
 * yuklash harakatlari to'siladi. Xabar yuborishning o'zi ishlayveradi.
 */
public class YuzGhostMode {

    private static final String FILE = "yuz_ghost_mode";
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
