# Guide de travail

## Finalité et périmètre

Explorer et benchmarquer des approches permettant d’inférer des codes HS à partir de descriptions de marchandises. Le moteur actuel cible des descriptions anglaises et le HS international 2022 à six chiffres : LLM direct, embeddings et RAG. CLI, interface Flask, collecte sur CSV et analyse des runs sont disponibles.

**Ne pas explorer, lire, rechercher dans ou modifier `sandbox_java/`, sauf demande explicite de l’utilisateur.** Exclure ce dossier des recherches globales, par exemple `rg --files -g '!sandbox_java'` ou `rg -n 'motif' hs_matching scripts benchmark tests traitement_csv`.

## Économie de contexte et autonomie

1. Lire d’abord les fichiers explicitement mentionnés dans le prompt, sous réserve de la restriction sur `sandbox_java/` et de la confidentialité des secrets.
2. Si nécessaire, consulter ensuite `README.md` et la documentation locale du composant concerné.
3. Explorer seulement ensuite les fichiers utiles, avec des recherches ciblées (`rg`, plages de lignes). Utiliser la carte ci-dessous ; ne pas refaire un audit global à chaque tâche.
4. Ne pas charger en masse les datasets, index vectoriels, runs, captures, environnements ou fichiers générés. Limiter les sorties des commandes et éviter les relectures sans raison.
5. Les appels API payants nécessaires à la tâche sont autorisés par défaut, sauf indication contraire dans le prompt. Ne pas redemander une autorisation du seul fait qu’ils sont facturables. Respecter les modèles, volumes et limites demandés ; éviter les répétitions inutiles.
6. Lancer uniquement les tests pertinents pour le changement. Élargir aux composants dépendants si le contrat partagé change ; ne pas lancer systématiquement toutes les suites. Pour une modification documentaire, vérifier les commandes et liens concernés suffit généralement.
7. Ne jamais afficher ni versionner les clés de `.env`. Préserver les modifications utilisateur déjà présentes. Mettre à jour cette carte et le README lorsque le fonctionnement change, sans ajouter de bilan historique.

## Carte du code

| Chemin | Responsabilité / point d’entrée |
| --- | --- |
| `hs_matching/approaches/base.py`, `registry.py` | Contrat `predict(query, top_k, context) -> Prediction`, dataclasses et registre des trois approches. |
| `hs_matching/approaches/{llm_direct,embeddings,rag}.py` | Prédiction, prompts et validation ; prompts direct et RAG indépendants et versionnés. |
| `hs_matching/providers/{openai,qwen}.py` | Génération HTTP, `ModelConfig`, erreurs et adaptation Qwen à l’enveloppe commune. |
| `hs_matching/vectorization/` | Contrat `Vectorizer`, configuration et transport OpenAI embeddings. |
| `hs_matching/catalog.py`, `embedding_index.py` | Catalogue, admissibilité, construction explicite et intégrité de l’index, recherche cosinus NumPy et retriever partagé. |
| `hs_matching/experiments.py`, `cli.py`, `config.py` | Exécution et traces détaillées, CLI, lecture `.env` (environnement prioritaire). |
| `hs_matching/web.py`, `web_runner.py` | Routes Flask, validation, exécution séquentielle et cache mémoire catalogue/index. |
| `hs_matching/web_presenters.py`, `templates/`, `static/`, `demo.py` | Présentation par approche, Jinja, CSS/JS sans compilation, fixtures sans API. |
| `scripts/` | `preprocess_h6.py` : référentiel ; `build_embeddings.py` : index ; `snapshot_web.py` : captures Playwright. |
| `benchmark/prepare_hscodecomp.py` | Extraction titre + HS6 de HSCodeComp vers CSV. |
| `benchmark/benchmark.py`, `process_runs.py` | Collecte séquentielle, traces compactes incrémentales, agrégation et rapport Markdown. |
| `traitement_csv/traiter.py` | Inférence RAG avec checkpoints, puis remplacement des descriptions par les libellés du catalogue ; documentation locale dans `traitement_csv/README.md`. |
| `tests/`, `benchmark/test_*.py`, `traitement_csv/test_traiter.py` | Tests `unittest`, fournisseurs simulés, sans appels API réels. |
| `data/`, `artifacts/`, `runs/`, `benchmark/runs/` | Données, index/captures et résultats locaux ignorés par Git. Les données et rapports de `benchmark/` sont ignorés, seuls ses scripts Python sont suivis. |
| `sandbox_java/` | Hors périmètre, sauf demande explicite ; contenu et versions non audités. |

## Outils et versions

Sources de vérité : `pyproject.toml`, `requirements.txt`, `requirements-dev.txt` ; aucun lockfile. Ne pas confondre plages compatibles et versions installées.

