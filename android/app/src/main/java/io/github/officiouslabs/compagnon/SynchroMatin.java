package io.github.officiouslabs.compagnon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/**
 * Synchro du matin (07/11/2026, décision 4 du 31/10). Lancé par Reveil vers 8 h 55, ce service de premier plan (type
 * dataSync) ouvre la page publiée de chaque outil affiché, l'un après l'autre, dans une vue invisible (WebView sans
 * activité, même processus et même profil que la vue normale : même stockage local, mêmes cookies) avec le même pont
 * (Pont.java, méthode mode = "synchro"). La page attend elle-même 9 h, synchronise, puis rend la main par synchroFinie
 * (son bilan) ; la vue est alors détruite et l'outil suivant chargé.
 * Délai de garde par outil : 3 minutes à partir du plus tard de « page chargée » et « 9 h ce jour-là ».
 * Un outil à l'écran à ce moment-là est laissé à lui-même (il fait déjà ce travail, voir LISEZMOI.md).
 * À la fin : une notification « Synchro du matin » (une ligne par outil), et le bilan gardé pour la méthode synchroMatin.
 */
public class SynchroMatin extends Service {
    static final String BILAN = "synchroMatin";
    private static final String CANAL = "synchro";
    private static final int NOTIF_EN_COURS = 2, NOTIF_BILAN = 3;
    private static final long MINUTE = 60 * 1000L, GARDE = 3 * MINUTE, CHARGEMENT = 3 * MINUTE, EN_TOUT = 30 * MINUTE;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean enCours = false, termine = false, charge = false;
    private int rang = -1;
    private WebView web;
    private Pont pont;
    private Runnable garde;
    private final JSONArray bilans = new JSONArray();
    private PowerManager.WakeLock verrou;

