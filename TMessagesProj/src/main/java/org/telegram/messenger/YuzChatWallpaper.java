package org.telegram.messenger;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.util.LruCache;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * YuzGram: har bir chat uchun alohida fon rasmi. Rasm ilova xotirasiga
 * nusxalanadi (dialog_id bo'yicha), shaffoflik shu chat uchun alohida
 * saqlanadi. Butun ilova fon rasmi (#193) olib tashlandi — endi faqat chat
 * ichida ishlaydi.
 */
public class YuzChatWallpaper {

    private static final String PREFS = "yuz_chat_wallpaper";
    private static final String DIR_NAME = "yuz_chat_wallpaper";
    private static final String KEY_OPACITY = "opacity_";
    private static final int MAX_SIDE = 1600;

    public static final int DEFAULT_OPACITY = 100;
    public static final int MIN_OPACITY = 10;

    private static final LruCache<String, Bitmap> cache;

    static {
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 12);
        cache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    private static File dir() {
        File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    private static String name(long dialogId) {
        return String.valueOf(dialogId).replace('-', 'm');
    }

    public static File imageFile(long dialogId) {
        return new File(dir(), name(dialogId) + ".jpg");
    }

    /** Bu chat uchun rasm tanlanganmi. */
    public static boolean isActive(long dialogId) {
        File file = imageFile(dialogId);
        return file.exists() && file.length() > 0;
    }

    public static int getOpacity(long dialogId) {
        int value = prefs().getInt(KEY_OPACITY + dialogId, DEFAULT_OPACITY);
        return Math.max(MIN_OPACITY, Math.min(100, value));
    }

    public static void setOpacity(long dialogId, int value) {
        int clamped = Math.max(MIN_OPACITY, Math.min(100, value));
        prefs().edit().putInt(KEY_OPACITY + dialogId, clamped).apply();
    }

    /**
     * Rasmni chat xotirasiga nusxalaydi va dekodlangan bitmap'ni qaytaradi
     * (preview uchun). Og'ir ish — fonda chaqiriladi.
     */
    public static Bitmap importImage(ContentResolver resolver, Uri uri, long dialogId) {
        if (resolver == null || uri == null) {
            return null;
        }
        InputStream in = null;
        File tmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            in = resolver.openInputStream(uri);
            BitmapFactory.decodeStream(in, null, bounds);
            closeQuietly(in);
            in = null;
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= MAX_SIDE
                    || bounds.outHeight / (sample * 2) >= MAX_SIDE) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            in = resolver.openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, opts);
            closeQuietly(in);
            in = null;
            if (bitmap == null) {
                return null;
            }
            bitmap = applyExifRotation(resolver, uri, bitmap);

            File target = imageFile(dialogId);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            tmp = new File(parent, target.getName() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            out.flush();
            out.close();

            if (target.exists() && !target.delete()) {
                return null;
            }
            if (!tmp.renameTo(target)) {
                return null;
            }
            cache.put(cacheKey(dialogId), bitmap);
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            closeQuietly(in);
            if (tmp != null && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    public static void clear(long dialogId) {
        File file = imageFile(dialogId);
        if (file.exists()) {
            file.delete();
        }
        cache.remove(cacheKey(dialogId));
        prefs().edit().remove(KEY_OPACITY + dialogId).apply();
    }

    /** Dekodlangan rasm (kichik LRU kesh bilan). */
    public static Bitmap getBitmap(long dialogId) {
        String key = cacheKey(dialogId);
        Bitmap cached = cache.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        File file = imageFile(dialogId);
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap != null) {
                cache.put(key, bitmap);
            }
            return bitmap;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static String cacheKey(long dialogId) {
        File file = imageFile(dialogId);
        return dialogId + ":" + file.lastModified() + ":" + file.length();
    }

    /** Kamera rasmlaridagi EXIF burilishini qo'llaydi (aks holda yonbosh ko'rinadi). */
    private static Bitmap applyExifRotation(ContentResolver resolver, Uri uri, Bitmap bitmap) {
        InputStream in = null;
        try {
            in = resolver.openInputStream(uri);
            if (in == null) {
                return bitmap;
            }
            int orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            Matrix matrix = new Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    matrix.postRotate(90);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    matrix.postRotate(180);
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    matrix.postRotate(270);
                    break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                    matrix.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                    matrix.postScale(1, -1);
                    break;
                case ExifInterface.ORIENTATION_TRANSPOSE:
                    matrix.postRotate(90);
                    matrix.postScale(-1, 1);
                    break;
                case ExifInterface.ORIENTATION_TRANSVERSE:
                    matrix.postRotate(270);
                    matrix.postScale(-1, 1);
                    break;
                default:
                    return bitmap;
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
                return rotated;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        } finally {
            closeQuietly(in);
        }
        return bitmap;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (Throwable ignore) {
            }
        }
    }
}
