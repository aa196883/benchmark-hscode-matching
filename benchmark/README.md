# Benchmark MLflow

Une expérience **`hs-matching/<dataset>`** regroupe les versions du dataset et les runs de toutes les approches. Le benchmark réutilise le moteur `hs_matching`, mais sa CLI, ses dépendances de suivi et son stockage restent dans `benchmark/`.

## Installation et CLI

Depuis la racine, dans le venv du projet :

```bash
python -m pip install -r benchmark/requirements.txt
python benchmark/benchmark.py --help
python benchmark/benchmark.py datasets
python benchmark/benchmark.py import-dataset --dataset hscodecomp
python benchmark/benchmark.py run --dataset hscodecomp --approach llm_direct --limit 3
python benchmark/benchmark.py ui
```

L’interface MLflow est disponible sur **http://127.0.0.1:5001**. Aucune commande Make n’est nécessaire. Depuis `benchmark/`, les commandes sont identiques en utilisant `python benchmark.py ...` ; les chemins par défaut restent ancrés au projet. `python -m benchmark ...` fonctionne depuis la racine.

Le stockage par défaut est `benchmark/mlflow.db` (SQLite) et `benchmark/mlartifacts/`. `--tracking-uri` ou `MLFLOW_TRACKING_URI` permet de choisir un autre backend SQL ou un serveur MLflow. Un FileStore `file://` ne prend pas en charge les Evaluation Datasets. `.env` est chargé depuis la racine, sauf `--env-file` ; les variables d’environnement restent prioritaires. L’option `ui --port` change le port local.

## Plusieurs datasets, une expérience par dataset

Chaque dataset local possède un dossier `benchmark/datasets/<nom>/` contenant un CSV et un `manifest.json` versionné :

```json
{
  "schema_version": 1,
  "name": "hscodecomp",
  "csv": "data.csv",
  "edition": "2022",
  "language": "en"
}
```

Les CSV restent ignorés par Git. Pour préparer le dataset HSCodeComp depuis la source locale :

```bash
python benchmark/prepare_hscodecomp.py \
  benchmark/test_data.jsonl benchmark/datasets/hscodecomp/data.csv
```

L’extraction conserve le titre du produit et les six premiers chiffres de son annotation HS. Les anciens CSV locaux ne sont pas supprimés. Pour ajouter un dataset, créer un autre dossier et son manifest, puis l’importer par nom, dossier ou chemin CSV avec manifest adjacent :

```bash
python benchmark/benchmark.py import-dataset --dataset mon_dataset
python benchmark/benchmark.py run --dataset mon_dataset --approach rag --limit 3
```

Format CSV : exactement deux colonnes, code HS à six chiffres puis description anglaise non vide ; séparateur `;` ou `,`, UTF-8 avec ou sans BOM, avec ou sans en-tête (`HS code;description`). Les zéros initiaux, l’ordre et les doublons sont conservés. La validation porte sur **tout le CSV**, même avec `--limit`, sans appel API.

L’import sauvegarde les entrées et annotations dans **MLflow Evaluation Datasets**, associé à l’expérience du dataset. Le manifest et les empreintes sont stockés avec cette version. Un import identique réutilise la version existante ; changer le CSV ou le manifest crée une nouvelle version. La CLI ne modifie pas les versions déjà importées et contrôle leur intégrité à la lecture. Un identifiant `row_id` préserve les doublons et l’ordre ; seule la description est transmise au modèle.

Un `run` sans `--dataset-version` importe automatiquement le CSV local ou réutilise sa version. Pour reproduire une version, même après modification ou suppression des fichiers locaux :

```bash
python benchmark/benchmark.py run --dataset hscodecomp --dataset-version 1 \
  --approach llm_direct --model gpt-4.1-mini --limit 3
```

L’identité de l’expérience dépend du nom du dataset, jamais de l’approche ou d’une variable `MLFLOW_EXPERIMENT_NAME`. Les versions sont gérées par la CLI (v1, v2…), sans supposer un service Databricks. Effectuer les imports d’un même dataset successivement.

## Évaluation

```bash
python benchmark/benchmark.py run --dataset hscodecomp --approach llm_direct --model qwen3 --limit 3
python benchmark/benchmark.py run --dataset hscodecomp --approach embeddings --top-k 5 --limit 3
python benchmark/benchmark.py run --dataset hscodecomp --approach rag \
  --model gpt-4.1-mini --retrieval-k 20 --top-k 5 --offset 3 --limit 3
```