| Outil | Version déclarée | Observée dans `.venv` lors de l’audit du 2026-10-09 |
| --- | --- | --- |
| Python | `>=3.10` | `3.12.3` |
| Flask | `>=3.1,<4` | `3.1.3` |
| NumPy | `>=1.24,<3` | `2.5.3` |
| Playwright (captures facultatives) | `>=1.50,<2` | `1.62.0` |
| setuptools (build) | `>=68` | Non installé dans `.venv` ; build isolé possible via pip. |
| pip | Non fixé | `24.0` |

Package `hs-matching` : `0.1.0`. `unittest` et transports HTTP `urllib` viennent de Python ; aucun SDK OpenAI ni chaîne Node/npm. `pip install -e .` seul n’installe pas Flask/NumPy : utiliser les requirements ou les extras `.[web]`, `.[embeddings]`, `.[visual]` selon le besoin.

Modèles configurés dans le code : `gpt-4.1-mini` par défaut (surcharge `OPENAI_MODEL`/`--model`), `text-embedding-3-small` pour construire un index, alias `qwen3` → `Qwen/Qwen3-VL-4B-Instruct-FP8`. Ce sont des choix du projet, pas des garanties de disponibilité ni des snapshots figés. Qwen est routé dans les CLI d’inférence et benchmark ; la GUI utilise OpenAI.

## Commandes utiles

Depuis la racine, utiliser `.venv/bin/python` ou activer `.venv` puis utiliser `python`. Installation et commandes d’inférence complètes : `README.md`.

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m hs_matching approaches
.venv/bin/python -m hs_matching predict --help
.venv/bin/python benchmark/benchmark.py --help
.venv/bin/python -m hs_matching.web --demo
```

Sélectionner la commande correspondant à la modification, pas tout le bloc :

```bash
.venv/bin/python -m unittest discover -s tests -p 'test_llm_direct.py' -v
.venv/bin/python -m unittest discover -s tests -p 'test_embeddings.py' -v
.venv/bin/python -m unittest discover -s tests -p 'test_rag.py' -v
.venv/bin/python -m unittest discover -s tests -p 'test_qwen.py' -v
.venv/bin/python -m unittest discover -s tests -p 'test_web.py' -v
.venv/bin/python -m unittest discover -s tests -p 'test_preprocess_h6.py' -v
.venv/bin/python -m unittest benchmark.test_benchmark -v
.venv/bin/python -m unittest benchmark.test_process_runs -v
.venv/bin/python -m unittest discover -s traitement_csv -p 'test_traiter.py' -v
```

Les tests de prétraitement nécessitent `data/H6.json` (ou `H6.json` à la racine) ; les tests LLM direct et certains tests Qwen nécessitent `data/processed/h6_2022/catalog.jsonl`. Préparer ce catalogue au besoin avec `python scripts/preprocess_h6.py --input data/H6.json`. Un échec pour données manquantes n’est pas une régression métier. Les tests embeddings/RAG utilisent des petits index temporaires ; aucune reconstruction payante de l’index complet n’est nécessaire pour eux.

Pour un changement visuel, si pertinent : installer `requirements-dev.txt`, puis `PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright .venv/bin/python -m playwright install chromium` et `PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright .venv/bin/python scripts/snapshot_web.py`.

## Invariants à préserver

- Conserver les codes comme chaînes, avec zéros initiaux. Valider format, existence, admissibilité, doublons et limite N. Ne pas corriger silencieusement les codes ni compacter les rangs après rejet.
- Préserver `ok`, `needs_info`, `abstained`, `error`, les informations manquantes et les erreurs par approche. Les libellés affichés proviennent du catalogue ; les scores cosinus ne sont pas des probabilités.
- Le RAG ne peut retenir que les candidats récupérés, avec `retrieval_k >= top_k`. Son prompt, son schéma et sa validation sont distincts du LLM direct.
- Aucun précalcul implicite à l’inférence. Un index existant n’est pas écrasé ; une modification des codes/descriptions contextualisées impose une reconstruction explicite. Pas de retry ni de substitution automatique de modèle dans les fournisseurs actuels.
- Réutiliser les approches et le retriever communs dans les interfaces. La GUI exécute les approches successivement et échappe les textes via Jinja.
- Distinguer les traces détaillées d’inférence (`runs/`) des traces compactes de benchmark (`benchmark/runs/`), qui retirent les métadonnées brutes et sauvegardent chaque résultat. Le rapport agrège par `(model, approach)` seulement, tous candidats `ok`/`needs_info` confondus, même si les datasets ou paramètres diffèrent. Erreurs et abstentions restent au dénominateur ; les tokens inconnus restent `null`.
