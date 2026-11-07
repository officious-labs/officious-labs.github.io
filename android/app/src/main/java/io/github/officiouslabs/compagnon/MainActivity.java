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
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;

/**
 * Compagnon Officine : une seule appli pour toute la suite, une icône (une activité) par outil.
 * Chaque activité affiche la page publiée de son outil (https://officious-labs.github.io/<outil>/) et lui prête l'accès
 * à son fichier par le pont « Compagnon ». Dérivé de la coquille de Tâches (15/10/2026), via celle de Saison.
 *
 * Contrat du pont : voir Pont.java (07/11/2026 : le pont est commun avec la vue invisible de la synchro du matin) ; cette
 * activité ne fait que les méthodes qui demandent l'écran (demande) : pick, create, spLogin, paste, copy, haptic, print,
 * saveAs ; pièces jointes (reprises du Compagnon de Tâches, 24/10/2026) : saveAttachment, saveAttachmentAs,
 * sendToOneDrive, keepFile, openAttachment, discardFile ; update.
 * Vers la page : compagnonResume, compagnonPause, compagnonBack ; compagnonTexte(texte) (raccourci « Nouvelle note » ou
 * texte partagé, '' pour une note vide) et compagnonFichier({name, size, type}) (fichier partagé), pour les outils dont
 * l'activité reçoit le menu Partager (déclaré dans AndroidManifest.xml).
 * Un champ <input type="file"> de la page ouvre le sélecteur d'Android (onShowFileChooser).
 */
public abstract class MainActivity extends Activity implements Pont.Ecran {
    /** Nom de l'outil de cette activité : chemin de sa page, préfixe de ses réglages. */
    protected abstract String appli();

    private static final String FIN_INSTALLATION = "io.github.officiouslabs.compagnon.INSTALLATION";
    private static final int CHOISIR = 1, CREER = 2, CHOISIR_DOSSIER = 4, ENREGISTRER_SOUS = 5, CONNEXION = 6,
            FICHIER_PAGE = 8, ENREGISTRER_COPIE = 9;
    /** Action du raccourci « Nouvelle note » (appui long sur l'icône). */
    static final String NOTE = "io.github.officiouslabs.compagnon.NOTE";
    private static final String PJ = "Pièces jointes";
    /** Fichier reçu par le menu Partager, copié dans le cache en attendant d'être rangé. */
    private File partage = null;
    private String partageNom = null, partageType = null;
    private boolean partageALivrer = false, pageChargee = false;
    /** Texte à donner à la page (raccourci ou partage) quand elle sera chargée ; null = rien. */
    private String texteEnAttente = null;
    private int pjEnCours = -1, copieEnCours = -1;
    private String pjNom = null, copieTexte = null;
    private ValueCallback<Uri[]> fichierPage = null;

