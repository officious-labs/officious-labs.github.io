package io.github.officiouslabs.compagnon;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pont « Compagnon » commun à la vue normale (MainActivity) et à la vue invisible de la synchro du matin (SynchroMatin),
 * 07/11/2026. Il porte tout ce qui se fait sans écran : fichier de l'appli OneDrive, accès direct (OneDriveWeb), pièces
 * jointes en attente, versions, et le service de la page elle-même (en ligne, sinon copie, sinon embarquée).
 * Ce qui demande une activité (sélecteurs, connexion, presse-papiers, impression, vibrations, mise à jour…) est passé à
 * l'écran (interface Ecran) ; dans la vue invisible, ces méthodes répondent « ecran:… ».
 *
 * Contrat : request(id, méthode, argument) ; la réponse revient par window.compagnonReply(id, ok, valeur).
 * Méthodes sans écran : name, local, read, write, spRead, spWrite, spCreate, spList, spRecycle, spUpload, dropKept,
 * outils, afficherOutil, version, versionNom, checkUpdate, mode, synchroFinie, synchroMatin.
 * Méthodes de l'écran : pick, create, spLogin, paste, copy, haptic, print, saveAs, saveBytes, saveAttachment, saveAttachmentAs,
 * sendToOneDrive, keepFile, openAttachment, discardFile, update.
 * Les erreurs portent un code en tête (connexion:, introuvable:, conflit:, http:, reseau:, perdu:, ecran:).
 */
final class Pont {
    /** Ce que seule une activité sait faire. Renvoie faux pour une méthode inconnue. */
    interface Ecran { boolean demande(int id, String methode, String arg); }

    static final String HOTE = "officious-labs.github.io";
    static final String DEPOT = "https://github.com/officious-labs/officious-labs.github.io/releases/download/android/";
    private static final Set<String> A_ECRAN = new HashSet<>(Arrays.asList("pick", "create", "spLogin", "paste", "copy",
            "haptic", "print", "saveAs", "saveBytes", "saveAttachment", "saveAttachmentAs", "sendToOneDrive", "keepFile",
            "openAttachment", "discardFile", "update"));

    final Context ctx;
    final String appli;
    final WebView web;
    final SharedPreferences prefs;
    final ExecutorService io = Executors.newSingleThreadExecutor();
    final ExecutorService reseau = Executors.newSingleThreadExecutor();
    private final Ecran ecran;          // null dans la vue invisible
    private final SynchroMatin synchro; // null dans la vue normale
    private final Handler principal = new Handler(Looper.getMainLooper());
    /** La page affichée est-elle des nôtres (officious-labs.github.io) ? */
    volatile boolean autorisee = false;
    /** Vue détruite (vue invisible terminée) : plus aucune réponse. */
    volatile boolean detruit = false;

    Pont(Context ctx, String appli, WebView web, Ecran ecran, SynchroMatin synchro) {
        this.ctx = ctx;
        this.appli = appli;
        this.web = web;
        this.ecran = ecran;
        this.synchro = synchro;
        this.prefs = ctx.getSharedPreferences("compagnon", Context.MODE_PRIVATE);
    }

