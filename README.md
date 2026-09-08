# Benchmark de correspondance produit → HS code

Prototype Python pour proposer les **N codes HS les plus pertinents** à partir d’un nom de marchandise ou d’une description, puis comparer plusieurs approches sur les mêmes exemples. La priorité est la précision des résultats et la rapidité d’expérimentation.

**État : prétraitement H6, approche LLM directe et première CLI disponibles.** Les autres approches, l’évaluation et la GUI restent à implémenter. Les choix ci-dessous constituent la direction initiale du projet.

## Périmètre initial

- Entrées en anglais ; description libre.
- Cible : HS international à **6 chiffres**, édition **2022**.
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

Comparer les méthodes sur un jeu annoté indépendant : réussite top-1/top-3/top-5, MRR, codes invalides, abstentions, latence et coût. Conserver les versions des données, modèles, prompts, paramètres et résultats bruts de chaque exécution.

La préparation des données, l’entraînement éventuel et leurs artefacts font partie du projet. Les détails des sources, du schéma et de l’ordre d’implémentation sont dans [SUITE.md](SUITE.md).

## Prétraitement H6

Depuis la racine, avec Python 3 sans dépendance externe :

```bash
python3 scripts/preprocess_h6.py
python3 -m unittest discover -s tests
```

`H6.json` reste intact. Les exports dans `data/processed/h6_2022/` sont :

- `catalog.jsonl` : 6 939 nœuds avec parents, champs source intacts, codes ancêtres et descriptions contextualisées ;
- `candidates.jsonl` : 5 612 candidats à six chiffres ;
- `excluded.json` : ligne `TOTAL` écartée et motif ;
- `manifest.json` : provenance, empreintes SHA-256, transformations, effectifs et état des contrôles.

Les codes restent des chaînes et les parents des chapitres deviennent `null`. Le nettoyage retire le préfixe exact `code - ` et normalise les espaces sans changer la casse ni la ponctuation. Les codes statistiques `99`, `9999` et `999999` restent dans la hiérarchie avec `is_special=true`, mais sont exclus des candidats.

Options : `--input`, `--output-dir`, `--retrieved-at YYYY-MM-DD`. Une nouvelle exécution remplace les exports. La date réelle de récupération reste inconnue par défaut et se distingue de la date de traitement.

Les doublons, formats invalides, parents incohérents et feuilles incohérentes bloquent l’export. La comparaison exhaustive code par code avec la nomenclature OMD et les conditions de réutilisation restent à vérifier, comme indiqué dans le manifeste. Il s’agit d’un catalogue Comtrade filtré, sans certification de conformité OMD.

## Approche LLM directe et CLI

Python **3.10+**, sans dépendance d’exécution. Depuis la racine :

1. Remplacer `YOUR_OPENAI_API_KEY_HERE` dans `.env` par la clé OpenAI. Le fichier est ignoré par Git ; `.env.example` sert de modèle.
2. Lancer une prédiction avec une description anglaise :

```bash
python3 -m hs_matching predict "Live purebred breeding horses" --top-k 3
python3 -m hs_matching predict "Cotton knitted T-shirt" --model gpt-4.1-mini --top-k 5 --json
python3 -m hs_matching approaches
```

`--model` est répétable pour comparer plusieurs modèles dans un même run. Sans cette option, `OPENAI_MODEL` dans `.env` est utilisé, puis `gpt-4.1-mini` par défaut. Indiquer un identifiant de snapshot pour figer un modèle lors d’un benchmark. Le modèle choisi doit être accessible au compte et accepter Responses et Structured Outputs.

L’adaptateur appelle directement l’[API Responses avec sorties structurées](https://developers.openai.com/api/docs/guides/structured-outputs), via la bibliothèque standard Python. Les paramètres `--max-output-tokens`, `--timeout`, `--temperature` et `--reasoning-effort` sont configurables. Température et effort de raisonnement sont omis par défaut : leurs valeurs et leur compatibilité dépendent du modèle. Aucune substitution de modèle ni nouvelle tentative automatique n’est effectuée en cas d’erreur.

Autres options : `--catalog`, `--runs-dir`, `--env-file`. La description `-` lit stdin. Les chemins sont relatifs au répertoire courant. Le format `.env` accepté est `KEY=value`, avec guillemets optionnels et commentaires sur leur propre ligne, sans interpolation ; les variables déjà définies dans l’environnement sont prioritaires. Installation facultative avec `python3 -m pip install -e .` pour disposer de la commande `hs-matching`.

### Contrat et validation

`approaches/base.py` définit `Candidate`, `Prediction`, `PredictionContext` et le protocole `predict(query, top_k, context) -> Prediction`. `approaches/registry.py` enregistre `llm_direct`. `providers/openai.py` isole le transport et `ModelConfig` ; un fournisseur alternatif peut implémenter `LLMProvider.generate` en renvoyant la même enveloppe de réponse (statut, messages, modèle et usage). `experiments.py` exécute les configurations et sauvegarde les résultats, indépendamment de la CLI.

Le prompt ne contient ni catalogue ni documents. Après la réponse, le moteur contrôle le format exact à six chiffres, l’existence, l’admissibilité et les doublons. Les descriptions affichées viennent du catalogue ; les explications viennent du LLM. Les scores restent `null` avec `score_type="none"`.

Les candidats rejetés sont consignés avec leur rang et leur motif, sans correction des codes. Les candidats valides conservent leur **rang original**, éventuellement discontinu, pour ne pas améliorer artificiellement les métriques. Les propositions au-delà de N sont rejetées. Si toutes les propositions sont invalides, le statut devient `error` ; une réponse partiellement valide conserve son statut et ses rejets explicites. `needs_info` contient des questions et peut contenir des candidats ; `abstained` n’en contient aucun. Un refus du fournisseur donne `abstained`, une réponse tronquée ou mal formée donne `error`.

### Traces et tests

Chaque invocation sauvegarde un JSON dans `runs/` : requête, configurations, prompt et schéma effectifs, empreinte du catalogue, révision Git et empreintes du code, réponse brute, tokens, durée, prédictions et rejets. Les erreurs HTTP conservent le statut HTTP et l’identifiant de requête sans journaliser la clé ni les en-têtes d’authentification. Les runs sont ignorés par Git. Aucun coût n’est estimé à ce stade.

Avec `--json`, stdout contient le run complet ; le chemin du fichier est écrit sur stderr. Codes de sortie : `0` pour une exécution sans erreur (abstention comprise), `1` si au moins un modèle échoue, `2` pour une erreur de configuration CLI ou de fichier. Un échec de modèle n’empêche pas l’exécution des suivants.

```bash
python3 -m unittest discover -s tests -v
```

Les tests simulent le fournisseur et le transport HTTP ; ils ne consomment aucun token. L’intégration réelle et la qualité du classement restent à mesurer après ajout de la clé.