    /** Depuis le réveil : démarre le service de premier plan (permis juste après une alarme exacte). */
    static void lancer(Context c) {
        try {
            c.startForegroundService(new Intent(c, SynchroMatin.class));
        } catch (Exception e) {
            // Android refuse le lancement depuis l'arrière-plan (alarme non exacte) : on le dit, sans réessayer aujourd'hui
            canal(c);
            notifier(c, "Synchro du matin", "Pas de synchro ce matin : Android n’a pas permis de la lancer (" + Pont.message(e) + ").");
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        canal(this);
        Notification n = new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Synchro du matin")
                .setContentText("En cours…")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_EN_COURS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIF_EN_COURS, n);
        if (!enCours) {
            enCours = true;
            try {
                verrou = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "officine:synchro");
                verrou.acquire(EN_TOUT + MINUTE);
            } catch (Exception ignored) { }
            h.postDelayed(() -> { if (pont != null) bilans.put(delai(rang)); terminer(); }, EN_TOUT);
            suivant();
        }
        return START_NOT_STICKY;
    }

    /** Outil suivant (icône affichée) ; à la fin, le bilan. */
    private void suivant() {
        detruireVue();
        if (termine) return;
        rang++;
        while (rang < Outils.NOMS.length && !Outils.visible(this, rang)) rang++;
        if (rang >= Outils.NOMS.length) { terminer(); return; }
        final int k = rang;
        String appli = Outils.NOMS[k], titre = Outils.TITRES[k];
        if (MainActivity.A_L_ECRAN.contains(appli)) {
            bilans.put(simple(k, "ouvert", titre + " : ouvert à l’écran, synchro laissée à l’appli"));
            h.post(this::suivant);
            return;
        }
        afficherEnCours(titre + "…");
        web = new WebView(this);
        web.layout(0, 0, 1080, 2000); // la page a une taille, même sans fenêtre
        final Pont p = new Pont(this, appli, web, null, this);
        pont = p;
        p.regler();
        charge = false;
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) { return !Pont.notre(r.getUrl()); }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) { p.autorisee = Pont.notre(Uri.parse(url)); }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (p != pont || charge || !Pont.notre(Uri.parse(url))) return;
                charge = true;
                // 3 minutes à partir du plus tard de « page chargée » et « 9 h ce jour-là »
                Calendar neuf = Calendar.getInstance();
                neuf.set(Calendar.HOUR_OF_DAY, 9);
                neuf.set(Calendar.MINUTE, 0);
                neuf.set(Calendar.SECOND, 0);
                neuf.set(Calendar.MILLISECOND, 0);
                long now = System.currentTimeMillis();
                armerGarde(p, k, Math.max(now, neuf.getTimeInMillis()) + GARDE - now);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return p.servir(r); }
        });
        armerGarde(p, k, CHARGEMENT); // page jamais chargée
        web.loadUrl(p.depart());
    }

    private void armerGarde(Pont p, int k, long delai) {
        if (garde != null) h.removeCallbacks(garde);
        garde = () -> { if (p == pont) { bilans.put(delai(k)); suivant(); } };
        h.postDelayed(garde, delai);
    }

    /** Bilan rendu par la page (méthode synchroFinie du pont), sur le fil principal. */
    void fini(Pont p, String json) {
        if (p != pont || termine) return;
        JSONObject b;
        try { b = new JSONObject(json == null || json.isEmpty() ? "{}" : json); }
        catch (Exception e) { b = simple(rang, "illisible", Outils.TITRES[rang] + " : bilan illisible"); }
        try {
            if (!b.has("appli")) b.put("appli", Outils.NOMS[rang]);
            if (!b.has("titre")) b.put("titre", Outils.TITRES[rang]);
        } catch (Exception ignored) { }
        bilans.put(b);
        suivant();
    }

    private static JSONObject simple(int k, String raison, String resume) {
        JSONObject o = new JSONObject();
        try {
            o.put("appli", Outils.NOMS[k]);
            o.put("titre", Outils.TITRES[k]);
            o.put("ok", false);
            o.put("raison", raison);
            o.put("resume", resume);
        } catch (Exception ignored) { }
        return o;
    }

    private static JSONObject delai(int k) {
        return simple(k, "delai", Outils.TITRES[k] + " : pas de réponse (délai dépassé)");
    }

    private void detruireVue() {
        if (garde != null) { h.removeCallbacks(garde); garde = null; }
        if (pont != null) pont.fermer();
        pont = null;
        if (web != null) {
            WebView w = web;
            web = null;
            try { w.stopLoading(); w.destroy(); } catch (Exception ignored) { }
        }
    }

    /** Bilan gardé et notifié, puis arrêt (quelques secondes plus tard, le temps que le stockage de la page s'écrive). */
    private void terminer() {
        if (termine) return;
        termine = true;
        detruireVue();
        h.removeCallbacksAndMessages(null);
        long now = System.currentTimeMillis();
        StringBuilder texte = new StringBuilder();
        for (int i = 0; i < bilans.length(); i++) {
            JSONObject b = bilans.optJSONObject(i);
            if (b == null) continue;
            String r = b.optString("resume");
            if (r.isEmpty()) r = b.optString("titre") + " : " + (b.optBoolean("ok") ? "fait" : b.optString("raison", "échec"));
            if (texte.length() > 0) texte.append('\n');
            texte.append(r);
        }
        try {
            JSONObject tout = new JSONObject();
            tout.put("quand", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.FRANCE).format(now));
            tout.put("outils", bilans);
            getSharedPreferences("compagnon", MODE_PRIVATE).edit().putString(BILAN, tout.toString()).apply();
        } catch (Exception ignored) { }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        String heure = String.format(Locale.FRANCE, "%d h %02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        notifier(this, "Synchro du matin, " + heure, texte.length() == 0 ? "Aucun outil affiché." : texte.toString());
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) { }
        h.postDelayed(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            try { if (verrou != null && verrou.isHeld()) verrou.release(); } catch (Exception ignored) { }
            stopSelf();
        }, 5000);
    }

    @Override
    public void onDestroy() {
        detruireVue();
        try { if (verrou != null && verrou.isHeld()) verrou.release(); } catch (Exception ignored) { }
        super.onDestroy();
    }

    private void afficherEnCours(String texte) {
        try {
            Notification n = new Notification.Builder(this, CANAL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("Synchro du matin")
                    .setContentText(texte)
                    .setOngoing(true)
                    .build();
            getSystemService(NotificationManager.class).notify(NOTIF_EN_COURS, n);
        } catch (Exception ignored) { }
    }

    private static void canal(Context c) {
        try {
            NotificationChannel ch = new NotificationChannel(CANAL, "Synchro du matin", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Bilan de la synchro des jours ouvrés, vers 9 h");
            c.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        } catch (Exception ignored) { }
    }

    /** Notification du bilan : une ligne par outil ; un toucher ouvre Tâches (ou le premier outil affiché). */
    private static void notifier(Context c, String titre, String texte) {
        try {
            int k = -1;
            for (int i = 0; i < Outils.NOMS.length; i++) if ("taches".equals(Outils.NOMS[i]) && Outils.visible(c, i)) k = i;
            for (int i = 0; k < 0 && i < Outils.NOMS.length; i++) if (Outils.visible(c, i)) k = i;
            Intent ouvrir = new Intent(c, Outils.ACTIVITES[Math.max(k, 0)]).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(c, 11, ouvrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            String premiere = texte.contains("\n") ? texte.substring(0, texte.indexOf('\n')) : texte;
            Notification n = new Notification.Builder(c, CANAL)
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(titre)
                    .setContentText(premiere)
                    .setStyle(new Notification.BigTextStyle().bigText(texte))
                    .setShowWhen(true)
                    .setWhen(System.currentTimeMillis())
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            c.getSystemService(NotificationManager.class).notify(NOTIF_BILAN, n);
        } catch (Exception ignored) { }
    }
}