    /** Réglages communs aux deux vues : même profil, donc même stockage local (localStorage, IndexedDB) et mêmes cookies. */
    void regler() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(this, "Compagnon");
    }

    String depart() { return "https://" + HOTE + "/" + appli + "/"; }

    // ---------- la page elle-même ----------

    /** Pages de la suite : elles restent dans l'appli et ont accès au pont ; tout le reste part vers une autre appli. */
    static boolean notre(Uri u) {
        return u != null && "https".equals(u.getScheme()) && HOTE.equals(u.getHost());
    }

    /** La page de l'outil : version en ligne si possible (gardée en copie), sinon dernière copie, sinon celle embarquée. */
    WebResourceResponse servir(WebResourceRequest r) {
        Uri u = r.getUrl();
        if (!r.isForMainFrame() || !notre(u) || !"GET".equals(r.getMethod())) return null;
        String p = u.getPath(), chemin = "/" + appli + "/";
        if (!chemin.equals(p) && !(chemin + "index.html").equals(p)) return null;
        byte[] page = pageEnLigne();
        if (page == null) page = copie();
        if (page == null) return null;
        WebResourceResponse rep = new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(page));
        rep.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-store"));
        return rep;
    }

    private boolean pageValide(byte[] b) {
        if (b == null || b.length < 1000) return false;
        String t = new String(b, StandardCharsets.UTF_8);
        return t.contains("compagnonReply") && t.contains("</html>")
                && t.contains("<meta name=\"officious-appli\" content=\"" + appli + "\">");
    }

    private byte[] pageEnLigne() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(depart() + "index.html").openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            byte[] b;
            try (InputStream in = c.getInputStream()) { b = tout(in); }
            if (!pageValide(b)) return null;
            try (OutputStream o = new FileOutputStream(new File(ctx.getFilesDir(), appli + ".html"))) { o.write(b); }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] copie() {
        try (InputStream in = new java.io.FileInputStream(new File(ctx.getFilesDir(), appli + ".html"))) {
            byte[] b = tout(in);
            if (pageValide(b)) return b;
        } catch (Exception ignored) { }
        try (InputStream in = ctx.getAssets().open(appli + ".html")) {
            return tout(in);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- pont JavaScript ----------

    @JavascriptInterface
    public void request(final int id, final String methode, final String arg) {
        if (!autorisee) { repondre(id, false, "page non autorisée"); return; }
        switch (methode) {
            case "name":
                io.execute(() -> repondre(id, true, fichier() == null ? "" : prefs.getString("nom:" + appli, "")));
                break;
            case "local":
                // "1" si le fichier choisi est sur le téléphone (pas dans OneDrive) : il se lit et s'écrit à toute heure
                io.execute(() -> { Uri u = fichier(); repondre(id, true, u != null && !surOneDrive(u) ? "1" : ""); });
                break;
            case "read":
                io.execute(() -> {
                    try {
                        Uri u = exige();
                        // « frais » : demander d'abord à OneDrive sa dernière version (au démarrage, en actualisant)
                        if ("frais".equals(arg)) { try { ctx.getContentResolver().refresh(u, null, null); } catch (Exception ignored) { } }
                        repondre(id, true, lire(u));
                    }
                    catch (Exception e) { repondre(id, false, message(e)); }
                });
                break;
            case "write":
                io.execute(() -> {
                    try { repondre(id, true, ecrire(exige(), arg) ? "" : "non vérifié"); }
                    catch (Exception e) { repondre(id, false, message(e)); }
                });
                break;
            case "spRead":
                enLigne(id, () -> OneDriveWeb.lire(ctx, arg).toString());
                break;
            case "spWrite":
                enLigne(id, () -> {
                    JSONObject o = new JSONObject(arg);
                    OneDriveWeb.ecrire(ctx, o.getString("url"), o.getString("text"), o.optString("etag"));
                    return OneDriveWeb.lire(ctx, o.getString("url")).optString("etag");
                });
                break;
            case "spCreate":
                // page de l’outil déposée pour le PC, à côté de son fichier, la première fois
                enLigne(id, () -> {
                    JSONObject o = new JSONObject(arg);
                    OneDriveWeb.creer(ctx, o.getString("url"), o.getString("text"));
                    return "";
                });
                break;
            case "spList":
                enLigne(id, () -> OneDriveWeb.lister(ctx, arg));
                break;
            case "spRecycle":
                enLigne(id, () -> { OneDriveWeb.corbeille(ctx, arg); return ""; });
                break;
            case "spUpload":
                reseau.execute(() -> {
                    try {
                        JSONObject o = new JSONObject(arg);
                        File f = new File(attente(), propre(o.getString("key")));
                        if (!f.exists()) { repondre(id, false, "perdu:fichier introuvable sur le téléphone"); return; }
                        String n = OneDriveWeb.deposer(ctx, o.getString("url"), f);
                        f.delete();
                        repondre(id, true, n);
                    }
                    catch (OneDriveWeb.Exc e) { repondre(id, false, e.code + ":" + e.getMessage()); }
                    catch (Exception e) { repondre(id, false, "reseau:" + message(e)); }
                });
                break;
            case "dropKept":
                io.execute(() -> { new File(attente(), propre(arg)).delete(); repondre(id, true, ""); });
                break;
            case "outils":
                io.execute(() -> {
                    try { repondre(id, true, Outils.liste(ctx, appli)); }
                    catch (Exception e) { repondre(id, false, message(e)); }
                });
                break;
            case "afficherOutil":
                io.execute(() -> {
                    try {
                        JSONObject o = new JSONObject(arg);
                        Outils.afficher(ctx, appli, o.getString("appli"), o.getBoolean("visible"));
                        repondre(id, true, "");
                    } catch (Exception e) { repondre(id, false, message(e)); }
                });
                break;
            case "version":
                repondre(id, true, String.valueOf(versionInstallee(ctx)));
                break;
            case "versionNom":   // numéro lisible, ex. 3.0.50 (la fabrication seule reste dans « version »)
                repondre(id, true, nomVersionInstallee(ctx));
                break;
            case "checkUpdate":
                reseau.execute(() -> {
                    try { repondre(id, true, new String(telecharger(DEPOT + "version.txt", 100), StandardCharsets.UTF_8).trim()); }
                    catch (Exception e) { repondre(id, false, message(e)); }
                });
                break;
            case "mode":   // synchro du matin (07/11/2026) : "synchro" dans la vue invisible, "" dans la vue normale
                repondre(id, true, synchro != null ? "synchro" : "");
                break;
            case "synchroFinie":
                // bilan de la page : la vue invisible le garde, se détruit et passe à l'outil suivant
                repondre(id, true, "");
                if (synchro != null) principal.post(() -> synchro.fini(this, arg));
                break;
            case "synchroMatin":   // dernier passage : {quand, outils:[bilans]}, ou ""
                repondre(id, true, prefs.getString(SynchroMatin.BILAN, ""));
                break;
            default:
                if (ecran != null && ecran.demande(id, methode, arg)) return;
                repondre(id, false, ecran == null && A_ECRAN.contains(methode)
                        ? "ecran:pas d’écran pendant la synchro du matin (" + methode + ")"
                        : "méthode inconnue : " + methode);
        }
    }

    private interface Appel { String faire() throws Exception; }

    /** Accès direct à OneDrive, sur le fil du réseau, avec le code d'erreur en tête. */
    private void enLigne(int id, Appel a) {
        reseau.execute(() -> {
            try { repondre(id, true, a.faire()); }
            catch (OneDriveWeb.Exc e) { repondre(id, false, e.code + ":" + e.getMessage()); }
            catch (Exception e) { repondre(id, false, "reseau:" + message(e)); }
        });
    }

    void repondre(int id, boolean ok, String valeur) {
        final String js = "window.compagnonReply&&compagnonReply(" + id + "," + ok + "," + JSONObject.quote(valeur == null ? "" : valeur) + ")";
        Runnable r = () -> { if (!detruit) web.evaluateJavascript(js, null); };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run(); else principal.post(r);
    }

    static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ---------- fichier choisi par l'appli OneDrive ----------

    /** Le fichier mémorisé, s'il a encore son autorisation de lecture et d'écriture. */
    Uri fichier() {
        String s = prefs.getString("uri:" + appli, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : ctx.getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return u;
        return null;
    }

    private Uri exige() throws Exception {
        Uri u = fichier();
        if (u == null) throw new Exception("autorisation perdue : choisis à nouveau le fichier");
        return u;
    }

    private String lire(Uri u) throws Exception {
        try (InputStream in = ctx.getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("fichier illisible");
            String t = new String(tout(in), StandardCharsets.UTF_8);
            return t.startsWith("﻿") ? t.substring(1) : t;
        }
    }

    /** Remplace le contenu (mode « wt »), puis relit pour vérifier ; OneDrive peut renvoyer un moment l'ancienne version. */
    private boolean ecrire(Uri u, String texte) throws Exception {
        try (OutputStream o = ctx.getContentResolver().openOutputStream(u, "wt")) {
            if (o == null) throw new Exception("écriture impossible");
            o.write(texte.getBytes(StandardCharsets.UTF_8));
            o.flush();
        }
        for (int essai = 0; essai < 6; essai++) {
            if (lire(u).equals(texte)) return true;
            Thread.sleep(500);
        }
        return false;
    }

    /** Fichier fourni par l'appli OneDrive (son fournisseur de documents), et non par le téléphone. */
    static boolean surOneDrive(Uri u) {
        String a = u.getAuthority() == null ? "" : u.getAuthority().toLowerCase();
        return a.contains("skydrive") || a.contains("onedrive") || a.contains("microsoft");
    }

    /** Fichiers partagés en attente d'envoi dans OneDrive (gardés jusqu'à l'envoi, même hors des heures de travail). */
    File attente() {
        File d = new File(ctx.getFilesDir(), "pj-attente-" + appli);
        d.mkdirs();
        return d;
    }

    static String propre(String nom) {
        String n = nom == null ? "" : nom.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return n.isEmpty() ? "fichier" : n;
    }

    static byte[] tout(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // ---------- versions de l'appli ----------

    static String nomVersionInstallee(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return String.valueOf(versionInstallee(c));
        }
    }

    @SuppressWarnings("deprecation")
    static long versionInstallee(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static byte[] telecharger(String adresse, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(adresse).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(20000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        if (c.getResponseCode() != 200) throw new Exception("réponse " + c.getResponseCode() + " de GitHub");
        try (InputStream in = c.getInputStream()) {
            byte[] b = tout(in);
            if (b.length > max) throw new Exception("fichier trop gros");
            return b;
        }
    }

    /** Fin de la vue : plus de réponses, fils arrêtés une fois leur travail en cours fini. */
    void fermer() {
        detruit = true;
        io.shutdown();
        reseau.shutdown();
    }
}
