package com.sightline.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

/**
 * A foreground service that keeps the Sightline monitoring session alive while
 * the app is in the background, and shows a persistent notification. It does NOT
 * itself read the camera — on Android the WebView camera only delivers frames
 * while the app is visible (use split-screen on a tablet). This service's job is
 * to keep the process alive so timers, state and breach notifications survive
 * backgrounding, and to give the user a clear "monitoring is on" indicator.
 */
public class SightlineForegroundService extends Service {
    public static final String CHANNEL_ID = "sightline-foreground";
    public static final int NOTIF_ID = 7788;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String title = intent != null && intent.getStringExtra("title") != null
                ? intent.getStringExtra("title") : "Sightline is guarding your eyes";
        String text = intent != null && intent.getStringExtra("text") != null
                ? intent.getStringExtra("text") : "Monitoring screen distance & blink rate";

        createChannel();

        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(getResources().getIdentifier("ic_stat_eye", "drawable", getPackageName()))
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIF_ID, n);
        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        "Sightline session", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Shown while a monitoring session is running.");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
