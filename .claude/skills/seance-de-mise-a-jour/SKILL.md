---
name: seance-de-mise-a-jour
description: Séance de mise à jour de la suite Officine (Tâches, Saison, socle). À utiliser dès que François dit « séance de mise à jour », « séance du week-end », « on fait la mise à jour », ou envoie une liste de points à faire dans un ou plusieurs outils.
---

# Séance de mise à jour

Suivre la procédure du socle : `docs/seance-de-mise-a-jour.md` du dépôt `officious-labs/socle` (dans une séance qui a plusieurs dépôts : `../socle/docs/seance-de-mise-a-jour.md` ; s'il manque, l'ajouter avec `add_repo`, puis le cloner à côté des outils).

L'essentiel, si le fichier est introuvable :

1. Dépôts : les outils concernés, `officious-labs/socle`, `officious-labs/officious-labs.github.io` (publication). `pip install playwright==1.63.0` si besoin. `git fetch` et regarder les autres branches : une autre séance a pu publier entre-temps.
2. Lire toute la liste, la trier (clair, question, bogue, format de fichier, socle, Compagnon, coûteux et ambigu), puis coder sans demander de validation ; les hypothèses vont au journal et au bilan.
3. Par outil : une version, un test neuf `tests/test_<version>.py`, des captures à 1 280 px et 390 px, `NOUVEAUTES`, journal, feuille de route, `livrer.sh` (tests complets, jamais `SANS_TESTS`, jamais `--force`).
4. Finir par un bilan point par point, les hypothèses, quoi essayer, et le prompt de la séance suivante s'il en faut une.