`--approach` et `--dataset` sont obligatoires. Une commande crée un run, pour une approche sur un dataset. `--limit N` borne le nombre de descriptions inférées ; `--offset N` ignore les N premières lignes. Sans limite, toutes les lignes restantes sont traitées. La sélection est déterministe et archivée ; aucun juge LLM supplémentaire n’est appelé.

Le catalogue préparé est nécessaire pour toutes les approches, l’index précalculé pour embeddings/RAG. Options : `--catalog`, `--index`, `--timeout`, `--max-output-tokens`, `--temperature`, `--reasoning-effort`, `--run-name`, `--runs-dir`. Le modèle LLM suit `--model`, puis `OPENAI_MODEL`, puis `gpt-4.1-mini`. Embeddings impose le modèle du manifeste d’index ; ne pas passer `--model`. Le RAG exige `--retrieval-k >= --top-k`.

Qwen utilise l’alias `qwen3`, `LOCAL_QWEN_KEY` et `QWEN_BASE_URL`, avec tunnel déjà ouvert. Son RAG utilise encore OpenAI pour la vectorisation. Les inférences sont facturables selon le fournisseur ; ni l’import ni l’interface ni les métriques ne consomment de tokens modèle.

### Ce que contient un run

- Paramètres : approche/modèle, version et ID du dataset, empreintes, sélection, catalogue et paramètres d’inférence.
- Dataset : lien MLflow Input vers la sélection, CSV complet normalisé, manifest et lignes sélectionnées en artifacts. Le dataset complet reste également disponible dans Evaluation Datasets.
- Résultats : `evaluation/results.jsonl` sauvegardé localement et dans MLflow après chaque ligne, puis `results.json` et `table.json` pour consulter les prédictions. Les sorties gardent les statuts, candidats, scores, questions et erreurs, sans les réponses brutes du fournisseur.
- Reproductibilité : sources Python du benchmark/moteur et empreintes, révision Git, manifeste de l’index si applicable. Les clés `.env` ne sont jamais archivées.

Les métriques sont calculées sur les résultats présents, avec un historique après chaque ligne :

| Métrique | Sens |
| --- | --- |
| `chapter_accuracy`, `heading_accuracy`, `hs6_accuracy` | Fraction ayant au moins un candidat correct à 2, 4 ou 6 chiffres, parmi tous les candidats `ok`/`needs_info`. Erreurs et abstentions restent au dénominateur. |
| `mean_response_time` | Temps moyen en secondes de prédiction, retrieval inclus, hors initialisation et suivi MLflow. |
| `mean_input_tokens`, `mean_output_tokens`, `mean_total_tokens` | Moyennes des compteurs connus uniquement ; RAG inclut la vectorisation de la requête, pas le précalcul. |
| `*_tokens_measured_rows`, `sum_*_tokens` | Nombre de mesures disponibles et somme des usages connus. Une moyenne inconnue est absente, pas remplacée par zéro. |
| `evaluated_rows`, `*_count`, `*_rate` | Nombre de lignes terminées et effectifs/taux par statut : `ok`, `needs_info`, `abstained`, `error`. |

Le tag `complete` indique si la sélection demandée est terminée ; `full_dataset` distingue un sous-ensemble du dataset entier. Comparer des runs de même version, sélection et `top_k`. L’ancien mode `--process-runs` et son rapport Markdown sont remplacés par les comparaisons MLflow ; les anciens fichiers de résultats restent intacts, sans migration automatique.

Les appels sont séquentiels. Une erreur de prédiction est enregistrée et les lignes suivantes continuent. Une interruption conserve les lignes terminées dans MLflow et `benchmark/runs/<run_id>/`, sans reprise automatique. Statuts MLflow : `FINISHED` en succès, `FAILED` en erreur, `KILLED` sur interruption clavier. Codes CLI : `0` succès, `1` erreurs de prédiction, `2` configuration/exécution/suivi, `130` interruption. Une défaillance du stockage MLflow arrête l’évaluation ; les checkpoints locaux déjà écrits restent disponibles.

## Code et tests ciblés

`benchmark.py`/`__main__.py` exposent `cli.py`. `datasets.py` gère validation et versions ; `tracking.py` configure le stockage ; `runtime.py` adapte les approches existantes ; `runner.py` exécute les lignes ; `metrics.py` calcule les mesures sans API.

```bash
python -m unittest benchmark.test_benchmark -v
```

Les tests utilisent de petits CSV temporaires, un véritable backend SQLite MLflow et des fournisseurs simulés. Ils ne traitent pas le dataset complet et ne font aucun appel API payant.

Référence technique : [Evaluation Datasets MLflow](https://mlflow.org/docs/latest/genai/datasets/sdk-guide/).
