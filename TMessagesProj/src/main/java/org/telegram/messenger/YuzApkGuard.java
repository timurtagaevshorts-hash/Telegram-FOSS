package org.telegram.messenger;

import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

/**
 * YuzGram #100: APK himoyasi — chatdagi APK fayllar qizil belgilanadi va
 * ochishdan oldin tasdiq so'raladi.
 *
 * r63: himoya DOIMIY YONIQ — foydalanuvchi o'chira olmaydi, shu sababli holat
 * endi sozlamalarda saqlanmaydi.
 */
public class YuzApkGuard {

    private static final String APK_MIME = "application/vnd.android.package-archive";

    public static boolean isEnabled() {
        return true;
    }

    /** Eski sozlama joylaridan chaqirilishi mumkin — endi hech narsa qilmaydi. */
    public static void setEnabled(boolean value) {
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
