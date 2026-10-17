package io.github.officiouslabs.compagnon;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageInstaller;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

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
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compagnon Officious : une seule appli pour toute la suite, une icône (une activité) par outil.
 * Chaque activité affiche la page publiée de son outil (https://officious-labs.github.io/<outil>/) et lui prête l'accès
 * à son fichier par le pont « Compagnon ». Dérivé de la coquille de Tâches (15/10/2026), via celle de Saison.
 *
 * Contrat du pont : request(id, méthode, argument) ; la réponse revient par window.compagnonReply(id, ok, valeur).
 * Méthodes : name, local, pick, create, read, write, spLogin, spRead, spWrite, spCreate, spList, spRecycle, copy, paste, haptic,
 * outils, afficherOutil, version, checkUpdate, update. Les erreurs portent un code en tête (connexion:, introuvable:, conflit:, http:, reseau:).
 */
public abstract class MainActivity extends Activity {
    /** Nom de l'outil de cette activité : chemin de sa page, préfixe de ses réglages. */
    protected abstract String appli();

    static final String HOTE = "officious-labs.github.io";
    private static final String DEPOT = "https://github.com/officious-labs/officious-labs.github.io/releases/download/android/";
    private static final String FIN_INSTALLATION = "io.github.officiouslabs.compagnon.INSTALLATION";
    private String CHEMIN, DEPART, PAGE;
    private static final int CHOISIR = 1, CREER = 2;
    private static final int CONNEXION = 6;

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService reseau = Executors.newSingleThreadExecutor();
    private volatile boolean pageAutorisee = false;
    private int choixEnCours = -1, connexionEnCours = -1, majEnCours = -1, majApresPermission = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("compagnon", MODE_PRIVATE);
        CHEMIN = "/" + appli() + "/";
        DEPART = "https://" + HOTE + CHEMIN;
        PAGE = DEPART + "index.html";
        prefs.edit().putString("dernier", appli()).apply(); // rouvert après une mise à jour
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setSupportMultipleWindows(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Pont(), "Compagnon");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new Client());
        setContentView(web);
        try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(MajRecue.NOTIF); } catch (Exception ignored) { }
        finInstallation(getIntent());
        web.loadUrl(DEPART);
    }

    // ---------- navigation : seule notre page reste dans l'appli ----------

    /** Pages de la suite : elles restent dans l'appli et ont accès au pont ; tout le reste part vers une autre appli. */
    private static boolean notre(Uri u) {
        return u != null && "https".equals(u.getScheme()) && HOTE.equals(u.getHost());
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (notre(u)) return false;
            ouvrirAilleurs(u);
            return true;
        }

        @Override
        public void onPageStarted(WebView v, String url, android.graphics.Bitmap f) {
            pageAutorisee = notre(Uri.parse(url));
        }

        /** La page elle-même : version en ligne si possible (gardée en copie), sinon dernière copie, sinon celle embarquée. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            Uri u = r.getUrl();
            if (!r.isForMainFrame() || !notre(u) || !"GET".equals(r.getMethod())) return null;
            String p = u.getPath();
            if (!CHEMIN.equals(p) && !(CHEMIN + "index.html").equals(p)) return null;
            byte[] page = pageEnLigne();
            if (page == null) page = copie();
            if (page == null) return null;
            WebResourceResponse rep = new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(page));
            rep.setResponseHeaders(Collections.singletonMap("Cache-Control", "no-store"));
            return rep;
        }
    }

    private void ouvrirAilleurs(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Aucune appli pour ouvrir ce lien", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean pageValide(byte[] b) {
        if (b == null || b.length < 1000) return false;
        String t = new String(b, StandardCharsets.UTF_8);
        return t.contains("compagnonReply") && t.contains("</html>")
                && t.contains("<meta name=\"officious-appli\" content=\"" + appli() + "\">");
    }

    private byte[] pageEnLigne() {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(PAGE).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(8000);
            c.setUseCaches(false);
            c.setRequestProperty("Cache-Control", "no-cache");
            if (c.getResponseCode() != 200) return null;
            byte[] b;
            try (InputStream in = c.getInputStream()) { b = tout(in); }
            if (!pageValide(b)) return null;
            try (OutputStream o = new FileOutputStream(new File(getFilesDir(), appli() + ".html"))) { o.write(b); }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] copie() {
        try (InputStream in = new java.io.FileInputStream(new File(getFilesDir(), appli() + ".html"))) {
            byte[] b = tout(in);
            if (pageValide(b)) return b;
        } catch (Exception ignored) { }
        try (InputStream in = getAssets().open(appli() + ".html")) {
            return tout(in);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] tout(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // ---------- pont JavaScript ----------

    private class Pont {
        @JavascriptInterface
        public void request(final int id, final String methode, final String arg) {
            if (!pageAutorisee) { repondre(id, false, "page non autorisée"); return; }
            switch (methode) {
                case "name":
                    io.execute(() -> repondre(id, true, fichier() == null ? "" : prefs.getString("nom:" + appli(), "")));
                    break;
                case "local":
                    // "1" si le fichier choisi est sur le téléphone (pas dans OneDrive) : il se lit et s'écrit à toute heure
                    io.execute(() -> { Uri u = fichier(); repondre(id, true, u != null && !surOneDrive(u) ? "1" : ""); });
                    break;
                case "pick":
                    runOnUiThread(() -> choisir(id));
                    break;
                case "create":
                    runOnUiThread(() -> creer(id, arg));
                    break;
                case "read":
                    io.execute(() -> {
                        try {
                            Uri u = exige();
                            // « frais » : demander d'abord à OneDrive sa dernière version (au démarrage, en actualisant)
                            if ("frais".equals(arg)) { try { getContentResolver().refresh(u, null, null); } catch (Exception ignored) { } }
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
                case "spLogin":
                    runOnUiThread(() -> {
                        if (connexionEnCours != -1) repondre(connexionEnCours, false, "annulé");
                        connexionEnCours = id;
                        Intent i = new Intent(MainActivity.this, ConnexionActivity.class);
                        i.putExtra(ConnexionActivity.URL, arg);
                        startActivityForResult(i, CONNEXION);
                    });
                    break;
                case "spRead":
                    reseau.execute(() -> {
                        try { repondre(id, true, OneDriveWeb.lire(MainActivity.this, arg).toString()); }
                        catch (OneDriveWeb.Exc e) { repondre(id, false, e.code + ":" + e.getMessage()); }
                        catch (Exception e) { repondre(id, false, "reseau:" + message(e)); }
                    });
                    break;
                case "spWrite":
                    reseau.execute(() -> {
                        try {
                            JSONObject o = new JSONObject(arg);
                            OneDriveWeb.ecrire(MainActivity.this, o.getString("url"), o.getString("text"), o.optString("etag"));
                            repondre(id, true, OneDriveWeb.lire(MainActivity.this, o.getString("url")).optString("etag"));
                        }
                        catch (OneDriveWeb.Exc e) { repondre(id, false, e.code + ":" + e.getMessage()); }
                        catch (Exception e) { repondre(id, false, "reseau:" + message(e)); }
                    });
                    break;
                case "spCreate":
                    // page de l’outil déposée pour le PC, à côté de son fichier, la première fois
                    reseau.execute(() -> {
                        try {
                            JSONObject o = new JSONObject(arg);
                            OneDriveWeb.creer(MainActivity.this, o.getString("url"), o.getString("text"));
                            repondre(id, true, "");
                        }
                        catch (OneDriveWeb.Exc e) { repondre(id, false, e.code + ":" + e.getMessage()); }
                        catch (Exception e) { repondre(id, false, "reseau:" + message(e)); }
                    });
                    break;
                case "spList":
                    reseau.execute(() -> {
                        try { repondre(id, true, OneDriveWeb.lister(MainActivity.this, arg)); }
                        catch (OneDriveWeb.Exc e) { repondre(id, false, e.code + ":" + e.getMessage()); }
                        catch (Exception e) { repondre(id, false, "reseau:" + message(e)); }
                    });
                    break;
                case "spRecycle":
                    reseau.execute(() -> {
                        try { OneDriveWeb.corbeille(MainActivity.this, arg); repondre(id, true, ""); }
                        catch (OneDriveWeb.Exc e) { repondre(id, false, e.code + ":" + e.getMessage()); }
                        catch (Exception e) { repondre(id, false, "reseau:" + message(e)); }
                    });
                    break;
                case "paste":
                    runOnUiThread(() -> {
                        try {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            ClipData c = cm.getPrimaryClip();
                            CharSequence t = c != null && c.getItemCount() > 0 ? c.getItemAt(0).coerceToText(MainActivity.this) : "";
                            repondre(id, true, t == null ? "" : t.toString());
                        } catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "haptic":
                    // retour haptique : la WebView ignore navigator.vibrate
                    try {
                        android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
                        if (v != null && v.hasVibrator()) {
                            if (Build.VERSION.SDK_INT >= 29) {
                                int e = "tick".equals(arg) ? android.os.VibrationEffect.EFFECT_TICK
                                      : "double".equals(arg) ? android.os.VibrationEffect.EFFECT_DOUBLE_CLICK
                                      : android.os.VibrationEffect.EFFECT_CLICK;
                                v.vibrate(android.os.VibrationEffect.createPredefined(e));
                            } else {
                                v.vibrate(android.os.VibrationEffect.createOneShot("tick".equals(arg) ? 8 : 20, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                            }
                        }
                        repondre(id, true, "");
                    } catch (Exception e) { repondre(id, false, message(e)); }
                    break;
                case "outils":
                    io.execute(() -> {
                        try { repondre(id, true, Outils.liste(MainActivity.this, appli())); }
                        catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "afficherOutil":
                    io.execute(() -> {
                        try {
                            JSONObject o = new JSONObject(arg);
                            Outils.afficher(MainActivity.this, appli(), o.getString("appli"), o.getBoolean("visible"));
                            repondre(id, true, "");
                        } catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "copy":
                    // presse-papiers : texte brut + mise en forme (le mail « Demandez le programme » se colle tel quel)
                    runOnUiThread(() -> {
                        try {
                            JSONObject o = new JSONObject(arg);
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newHtmlText(appli(), o.optString("text"), o.optString("html")));
                            repondre(id, true, Build.VERSION.SDK_INT >= 33 ? "systeme" : "");
                        } catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "version":
                    repondre(id, true, String.valueOf(versionInstallee()));
                    break;
                case "checkUpdate":
                    reseau.execute(() -> {
                        try { repondre(id, true, new String(telecharger(DEPOT + "version.txt", 100), StandardCharsets.UTF_8).trim()); }
                        catch (Exception e) { repondre(id, false, message(e)); }
                    });
                    break;
                case "update":
                    runOnUiThread(() -> mettreAJour(id));
                    break;
                default:
                    repondre(id, false, "méthode inconnue : " + methode);
            }
        }
    }

    private void repondre(int id, boolean ok, String valeur) {
        final String js = "window.compagnonReply&&compagnonReply(" + id + "," + ok + "," + JSONObject.quote(valeur == null ? "" : valeur) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static String message(Exception e) {
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    // ---------- fichier choisi par l'appli OneDrive ----------

    /** Le fichier mémorisé, s'il a encore son autorisation de lecture et d'écriture. */
    private Uri fichier() {
        String s = prefs.getString("uri:" + appli(), null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return u;
        return null;
    }

    private Uri exige() throws Exception {
        Uri u = fichier();
        if (u == null) throw new Exception("autorisation perdue : choisis à nouveau le fichier");
        return u;
    }

    private String lire(Uri u) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(u)) {
            if (in == null) throw new Exception("fichier illisible");
            String t = new String(tout(in), StandardCharsets.UTF_8);
            return t.startsWith("﻿") ? t.substring(1) : t;
        }
    }

    /** Remplace le contenu (mode « wt »), puis relit pour vérifier ; OneDrive peut renvoyer un moment l'ancienne version. */
    private boolean ecrire(Uri u, String texte) throws Exception {
        try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
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

    private void choisir(int id) {
        if (choixEnCours != -1) repondre(choixEnCours, false, "annulé");
        choixEnCours = id;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, CHOISIR);
        } catch (Exception e) {
            choixEnCours = -1;
            repondre(id, false, "sélecteur de fichiers indisponible");
        }
    }

    /** Fichier fourni par l'appli OneDrive (son fournisseur de documents), et non par le téléphone. */
    private static boolean surOneDrive(Uri u) {
        String a = u.getAuthority() == null ? "" : u.getAuthority().toLowerCase();
        return a.contains("skydrive") || a.contains("onedrive") || a.contains("microsoft");
    }

    /** Nouveau fichier, à l'endroit choisi (Téléchargements, Documents… ou OneDrive), avec le nom proposé. */
    private void creer(int id, String nom) {
        if (choixEnCours != -1) repondre(choixEnCours, false, "annulé");
        choixEnCours = id;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/markdown");
        i.putExtra(Intent.EXTRA_TITLE, nom == null || nom.isEmpty() ? "fichier.md" : nom);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, CREER);
        } catch (Exception e) {
            choixEnCours = -1;
            repondre(id, false, "sélecteur de fichiers indisponible");
        }
    }

    @Override
    protected void onActivityResult(int code, int res, Intent data) {
        super.onActivityResult(code, res, data);
        if (code == CONNEXION) {
            int i = connexionEnCours;
            connexionEnCours = -1;
            if (i != -1) repondre(i, res == RESULT_OK, res == RESULT_OK ? "" : "annulé");
            return;
        }
        if (code != CHOISIR && code != CREER) return;
        final int id = choixEnCours;
        choixEnCours = -1;
        if (id == -1) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { repondre(id, false, "annulé"); return; }
        final Uri u = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(u,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            repondre(id, false, "ce fichier ne peut pas être modifié par l'appli (" + message(e) + ")");
            return;
        }
        io.execute(() -> {
            // on libère l'ancien fichier de cet outil (ceux des autres outils gardent leur autorisation)
            String ancien = prefs.getString("uri:" + appli(), null);
            for (UriPermission p : getContentResolver().getPersistedUriPermissions())
                if (ancien != null && !p.getUri().equals(u) && p.getUri().toString().equals(ancien)) {
                    try { getContentResolver().releasePersistableUriPermission(p.getUri(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
                    catch (Exception ignored) { }
                }
            String nom = "fichier.md";
            try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst() && !c.isNull(0)) nom = c.getString(0);
            } catch (Exception ignored) { }
            prefs.edit().putString("uri:" + appli(), u.toString()).putString("nom:" + appli(), nom).apply();
            repondre(id, true, nom);
        });
    }

    // ---------- mise à jour de l'appli depuis la version publiée sur GitHub ----------

    private long versionInstallee() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] telecharger(String adresse, int max) throws Exception {
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

    /** Avant la première mise à jour : demander le droit d'afficher une notification (Android 13 et plus), puis continuer. */
    private void mettreAJour(int id) {
        if (majEnCours != -1) { repondre(id, false, "mise à jour déjà en cours"); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("notifDemandee", false)) {
            prefs.edit().putBoolean("notifDemandee", true).apply();
            majApresPermission = id;
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
            return;
        }
        installer(id);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 3 && majApresPermission != -1) { int id = majApresPermission; majApresPermission = -1; installer(id); }
    }

    private void installer(int id) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            repondre(id, false, "autorise d'abord « Officious » à installer des applis, puis touche à nouveau « Mettre à jour »");
            return;
        }
        majEnCours = id;
        reseau.execute(() -> {
            try {
                byte[] apk = telecharger(DEPOT + "officious.apk", 30 * 1024 * 1024);
                if (apk.length < 4 || apk[0] != 'P' || apk[1] != 'K') throw new Exception("fichier téléchargé invalide");
                PackageInstaller pi = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(getPackageName());
                if (Build.VERSION.SDK_INT >= 31) sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                int sid = pi.createSession(sp);
                try (PackageInstaller.Session s = pi.openSession(sid)) {
                    try (OutputStream o = s.openWrite("officious.apk", 0, apk.length)) {
                        o.write(apk);
                        s.fsync(o);
                    }
                    Intent retour = new Intent(this, getClass()).setAction(FIN_INSTALLATION);
                    int drap = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
                    PendingIntent pe = PendingIntent.getActivity(this, 7, retour, drap);
                    s.commit(pe.getIntentSender());
                }
            } catch (Exception e) {
                int i = majEnCours;
                majEnCours = -1;
                repondre(i, false, message(e));
            }
        });
    }

    /** Résultat de l'installation, renvoyé par Android. */
    private boolean finInstallation(Intent i) {
        if (i == null || !FIN_INSTALLATION.equals(i.getAction())) return false;
        int statut = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (statut == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirmer = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirmer != null) {
                confirmer.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(confirmer); } catch (Exception ignored) { }
            }
            return true;
        }
        int id = majEnCours;
        majEnCours = -1;
        if (id != -1) {
            if (statut == PackageInstaller.STATUS_SUCCESS) repondre(id, true, "");
            else {
                String m = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                repondre(id, false, statut == PackageInstaller.STATUS_FAILURE_ABORTED ? "annulé" : (m == null ? "échec de l'installation" : m));
            }
        }
        return true;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        finInstallation(i);
    }

    // ---------- cycle de vie ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.compagnonResume&&compagnonResume()", null);
    }

    @Override
    protected void onPause() {
        if (web != null) web.evaluateJavascript("window.compagnonPause&&compagnonPause()", null);
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.compagnonBack?compagnonBack():false", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) web.goBack();
            else finish();
        });
    }
}
