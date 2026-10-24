package io.github.officiouslabs.compagnon;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Les outils de la suite : une activité (une icône) chacun. Pour en ajouter un : une ligne ici, son activité
 * (XxxActivity extends MainActivity) et sa déclaration dans AndroidManifest.xml.
 * Chaque icône peut être masquée (réglage « Outils affichés », depuis n'importe quel outil) : Android désactive alors
 * l'activité, qui disparaît de l'écran d'accueil sans que rien ne soit désinstallé.
 */
final class Outils {
    private Outils() { }

    static final String[] NOMS = { "saison", "taches" };
    static final String[] TITRES = { "Saison", "Tâches" };
    static final Class<?>[] ACTIVITES = { SaisonActivity.class, TachesActivity.class };

    static Class<?> activiteDe(String appli) {
        for (int i = 0; i < NOMS.length; i++) if (NOMS[i].equals(appli)) return ACTIVITES[i];
        return ACTIVITES[0];
    }

    static boolean visible(Context c, int i) {
        int etat = c.getPackageManager().getComponentEnabledSetting(new ComponentName(c, ACTIVITES[i]));
        return etat != PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
    }

    /** [{appli, titre, visible, ici}] */
    static String liste(Context c, String ici) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < NOMS.length; i++) {
            JSONObject o = new JSONObject();
            o.put("appli", NOMS[i]);
            o.put("titre", TITRES[i]);
            o.put("visible", visible(c, i));
            o.put("ici", NOMS[i].equals(ici));
            a.put(o);
        }
        return a.toString();
    }

    /** Affiche ou masque l'icône d'un outil ; jamais celle de l'outil ouvert, jamais la dernière visible. */
    static void afficher(Context c, String ici, String appli, boolean voir) throws Exception {
        int k = -1, visibles = 0;
        for (int i = 0; i < NOMS.length; i++) {
            if (NOMS[i].equals(appli)) k = i;
            if (visible(c, i)) visibles++;
        }
        if (k < 0) throw new Exception("outil inconnu : " + appli);
        if (!voir && appli.equals(ici)) throw new Exception("on ne peut pas masquer l'outil ouvert : fais-le depuis un autre outil");
        if (!voir && visible(c, k) && visibles <= 1) throw new Exception("il faut garder au moins une icône");
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, ACTIVITES[k]),
                voir ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
