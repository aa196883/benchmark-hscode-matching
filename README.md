# Benchmark de correspondance produit → HS code

Prototype Python pour proposer les **N codes HS les plus pertinents** à partir d’un nom de marchandise ou d’une description, puis comparer plusieurs approches sur les mêmes exemples. La priorité est la précision des résultats et la rapidité d’expérimentation.

**État : prétraitement H6, approches LLM directe, embeddings et RAG, CLI et GUI Flask disponibles.** La baseline lexicale et l’évaluation restent à implémenter. Les sections ci-dessous décrivent l’installation, l’architecture et les commandes disponibles.

## Installation sous Linux

Python **3.10+** est requis. Depuis la racine du dépôt, créer puis activer un environnement virtuel :

```bash
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install -r requirements.txt
```

Sur Debian/Ubuntu, si la création du venv échoue faute du module correspondant, installer `python3-venv` avec `sudo apt install python3-venv`, puis recommencer.

**Toutes les commandes ci-dessous s’exécutent depuis la racine du dépôt, avec le venv activé.** Dans un nouveau terminal, relancer `source .venv/bin/activate`. `python3` utilise alors l’interpréteur du venv. Pour quitter cet environnement : `deactivate`.

`requirements.txt` contient les dépendances directes de l’application (Flask et NumPy), avec les mêmes plages de versions que `pyproject.toml`. `requirements-dev.txt` ajoute Playwright pour les captures navigateur ; les tests unitaires utilisent `unittest`. Ces fichiers ne sont pas un verrouillage exhaustif des versions transitives.

Configurer `OPENAI_API_KEY` dans `.env` pour les appels réels. Si ce fichier n’existe pas, le créer à partir de `.env.example` ; ne pas écraser une clé déjà renseignée. Le mode démo fonctionne sans clé ni données locales.

## Périmètre

- Entrées en anglais ; description libre.
- Cible : HS international à **6 chiffres**, édition **2022**.
- Sortie : candidats ordonnés, libellés du référentiel, scores propres à l’approche, justification si disponible et informations manquantes. Une description insuffisante peut conduire à une abstention.
- CLI pour rechercher et comparer les modèles ; GUI Flask pour comparer les approches côte à côte. L’évaluation sur jeu annoté reste à implémenter.
- OpenAI comme premier fournisseur pour les approches LLM et embeddings ; clé via `OPENAI_API_KEY`, modèles et paramètres configurables.

## Approches à comparer

| Approche | Principe |
| --- | --- |
| Embeddings | Vectoriser les descriptions HS et la requête, puis classer par similarité cosinus. |
| LLM direct | Demander les N codes au LLM, sans lui fournir de documents, puis contrôler leur existence dans l’édition choisie. |
| RAG | Rechercher K candidats, fournir leurs descriptions et leur contexte hiérarchique au LLM, puis lui faire sélectionner et ordonner au plus N codes parmi ces candidats. |

Les approches partagent un contrat `predict(query, top_k, context) -> Prediction`. Un registre permet de sélectionner une ou plusieurs approches par configuration. `Prediction` contient les candidats, le statut, les informations manquantes et les métadonnées d’exécution ; les erreurs d’un modèle restent visibles sans bloquer toute la comparaison. Un score de similarité ou un score fourni par un LLM n’est pas une probabilité de justesse comparable entre modèles.

## Architecture du projet

Le package `hs_matching` contient le moteur partagé par la CLI et Flask. Les approches implémentent le même contrat de prédiction ; les appels aux fournisseurs, la recherche vectorielle et le rendu HTML sont isolés dans leurs modules. Le précalcul reste une étape explicite, distincte de l’inférence.

