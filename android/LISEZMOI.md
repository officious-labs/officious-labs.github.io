# Compagnon Officine

Une seule appli Android pour toute la suite (« Officine » dans les réglages du téléphone), avec une icône par outil.

- **Ajouter un outil** :
  1. Créer une activité de trois lignes (`XxxActivity extends MainActivity`, `appli()` renvoie le nom de l’outil).
  2. La déclarer dans `AndroidManifest.xml` avec son libellé, son icône (`mipmap-anydpi-v26/ic_xxx.xml`) et sa `taskAffinity`.
  3. L’ajouter à la table de `Outils.java`.
- **Outils affichés** : depuis n’importe quel outil (menu ⋯), on masque ou réaffiche les icônes des autres. Android désactive l’activité, rien n’est désinstallé. Pour partager un seul outil, on installe Officine et on ne garde que son icône.
- **La page d’un outil** est `https://officious-labs.github.io/<outil>/`, servie par l’appli dans cet ordre :
  - en ligne si le réseau répond en 4 s, et elle est alors gardée en copie ;
  - sinon la dernière copie ;
  - sinon celle embarquée à la fabrication.
- **Pont `Compagnon`** : voir l’en-tête de `MainActivity.java`. Le fichier choisi dans l’appli OneDrive est mémorisé par outil (`uri:<outil>`).
- **Partage et raccourci** (1.1, pour Tâches) : l'activité qui déclare dans le manifeste le menu Partager (`SEND`) et l'action `io.github.officiouslabs.compagnon.NOTE` reçoit le texte ou le fichier ; la page les reçoit par `compagnonTexte(t)` et `compagnonFichier({name, size, type})`. Le fichier reste dans le cache jusqu'à ce que la page le range (pièces jointes : `keepFile`, `spUpload`, `sendToOneDrive`…, fournisseur `Partage`).
- **Fichiers dans les pages** (1.1) : un `<input type="file">` ouvre le sélecteur d'Android (`onShowFileChooser`) ; « Télécharger une copie » passe par `saveAs` (fenêtre « Enregistrer sous »), la WebView ignorant les téléchargements.
- **Fabrication** : GitHub Actions (`.github/workflows/android.yml`) à chaque changement de `android/**`, ou à la main (Actions → Compagnon Android → Run workflow). Publication dans la version « android » de ce dépôt (`officious.apk`, `version.txt`), **depuis `main` seulement** : une autre branche ne fait que compiler (utile pour vérifier une modification avant de la publier).
- **Secrets** : `KEYSTORE_B64` et `KEYSTORE_PASS` (clé `compagnon`). Sans eux, la fabrication échoue volontairement.
- **Signature Chrome** : tout ce qui va à OneDrive se présente comme un Chrome ordinaire (`OneDriveWeb.agent`, `ConnexionActivity`).
