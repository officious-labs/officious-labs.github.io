# Compagnon Officious

Une seule appli Android pour toute la suite (« Officious » dans les réglages du téléphone), avec une icône par outil.

- **Ajouter un outil** :
  1. Créer une activité de trois lignes (`XxxActivity extends MainActivity`, `appli()` renvoie le nom de l’outil).
  2. La déclarer dans `AndroidManifest.xml` avec son libellé, son icône (`mipmap-anydpi-v26/ic_xxx.xml`) et sa `taskAffinity`.
  3. L’ajouter à `MajRecue.activiteDe`.
- **La page d’un outil** est `https://officious-labs.github.io/<outil>/`, servie par l’appli dans cet ordre :
  - en ligne si le réseau répond en 4 s, et elle est alors gardée en copie ;
  - sinon la dernière copie ;
  - sinon celle embarquée à la fabrication.
- **Pont `Compagnon`** : voir l’en-tête de `MainActivity.java`. Le fichier choisi dans l’appli OneDrive est mémorisé par outil (`uri:<outil>`).
- **Fabrication** : GitHub Actions (`.github/workflows/android.yml`) à chaque changement de `android/**`, ou à la main (Actions → Compagnon Android → Run workflow). Publication dans la version « android » de ce dépôt (`officious.apk`, `version.txt`).
- **Secrets** : `KEYSTORE_B64` et `KEYSTORE_PASS` (clé `compagnon`). Sans eux, la fabrication échoue volontairement.
- **Signature Chrome** : tout ce qui va à OneDrive se présente comme un Chrome ordinaire (`OneDriveWeb.agent`, `ConnexionActivity`).
