# Benchmark description de marchandise → code HS

Projet Python pour explorer et comparer des approches d’inférence de codes HS à partir de descriptions de marchandises en anglais. Le référentiel est le **HS international 2022 à six chiffres**.

| Approche | Fonctionnement |
| --- | --- |
| `llm_direct` | Le LLM propose des codes à partir de ses connaissances ; le catalogue valide les propositions. |
| `embeddings` | Recherche cosinus sur les descriptions contextualisées du catalogue. |
| `rag` | Récupération de K voisins, puis sélection et classement par un LLM parmi ces candidats. |

Les résultats comprennent des candidats ordonnés, leurs libellés, des explications ou scores selon l’approche, et éventuellement une demande de précisions ; le RAG vise N candidats pour une marchandise identifiée, en complétant les correspondances par des alternatives proches qui nécessiteraient une petite modification de la description, explicitée avec un rating plus faible. Il peut en retourner moins si les voisins récupérés ne suffisent pas. Pour une description imprécise, il conserve les candidats provisoires compatibles et utilise `needs_info` si des précisions sont nécessaires. Les scores cosinus ne sont pas des probabilités de justesse.

## Installation

Depuis la racine du dépôt, avec **Python 3.10+** :

```bash
python3 -m venv .venv
source .venv/bin/activate
python -m pip install -r requirements.txt
```

Les commandes suivantes supposent le venv activé. Dépendances : Flask `>=3.1,<4`, NumPy `>=1.24,<3` ; Playwright `>=1.50,<2` est facultatif pour les captures. Les versions ne sont pas verrouillées. Les métadonnées et extras du package sont dans `pyproject.toml`.

Créer `.env` à partir de `.env.example` s’il n’existe pas, puis renseigner `OPENAI_API_KEY`. Ne pas écraser une configuration existante. Les variables déjà présentes dans l’environnement ont priorité. Le mode démo fonctionne sans clé ni données :

```bash
python -m hs_matching.web --demo
```

Ouvrir http://127.0.0.1:5000. Les résultats et temps de la démo sont simulés à partir d’une fixture de T-shirts.

## Préparer le catalogue et l’index

Placer le référentiel Comtrade H6 brut dans `data/H6.json`, puis lancer :

```bash
python scripts/preprocess_h6.py --input data/H6.json
```

Le script conserve la source et produit dans `data/processed/h6_2022/` : `catalog.jsonl` (hiérarchie), `candidates.jsonl` (codes admissibles à six chiffres), `excluded.json` et `manifest.json` (provenance, empreintes et contrôles). Les exports existants sont remplacés. Les codes conservent leurs zéros initiaux ; les descriptions contextualisées incluent les ancêtres. Le catalogue filtré Comtrade n’est pas certifié par une comparaison exhaustive avec la nomenclature OMD.

Pour embeddings et RAG, construire explicitement l’index **avec des appels API facturables** :

```bash
python scripts/build_embeddings.py \
  --input data/processed/h6_2022/candidates.jsonl \
  --output-dir artifacts/embeddings/h6_2022 \
  --model text-embedding-3-small
```

Le dossier de sortie doit être nouveau. Il contient `vectors.jsonl` et `manifest.json` ; aucun index partiel n’est publié. Options utiles : `--batch-size` (64 par défaut), `--dimensions`, `--timeout`. Il n’y a pas de reprise des lots : relancer un précalcul interrompu refait les appels. L’inférence ne construit jamais l’index automatiquement ; une modification des codes ou descriptions contextualisées exige sa reconstruction.

## Prédire et comparer

```bash
python -m hs_matching approaches
python -m hs_matching predict "Cotton knitted T-shirt" --top-k 5 --json
python -m hs_matching predict "Cotton knitted T-shirt" --approach embeddings --top-k 5
python -m hs_matching predict "Cotton knitted T-shirt" \
  --approach rag --retrieval-k 20 --top-k 5 --model gpt-4.1-mini
python -m hs_matching.web
```

Le modèle LLM est `--model`, sinon `OPENAI_MODEL`, sinon `gpt-4.1-mini`. Dans la CLI d’inférence, répéter `--model` permet de comparer plusieurs modèles. Pour embeddings, ne pas passer `--model` : le modèle et les dimensions viennent du manifeste de l’index. Le RAG exige `--retrieval-k >= --top-k`.

Options communes utiles : `--catalog`, `--index`, `--env-file`, `--runs-dir`, `--timeout`, `--max-output-tokens`, `--temperature`, `--reasoning-effort` (paramètres LLM selon leur compatibilité). Consulter `python -m hs_matching predict --help`. La description `-` lit stdin.

Les runs détaillés sont enregistrés dans `runs/` avec configurations, prompts, réponses, usage, durées et empreintes. `--json` les écrit aussi sur stdout ; le chemin sauvegardé est écrit sur stderr. Les codes rejetés gardent leur rang original dans les traces. Codes de sortie : `0` sans erreur, `1` si une prédiction échoue, `2` pour une erreur de configuration ou de fichier.

La GUI Flask compare successivement les approches sélectionnées, conserve catalogue/index en mémoire et propose un export JSON dans `runs/web/`. Son temps total par approche comprend la préparation des ressources et l’inférence. La génération dans la GUI utilise OpenAI.

### Rapport HTML RAG

Modifier [`scripts/rag_descriptions.csv`](scripts/rag_descriptions.csv) : une colonne `description`, une description anglaise par ligne. Entourer de guillemets les descriptions contenant des virgules. L’ordre et les doublons sont conservés ; les lignes vides sont ignorées.

```bash
.venv/bin/python scripts/render_rag_report.py
```

