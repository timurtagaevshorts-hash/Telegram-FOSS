package org.telegram.messenger;

import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

final class YuzCrashPostman {

    private static final String ENDPOINT = "https://yuzgram-ai-proxy.timurtagaevshorts.workers.dev/crash";
    private static volatile boolean installed;

    static void install() {
        if (installed) {
            return;
        }
        installed = true;
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                report(t, e);
            } catch (Throwable ignore) {
            }
            if (prev != null) {
                prev.uncaughtException(t, e);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(2);
            }
        });
    }

    private static void report(Thread t, Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append("FATAL ").append(t != null ? t.getName() : "?").append('\n');
        try {
            String ver = ApplicationLoader.applicationContext.getPackageManager()
                    .getPackageInfo(ApplicationLoader.applicationContext.getPackageName(), 0).versionName;
            sb.append("v=").append(ver).append(" sdk=").append(android.os.Build.VERSION.SDK_INT);
        } catch (Throwable ignore) {
        }
        sb.append(" dev=").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 6) {
            sb.append(cur.getClass().getName()).append(": ").append(cur.getMessage()).append('\n');
            StackTraceElement[] frames = cur.getStackTrace();
            for (int i = 0; i < frames.length && i < 24; i++) {
                sb.append("  at ").append(frames[i]).append('\n');
            }
            cur = cur.getCause();
            depth++;
        }
        String body = "{\"stack\":" + quote(sb.toString()) + ",\"thread\":" + quote(t != null ? t.getName() : "?") + "}";
        for (int attempt = 0; attempt < 3; attempt++) {
            String send = body.length() > 14000 ? body.substring(0, 14000) : body;
            if (post("{\"stack\":" + quote(send) + "}")) {
                return;
            }
        }
    }

    private static boolean post(String json) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            OutputStream os = conn.getOutputStream();
            os.write(json.getBytes(Charset.forName("UTF-8")));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            try {
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                if (is != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        baos.write(buf, 0, n);
                    }
                    is.close();
                }
            } catch (Throwable ignore) {
            }
            conn.disconnect();
            return code == 200;
        } catch (Throwable e) {
            try {
                FileLog.e(e);
            } catch (Throwable ignore) {
            }
            return false;
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append(' ');
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(' ');
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
