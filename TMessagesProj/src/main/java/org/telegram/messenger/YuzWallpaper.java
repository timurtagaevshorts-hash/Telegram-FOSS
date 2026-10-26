package org.telegram.messenger;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * YuzGram #193: ilova fon rasmi — foydalanuvchi tanlagan rasm (shaffofligi
 * sozlanadi) butun ilova orqasiga chiziladi.
 *
 * Rasm ilova xotirasiga nusxalanadi (SAF ruxsati talab qilinmaydi), shuning
 * uchun Telegram keshini tozalash ham unga ta'sir qilmaydi. Holat alohida
 * SharedPreferences faylida; o'zgarishlar listener orqali darhol qo'llanadi.
 */
public class YuzWallpaper {

    private static final String PREFS = "yuz_wallpaper";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_OPACITY = "opacity";
    private static final String FILE_NAME = "yuz_theme_wallpaper.jpg";

    public static final int DEFAULT_OPACITY = 40;
    public static final int MIN_OPACITY = 5;
    private static final int MAX_SIDE = 1600;

    private static Bitmap cachedBitmap;
    private static long cachedStamp;
    // scroll paytida har kadrda fayl stat qilmaslik uchun holat keshi.
    private static volatile Boolean cachedActive;
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, 0);
    }

    public static File imageFile() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), FILE_NAME);
    }

    /** Fon rasmi yoqilganmi va fayl haqiqatan mavjudmi. */
    public static boolean isActive() {
        Boolean cached = cachedActive;
        if (cached != null) {
            return cached;
        }
        File file = imageFile();
        boolean value = prefs().getBoolean(KEY_ENABLED, true) && file.exists() && file.length() > 0;
        cachedActive = value;
        return value;
    }

    public static int getOpacity() {
        int value = prefs().getInt(KEY_OPACITY, DEFAULT_OPACITY);
        return Math.max(MIN_OPACITY, Math.min(100, value));
    }

    public static void setOpacity(int value) {
        int clamped = Math.max(MIN_OPACITY, Math.min(100, value));
        prefs().edit().putInt(KEY_OPACITY, clamped).apply();
        notifyChanged();
    }

    public static void setEnabled(boolean value) {
        prefs().edit().putBoolean(KEY_ENABLED, value).apply();
        cachedActive = null;
        notifyChanged();
    }

    /**
     * Tanlangan rasmni ilova xotirasiga nusxalaydi (katta rasm kichraytiriladi).
     * Og'ir ish — uni fonda chaqirish tavsiya etiladi.
     */
    public static boolean importImage(ContentResolver resolver, Uri uri) {
        if (resolver == null || uri == null) {
            return false;
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
                return false;
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
                return false;
            }
            bitmap = applyExifRotation(resolver, uri, bitmap);

            File target = imageFile();
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
                return false;
            }
            if (!tmp.renameTo(target)) {
                synchronized (YuzWallpaper.class) {
                    cachedBitmap = null;
                    cachedStamp = 0;
                }
                cachedActive = null;
                return false;
            }
            synchronized (YuzWallpaper.class) {
                // Dekodlangan rasm darhol keshga — fon UI oqimida qayta dekodlanmaydi.
                cachedBitmap = bitmap;
                cachedStamp = target.lastModified() ^ (target.length() << 8);
            }
            prefs().edit().putBoolean(KEY_ENABLED, true).apply();
            cachedActive = true;
            notifyChanged();
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        } finally {
            closeQuietly(in);
            if (tmp != null && tmp.exists()) {
                tmp.delete();
            }
        }
    }

    public static void clear() {
        File file = imageFile();
        if (file.exists()) {
            file.delete();
        }
        synchronized (YuzWallpaper.class) {
            cachedBitmap = null;
            cachedStamp = 0;
        }
        prefs().edit().putBoolean(KEY_ENABLED, false).apply();
        cachedActive = false;
        notifyChanged();
    }

    /** Dekodlangan rasm (kesh bilan). Fon uchun bir marta yuklanadi. */
    public static synchronized Bitmap getBitmap() {
        File file = imageFile();
        if (!file.exists() || file.length() == 0) {
            return null;
        }
        long stamp = file.lastModified() ^ (file.length() << 8);
        if (cachedBitmap != null && !cachedBitmap.isRecycled() && stamp == cachedStamp) {
            return cachedBitmap;
        }
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            cachedBitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            cachedStamp = stamp;
        } catch (Throwable e) {
            FileLog.e(e);
            cachedBitmap = null;
        }
        return cachedBitmap;
    }

    public static void addListener(Runnable listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public static void removeListener(Runnable listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    private static void notifyChanged() {
        for (Runnable listener : listeners) {
            AndroidUtilities.runOnUIThread(listener);
        }
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
