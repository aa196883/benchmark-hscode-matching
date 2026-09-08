# Benchmark de correspondance produit → HS code

Prototype Python pour proposer les **N codes HS les plus pertinents** à partir d’un nom de marchandise ou d’une description, puis comparer plusieurs approches sur les mêmes exemples. La priorité est la précision des résultats et la rapidité d’expérimentation.

**État : cadrage uniquement.** L’application, les données et les modèles restent à implémenter. Les choix ci-dessous constituent la direction initiale du projet.

## Périmètre initial

- Entrées en français et en anglais ; description libre, complétée si possible par la matière, l’usage et l’état du produit.
- Cible : HS international à **6 chiffres**, édition **2022**, explicitement associée à chaque jeu de données et résultat. Les nomenclatures nationales ou régionales seront des extensions distinctes.
- Sortie : candidats ordonnés, libellés du référentiel, scores propres à l’approche, justification si disponible et informations manquantes. Une description insuffisante peut conduire à une abstention.
- CLI pour rechercher, comparer et évaluer ; GUI Flask avec templates Jinja pour saisir une description et afficher les résultats côte à côte.
- OpenAI comme premier fournisseur pour les approches LLM et embeddings ; clé via `OPENAI_API_KEY`, modèles et paramètres configurables.

## Approches à comparer

| Approche | Principe |
| --- | --- |
| Lexicale | Baseline TF-IDF ou BM25 sur les descriptions HS contextualisées. |
| Embeddings | Vectoriser les descriptions HS et la requête, puis classer par similarité cosinus. |
| LLM direct | Demander les N codes au LLM, sans lui fournir de documents, puis contrôler leur existence dans l’édition choisie. |
| RAG | Rechercher K candidats, fournir leurs descriptions et leur contexte hiérarchique au LLM, puis lui faire sélectionner et ordonner au plus N codes parmi ces candidats. |

Les approches partagent un contrat `predict(query, top_k, context) -> Prediction`. Un registre permet de sélectionner une ou plusieurs approches par configuration. `Prediction` contient les candidats, le statut, les informations manquantes et les métadonnées d’exécution ; les erreurs d’un modèle restent visibles sans bloquer toute la comparaison. Un score de similarité ou un score fourni par un LLM n’est pas une probabilité de justesse comparable entre modèles.

## Organisation prévue

Un seul package Python, appelé directement par la CLI et Flask ; pas de service intermédiaire ni de frontend séparé.

```text
hs_matching/
  catalog.py       # import, hiérarchie et validation des codes
  approaches/      # contrat commun, registre et adaptateurs
  experiments.py   # préparation, évaluation et sauvegarde des runs
  cli.py
  web.py
  templates/
data/              # sources brutes, catalogue normalisé, jeux annotés
artifacts/         # embeddings, index et éventuels modèles entraînés
runs/              # configurations, prédictions et métriques
```

Commencer avec des fichiers JSONL/CSV, des matrices NumPy et une recherche exacte en mémoire. Ajouter SQLite ou un index spécialisé lorsqu’un besoin mesuré le justifie. Séparer la préparation des artefacts de l’inférence ; une approche entraînable pourra ajouter une étape `fit` sans changer le contrat de prédiction.

## Données et évaluation

Conserver une **table avec liens parent-enfant**, qui permet de reconstruire l’arbre sans base graphe. Les codes sont des chaînes pour préserver les zéros initiaux. Pour la recherche, associer chaque libellé à ses ancêtres : « autres » seul est peu informatif. Le [référentiel UN Comtrade HS 2022](https://comtradeapi.un.org/files/v1/app/reference/H6.json) fournit déjà codes, descriptions et parents ; les notes et règles demandent un enrichissement séparé.

Comparer les méthodes sur un jeu annoté indépendant : réussite top-1/top-3/top-5, MRR, codes invalides, abstentions, latence et coût. Pour le RAG, mesurer aussi la présence du bon code dans les K candidats avant génération. Conserver les versions des données, modèles, prompts, paramètres et résultats bruts de chaque exécution.

La préparation des données, l’entraînement éventuel et leurs artefacts font partie du projet. Les détails des sources, du schéma et de l’ordre d’implémentation sont dans [SUITE.md](SUITE.md).