```text
hs_matching/
  approaches/       # contrat, registre, LLM direct, embeddings et RAG
  providers/        # transport de génération OpenAI
  vectorization/    # interface de vectorisation et adaptateur OpenAI
  catalog.py        # lecture du catalogue et validation des codes
  embedding_index.py # précalcul, chargement de l’index et recherche cosinus
  experiments.py    # exécution des modèles et sauvegarde des runs
  config.py         # chargement du fichier .env
  cli.py            # commandes de prédiction et liste des approches
  web.py            # application Flask, routes et comparaisons séquentielles
  web_runner.py     # ressources en mémoire et appel du moteur commun
  web_presenters.py # adaptateurs de présentation par approche
  demo.py           # résultats simulés pour la GUI
  templates/        # page Jinja et cartes propres aux approches
  static/           # styles CSS et interactions JavaScript
scripts/            # prétraitement H6, précalcul embeddings et captures GUI
tests/              # tests unitaires et d’intégration hors API
data/               # données brutes et catalogue préparé (ignorés par Git)
artifacts/          # vecteurs JSONL, manifestes et captures (ignorés par Git)
runs/               # prédictions, traces et comparaisons (ignorés par Git)
requirements.txt    # dépendances de l’application
requirements-dev.txt # dépendances supplémentaires pour les captures
pyproject.toml      # métadonnées du package et installation facultative
```

Les données et les résultats sont stockés en JSON/JSONL. L’index lisible code–vecteur est chargé dans une matrice NumPy pour la recherche cosinus exacte ; il est partagé par les approches embeddings et RAG. La GUI utilise Flask/Jinja, sans service intermédiaire ni frontend séparé.

Le dossier `*.egg-info` est généré par les outils d’installation Python : il décrit le package, ses dépendances et ses commandes. Il ne contient pas de code métier et n’est pas nécessaire au fonctionnement depuis le dépôt. Il reste ignoré par Git et peut réapparaître lors d’une installation du package avec pip.

## Données et évaluation

