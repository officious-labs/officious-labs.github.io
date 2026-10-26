package io.github.officiouslabs.compagnon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Reçu par la nouvelle version juste après une mise à jour (MY_PACKAGE_REPLACED).
 * Rouvre l'appli ; si Android interdit l'ouverture depuis l'arrière-plan, une notification permet de le faire d'un toucher.
 */
public class MajRecue extends BroadcastReceiver {
    static final int NOTIF = 1;
    static final String CANAL = "maj";

    @Override
    public void onReceive(Context ctx, Intent recu) {
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(recu.getAction())) return;

        // rouvre le dernier outil utilisé
        String dernier = ctx.getSharedPreferences("compagnon", Context.MODE_PRIVATE).getString("dernier", "saison");
        Intent ouvrir = new Intent(ctx, Outils.activiteDe(dernier)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel(CANAL, "Mises à jour de l'appli", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, ouvrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CANAL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("Officine est à jour")
                    .setContentText("Toucher pour rouvrir l'appli")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF, n);
        } catch (Exception ignored) { }
        try { ctx.startActivity(ouvrir); } catch (Exception ignored) { }
    }
}