    private WebView web;
    private Pont pont;
    private SharedPreferences prefs;
    private ExecutorService io, reseau;
    /** Outils dont l'activité est à l'écran (la synchro du matin les laisse faire, voir SynchroMatin). */
    static final java.util.Set<String> A_L_ECRAN = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private int choixEnCours = -1, connexionEnCours = -1, majEnCours = -1, majApresPermission = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("compagnon", MODE_PRIVATE);
        prefs.edit().putString("dernier", appli()).apply(); // rouvert après une mise à jour
        web = new WebView(this);
        pont = new Pont(this, appli(), web, this, null);
        io = pont.io;
        reseau = pont.reseau;
        pont.regler();
        web.setWebChromeClient(new Chrome());
        web.setWebViewClient(new Client());
        setContentView(web);
        if (b == null) lireIntention(getIntent()); // pas après une rotation
        try { ((android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(MajRecue.NOTIF); } catch (Exception ignored) { }
        finInstallation(getIntent());
        Reveil.armer(this);          // synchro du matin : réarmée à chaque ouverture
        demanderNotifications();
        web.loadUrl(pont.depart());
    }

    /** Synchro du matin : son bilan est une notification ; demandé une fois (Android 13 et plus), sans rien bloquer si refusé. */
    private void demanderNotifications() {
        if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean("notifDemandee", false)) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        prefs.edit().putBoolean("notifDemandee", true).apply();
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 10);
    }

    // ---------- navigation : seule notre page reste dans l'appli ----------

    private static boolean notre(Uri u) { return Pont.notre(u); }

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
            pont.autorisee = notre(Uri.parse(url));
            pageChargee = false;
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            pageChargee = notre(Uri.parse(url));
            livrer();
        }

        /** La page elle-même : version en ligne si possible (gardée en copie), sinon dernière copie, sinon celle embarquée. */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) { return pont.servir(r); }
    }

    /** Un champ « fichier » de la page : le sélecteur d'Android (sans cela, rien ne s'ouvre, constaté le 18/10). */
    private class Chrome extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
            if (fichierPage != null) fichierPage.onReceiveValue(null);
            fichierPage = cb;
            try {
                Intent i = params.createIntent();
                i.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(i, FICHIER_PAGE);
                return true;
            } catch (Exception e) {
                fichierPage = null;
                Toast.makeText(MainActivity.this, "Sélecteur de fichiers indisponible", Toast.LENGTH_SHORT).show();
                return false;
            }
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

    // ---------- pont JavaScript : le commun est dans Pont.java, ici ce qui demande l'écran ----------

    @Override
    public boolean demande(final int id, final String methode, final String arg) {
        switch (methode) {
            case "pick":
                runOnUiThread(() -> choisir(id));
                return true;
            case "create":
                runOnUiThread(() -> creer(id, arg));
                return true;
            case "spLogin":
                runOnUiThread(() -> {
                    if (connexionEnCours != -1) repondre(connexionEnCours, false, "annulé");
                    connexionEnCours = id;
                    Intent i = new Intent(MainActivity.this, ConnexionActivity.class);
                    i.putExtra(ConnexionActivity.URL, arg);
                    startActivityForResult(i, CONNEXION);
                });
                return true;
            case "paste":
                runOnUiThread(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        ClipData c = cm.getPrimaryClip();
                        CharSequence t = c != null && c.getItemCount() > 0 ? c.getItemAt(0).coerceToText(MainActivity.this) : "";
                        repondre(id, true, t == null ? "" : t.toString());
                    } catch (Exception e) { repondre(id, false, message(e)); }
                });
                return true;
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
                return true;
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
                return true;
            case "print":
                // la WebView ignore window.print() : on passe par l'impression d'Android (imprimante ou PDF)
                runOnUiThread(() -> {
                    try {
                        JSONObject o = new JSONObject(arg == null || arg.isEmpty() ? "{}" : arg);
                        String nom = o.optString("nom", appli());
                        android.print.PrintManager pm = (android.print.PrintManager) getSystemService(PRINT_SERVICE);
                        android.print.PrintAttributes.MediaSize taille = "a3".equals(o.optString("format"))
                                ? android.print.PrintAttributes.MediaSize.ISO_A3.asLandscape()
                                : android.print.PrintAttributes.MediaSize.ISO_A4;
                        android.print.PrintAttributes attr = new android.print.PrintAttributes.Builder()
                                .setMediaSize(taille)
                                .setMinMargins(android.print.PrintAttributes.Margins.NO_MARGINS)
                                .build();
                        pm.print(nom, web.createPrintDocumentAdapter(nom), attr);
                        repondre(id, true, "");
                    } catch (Exception e) { repondre(id, false, message(e)); }
                });
                return true;
            case "saveAs":
                // « Télécharger une copie » : la WebView ignore les téléchargements ; fenêtre « Enregistrer sous » d'Android
                runOnUiThread(() -> enregistrerCopie(id, arg));
                return true;
            case "saveAttachment":
                rangerPieceJointe(id, arg);
                return true;
            case "saveAttachmentAs":
                rangerSous(id, arg);
                return true;
            case "sendToOneDrive":
                envoyerOneDrive(id, arg);
                return true;
            case "keepFile":
                garderFichier(id, arg);
                return true;
            case "openAttachment":
                ouvrirPieceJointe(id, arg);
                return true;
            case "discardFile":
                partage = null; partageNom = null;
                repondre(id, true, "");
                return true;
            case "update":
                runOnUiThread(() -> mettreAJour(id));
                return true;
            default:
                return false;
        }
    }

    private void repondre(int id, boolean ok, String valeur) { pont.repondre(id, ok, valeur); }

    private static String message(Exception e) { return Pont.message(e); }

    private static String propre(String nom) { return Pont.propre(nom); }

    // ---------- fichier choisi par l'appli OneDrive ----------

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
        if (code == FICHIER_PAGE) {
            ValueCallback<Uri[]> cb = fichierPage;
            fichierPage = null;
            if (cb != null) cb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            return;
        }
        if (code == ENREGISTRER_COPIE) { resultatCopie(res, data); return; }
        if (code == CHOISIR_DOSSIER || code == ENREGISTRER_SOUS) { resultatPieceJointe(code, res, data); return; }
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
            repondre(id, false, "autorise d'abord « Officine » à installer des applis, puis touche à nouveau « Mettre à jour »");
            return;
        }
        majEnCours = id;
        reseau.execute(() -> {
            try {
                byte[] apk = Pont.telecharger(Pont.DEPOT + "officious.apk", 30 * 1024 * 1024);
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
        if (finInstallation(i)) return;
        setIntent(i);
        lireIntention(i);
        livrer();
    }

    // ---------- « Télécharger une copie » ----------

    private void enregistrerCopie(int id, String arg) {
        try {
            JSONObject o = new JSONObject(arg);
            if (copieEnCours != -1) repondre(copieEnCours, false, "annulé");
            copieEnCours = id;
            copieTexte = o.optString("texte");
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(o.optString("type", "text/markdown"));
            i.putExtra(Intent.EXTRA_TITLE, propre(o.optString("nom", "copie.md")));
            startActivityForResult(i, ENREGISTRER_COPIE);
        } catch (Exception e) {
            copieEnCours = -1;
            repondre(id, false, message(e));
        }
    }

    private void resultatCopie(int res, Intent data) {
        final int id = copieEnCours;
        final String texte = copieTexte;
        copieEnCours = -1;
        copieTexte = null;
        if (id == -1) return;
        if (res != RESULT_OK || data == null || data.getData() == null) { repondre(id, false, "annulé"); return; }
        final Uri u = data.getData();
        io.execute(() -> {
            try (OutputStream o = getContentResolver().openOutputStream(u, "wt")) {
                if (o == null) throw new Exception("écriture impossible");
                o.write((texte == null ? "" : texte).getBytes(StandardCharsets.UTF_8));
                repondre(id, true, nomDocument(u, "copie"));
            } catch (Exception e) {
                repondre(id, false, message(e));
            }
        });
    }

    // ---------- raccourci « Nouvelle note » et menu Partager (outils qui les déclarent) ----------

    private void lireIntention(Intent i) {
        if (i == null) return;
        if (NOTE.equals(i.getAction())) {
            texteEnAttente = "";
        } else if (Intent.ACTION_SEND.equals(i.getAction()) && i.getParcelableExtra(Intent.EXTRA_STREAM) != null) {
            recevoirFichier((Uri) i.getParcelableExtra(Intent.EXTRA_STREAM), i.getType());
        } else if (Intent.ACTION_SEND.equals(i.getAction()) && "text/plain".equals(i.getType())) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            String sujet = i.getStringExtra(Intent.EXTRA_SUBJECT);
            String texte = t == null ? "" : t.toString().trim();
            if (sujet != null && !sujet.trim().isEmpty() && !texte.startsWith(sujet.trim())) texte = sujet.trim() + "\n" + texte;
            texteEnAttente = texte;
        }
    }

    /** Donne à la page, une fois chargée, le texte ou le fichier reçu. */
    private void livrer() {
        if (!pageChargee || web == null) return;
        if (partageALivrer && partage != null) {
            partageALivrer = false;
            try {
                JSONObject o = new JSONObject();
                o.put("name", partageNom); o.put("size", partage.length()); o.put("type", partageType);
                web.evaluateJavascript("window.compagnonFichier&&compagnonFichier(" + o + ")", null);
            } catch (Exception ignored) { }
        }
        if (texteEnAttente != null) {
            String js = "window.compagnonTexte&&compagnonTexte(" + JSONObject.quote(texteEnAttente) + ")";
            texteEnAttente = null;
            web.evaluateJavascript(js, null);
        }
    }

    // ---------- fichier partagé vers l'appli : rangé dans « Pièces jointes », à côté du fichier de l'outil ----------

    private void recevoirFichier(Uri u, String type) {
        String nom = "fichier";
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) nom = c.getString(0);
        } catch (Exception ignored) { }
        final String n = nom;
        partageNom = null;
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(u)) {
                if (in == null) throw new Exception("fichier illisible");
                File dir = new File(getCacheDir(), "partage");
                dir.mkdirs();
                File[] vieux = dir.listFiles();
                if (vieux != null) for (File f : vieux) f.delete();
                File f = new File(dir, "recu");
                try (OutputStream o = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536]; int k; long total = 0;
                    while ((k = in.read(buf)) > 0) { o.write(buf, 0, k); total += k; if (total > 60L * 1024 * 1024) throw new Exception("fichier trop gros (60 Mo au plus)"); }
                }
                partage = f; partageNom = n; partageType = type == null || type.isEmpty() ? "application/octet-stream" : type;
                partageALivrer = true;
                runOnUiThread(this::livrer);
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Fichier non reçu : " + message(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void resultatPieceJointe(int code, int res, Intent data) {
        final int id = pjEnCours;
        pjEnCours = -1;
        if (id == -1) return;
        if (res != RESULT_OK || data == null || data.getData() == null) {
            repondre(id, false, code == CHOISIR_DOSSIER ? "dossier-refuse" : "annulé");
            return;
        }
        final Uri u = data.getData();
        if (code == ENREGISTRER_SOUS) {
            io.execute(() -> {
                try { copierVers(u); repondre(id, true, PJ + "/" + nomDocument(u, propre(pjNom))); }
                catch (Exception e) { repondre(id, false, message(e)); }
            });
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            repondre(id, false, "dossier-refuse");
            return;
        }
        io.execute(() -> {
            // vérifier qu'il s'agit bien du dossier du fichier de l'outil
            boolean ok = false;
            String nomFichier = prefs.getString("nom:" + appli(), "");
            try {
                for (String[] e : enfants(u, DocumentsContract.getTreeDocumentId(u))) if (nomFichier.equals(e[1])) { ok = true; break; }
            } catch (Exception ignored) { }
            if (!ok) {
                try { getContentResolver().releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION); } catch (Exception ignored) { }
                repondre(id, false, "ce dossier ne contient pas " + nomFichier + " : choisis le dossier où il se trouve");
                return;
            }
            prefs.edit().putString("tree:" + appli(), u.toString()).apply();
            runOnUiThread(() -> rangerPieceJointe(id, pjNom));
        });
    }

    /** Dossier du fichier de l'outil, s'il a été autorisé (première pièce jointe). */
    private Uri dossier() {
        String s = prefs.getString("tree:" + appli(), null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            if (p.getUri().equals(u) && p.isWritePermission()) return u;
        return null;
    }

    /** Enfants d'un dossier : {identifiant, nom, type}. */
    private java.util.List<String[]> enfants(Uri tree, String parentId) {
        java.util.List<String[]> l = new java.util.ArrayList<>();
        Uri q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor c = getContentResolver().query(q, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) l.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        return l;
    }

    private String sousDossier(Uri tree, boolean creer) throws Exception {
        String racine = DocumentsContract.getTreeDocumentId(tree);
        for (String[] e : enfants(tree, racine))
            if (PJ.equals(e[1]) && DocumentsContract.Document.MIME_TYPE_DIR.equals(e[2])) return e[0];
        if (!creer) return null;
        Uri d = DocumentsContract.createDocument(getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, racine),
                DocumentsContract.Document.MIME_TYPE_DIR, PJ);
        if (d == null) throw new Exception("impossible de créer le dossier « " + PJ + " »");
        return DocumentsContract.getDocumentId(d);
    }

    private String nomDocument(Uri u, String defaut) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) { }
        return defaut;
    }

    private void copierVers(Uri doc) throws Exception {
        try (InputStream in = new java.io.FileInputStream(partage); OutputStream o = getContentResolver().openOutputStream(doc, "w")) {
            if (o == null) throw new Exception("écriture impossible");
            byte[] buf = new byte[65536]; int k;
            while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
        }
    }

    /** Range le fichier reçu dans « Pièces jointes » ; renvoie le chemin relatif au dossier du fichier de l'outil. */
    private void rangerPieceJointe(int id, String nom) {
        if (partage == null) { repondre(id, false, "aucun fichier reçu"); return; }
        Uri tree = dossier();
        if (tree == null) {
            // première fois : demander l'accès au dossier du fichier
            pjEnCours = id; pjNom = nom;
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                Uri f = pont.fichier();
                if (f != null && Build.VERSION.SDK_INT >= 26) i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, f);
                try { startActivityForResult(i, CHOISIR_DOSSIER); }
                catch (Exception e) { pjEnCours = -1; repondre(id, false, "dossier-refuse"); }
            });
            return;
        }
        io.execute(() -> {
            try {
                String sous = sousDossier(tree, true);
                java.util.Set<String> pris = new java.util.HashSet<>();
                for (String[] e : enfants(tree, sous)) pris.add(e[1]);
                String n = propre(nom), base = n, ext = "";
                int pt = n.lastIndexOf('.');
                if (pt > 0) { base = n.substring(0, pt); ext = n.substring(pt); }
                for (int k = 2; pris.contains(n); k++) n = base + " (" + k + ")" + ext;
                Uri doc = DocumentsContract.createDocument(getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, sous), partageType, n);
                if (doc == null) throw new Exception("création du fichier refusée");
                copierVers(doc);
                repondre(id, true, PJ + "/" + nomDocument(doc, n));
            } catch (Exception e) {
                repondre(id, false, message(e));
            }
        });
    }

    /** Met de côté le fichier reçu, pour l'envoyer dans « Pièces jointes » par l'accès direct ; renvoie sa clé. */
    private void garderFichier(int id, String nom) {
        if (partage == null) { repondre(id, false, "aucun fichier reçu"); return; }
        final File src = partage;
        io.execute(() -> {
            try {
                String cle = System.currentTimeMillis() + "-" + propre(nom);
                File f = new File(pont.attente(), cle);
                if (!src.renameTo(f)) {
                    try (InputStream in = new java.io.FileInputStream(src); OutputStream o = new FileOutputStream(f)) {
                        byte[] buf = new byte[65536]; int k;
                        while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                    }
                    src.delete();
                }
                partage = null; partageNom = null;
                repondre(id, true, cle);
            } catch (Exception e) {
                repondre(id, false, message(e));
            }
        });
    }

    /**
     * OneDrive refuse aux autres applis l'accès à ses dossiers : on lui confie le fichier par le menu Partager,
     * et c'est lui qui l'enregistre (l'utilisateur choisit le dossier « Pièces jointes »).
     */
    private void envoyerOneDrive(int id, String nom) {
        if (partage == null) { repondre(id, false, "aucun fichier reçu"); return; }
        io.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "envoi");
                dir.mkdirs();
                File[] vieux = dir.listFiles();
                if (vieux != null) for (File f : vieux) f.delete();
                File f = new File(dir, propre(nom));
                try (InputStream in = new java.io.FileInputStream(partage); OutputStream o = new FileOutputStream(f)) {
                    byte[] buf = new byte[65536]; int k;
                    while ((k = in.read(buf)) > 0) o.write(buf, 0, k);
                }
                final Uri u = new Uri.Builder().scheme("content").authority(getPackageName() + ".partage").path(f.getName()).build();
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType(partageType);
                    i.putExtra(Intent.EXTRA_STREAM, u);
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.setPackage("com.microsoft.skydrive");
                    try { startActivity(i); repondre(id, true, PJ + "/" + f.getName()); }
                    catch (ActivityNotFoundException e) { repondre(id, false, "l'appli OneDrive n'est pas installée"); }
                });
            } catch (Exception e) {
                repondre(id, false, message(e));
            }
        });
    }

    /** Solution de repli si OneDrive refuse l'accès au dossier : « Enregistrer sous » à chaque fichier. */
    private void rangerSous(int id, String nom) {
        if (partage == null) { repondre(id, false, "aucun fichier reçu"); return; }
        pjEnCours = id; pjNom = nom;
        runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(partageType);
            i.putExtra(Intent.EXTRA_TITLE, propre(nom));
            try { startActivityForResult(i, ENREGISTRER_SOUS); }
            catch (Exception e) { pjEnCours = -1; repondre(id, false, message(e)); }
        });
    }

    private void ouvrirPieceJointe(int id, String rel) {
        Uri tree = dossier();
        if (tree == null) { repondre(id, false, "l'appli n'a pas encore accès au dossier du fichier"); return; }
        io.execute(() -> {
            try {
                String[] parts = rel.split("/");
                String cur = DocumentsContract.getTreeDocumentId(tree), type = null;
                for (String part : parts) {
                    String trouve = null;
                    for (String[] e : enfants(tree, cur)) if (part.equals(e[1])) { trouve = e[0]; type = e[2]; break; }
                    if (trouve == null) throw new Exception("fichier introuvable : " + rel);
                    cur = trouve;
                }
                Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, cur);
                final String t = type;
                runOnUiThread(() -> {
                    Intent v = new Intent(Intent.ACTION_VIEW);
                    v.setDataAndType(doc, t == null ? "*/*" : t);
                    v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try { startActivity(Intent.createChooser(v, null)); repondre(id, true, ""); }
                    catch (Exception e) { repondre(id, false, "aucune appli pour ouvrir ce fichier"); }
                });
            } catch (Exception e) {
                repondre(id, false, message(e));
            }
        });
    }

    // ---------- cycle de vie ----------

    @Override
    protected void onResume() {
        super.onResume();
        A_L_ECRAN.add(appli());
        if (web != null) web.evaluateJavascript("window.compagnonResume&&compagnonResume()", null);
    }

    @Override
    protected void onPause() {
        A_L_ECRAN.remove(appli());
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
