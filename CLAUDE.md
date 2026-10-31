# officious-labs.github.io — consignes pour Claude

Pages publiées de la suite Officine (une par outil, déposées par `livrer.sh` de chaque outil : ne pas les modifier à la main) et Compagnon Android (`android/`, fabriqué par le flux `.github/workflows/android.yml` à chaque modification du code).

## Travailler avec François

- **Langue** : toujours en français, en le tutoyant. Prose directe, sans enthousiasme de façade ; les énumérations en listes.
- **Typographie française** soignée dans tout ce qui s'affiche : apostrophe ’, guillemets « », espaces insécables avant : ; ? ! et dans les nombres, « 1re », « 2e ».
- **Hypothèses explicites** : si une demande est ambiguë, dire l'hypothèse retenue plutôt qu'inventer en silence. Une décision coûteuse à défaire se pose en question avant de coder.
- **Chiffres exacts** partout.
- Une demande nouvelle qui contredit une ancienne : remettre l'ancienne en question, ne pas empiler les deux.

## Règles de la suite Officine (by Officious Labs)

- Les outils sont des pages HTML autonomes, assemblées : `python3 socle/outils/construire.py`. On ne modifie jamais le fichier assemblé à la main.
- **Socle** (dépôt `officious-labs/socle`) : versions figées sur des branches `vX.Y.Z`, embarquées dans chaque outil par `python3 socle/outils/socle.py vX.Y.Z [--depuis ../socle]`. On ne touche jamais au dossier `socle/` d'un outil : la correction se fait dans le socle, puis on monte de version.
- **Tests** : `python3 socle/outils/tester.py` (Playwright, en parallèle). Tout doit passer avant de livrer. Quelques tests sont capricieux sous charge (`test_volant`, `test_aller_retour`, `test_ergonomie`) : relancer seul avant de conclure.
- **Livrer** : `sh socle/outils/livrer.sh "Outil X.Y : …"` (assemble, teste, commit, push, publie dans `officious-labs.github.io/<appli>/index.html`). Avec la variable `SIGNE` pour les lignes d'attribution.
- Chaque version : une entrée dans `NOUVEAUTES` (dates **strictement décroissantes**, sinon les notes reviennent à chaque ouverture), une entrée dans `docs/journal.md` (décisions et pourquoi), la feuille de route à jour.
- **Noms interdits** dans les pages publiées et les commits : la liste `interdits` de `appli.json`. Aucun vrai prénom dans un commit ni une page publiée.
- **Accord explicite de François** avant tout `push --force`, réécriture d'historique ou déplacement d'une branche `vX.Y.Z`.
- **Compagnon Android** (`officious-labs.github.io/android/`) : toute modification du code déclenche une fabrication (numérotée 12, 13…, c'est aussi le numéro de version affiché). Un `.md` n'en déclenche pas.
- Ce que François voit : la page **publiée** (`officious-labs.github.io/<appli>/index.html`), jamais la copie de travail.
- Contraintes de François : OneDrive seulement pendant ses heures de travail (9 h – 18 h 30, jours ouvrés) ; son PC de bureau ne parle pas à GitHub.
- **Interface** : `socle/docs/charte-interface.md` (dix principes, game feel). Décisions en attente ou validées : `socle/docs/propositions-2026-10-31.md`.
- Les docs de chaque dépôt (`docs/`) ont une copie dans le Projet claude.ai « Conservatoire : régie, conventions, etc. » (`officious/`, `planning/`, `outil-taches/`) : la mettre à jour quand on en a l'accès, sinon le signaler à François.
