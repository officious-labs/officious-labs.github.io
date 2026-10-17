package io.github.officiouslabs.compagnon;

import android.content.Context;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Accès direct au OneDrive professionnel, comme le ferait un navigateur : la session web (cookies)
 * ouverte dans ConnexionActivity sert à lire et écrire le fichier par son chemin, via l'interface
 * REST de SharePoint. Plus d'intermédiaire (l'appli OneDrive du téléphone), plus de référence interne
 * qui change quand le PC remplace le fichier.
 */
final class OneDriveWeb {
    private OneDriveWeb() { }

    /** Navigateur Chrome ordinaire : la mention « ; wv » et « Version/x » de WebView sont retirées. */
    static String agent(Context c) {
        return WebSettings.getDefaultUserAgent(c).replace("; wv", "").replaceAll(" Version/\\d+(\\.\\d+)*", "");
    }

    static final class Exc extends Exception {
        final String code;
        Exc(String code, String msg) { super(msg); this.code = code; }
    }

    /** https://hote/personal/x/Documents/Tâches/taches.md → { site, chemin relatif au serveur } */
    private static String[] decoupe(String url) throws Exc {
        Uri u = Uri.parse(url);
        String chemin = u.getPath();
        /* adresse copiée sous forme de lien de partage : « /:f:/r/personal/… » */
        if (chemin != null) chemin = chemin.replaceFirst("^/:[a-zA-Z]:/[a-zA-Z](?=/)", "");
        if (chemin == null) throw new Exc("adresse", "adresse du fichier incomplète");
        String[] p = chemin.split("/");
        if (p.length < 4 || !("personal".equals(p[1]) || "sites".equals(p[1])))
            throw new Exc("adresse", "adresse OneDrive incomplète : vérifie « Adresse en ligne » dans le menu ⋯ du PC");
        String site = u.getScheme() + "://" + u.getHost() + "/" + p[1] + "/" + p[2];
        return new String[] { site, chemin };
    }

    private static String api(String site, String chemin) {
        String enc = Uri.encode(chemin.replace("'", "''"), "/");
        return site + "/_api/web/GetFileByServerRelativePath(decodedurl='" + enc + "')/$value";
    }

