package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

/**
 * YuzGram #100: APK himoyasi — yangi o‘rnatishda yoqilgan holda keladi.
 * Yoqilganda chatdagi APK fayllar qizil belgilanadi va ochishdan oldin
 * tasdiq so‘raladi. Holat alohida SharedPreferences’da saqlanadi.
 */
public class YuzApkGuard {

    private static final String FILE = "yuz_apk_guard";
    private static final String KEY = "enabled";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(FILE, 0);
    }

    public static boolean isEnabled() {
        return prefs().getBoolean(KEY, true);
    }

    public static void setEnabled(boolean value) {
        prefs().edit().putBoolean(KEY, value).apply();
    }

    public static boolean isApkFile(String fileName, String mimeType) {
        if (APK_MIME.equals(mimeType)) {
            return true;
        }
        if (TextUtils.isEmpty(fileName)) {
            return false;
        }
        int idx = fileName.lastIndexOf('.');
        return idx != -1 && idx < fileName.length() - 1 && fileName.substring(idx + 1).equalsIgnoreCase("apk");
    }

    public static boolean isApkMessage(MessageObject message) {
        if (message == null || message.messageOwner == null) {
            return false;
        }
        TLRPC.Document document = message.getDocument();
        return document != null && isApkFile(message.getFileName(), document.mime_type);
    }
}
