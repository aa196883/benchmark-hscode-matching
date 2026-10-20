# Guide des agents

## Objectif et périmètre

Comparer des approches d’inférence de codes HS à partir de descriptions de marchandises **en anglais**, sur le **HS international 2022 à six chiffres**. Le moteur commun sert le LLM direct, les embeddings et le RAG ; les interfaces comprennent une CLI, Flask, des rapports HTML et le benchmark MLflow.

**`sandbox_java/` est hors périmètre : ne pas l’explorer, le lire, y rechercher ou le modifier sans demande explicite.** Pour une recherche globale, utiliser `rg --files -g '!sandbox_java'` ; préférer les répertoires ciblés de la carte ci-dessous.

## Méthode de travail

- Lire les fichiers cités dans la demande, puis la documentation du composant si nécessaire. Cibler les recherches et les plages de lecture ; les datasets, index, runs et environnements sont volumineux.
- Préserver les modifications utilisateur. Garder les secrets de `.env` hors des sorties et de Git ; les variables d’environnement sont prioritaires.
- Les appels API payants nécessaires sont autorisés par défaut. Respecter les modèles et volumes demandés, limiter les essais réels à quelques descriptions et utiliser `--limit` dans le benchmark.
- Réutiliser les approches, fournisseurs et retriever communs. Mettre à jour le README et cette carte quand les points d’entrée ou les contrats changent.
- Vérifier les composants modifiés avec les tests ciblés ; élargir aux consommateurs lorsqu’un contrat partagé change. Pour la documentation, vérifier les commandes et chemins concernés.

## Carte du code

| Besoin | Fichiers à consulter |
| --- | --- |
| Contrat de prédiction et registre | `hs_matching/approaches/base.py`, `registry.py` : `predict(query, top_k, context) -> Prediction`. |
| Prompts et validation | `hs_matching/approaches/{llm_direct,embeddings,rag}.py` ; prompts direct et RAG indépendants et versionnés. |
| Génération LLM | `hs_matching/providers/{openai,qwen}.py` : transport HTTP, `ModelConfig`, erreurs. |
| Vectorisation et recherche | `hs_matching/vectorization/`, `catalog.py`, `embedding_index.py` : admissibilité, intégrité de l’index, cosinus NumPy, retriever. |
| CLI et traces locales | `hs_matching/cli.py`, `experiments.py`, `config.py`. |
| Interface Flask | `hs_matching/web.py`, `web_runner.py` : routes, validation, exécution séquentielle, cache catalogue/index. |
| Présentation et démo | `hs_matching/web_presenters.py`, `templates/`, `static/`, `demo.py` : Jinja, CSS/JS, fixtures sans API. |
| Rapport HTML RAG | `scripts/render_rag_report.py`, `scripts/rag_descriptions.csv`, `hs_matching/templates/rag_report.html` : script sans paramètres, constantes modèle/K/N, sortie autonome `artifacts/rag_report.html`. |
| Préparation et captures | `scripts/preprocess_h6.py`, `build_embeddings.py`, `snapshot_web.py`. |
| Préparation des datasets | `benchmark/prepare_{hscodecomp,cross_rulings,hsclassify}.py` et leurs tests : extraction description/HS6, admissibilité, provenance et rejets. |
| CLI benchmark | `benchmark/README.md`, `benchmark/{benchmark,cli,__main__}.py` : `run`, `import-dataset`, `datasets`, `ui`. |
| Versions de datasets | `benchmark/datasets.py`, `datasets/*/manifest.json`, `tracking.py` : MLflow Evaluation Datasets, stockage SQLite par défaut. |
| Exécution et métriques MLflow | `benchmark/{runtime,runner,traces,metrics}.py` : traces par ligne, checkpoints, classement, latence et tokens. |
| Traitement CSV RAG | `traitement_csv/README.md`, `traiter.py`, `test_traiter.py` : inférence avec checkpoints puis remplacement par les libellés du catalogue. |
| Tests | `tests/test_*.py`, `benchmark/test_*.py`, `traitement_csv/test_traiter.py` : `unittest`, fournisseurs simulés. |
| Données et sorties locales | `data/`, `artifacts/`, `runs/`, `benchmark/runs/`, base et artifacts MLflow : ignorés par Git. Consulter `.gitignore` pour les exceptions suivies. |

## Contrats métier

### Prédictions

