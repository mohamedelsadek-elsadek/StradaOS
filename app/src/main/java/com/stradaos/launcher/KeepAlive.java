package com.stradaos.launcher;

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
 * Elsadek Car: servicio en primer plano mientras el launcher está abierto. Android 8+ le da al GPS de una app en
 * segundo plano solo unas pocas lecturas por hora; con Maps o Anghami delante se perdían el viaje, los avisos de
 * velocidad y de radares. Con este servicio la app cuenta como «en primer plano» y recibe todas las lecturas.
 */
public class KeepAlive extends Service {
    static final String CH = "elsadek_trip";

    static void start(Context c) {
        try {
            Intent i = new Intent(c, KeepAlive.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Throwable e) { Guard.log("keepalive: " + e); }
    }

    static void stop(Context c) {
        try { c.stopService(new Intent(c, KeepAlive.class)); } catch (Throwable ignored) { }
    }

    @Override
    @SuppressWarnings("deprecation")
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
                nm.createNotificationChannel(new NotificationChannel(CH, "متابعة الرحلة", NotificationManager.IMPORTANCE_MIN));
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH) : new Notification.Builder(this);
            b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.brand))
                .setContentText("متابعة الرحلة وتنبيهات السرعة والرادار")
                .setContentIntent(open).setOngoing(true);
            if (Build.VERSION.SDK_INT >= 16) b.setPriority(Notification.PRIORITY_MIN);
            startForeground(78, b.build());
        } catch (Throwable e) { Guard.log("keepalive: notificación " + e); }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
