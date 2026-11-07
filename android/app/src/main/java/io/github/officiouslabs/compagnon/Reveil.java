package io.github.officiouslabs.compagnon;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import java.util.Calendar;

/**
 * Réveil de la synchro du matin (07/11/2026, décision 4 du 31/10) : du lundi au vendredi à 8 h 55, heure du téléphone.
 * Alarme exacte « même en veille » (setExactAndAllowWhileIdle) : l'appli déclare USE_EXACT_ALARM (Android 13 et plus,
 * accordée à l'installation, sans réglage à toucher ; l'appli n'est pas sur le Play Store, dont seul le règlement la
 * réserve aux réveils et agendas) et SCHEDULE_EXACT_ALARM jusqu'à Android 12L (accordée d'office). Une alarme exacte est
 * aussi ce qui autorise Android 12 et plus à lancer le service de premier plan depuis l'arrière-plan. Si Android la
 * refuse malgré tout : fenêtre de 8 h 55 à 9 h 05 (setWindow), sans garantie que le service puisse démarrer.
 * Réarmée après chaque passage, au démarrage du téléphone, après une mise à jour (MajRecue) et à chaque ouverture.
 */
public class Reveil extends BroadcastReceiver {
    static final String ACTION = "io.github.officiouslabs.compagnon.REVEIL";

    @Override
    public void onReceive(Context ctx, Intent recu) {
        armer(ctx); // le prochain jour ouvré
        if (ACTION.equals(recu.getAction())) SynchroMatin.lancer(ctx);
    }

    /** Prochain jour ouvré (lundi à vendredi) à 8 h 55, strictement après maintenant. */
    static long prochain(long maintenant) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(maintenant);
        c.set(Calendar.HOUR_OF_DAY, 8);
        c.set(Calendar.MINUTE, 55);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= maintenant) c.add(Calendar.DAY_OF_MONTH, 1);
        while (c.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY || c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY)
            c.add(Calendar.DAY_OF_MONTH, 1);
        return c.getTimeInMillis();
    }

    static void armer(Context c) {
        try {
            AlarmManager am = c.getSystemService(AlarmManager.class);
            Intent i = new Intent(c, Reveil.class).setAction(ACTION);
            PendingIntent pi = PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            long t = prochain(System.currentTimeMillis());
            boolean exacte = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms();
            if (exacte) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi);
            else am.setWindow(AlarmManager.RTC_WAKEUP, t, 10 * 60 * 1000L, pi);
            c.getSharedPreferences("compagnon", Context.MODE_PRIVATE).edit()
                    .putLong("reveil", t).putBoolean("reveilExact", exacte).apply();
        } catch (Exception ignored) { }
    }
}