    private static HttpURLConnection ouvre(Context c, String url, String methode) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setInstanceFollowRedirects(false);
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        h.setRequestMethod(methode);
        h.setRequestProperty("User-Agent", agent(c));
        String cookies = CookieManager.getInstance().getCookie(url);
        if (cookies != null) h.setRequestProperty("Cookie", cookies);
        return h;
    }

    private static byte[] corps(HttpURLConnection h) throws Exception {
        try (InputStream in = h.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            return bo.toByteArray();
        }
    }

    private static void verifie(HttpURLConnection h) throws Exception {
        int r = h.getResponseCode();
        if (r == 401 || r == 403 || (r >= 300 && r < 400)) throw new Exc("connexion", "connexion à OneDrive nécessaire");
        if (r == 404) throw new Exc("introuvable", "fichier introuvable sur OneDrive");
        if (r == 412) throw new Exc("conflit", "le fichier a changé entre-temps");
        if (r >= 400) throw new Exc("http", "OneDrive a répondu " + r);
    }

    /** Lit le fichier : { text, etag } */
    static JSONObject lire(Context c, String url) throws Exception {
        String[] d = decoupe(url);
        HttpURLConnection h = ouvre(c, api(d[0], d[1]), "GET");
        h.setRequestProperty("Accept", "*/*");
        verifie(h);
        String t = new String(corps(h), StandardCharsets.UTF_8);
        if (t.startsWith("﻿")) t = t.substring(1);
        JSONObject o = new JSONObject();
        o.put("text", t);
        o.put("etag", h.getHeaderField("ETag") == null ? "" : h.getHeaderField("ETag"));
        return o;
    }

    private static String jeton(Context c, String site) throws Exception {
        HttpURLConnection g = ouvre(c, site + "/_api/contextinfo", "POST");
        g.setRequestProperty("Accept", "application/json;odata=nometadata");
        g.setDoOutput(true);
        g.setFixedLengthStreamingMode(0);
        g.getOutputStream().close();
        verifie(g);
        return new JSONObject(new String(corps(g), StandardCharsets.UTF_8)).getString("FormDigestValue");
    }

    /** Crée un fichier qui n'existe pas encore (et son dossier au besoin) ; refuse d'écraser un fichier existant (conflit). */
    static void creer(Context c, String url, String texte) throws Exception {
        byte[] b = texte.getBytes(StandardCharsets.UTF_8);
        creerFlux(c, url, new java.io.ByteArrayInputStream(b), b.length);
    }

    private static void creerFlux(Context c, String url, InputStream in, long taille) throws Exception {
        String[] d = decoupe(url);
        String chemin = d[1];
        int k = chemin.lastIndexOf('/');
        String dossier = chemin.substring(0, k), nom = chemin.substring(k + 1);
        String digest = jeton(c, d[0]);
        /* dossier : sans effet s'il existe déjà */
        HttpURLConnection f = ouvre(c, d[0] + "/_api/web/folders/AddUsingPath(decodedurl='" + Uri.encode(dossier.replace("'", "''"), "/") + "')", "POST");
        f.setRequestProperty("Accept", "application/json;odata=nometadata");
        f.setRequestProperty("X-RequestDigest", digest);
        f.setDoOutput(true);
        f.setFixedLengthStreamingMode(0);
        f.getOutputStream().close();
        int rf = f.getResponseCode();
        if (rf == 401 || rf == 403) throw new Exc("connexion", "connexion à OneDrive nécessaire");
        HttpURLConnection h = ouvre(c, d[0] + "/_api/web/GetFolderByServerRelativePath(decodedurl='" + Uri.encode(dossier.replace("'", "''"), "/")
                + "')/Files/AddUsingPath(decodedurl='" + Uri.encode(nom.replace("'", "''")) + "',overwrite=false)", "POST");
        h.setRequestProperty("Accept", "application/json;odata=nometadata");
        h.setRequestProperty("X-RequestDigest", digest);
        h.setDoOutput(true);
        h.setFixedLengthStreamingMode(taille);
        try (OutputStream o = h.getOutputStream()) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        int r = h.getResponseCode();
        if (r == 409 || r == 400 && dejaLa(h)) throw new Exc("conflit", "le fichier existe déjà");
        verifie(h);
    }

    /**
     * Dépose un fichier du téléphone (pièce jointe) ; si le nom est pris, « nom (2).ext », « nom (3).ext »…
     * Renvoie le nom retenu.
     */
    static String deposer(Context c, String url, java.io.File fichier) throws Exception {
        int k = url.lastIndexOf('/');
        String base = url.substring(0, k + 1), nom = Uri.decode(url.substring(k + 1)), racine = nom, ext = "";
        int pt = nom.lastIndexOf('.');
        if (pt > 0) { racine = nom.substring(0, pt); ext = nom.substring(pt); }
        for (int i = 1; i <= 50; i++) {
            String n = i == 1 ? nom : racine + " (" + i + ")" + ext;
            try (InputStream in = new java.io.FileInputStream(fichier)) {
                creerFlux(c, base + Uri.encode(n), in, fichier.length());
                return n;
            } catch (Exc e) {
                if (!"conflit".equals(e.code)) throw e;
            }
        }
        throw new Exc("conflit", "trop de fichiers du même nom");
    }

    /** Fichiers d'un dossier : [{ name, modified }] */
    static String lister(Context c, String dossierUrl) throws Exception {
        String[] d = decoupe(dossierUrl);
        HttpURLConnection h = ouvre(c, d[0] + "/_api/web/GetFolderByServerRelativePath(decodedurl='" + Uri.encode(d[1].replace("'", "''"), "/")
                + "')/Files?$select=Name,TimeLastModified", "GET");
        h.setRequestProperty("Accept", "application/json;odata=nometadata");
        verifie(h);
        org.json.JSONArray v = new JSONObject(new String(corps(h), StandardCharsets.UTF_8)).getJSONArray("value");
        org.json.JSONArray out = new org.json.JSONArray();
        for (int i = 0; i < v.length(); i++) {
            JSONObject f = v.getJSONObject(i), o = new JSONObject();
            o.put("name", f.optString("Name"));
            o.put("modified", f.optString("TimeLastModified"));
            out.put(o);
        }
        return out.toString();
    }

    /** Met un fichier à la corbeille de OneDrive (récupérable depuis OneDrive) */
    static void corbeille(Context c, String url) throws Exception {
        String[] d = decoupe(url);
        String digest = jeton(c, d[0]);
        HttpURLConnection h = ouvre(c, d[0] + "/_api/web/GetFileByServerRelativePath(decodedurl='" + Uri.encode(d[1].replace("'", "''"), "/") + "')/recycle()", "POST");
        h.setRequestProperty("Accept", "application/json;odata=nometadata");
        h.setRequestProperty("X-RequestDigest", digest);
        h.setDoOutput(true);
        h.setFixedLengthStreamingMode(0);
        h.getOutputStream().close();
        verifie(h);
    }

    private static boolean dejaLa(HttpURLConnection h) {
        try (InputStream in = h.getErrorStream()) {
            if (in == null) return false;
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            String t = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            return t.contains("exist");
        } catch (Exception e) { return false; }
    }

    /** Écrit le fichier ; si etag est fourni, refuse d'écraser une version plus récente (conflit) */
    static void ecrire(Context c, String url, String texte, String etag) throws Exception {
        String[] d = decoupe(url);
        HttpURLConnection g = ouvre(c, d[0] + "/_api/contextinfo", "POST");
        g.setRequestProperty("Accept", "application/json;odata=nometadata");
        g.setDoOutput(true);
        g.setFixedLengthStreamingMode(0);
        g.getOutputStream().close();
        verifie(g);
        String digest = new JSONObject(new String(corps(g), StandardCharsets.UTF_8)).getString("FormDigestValue");
        HttpURLConnection h = ouvre(c, api(d[0], d[1]), "POST");
        h.setRequestProperty("X-HTTP-Method", "PUT");
        h.setRequestProperty("X-RequestDigest", digest);
        h.setRequestProperty("If-Match", etag == null || etag.isEmpty() ? "*" : etag);
        byte[] b = texte.getBytes(StandardCharsets.UTF_8);
        h.setDoOutput(true);
        h.setFixedLengthStreamingMode(b.length);
        try (OutputStream o = h.getOutputStream()) { o.write(b); }
        verifie(h);
    }
}
