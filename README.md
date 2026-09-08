# Benchmark de correspondance produit → HS code

Prototype Python pour proposer les **N codes HS les plus pertinents** à partir d’un nom de marchandise ou d’une description, puis comparer plusieurs approches sur les mêmes exemples. La priorité est la précision des résultats et la rapidité d’expérimentation.

**État : prétraitement H6, approches LLM directe, embeddings et RAG, et CLI disponibles.** Les autres approches, l’évaluation et la GUI restent à implémenter. Les choix ci-dessous constituent la direction initiale du projet.

## Périmètre initial

- Entrées en anglais ; description libre.
- Cible : HS international à **6 chiffres**, édition **2022**.
- Sortie : candidats ordonnés, libellés du référentiel, scores propres à l’approche, justification si disponible et informations manquantes. Une description insuffisante peut conduire à une abstention.
- CLI pour rechercher, comparer et évaluer ; GUI Flask pour saisir une description et afficher les résultats côte à côte.
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

Python **3.10+**, sans dépendance d’exécution pour le LLM direct. La recherche par embeddings utilise NumPy (installation ci-dessous). Depuis la racine :

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

## Embeddings et similarité cosinus

L’approche `embeddings` vectorise exclusivement **`contextual_description` de `candidates.jsonl`** au précalcul. À l’inférence, elle vectorise uniquement la description utilisateur, puis classe les candidats par similarité cosinus. Aucun appel de génération LLM n’est nécessaire.

### Installation

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -e '.[embeddings]'
```

NumPy est utilisé pour la recherche matricielle en mémoire. Le précalcul lui-même utilise uniquement la bibliothèque standard. La clé est lue dans `.env` ou dans `OPENAI_API_KEY` déjà défini dans l’environnement.

### 1. Précalcul explicite

```bash
.venv/bin/python scripts/build_embeddings.py \
  --input data/processed/h6_2022/candidates.jsonl \
  --output-dir artifacts/embeddings/h6_2022 \
  --model text-embedding-3-small
```

Cette commande appelle l’[API de vectorisation OpenAI](https://developers.openai.com/api/reference/python/resources/embeddings/methods/create), par lots de 64 descriptions par défaut. Options : `--batch-size`, `--dimensions`, `--timeout`, `--env-file`. La réduction de dimensions dépend du modèle choisi ; sans `--dimensions`, le modèle utilise sa dimension par défaut. `OPENAI_MODEL`, réservé au LLM direct, n’intervient pas ici.

Le dossier de sortie doit être nouveau. Aucun index existant n’est écrasé. Il n’apparaît qu’après réussite de tous les lots ; un échec ne laisse pas d’index partiel utilisable. Il n’y a pas encore de reprise des lots ni de nouvelle tentative automatique : relancer un précalcul interrompu refait les appels, y compris ceux déjà réussis. Les limites de tokens restent celles du fournisseur ; ajuster la taille des lots si nécessaire.

Deux fichiers sont produits :

- **`vectors.jsonl`** : un objet JSON par ligne, avec seulement `code` et `vector` ; les vecteurs sont conservés tels que retournés par le fournisseur.
- **`manifest.json`** : fournisseur, modèle demandé et retourné, dimensions, nombre de candidats, empreinte des descriptions et du fichier de vecteurs, provenance, date, durée et usage des tokens par lot.

Exemple de format, avec un vecteur fictif à trois dimensions :

```json
{"code": "010121", "vector": [0.12, -0.04, 0.31]}
```

Les fichiers s’ouvrent dans un éditeur de texte ou se lisent ligne par ligne avec `json.loads`. Aucun format binaire ni base vectorielle n’est requis. `artifacts/` reste ignoré par Git.

### 2. Recherche

```bash
.venv/bin/python -m hs_matching predict "Live purebred breeding horses" \
  --approach embeddings \
  --index artifacts/embeddings/h6_2022 \
  --top-k 5