Ce script indépendant de la CLI ne prend aucun paramètre. Les constantes `MODEL`, `RETRIEVAL_K` et `TOP_K` dans le script valent `gpt-4.1-mini`, `20` et `5`. Il utilise `.env`, le catalogue et l’index existants aux chemins par défaut, puis effectue les appels API pour chaque description.

Le fichier **`artifacts/rag_report.html`** est remplacé à chaque exécution. Il s’ouvre directement dans un navigateur et peut être partagé seul : les styles de l’UI sont intégrés. Il présente la configuration, puis les codes, libellés, ratings et explications de chaque description, ainsi que les demandes de précisions ou erreurs. Le script termine avec le code `1` si une prédiction échoue, tout en produisant le rapport des résultats obtenus. Les chemins sont résolus depuis le dépôt, quel que soit le dossier de lancement.

### Qwen dans les CLI

Avec un tunnel SSH déjà ouvert, configurer `LOCAL_QWEN_KEY` et éventuellement `QWEN_BASE_URL` (défaut : `http://localhost:8000/v1`) :

```bash
python -m hs_matching predict "Cotton knitted T-shirt" --model qwen3 --json
```

L’alias `qwen3` désigne `Qwen/Qwen3-VL-4B-Instruct-FP8`. Le serveur doit accepter Chat Completions avec `response_format=json_schema`. `--reasoning-effort` n’est pas pris en charge. Le direct Qwen ne nécessite pas de clé OpenAI ; le RAG Qwen utilise encore OpenAI pour vectoriser la requête. Les fournisseurs ne retentent pas automatiquement les appels et ne substituent pas de modèle.

## Benchmarker avec MLflow

HSClassify est également disponible : `python benchmark/prepare_hsclassify.py --download`
prépare les lignes anglaises avec codes admissibles du dataset `hsclassify_en` au format CSV habituel.
Voir les [commandes et limites](benchmark/README.md#dataset-hsclassify-lignes-anglaises).

Le split test CROSS Rulings peut être préparé avec
`python benchmark/prepare_cross_rulings.py --download`, puis importé comme
`cross_rulings_test`. Filtrage et limites des annotations sont documentés dans
[benchmark/README.md](benchmark/README.md#dataset-cross-rulings-split-test).

Le benchmark possède sa CLI Python dans `benchmark/`, avec une expérience
`hs-matching/<dataset>` et des versions de datasets sauvegardées dans MLflow.

```bash
python -m pip install -r benchmark/requirements.txt
python benchmark/benchmark.py import-dataset --dataset hscodecomp
python benchmark/benchmark.py run --dataset hscodecomp --approach llm_direct --model gpt-4.1-mini --limit 3
python benchmark/benchmark.py ui
```

Ouvrir http://127.0.0.1:5001 pour comparer qualité du classement, latence et tokens.
`--limit` borne les appels lors des essais. Ajouter un dossier avec manifest et CSV
permet d’ajouter un dataset ; `--dataset-version` rejoue une version sauvegardée.
Le choix `--model` est transmis au moteur ; le modèle résolu figure dans le nom du run et les traces MLflow.

Le stockage local est dans `benchmark/mlflow.db` et `benchmark/mlartifacts/`.

Voir [benchmark/README.md](benchmark/README.md) pour les options, les formats,
les métriques et la gestion des erreurs/interruption.

## Structure et développement

```text
hs_matching/     Moteur, approches, fournisseurs, index, CLI et GUI Flask
scripts/         Prétraitement H6, précalcul embeddings, captures navigateur
benchmark/       CLI autonome, datasets versionnés et suivi MLflow, tests
traitement_csv/  Traitement RAG d’un CSV et remplacement des libellés, tests
tests/           Tests du moteur, des fournisseurs et de la GUI
data/            Sources et catalogue préparé (ignorés par Git)
artifacts/       Index et captures (ignorés par Git)
runs/            Traces d’inférence et comparaisons (ignorées par Git)
sandbox_java/    Espace séparé, à explorer/modifier seulement sur demande explicite
```

Les consignes de contribution, versions locales et commandes de tests ciblées sont dans [AGENTS.md](AGENTS.md). Le traitement CSV avec checkpoints est documenté dans [traitement_csv/README.md](traitement_csv/README.md). Les données, artifacts et bases MLflow de `benchmark/` restent locaux et ignorés par Git ; les manifests sont versionnés.

Les tests utilisent `unittest` et des fournisseurs simulés, sans appels API. Exécuter seulement ceux concernés par le changement, par exemple :

```bash
python -m unittest discover -s tests -p 'test_rag.py' -v
python -m unittest benchmark.test_benchmark -v
```

Les tests de prétraitement exigent la source H6 ; ceux du LLM direct et certains tests Qwen exigent le catalogue préparé. Pour vérifier un changement visuel avec la démo :

```bash
python -m pip install -r requirements-dev.txt
PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright python -m playwright install chromium
PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright python scripts/snapshot_web.py
```

Les quatre captures desktop/mobile sont écrites dans `artifacts/screenshots/`.

Pour une marchandise identifiée avec une correspondance étayée, le RAG vise `top_k` en ajoutant les alternatives récupérées les plus proches, applicables après une petite modification explicitée de la description, avec un rating inférieur plafonné à 3. Ces alternatives seules ne déclenchent pas `needs_info`. Le nombre peut rester inférieur à `top_k` si le pool ne contient pas assez d’alternatives proches ; les marchandises sans rapport restent exclues.

Le RAG (`rag_v4`) fournit un `rating` requis par candidat généré, nombre de 1 à 5 (décimales admises), indépendant du score cosinus et des autres candidats. Le classement suit les ratings décroissants, sans renumérotation après rejet. Les explications précisent la confiance et les conditions manquantes. CLI et UI affichent des étoiles et la valeur exacte ; `rating=null` pour les approches sans rating. Un refus fournisseur ne fabrique ni candidat ni rating.
