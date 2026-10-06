# Traitement de dataset_liens_subtils.csv

Depuis la racine du projet, avec les dépendances, la clé `.env` et l’index existants :

```bash
.venv/bin/python traitement_csv/traiter.py --input dataset_liens_subtils.csv
```

Le script utilise le moteur commun à la CLI : RAG, `gpt-4.1-mini`, 20 voisins récupérés et 5 candidats maximum. Il infère uniquement à partir de `description marchandise` (ou `marchandise`) et remplit la première colonne avec le candidat valide de meilleur rang. Les candidats provisoires `needs_info` sont retenus et signalés dans `resultats/audit.json`. Aucun code n’est inventé en cas d’erreur ou d’abstention : une ligne sans candidat empêche l’écriture du résultat final.

Dans un second temps, chaque description est remplacée par le champ `description` exact de `data/processed/h6_2022/candidates.jsonl`. Les autres colonnes, l’ordre des lignes et les codes à six chiffres sont conservés. La sortie par défaut du traitement complet est `traitement_csv/dataset_liens_subtils.csv` ; `--output` permet de choisir un autre chemin.

Le dossier `resultats/` conserve la source dans `original.csv`, la première étape dans `codes_inferes.csv`, les statuts et alternatives dans `audit.json`, ainsi que les traces complètes dans `runs/`. Les prédictions sont mises en cache par description et configuration pour permettre une reprise sans répéter les appels réussis. Les erreurs sont retentées à la prochaine exécution.

Pour reprendre ou reproduire après remplacement du CSV, utiliser la sauvegarde originale :

```bash
.venv/bin/python traitement_csv/traiter.py --input traitement_csv/resultats/original.csv
```

Pour une autre source ou une nouvelle inférence complète, choisir un nouveau dossier avec `--work-dir`. Les inférences font des appels API facturables, comme la CLI du projet. Les codes sont des propositions du modèle ; les demandes de précisions restent visibles dans l’audit.

## Remplacement des descriptions sans appel API

Pour appliquer uniquement la seconde étape aux codes déjà inférés et écraser les descriptions dans le même CSV :

```bash
.venv/bin/python traitement_csv/traiter.py --input traitement_csv/resultats/codes_inferes.csv --descriptions-only
```

Pour écrire également le résultat final :

```bash
.venv/bin/python traitement_csv/traiter.py --input traitement_csv/resultats/codes_inferes.csv --output traitement_csv/dataset_liens_subtils.csv --descriptions-only
```

Ce mode ne charge ni la clé API ni l’index et ne relance aucune inférence. Il utilise le champ `description` exact du catalogue candidat (`--candidates` pour changer son chemin). Tous les codes sont vérifiés avant écriture ; un code vide, inconnu ou invalide laisse les fichiers intacts. Les zéros initiaux, les autres colonnes et l’ordre des lignes sont conservés. Répéter cette étape produit le même contenu.

Les deux CSV présents ont été mis à jour avec ce mode ; `resultats/original.csv` conserve les descriptions originales et `audit.json` conserve les inférences.

Tests hors API :

```bash
.venv/bin/python -m unittest discover -s traitement_csv -p 'test_*.py' -v
```