Conserver une **table avec liens parent-enfant**, qui permet de reconstruire l’arbre sans base graphe. Les codes sont des chaînes pour préserver les zéros initiaux. Pour la recherche, associer chaque libellé à ses ancêtres : « autres » seul est peu informatif. Le [référentiel UN Comtrade HS 2022](https://comtradeapi.un.org/files/v1/app/reference/H6.json) fournit déjà codes, descriptions et parents ; les notes et règles demandent un enrichissement séparé.

Comparer les méthodes sur un jeu annoté indépendant : réussite top-1/top-3/top-5, MRR, codes invalides, abstentions, latence et coût. Conserver les versions des données, modèles, prompts, paramètres et résultats bruts de chaque exécution.

Deux pistes de développement sont décrites dans [PISTES.md](PISTES.md) : une quatrième approche RAG suivant la hiérarchie chapitre → position → sous-position, et une meilleure détection des informations manquantes pour départager les candidats.

## Prétraitement H6

Le prétraitement utilise uniquement la bibliothèque standard. Avec la source brute placée dans `data/H6.json` :

```bash
python3 scripts/preprocess_h6.py --input data/H6.json
python3 -m unittest discover -s tests
```

`data/H6.json` reste intact. Les exports dans `data/processed/h6_2022/` sont :

- `catalog.jsonl` : 6 939 nœuds avec parents, champs source intacts, codes ancêtres et descriptions contextualisées ;
- `candidates.jsonl` : 5 612 candidats à six chiffres ;
- `excluded.json` : ligne `TOTAL` écartée et motif ;
- `manifest.json` : provenance, empreintes SHA-256, transformations, effectifs et état des contrôles.

Les codes restent des chaînes et les parents des chapitres deviennent `null`. Le nettoyage retire le préfixe exact `code - ` et normalise les espaces sans changer la casse ni la ponctuation. Les codes statistiques `99`, `9999` et `999999` restent dans la hiérarchie avec `is_special=true`, mais sont exclus des candidats.

Options : `--input`, `--output-dir`, `--retrieved-at YYYY-MM-DD`. Une nouvelle exécution remplace les exports. La date réelle de récupération reste inconnue par défaut et se distingue de la date de traitement.

Les doublons, formats invalides, parents incohérents et feuilles incohérentes bloquent l’export. La comparaison exhaustive code par code avec la nomenclature OMD et les conditions de réutilisation restent à vérifier, comme indiqué dans le manifeste. Il s’agit d’un catalogue Comtrade filtré, sans certification de conformité OMD.

## Approche LLM directe et CLI

Après l’installation commune ci-dessus :

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

NumPy, installé via `requirements.txt`, assure la recherche matricielle en mémoire. Le précalcul lui-même utilise uniquement la bibliothèque standard. La clé est lue dans `.env` ou dans `OPENAI_API_KEY` déjà défini dans l’environnement.

### 1. Précalcul explicite

```bash
python3 scripts/build_embeddings.py \
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
python3 -m hs_matching predict "Live purebred breeding horses" \
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
python3 -m unittest discover -s tests -v
```

Les tests de précalcul, de classement cosinus, de compatibilité, d’intégrité et de CLI utilisent des vecteurs simulés. Ils ne lancent aucun appel payant.

## RAG : récupération cosinus puis classement par LLM

L’approche `rag` utilise l’index déjà précalculé par `scripts/build_embeddings.py`. Elle réutilise `EmbeddingRetriever` et le module `vectorization` pour vectoriser la requête avec le même modèle que les descriptions du catalogue. Aucun nouveau précalcul n’est nécessaire.

```bash
python3 -m hs_matching predict "Live purebred breeding horses" \
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

## Interface Flask

Après l’installation commune, lancer la GUI locale :

```bash
python3 -m hs_matching.web
```

Ouvrir **http://127.0.0.1:5000**. Après une installation facultative du package, la commande `hs-matching-web` est équivalente. Options de lancement : `--port`, `--catalog`, `--index`, `--runs-dir`, `--env-file`. L’interface lit la même clé `.env` que la CLI ; elle ne crée jamais d’index automatiquement.

Cocher une ou plusieurs approches : elles s’exécutent **successivement**, dans l’ordre LLM direct → Embeddings → RAG. Une erreur laisse les autres approches se poursuivre. Le formulaire permet de régler le nombre de résultats, le modèle LLM partagé par direct/RAG, les voisins RAG et les paramètres avancés. Le modèle de vectorisation reste celui du manifeste de l’index.

Chaque approche sélectionnée dispose d’une colonne et de cartes avec rang, code et description. Les colonnes sont côte à côte sur desktop et empilées sur mobile. Chaque colonne affiche toujours son **temps total**, erreur comprise : préparation des ressources et exécution du moteur. Le temps de sauvegarde de la comparaison et le rendu HTML ne sont pas inclus. Le catalogue et l’index sont gardés en mémoire et rechargés lorsque leurs fichiers changent ; le coût du premier chargement est donc attribué à l’approche qui le déclenche.

Un indicateur d’attente apparaît pendant l’exécution ; les colonnes de résultats sont affichées une fois toutes les approches terminées. Le JSON complet est sauvegardé dans `runs/web/` et téléchargeable depuis la page. Un rechargement de la page de résultats ne relance pas les modèles.

### Rendus propres aux approches

`web_presenters.py` contient le registre d’adaptateurs de présentation. Chaque adaptateur prépare les données et sélectionne un fragment Jinja dans `templates/cards/` :

- `llm_direct.html` : explication du modèle ;
- `embeddings.html` : score et indicateur de similarité cosinus ;
- `rag.html` : explication du reclassement, rang et score de récupération initiaux.

Le cadre des cartes reste partagé dans `templates/index.html`. Les textes utilisateur et les sorties des modèles sont échappés par Jinja. `web_runner.py` prépare les ressources et appelle `experiments.run_prediction`, le même moteur que la CLI. `web.py` gère les routes, la validation, l’exécution séquentielle et la sauvegarde ; aucun calcul de classement ne réside dans les templates.

### Démo et captures reproductibles

```bash
python3 -m hs_matching.web --demo
```

La démo ne lit pas la clé et n’appelle aucun fournisseur. `demo.py` contient les fixtures modifiables, marquées comme simulées dans l’interface et les exports. Les données affichées restent celles de la fixture T-shirts, quelle que soit la description saisie ; les temps sont également simulés. Dans « Paramètres avancés », le scénario alternatif affiche une demande de précisions, une erreur et une abstention. Les runs de démo vont dans `runs/demo/`.

Pour créer les snapshots sans lancer de serveur manuellement :

```bash
python3 -m pip install -r requirements-dev.txt
PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright python3 -m playwright install chromium
PLAYWRIGHT_BROWSERS_PATH=/tmp/hs-playwright python3 scripts/snapshot_web.py
```

Le script démarre un serveur de démo temporaire, soumet le formulaire dans Chromium, vérifie les trois colonnes et l’absence de débordement mobile, puis ferme le serveur. Il produit quatre captures dans `artifacts/screenshots/` : accueil desktop, comparaison desktop, comparaison mobile et états alternatifs. Les captures et les runs sont ignorés par Git.

Les tests Flask couvrent sélection, exécution séquentielle, durée totale, isolation des erreurs, validation du formulaire, échappement HTML et export :

```bash
python3 -m unittest discover -s tests -v
```

## Qwen via la CLI

Avec le tunnel SSH déjà ouvert vers le serveur, définir `LOCAL_QWEN_KEY` dans
`.env`. L’adaptateur utilise `http://localhost:8000/v1` par défaut ;
`QWEN_BASE_URL` permet de changer cette adresse. Le projet ne crée pas le tunnel.

```bash
python3 -m hs_matching predict "Live purebred breeding horses" --model qwen3 --json
```

`qwen3` désigne `Qwen/Qwen3-VL-4B-Instruct-FP8`. Les prompts, schémas et validations
existants sont conservés. Le serveur doit accepter Chat Completions avec
`response_format=json_schema`. Les limites de tokens, température et timeout sont
transmis ; `--reasoning-effort` est refusé explicitement pour Qwen.

L’approche `llm_direct` ne nécessite aucune clé OpenAI avec Qwen. Avec
`--approach rag --model qwen3`, Qwen effectue le classement, mais la vectorisation
de la requête utilise toujours OpenAI et nécessite sa clé ainsi que l’index existant.
L’approche `embeddings` ne prend pas de modèle de génération. L’UI reste inchangée.

Une erreur de connexion, HTTP ou de réponse produit un run en erreur et un code de
sortie non nul, sans nouvelle tentative ni substitution de modèle. Avec plusieurs
options `--model`, seuls les modèles explicitement demandés sont exécutés, dans
l’ordre, même si l’un échoue. La réponse native Qwen figure dans
`metadata.raw_response.provider_response`.

## Préparation des données de benchmark : HSCodeComp

Le fichier `benchmark/test_data.jsonl` provient du dataset présenté par Tian Lan
et al. dans [HSCodeComp: A Realistic and Expert-level Agent Benchmark for
Hierarchical Rule Application](https://aclanthology.org/2026.acl-long.937/)
(ACL 2026, DOI : `10.18653/v1/2026.acl-long.937`). Le benchmark original contient
632 produits issus du commerce en ligne, avec des annotations expertes à dix
chiffres. Notre extraction conserve uniquement le titre et les six premiers
chiffres du code ; elle constitue une version simplifiée du benchmark original.

Depuis la racine du projet, avec Python standard, sans installation ni appel API :

```bash
python3 benchmark/prepare_hscodecomp.py \
  benchmark/test_data.jsonl \
  benchmark/hscodecomp_hs6_v1.csv
```

Le script autonome accepte le fichier tel qu’il est fourni, y compris ses objets
multilignes et ses retours à la ligne non échappés dans les chaînes, ainsi que
le JSONL standard. Il produit un CSV UTF-8 séparé par des points-virgules (`;`), avec exactement
deux colonnes, dans cet ordre : `HS code`, `description`.

- `hs_code` est converti en texte puis tronqué aux six premiers chiffres :
  `7117199000` devient `711719`. Les zéros initiaux des codes fournis en texte sont
  conservés ; charger cette colonne comme du texte dans un tableur.
- `product_name` devient `description` ; les espaces successifs et retours à la
  ligne sont remplacés par un espace. Les autres champs sont écartés.
- L’ordre et les doublons sont conservés : une entrée produit une ligne CSV.
  Une entrée invalide provoque une erreur explicite avant l’ouverture du CSV.

Les chemins d’entrée et de sortie sont obligatoires. Une sortie existante est
remplacée ; utiliser un nouveau suffixe (`_v2.csv`, etc.) pour conserver plusieurs
versions locales. Le nom `prepare_hscodecomp.py` réserve ce script à ce dataset ;
les futurs datasets pourront avoir leur propre `prepare_<dataset>.py`.
Les scripts Python de `benchmark/` sont suivis par Git ; les données d’entrée et
de sortie sont ignorées, y compris les différentes versions CSV.
## CLI de benchmark : collecte sans métriques

La CLI indépendante `benchmark/benchmark.py` exécute une approche sur chaque
ligne d'un ou plusieurs CSV. Depuis la racine du dépôt :

```bash
python benchmark/benchmark.py --approach llm_direct --model qwen3
python benchmark/benchmark.py --datasets dataset_1.csv dataset_2.csv --model qwen3 --approach llm_direct --top-k 5
python benchmark/benchmark.py --datasets dataset_1.csv --approach embeddings --index artifacts/embeddings/h6_2022 --top-k 10
python benchmark/benchmark.py --datasets dataset_1.csv --approach rag --model qwen3 --top-k 5 --retrieval-k 20
```

Le dataset par défaut est `benchmark/hscodecomp_hs6_v1.csv`. Les CSV UTF-8
(avec ou sans BOM) doivent avoir exactement deux colonnes : code HS de six
chiffres, puis description non vide. Les séparateurs `;` et `,` sont acceptés,
avec ou sans en-tête (`HS code;description`, `hs_code,description`, etc.).
Les descriptions contenant le séparateur ou des retours à la ligne doivent être
entre guillemets CSV. Tous les fichiers sont validés avant les appels aux modèles ;
les zéros initiaux, l'ordre et les doublons sont conservés.

Les conventions sont celles de l'inférence : `llm_direct`, `embeddings`, `rag`,
alias `qwen3`, `--top-k`, `--retrieval-k`, `--index`, `--catalog`, `--env-file`,
`--max-output-tokens`, `--temperature`, `--reasoning-effort` et `--timeout`.
`--approach` est obligatoire. Un seul modèle est utilisé par invocation ; à défaut,
le LLM est `OPENAI_MODEL` ou `gpt-4.1-mini`. Pour `embeddings`, omettre `--model`
ou passer `--model ""` : le modèle de vectorisation vient de l'index.

Chaque couple dataset / approche / modèle produit un nouveau JSON indenté dans
`benchmark/runs/` (modifiable avec `--runs-dir`), avec un identifiant unique dans
son nom. Le fichier est créé avant l'initialisation de l'approche et reste lisible
après chaque résultat sauvegardé. Exemple de structure abrégée :

```json
{
  "model": "qwen3",
  "approach": "llm_direct",
  "dataset": "dataset_1.csv",
  "top_k": 5,
  "results": [
    {
      "response_time": 1.25,
      "ground_truth": "010121",
      "description": "Live pure-bred breeding horses",
      "answer": {
        "status": "abstained",
        "candidates": [],
        "missing_information": [],
        "error": null
      }
    }
  ]
}
```

`response_time` mesure en secondes l'appel complet à l'approche pour la ligne,
retrieval inclus, hors initialisation et écriture du fichier. `answer` conserve
le statut, les candidats et leurs scores éventuels, les informations manquantes
et les erreurs. Les métadonnées des réponses sont supprimées pour toutes les
approches, y compris les réponses brutes LLM qu'elles contenaient. Le champ
`raw_response` est également exclu de `answer` pour `llm_direct`.
La validation interne des approches existantes s'applique toujours ; aucune
métrique n'est calculée. Tous les attributs à la racine du JSON sont conservés,
notamment les paramètres applicables, `created_at` et `dataset_size`.
Pour `embeddings`, l'attribut racine `model` vaut `""` et `index` indique l'index utilisé.

La progression est affichée sur stderr avec le modèle, l'approche, le chemin du
dataset et le compteur `fini/total`, dès `0/total` puis après chaque résultat
sauvegardé (erreurs incluses). Dans un terminal, une barre est actualisée sur la
même ligne. Lors d'une redirection vers un fichier ou un pipe, chaque mise à jour
occupe une ligne autonome, adaptée aux lancements successifs par script.
Pour `embeddings`, le modèle est affiché comme `(index)`.

Une erreur de prédiction est enregistrée et les lignes suivantes sont traitées.
Une interruption conserve les résultats déjà écrits ; comparer leur nombre à
`dataset_size` pour repérer une collecte partielle. Codes de sortie : `0` si aucune
prédiction n'est en erreur, `1` si au moins une l'est, `2` pour une erreur de
configuration, de CSV ou d'exécution, `130` sur interruption clavier.

Tests hors réseau sur un petit CSV temporaire :

```bash
python -m unittest discover -s benchmark -t . -p 'test_*.py'
```

### Analyser plusieurs runs

```bash
python benchmark/benchmark.py --process-runs --runs benchmark/runs/* --output benchmark/report.md
```

Ce mode n'effectue aucune inférence et ne nécessite ni clé API, ni catalogue,
ni index. `--runs` accepte plusieurs fichiers ou motifs glob (également entre
 guillemets). `--output` vaut `benchmark/report.md` par défaut ; le rapport existant
est remplacé après validation des entrées.

Une première passe regroupe les fichiers par valeurs exactes de `model` et
`approach`, quels que soient les datasets et les autres paramètres (`top_k`,
`retrieval_k`, index, etc.). Le même chemin fourni plusieurs fois n'est lu qu'une
fois. Des runs distincts comptent chacun, même s'ils portent sur le même dataset.
Pour comparer des configurations différentes séparément, produire des rapports
avec des sélections de fichiers différentes.

Le Markdown contient uniquement une table : une colonne identifie le couple
modèle / approche, suivie des quatre colonnes de résultats demandées :

- Chapitre : au moins un candidat partage les deux premiers chiffres du code attendu.
- Position : au moins un candidat partage les quatre premiers chiffres.
- Sous-position : au moins un candidat partage les six chiffres.
- Temps moyen : moyenne de `response_time`, en secondes par élément, erreurs incluses.

Tous les candidats des statuts `ok` et `needs_info` sont examinés, pas seulement
le premier. Une liste vide, une abstention ou une erreur vaut une non-correspondance
aux trois niveaux. Un code candidat mal formé ne correspond à aucun niveau.
Chaque élément contribue au plus une réussite par niveau, même si plusieurs
candidats correspondent. Les fractions sont affichées sous la forme `réussites/total`
avec le pourcentage. Les sommes sont calculées sur toutes les lignes regroupées,
sans faire la moyenne des pourcentages par fichier.

Les runs partiels sont signalés sur stderr et seuls les résultats présents sont
analysés. Un groupe vide affiche `N/A`. Les vérités terrain et durées manquantes
ou invalides provoquent une erreur explicite avant l'écriture du rapport.
Le modèle vide des embeddings est affiché comme `(sans modèle)`.
