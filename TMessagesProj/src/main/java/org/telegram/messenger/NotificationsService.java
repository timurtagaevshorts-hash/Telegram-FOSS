/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class NotificationsService extends Service {

    private static final String CHANNEL_ID = "yuz_background_service";
    private static final String LEGACY_CHANNEL_ID = "push_service_channel";

    private boolean yuzInitialized;

    @Override
    public void onCreate() {
        super.onCreate();
        // startForegroundService() foreground holatni 5 soniya ichida talab
        // qiladi, shuning uchun og'ir postInitApplication()dan OLDIN
        // startForeground() chaqiriladi.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            // YuzGram: eski inglizcha "Push service" kanali foydalanuvchi
            // bildirishnomalarida qolib ketmasin.
            notificationManager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.yuz_service_channel_name),
                    NotificationManager.IMPORTANCE_MIN);
            channel.setShowBadge(false);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            notificationManager.createNotificationChannel(channel);
            // Fon ulanishini ushlab turish uchun foreground servis shart,
            // lekin tizim talab qiladigan bildirishnoma jim, matnsiz va
            // bosilmaydigan bo'lsin — foydalanuvchini chalg'itmasin.
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setShowWhen(false)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setSmallIcon(R.drawable.notification)
                    .build();
            try {
                startForeground(9999, notification);
            } catch (Throwable t) {
                // ForegroundServiceStartNotAllowedException (API 31+) — fon
                // holatdan boshlangan servis ruxsat bermaydi; yiqilmasdan
                // o'zini to'xtatadi va push xizmatini alarm qayta tiklaydi.
                FileLog.e(t);
                stopSelf();
                return;
            }
        }
        ApplicationLoader.postInitApplication();
        yuzInitialized = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onDestroy() {
        super.onDestroy();
        // Initializatsiya boshlanmasdan to'xtatilgan bo'lsa, global
        // sozlamalarni o'qishga urinmaymiz (u hali yaratilmagan bo'ladi).
        if (!yuzInitialized) {
            return;
        }
        SharedPreferences preferences = MessagesController.getGlobalNotificationsSettings();
        if (preferences.getBoolean("pushService", true)) {
            Intent intent = new Intent("org.telegram.start");
            sendBroadcast(intent);
        }
    }
}