```

Ajouter `--json` pour le résultat complet. Les runs sont sauvegardés comme pour le LLM direct. Chaque candidat a un score `cosine_similarity`, compris entre -1 et 1, et un libellé issu du catalogue. Ce score n’est pas une probabilité. Cette première baseline renvoie les N voisins disponibles sans seuil d’abstention ; la calibration d’un seuil nécessitera un jeu annoté.

Le modèle, le fournisseur et les dimensions de la requête viennent du manifeste. `--model` n’est donc pas accepté pour cette approche : pour changer de modèle, construire un autre index puis le sélectionner avec `--index`. Un index absent ne déclenche jamais de précalcul automatique. Un changement des codes ou des descriptions contextualisées impose une reconstruction ; une simple remise en forme du JSON ne l’impose pas.

À l’ouverture, `EmbeddingIndex` vérifie l’intégrité et charge une matrice NumPy `float32`, normalisée L2 une seule fois. Chaque recherche effectue un produit matrice-vecteur et un tri stable ; les égalités sont départagées par code. La matrice occupe environ `nombre_de_codes × dimensions × 4` octets, hors métadonnées et mémoire temporaire de chargement. Le fichier JSON est plus volumineux, en échange de sa lisibilité. Dans une application persistante, réutiliser le même index chargé pour toutes les requêtes.

### Interfaces partagées avec le RAG

- `vectorization/base.py` définit `Vectorizer.embed(texts) -> EmbeddingBatch` et `EmbeddingConfig`. Les vecteurs sont rendus dans l’ordre des entrées, avec le modèle retourné et l’usage des tokens.
- `vectorization/openai.py` contient l’adaptateur OpenAI : HTTP, paramètres, contrôle et remise en ordre de la réponse. Le précalcul et la recherche passent tous deux par `Vectorizer`.
- `embedding_index.py` expose `build_index`, `EmbeddingIndex.search(vector, top_k)` et `EmbeddingRetriever.retrieve(query, top_k)`. Ce dernier retourne les voisins avec code, score, description et description contextualisée, ainsi que les métadonnées de vectorisation.
- `approaches/embeddings.py` adapte ce résultat au contrat commun `Prediction` ; le registre expose `embeddings`.

Le RAG réutilise le même `EmbeddingRetriever`, demande K voisins, puis fournit leurs descriptions contextualisées au LLM pour sélectionner N codes. L’index et la recherche n’ont aucune dépendance envers la génération LLM. Pour un autre fournisseur de vecteurs, implémenter `Vectorizer` et l’injecter dans le précalcul et le retriever.

Les runs embeddings conservent le manifeste, l’empreinte du catalogue, le vecteur de requête, les tokens, les scores et les durées. Le chargement de l’index est effectué avant la prédiction ; sa durée n’est pas incluse dans celle de la recherche.

```bash
.venv/bin/python -m unittest discover -s tests -v
```

Les tests de précalcul, de classement cosinus, de compatibilité, d’intégrité et de CLI utilisent des vecteurs simulés. Ils ne lancent aucun appel payant.

## RAG : récupération cosinus puis classement par LLM

L’approche `rag` utilise l’index déjà précalculé par `scripts/build_embeddings.py`. Elle réutilise `EmbeddingRetriever` et le module `vectorization` pour vectoriser la requête avec le même modèle que les descriptions du catalogue. Aucun nouveau précalcul n’est nécessaire.

```bash
.venv/bin/python -m hs_matching predict "Live purebred breeding horses" \
  --approach rag \
  --index artifacts/embeddings/h6_2022 \
  --retrieval-k 20 \
  --top-k 5 \
  --model gpt-4.1-mini
```

- `--retrieval-k` : K voisins récupérés par similarité cosinus (20 par défaut), avec K ≥ N ; si le catalogue contient moins de K candidats, tous les candidats disponibles sont récupérés.
- `--top-k` : N résultats finaux au maximum (5 par défaut).
- `--model` : modèle de **classement LLM**, répétable pour comparer plusieurs modèles ; sans cette option, même configuration que le LLM direct. Le modèle d’embeddings reste imposé par le manifeste de `--index`.

Les options `--temperature`, `--reasoning-effort`, `--max-output-tokens`, `--timeout`, `--json` et `--runs-dir` restent disponibles. Chaque modèle comparé exécute sa propre récupération et son propre classement. Un index absent provoque une erreur sans lancer de précalcul.

### Prompt indépendant et résultats contrôlés

`hs_matching/approaches/rag.py` contient son propre prompt versionné, son schéma et son analyse de réponse ; il n’importe ni ne factorise le prompt du LLM direct. Le transport OpenAI existant est réutilisé.

Le LLM reçoit la description du produit et uniquement les codes récupérés avec leurs descriptions et descriptions contextualisées. Le prompt lui demande de les reclasser en utilisant exclusivement les attributs explicites de ces textes, sans connaissances externes, règles tarifaires mémorisées ou propriétés supposées. Les textes sont désignés comme données et non comme instructions. Les scores cosinus sont conservés dans les traces ; ils ne sont pas transmis au LLM.

La liste des codes autorisés est imposée dans le schéma de sortie, puis vérifiée localement : format, existence dans le catalogue, appartenance à la sélection récupérée, absence de doublons et respect de N. Un code valide du catalogue mais absent de la sélection est rejeté avec le motif `not_retrieved`. Les rejets conservent leur rang et les rangs finaux ne sont pas compactés. La consigne limite les sources demandées au LLM ; elle ne permet pas de prouver qu’il n’utilise aucune connaissance mémorisée. La vérification de l’appartenance des codes, elle, est déterministe.

Le LLM peut demander des informations (`needs_info`) ou s’abstenir si aucun candidat n’est étayé par les textes. Les candidats finaux ont `score=null` et `score_type="none"` : leur ordre est celui du LLM. Leurs références `catalog:2022:CODE` identifient les lignes du catalogue. Les similarités initiales sont disponibles séparément dans `metadata.retrieved_candidates`.

### Traces et validation

Les runs enregistrent K et N, le manifeste de l’index, les candidats récupérés avec rang et cosinus, le vecteur et l’usage de la requête dans `metadata.retrieval`, le prompt exact, la réponse brute, l’usage du LLM et les durées de récupération et de génération. Une erreur du LLM conserve les candidats récupérés ; une erreur de récupération empêche l’appel du LLM. Un échec ne bloque pas les modèles suivants.

Les tests RAG simulent les deux fournisseurs et vérifient aussi l’intégration CLI avec le véritable index cosinus local. Aucun appel OpenAI réel n’est exécuté par les tests.