- Les codes sont des chaînes préservant les zéros initiaux. Valider format, existence, admissibilité, unicité et limite N. Garder les rangs d’origine après rejet.
- Les libellés affichés viennent du catalogue. Préserver statuts, informations manquantes et erreurs propres à chaque approche (`ok`, `needs_info`, `abstained`, `error`). Les scores cosinus représentent une proximité, pas une probabilité.
- L’inférence utilise un index déjà construit. Une modification des codes ou descriptions contextualisées exige une reconstruction explicite dans un nouveau dossier. Les fournisseurs exécutent un appel sans retry ni substitution automatique de modèle.
- Les interfaces utilisent le moteur commun ; Flask exécute les approches successivement. Échapper les descriptions et réponses dans les rendus HTML.

### RAG

- Sélectionner uniquement des codes récupérés, avec `retrieval_k >= top_k`. Les statuts métier sont `ok` et `needs_info` ; les échecs techniques utilisent `error`.
- Marchandise identifiée : viser `top_k` en complétant les correspondances par des alternatives proches qui nécessitent une petite modification explicitée de la description. Ces alternatives seules ne déclenchent pas `needs_info`. Le pool peut contenir moins d’alternatives pertinentes que demandé.
- Description imprécise : conserver des candidats provisoires compatibles avec les attributs connus, demander les précisions utiles et attribuer des notes faibles. Une liste vide est possible si aucun candidat ne convient ou si le fournisseur refuse.
- `rag_v4` requiert un `rating` numérique de 1 à 5, décimales admises, indépendant du cosinus et des autres candidats. Le prompt demande un plafond de 3 aux alternatives contrefactuelles. Trier par rating décroissant ; expliquer la confiance et les conditions ou modifications nécessaires.
- Afficher les étoiles et la valeur exacte. Les autres approches ont `rating=null` ; un refus fournisseur conserve une liste vide.

### Benchmark

- Une expérience `hs-matching/<dataset>`, un run par approche/configuration/sélection. Préserver versions importées, empreintes, ordre et doublons ; identifier le modèle résolu dans les noms de runs et les traces.
- Créer une trace native MLflow par ligne tentée, liée au run, avec code attendu, réponse et scores individuels. Vérifier l’export et conserver le `trace_id`. Une trace interrompue ne constitue pas une prédiction terminée.
- Enregistrer les résultats après chaque ligne dans MLflow et les checkpoints `benchmark/runs/`. Les statuts et tags distinguent succès, erreur, interruption et sous-ensemble.
- Calculer les métriques sur tous les candidats `ok`/`needs_info`, en gardant les erreurs et abstentions au dénominateur. Exclure les tokens inconnus des moyennes.

## Environnement et vérification

Utiliser `.venv/bin/python` depuis la racine. Les dépendances déclarées dans `pyproject.toml`, `requirements.txt`, `requirements-dev.txt` et `benchmark/requirements.txt` font référence ; vérifier les versions installées uniquement si nécessaire. Python >=3.10, transports HTTP `urllib`, tests `unittest`, frontend Jinja/CSS/JS.

```bash
.venv/bin/python -m pip install -r requirements.txt
.venv/bin/python -m hs_matching predict --help
.venv/bin/python benchmark/benchmark.py --help
.venv/bin/python -m hs_matching.web --demo
.venv/bin/python scripts/render_rag_report.py
```

Configuration des modèles : `gpt-4.1-mini` par défaut, surcharge `OPENAI_MODEL`/`--model` dans les CLI ; `text-embedding-3-small` pour construire un index ; alias `qwen3` → `Qwen/Qwen3-VL-4B-Instruct-FP8`. Flask utilise OpenAI ; le rapport HTML utilise les constantes de son script.

Exécuter uniquement la suite concernée, par exemple :

```bash
.venv/bin/python -m unittest discover -s tests -p 'test_rag.py' -v
.venv/bin/python -m unittest discover -s tests -p 'test_rag_report.py' -v
.venv/bin/python -m unittest benchmark.test_benchmark -v
.venv/bin/python -m unittest discover -s traitement_csv -p 'test_traiter.py' -v
```

Les suites `test_llm_direct.py`, `test_embeddings.py`, `test_qwen.py`, `test_web.py` et `test_preprocess_h6.py` utilisent la même commande `discover`. Le prétraitement exige `data/H6.json` (ou `H6.json` à la racine) ; le LLM direct et certains tests Qwen exigent le catalogue préparé. Au besoin : `.venv/bin/python scripts/preprocess_h6.py --input data/H6.json`. Les tests embeddings/RAG construisent de petits index temporaires avec fournisseurs simulés.

Pour vérifier l’UI, les outils sont dans `requirements-dev.txt` : installer Chromium avec `PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright .venv/bin/python -m playwright install chromium`, puis utiliser `scripts/snapshot_web.py`. Installation, inférence et benchmark détaillés : `README.md` et `benchmark/README.md`.
