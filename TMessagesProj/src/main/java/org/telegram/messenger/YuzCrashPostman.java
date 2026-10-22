package org.telegram.messenger;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

final class YuzCrashPostman {

    private static volatile boolean installed;

    static void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        File reportFile = new File(context.getFilesDir(), "yuz-startup-crash.txt");
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                String report = "time=" + System.currentTimeMillis()
                        + " sdk=" + Build.VERSION.SDK_INT
                        + " device=" + Build.MANUFACTURER + " " + Build.MODEL
                        + "\nthread=" + thread.getName() + "\n"
                        + Log.getStackTraceString(error);
                try (FileOutputStream output = new FileOutputStream(reportFile)) {
                    output.write(report.getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            } catch (Throwable saveError) {
                Log.e("YuzCrashPostman", "Could not save crash report", saveError);
            } finally {
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                    System.exit(2);
                }
            }
        });
    }

    static String read(Context context) {
        File reportFile = new File(context.getFilesDir(), "yuz-startup-crash.txt");
        if (!reportFile.isFile()) {
            return "Java crash logi hali yo'q.";
        }
        try (FileInputStream input = new FileInputStream(reportFile)) {
            byte[] bytes = new byte[32768];
            int length = 0;
            int count;
            while (length < bytes.length
                    && (count = input.read(bytes, length, bytes.length - length)) != -1) {
                length += count;
            }
            return new String(bytes, 0, length, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "Crash logini o'qib bo'lmadi: " + error;
        }
    }
}
