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
- **Pont `Compagnon`** (07/11/2026) : classe commune `Pont.java`, utilisée par la vue normale et par la vue invisible de la synchro du matin ; son en-tête donne le contrat. `MainActivity` ne fait plus que les méthodes qui demandent l’écran. Le fichier choisi dans l’appli OneDrive est mémorisé par outil (`uri:<outil>`).
- **Partage et raccourci** (1.1, pour Tâches) : l'activité qui déclare dans le manifeste le menu Partager (`SEND`) et l'action `io.github.officiouslabs.compagnon.NOTE` reçoit le texte ou le fichier ; la page les reçoit par `compagnonTexte(t)` et `compagnonFichier({name, size, type})`. Le fichier reste dans le cache jusqu'à ce que la page le range (pièces jointes : `keepFile`, `spUpload`, `sendToOneDrive`…, fournisseur `Partage`).
- **Fichiers dans les pages** (1.1) : un `<input type="file">` ouvre le sélecteur d'Android (`onShowFileChooser`) ; « Télécharger une copie » passe par `saveAs` (fenêtre « Enregistrer sous »), la WebView ignorant les téléchargements.
- **Fabrication** : GitHub Actions (`.github/workflows/android.yml`) à chaque changement de `android/**`, ou à la main (Actions → Compagnon Android → Run workflow). Publication dans la version « android » de ce dépôt (`officious.apk`, `version.txt`), **depuis `main` seulement** : une autre branche ne fait que compiler (utile pour vérifier une modification avant de la publier).
- **Numéro de version** (07/11/2026, schéma d'Impulsion) : `VERSION` (« 3.0 », à la main) suivi du numéro de fabrication, par exemple 3.0.50. La fabrication seule sert à la mise à jour (`versionCode`, `version.txt`, méthode `version` du pont) ; le numéro complet est donné par la méthode `versionNom`.
- **Secrets** : `KEYSTORE_B64` et `KEYSTORE_PASS` (clé `compagnon`). Sans eux, la fabrication échoue volontairement.
- **Signature Chrome** : tout ce qui va à OneDrive se présente comme un Chrome ordinaire (`OneDriveWeb.agent`, `ConnexionActivity`).

## Synchro du matin (07/11/2026, décision 4 du 31/10)

Les jours ouvrés vers 8 h 55, le Compagnon ouvre chaque outil dans une vue invisible ; la page (socle) relit, fusionne, écrit, dépose les pages et envoie les pièces jointes en attente, puis rend la main. Les outils n’ont rien à faire.

- **Réveil** (`Reveil.java`) : lundi à vendredi à 8 h 55, heure du téléphone. Les jours fériés et les vacances sont filtrés par la page, qui ne touche alors à rien (la notification le dit).
  - Alarme exacte « même en veille » (`setExactAndAllowWhileIdle`), avec `USE_EXACT_ALARM` (Android 13 et plus) et `SCHEDULE_EXACT_ALARM` (jusqu’à Android 12L) : les deux sont accordées à l’installation, sans réglage à toucher. `USE_EXACT_ALARM` n’est réservée aux réveils et agendas que par le règlement du Play Store, où l’appli n’est pas.
  - Pourquoi pas une alarme approximative (`setWindow` 8 h 55 – 9 h 05, sans autorisation) : depuis Android 12, seule une alarme exacte autorise l’appli, en arrière-plan, à lancer le service de premier plan qui fait le travail ; une alarme approximative est en plus repoussée tant que le téléphone dort. Elle ne reste qu’en secours, si Android refuse l’alarme exacte ; le service peut alors être refusé, et la notification le dit.
  - Réarmée après chaque passage, au démarrage du téléphone (`BOOT_COMPLETED`), après une mise à jour (`MajRecue`) et à chaque ouverture d’un outil.
  - Pas de nouvel essai le même jour (pas de réseau, refus d’Android, téléphone éteint à 8 h 55).
- **Service** (`SynchroMatin.java`) : service de premier plan court (type `dataSync`), notification « Synchro du matin — En cours… » pendant le passage, téléphone tenu éveillé (verrou partiel, 31 minutes au plus). Il s’arrête quand tous les outils ont fini, 30 minutes au plus.
- **Vue invisible** : une WebView sans activité, dans le même processus et le même profil que la vue normale (même `localStorage`, même IndexedDB, mêmes cookies de OneDrive). Elle charge la page publiée habituelle de chaque outil affiché (`Outils.java` ; une icône masquée est sautée), l’une après l’autre, servie comme d’habitude (en ligne, sinon copie, sinon embarquée).
  - Même pont que la vue normale pour tout ce qui se fait sans écran : `name`, `local`, `read`, `write`, `spRead`, `spWrite`, `spCreate`, `spList`, `spRecycle`, `spUpload`, `dropKept`, `outils`, `afficherOutil`, `version`, `versionNom`, `checkUpdate`.
  - Les méthodes qui demandent l’écran (`pick`, `create`, `spLogin`, `paste`, `copy`, `haptic`, `print`, `saveAs`, `saveAttachment`, `saveAttachmentAs`, `sendToOneDrive`, `keepFile`, `openAttachment`, `discardFile`, `update`) répondent une erreur `ecran:…`.
  - Sans fenêtre, la page est probablement vue comme cachée (`document.visibilityState` à `hidden`, pas de `requestAnimationFrame`, minuteries ralenties) : en mode synchro, elle ne doit pas en dépendre.
- **Méthodes du pont** :
  - `mode` : `"synchro"` dans la vue invisible, `""` dans la vue normale.
  - `synchroFinie(json)` : bilan de l’outil, `{appli, titre, ok, lu, ecrit, fusion, conflits, page, autres, raison, debut, fin, resume}` ; la vue invisible le garde, se détruit et passe à l’outil suivant. Dans la vue normale : répond ok, sans rien faire.
  - `synchroMatin` : le dernier passage, `{quand, outils: [bilans]}` (`quand` au format ISO, avec le décalage horaire), ou `""`.
- **Délai de garde** par outil : 3 minutes comptées à partir du plus tard des deux instants « page chargée » et « 9 h ce jour-là » (la page attend elle-même 9 h, OneDrive n’étant ouvert qu’aux heures de travail) ; une page qui ne se charge pas en 3 minutes compte aussi. Dépassé : vue détruite, bilan « <Titre> : pas de réponse (délai dépassé) » (`raison` `delai`).
- **Outil ouvert à l’écran** à ce moment-là : il est sauté, bilan « <Titre> : ouvert à l’écran, synchro laissée à l’appli » (`raison` `ouvert`). La page affichée fait déjà ce travail (elle relit à chaque retour et après chaque modification) ; deux copies de la même page qui écrivent en même temps le même stockage local risqueraient de se marcher dessus (même pièce jointe envoyée deux fois, état local écrasé), ce que l’ETag de OneDrive ne protège pas. Un outil seulement en arrière-plan (applis récentes) est synchronisé normalement : sa page est au repos et relit en revenant.
- **Bilan** : une notification « Synchro du matin, 9 h 02 » (canal « Synchro du matin », silencieux), une ligne par outil (le `resume` de chaque page), qui ouvre Tâches au toucher ; le même bilan est gardé pour `synchroMatin`.
- **Autorisations** : à la première ouverture d’un outil sur Android 13 et plus, Android demande d’autoriser les notifications (une fois ; refusé, la synchro se fait quand même, sans bilan visible). Tout le reste est accordé à l’installation. Sur certains téléphones (Samsung, Xiaomi…), l’économie de batterie peut bloquer le réveil : régler alors Officine sur « Non restreinte » dans la batterie de l’appli.
- **Vérifier** sans ouvrir les applis : la notification « Synchro du matin » vers 9 h, un jour ouvré, avec une ligne par outil ; au PC, le fichier de chaque outil a été modifié vers 9 h par le téléphone (historique des versions de OneDrive) et la copie de la page pour le PC est à jour.
