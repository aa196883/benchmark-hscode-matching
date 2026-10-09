# Benchmark description de marchandise → code HS

Projet Python pour explorer et comparer des approches d’inférence de codes HS à partir de descriptions de marchandises en anglais. Le référentiel est le **HS international 2022 à six chiffres**.

| Approche | Fonctionnement |
| --- | --- |
| `llm_direct` | Le LLM propose des codes à partir de ses connaissances ; le catalogue valide les propositions. |
| `embeddings` | Recherche cosinus sur les descriptions contextualisées du catalogue. |
| `rag` | Récupération de K voisins, puis sélection et classement par un LLM parmi ces candidats. |

Les résultats comprennent des candidats ordonnés, leurs libellés, des explications ou scores selon l’approche, et éventuellement une demande de précisions ou une abstention. Les scores cosinus ne sont pas des probabilités de justesse.

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

### Qwen dans les CLI

Avec un tunnel SSH déjà ouvert, configurer `LOCAL_QWEN_KEY` et éventuellement `QWEN_BASE_URL` (défaut : `http://localhost:8000/v1`) :

```bash
python -m hs_matching predict "Cotton knitted T-shirt" --model qwen3 --json
```

L’alias `qwen3` désigne `Qwen/Qwen3-VL-4B-Instruct-FP8`. Le serveur doit accepter Chat Completions avec `response_format=json_schema`. `--reasoning-effort` n’est pas pris en charge. Le direct Qwen ne nécessite pas de clé OpenAI ; le RAG Qwen utilise encore OpenAI pour vectoriser la requête. Les fournisseurs ne retentent pas automatiquement les appels et ne substituent pas de modèle.

## Benchmarker des datasets

Pour préparer le fichier HSCodeComp local :

```bash
python benchmark/prepare_hscodecomp.py \
  benchmark/test_data.jsonl benchmark/hscodecomp_hs6_v1.csv
```

Cette extraction conserve uniquement `product_name` et les six premiers chiffres de `hs_code` ; elle simplifie les annotations du dataset original. Elle produit un CSV UTF-8 `HS code;description`, en conservant l’ordre et les doublons. Une sortie existante est remplacée.

```bash
python benchmark/benchmark.py --approach llm_direct --model qwen3
python benchmark/benchmark.py --datasets benchmark/hscodecomp_hs6_v1.csv \
  --approach embeddings --top-k 5
python benchmark/benchmark.py --datasets benchmark/hscodecomp_hs6_v1.csv \
  --approach rag --model gpt-4.1-mini --top-k 5 --retrieval-k 20
```

`--datasets` accepte plusieurs CSV ; par défaut : `benchmark/hscodecomp_hs6_v1.csv`. Chaque CSV doit contenir exactement deux colonnes : code à six chiffres puis description non vide, avec séparateur `;` ou `,`, avec ou sans en-tête. Les fichiers sont tous validés avant les appels. Une invocation utilise une approche et un modèle ; pour embeddings, omettre `--model`.

La collecte écrit un nouveau JSON par dataset dans `benchmark/runs/`, sauvegardé après chaque résultat. Il contient vérité terrain, réponse validée, durée et tokens, sans les métadonnées brutes d’inférence. La durée exclut initialisation et écriture ; les tokens RAG incluent la vectorisation de la requête et le LLM, sans le précalcul. Un usage inconnu vaut `null`. La progression va sur stderr ; une erreur de prédiction n’arrête pas les lignes suivantes. Une interruption conserve les résultats présents, sans reprise automatique. Codes de sortie : `0` succès, `1` erreurs de prédiction, `2` erreur de configuration/exécution, `130` interruption clavier.

### Analyser les résultats sans API

```bash
python benchmark/benchmark.py --process-runs \
  --runs 'benchmark/runs/*.json' --output benchmark/report.md
```

Le rapport Markdown affiche les correspondances aux niveaux chapitre (2 chiffres), position (4) et sous-position (6), ainsi que les temps et tokens moyens. Une correspondance parmi **tous les candidats** des statuts `ok` ou `needs_info` suffit ; erreurs et abstentions restent au dénominateur. Les tokens absents sont exclus des moyennes concernées.

Les runs sont regroupés uniquement par **modèle et approche**, même si leurs datasets, `top_k` ou autres paramètres diffèrent. Sélectionner séparément les fichiers pour comparer des configurations différentes. Les runs partiels sont signalés et seuls leurs résultats présents sont analysés. Le rapport existant est remplacé après validation.

## Structure et développement

```text
hs_matching/     Moteur, approches, fournisseurs, index, CLI et GUI Flask
scripts/         Prétraitement H6, précalcul embeddings, captures navigateur
benchmark/       Préparation des datasets, collecte et analyse des runs, tests
traitement_csv/  Traitement RAG d’un CSV et remplacement des libellés, tests
tests/           Tests du moteur, des fournisseurs et de la GUI
data/            Sources et catalogue préparé (ignorés par Git)
artifacts/       Index et captures (ignorés par Git)
runs/            Traces d’inférence et comparaisons (ignorées par Git)
sandbox_java/    Espace séparé, à explorer/modifier seulement sur demande explicite
```

Les consignes de contribution, versions locales et commandes de tests ciblées sont dans [AGENTS.md](AGENTS.md). Le traitement CSV avec checkpoints est documenté dans [traitement_csv/README.md](traitement_csv/README.md). Les données et rapports de `benchmark/` restent locaux et ignorés par Git.

Les tests utilisent `unittest` et des fournisseurs simulés, sans appels API. Exécuter seulement ceux concernés par le changement, par exemple :

```bash
python -m unittest discover -s tests -p 'test_rag.py' -v
python -m unittest benchmark.test_process_runs -v
```

Les tests de prétraitement exigent la source H6 ; ceux du LLM direct et certains tests Qwen exigent le catalogue préparé. Pour vérifier un changement visuel avec la démo :

```bash
python -m pip install -r requirements-dev.txt
PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright python -m playwright install chromium
PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright python scripts/snapshot_web.py
```

Les quatre captures desktop/mobile sont écrites dans `artifacts/screenshots/`.
