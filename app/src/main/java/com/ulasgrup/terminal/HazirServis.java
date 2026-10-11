package com.ulasgrup.terminal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Uygulama arka plandayken (ekran kapalı) okuyucu bağlantısı kopmasın diye süreci ayakta tutan ön plan servisi.
 * Tetiğe basılınca MainActivity tam ekran bildirimle uyandırılır (Android 10+ arka plandan doğrudan açmaya izin vermez).
 */
public class HazirServis extends Service {
    static final String KANAL_HAZIR = "ulas_hazir";
    static final String KANAL_TETIK = "ulas_tetik";
    static final int NO_HAZIR = 41;
    static final int NO_TETIK = 42;

    static void kanallar(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        NotificationChannel h = new NotificationChannel(KANAL_HAZIR, "Okuyucu hazır", NotificationManager.IMPORTANCE_MIN);
        h.setShowBadge(false);
        nm.createNotificationChannel(h);
        NotificationChannel t = new NotificationChannel(KANAL_TETIK, "Tetikle açılma", NotificationManager.IMPORTANCE_HIGH);
        t.setSound(null, null);
        t.enableVibration(false);
        nm.createNotificationChannel(t);
    }

    static PendingIntent acilis(Context c, boolean tetik) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        if (tetik) i.putExtra("tetik", true);
        int bayrak = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return PendingIntent.getActivity(c, tetik ? 2 : 1, i, bayrak);
    }

    @SuppressWarnings("deprecation")
    static Notification.Builder yeni(Context c, String kanal) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, kanal) : new Notification.Builder(c);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        kanallar(this);
        Notification n = yeni(this, KANAL_HAZIR)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.hazir_bildirim))
            .setContentIntent(acilis(this, false))
            .setOngoing(true)
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NO_HAZIR, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            else startForeground(NO_HAZIR, n);
        } catch (Exception e) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    /** Tetik: ekranı açıp uygulamayı öne getiren tam ekran bildirim */
    static void uyandir(Context c) {
        kanallar(c);
        Notification n = yeni(c, KANAL_TETIK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(c.getString(R.string.app_name))
            .setContentText(c.getString(R.string.tetik_bildirim))
            .setCategory(Notification.CATEGORY_CALL)
            .setPriority(Notification.PRIORITY_MAX)
            .setFullScreenIntent(acilis(c, true), true)
            .setContentIntent(acilis(c, true))
            .setAutoCancel(true)
            .build();
        c.getSystemService(NotificationManager.class).notify(NO_TETIK, n);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
