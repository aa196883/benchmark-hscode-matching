# Périmètre et méthode

Ces consignes couvrent uniquement `sandbox_java/`, le portage Java du RAG de
classement HS : embedding de la description produit → voisins du catalogue HS 2022
→ sélection/explications par LLM → adaptation au contrat industriel.
Le projet industriel complet est inaccessible ; `lestr_sources/` contient les extraits reçus.

- Lire d’abord les fichiers cités dans le prompt. Utiliser cette carte, puis explorer
  uniquement les fichiers utiles ; ne pas rescanner systématiquement tout le dépôt.
- Ne pas explorer le code Python parent, sauf demande explicite. Le développement
  Java et ses tests sont autonomes. La copie des données préparées ne nécessite pas
  de lire le code Python.
- Préserver les interfaces, records et utilitaires industriels ; les modifier seulement
  si nécessaire à la tâche. Ne pas profiter d’un portage pour corriger leurs particularités.
- Tester d’abord les changements avec les classes/méthodes concernées. Réserver la suite
  complète aux changements transversaux et à l’export ; pas de relances sans raison.
- Quelques appels API payants sont autorisés pour vérifier une modification pertinente,
  sans redemander confirmation. Préférer un cas ciblé ; aucun benchmark massif ni
  recalcul payant du catalogue implicitement.

## Carte et versions

| Chemin | Rôle |
| --- | --- |
| `simplified_env/pom.xml` | Agrégateur Maven : `compat`, `extension`, `runner` |
| `simplified_env/extension/src/{main,test}/` | Production RAG et tests exportables |
| `simplified_env/compat/src/main/java/` | Copies des contrats/services industriels ; dépendance `provided` d’extension |
| `simplified_env/runner/src/{main,test}/java/` | CLI, référentiel en mémoire, doubles, tests de compatibilité |
| `lestr_sources/` | Extraits hors compilation, ignorés par Git, parfois absents |
| `simplified_env/reference-manifest.json` | Empreintes des références/copies et écarts autorisés |
| `scripts/` | Copie des ressources ; outils Python de parité réservés aux demandes explicites |

Versions épinglées : Java 21, Maven requis 3.9+, LangChain4j 1.20.0,
Jackson BOM 2.21.4, JUnit Jupiter 5.12.2, Lombok 1.18.46, JSpecify 1.0.0,
Commons Lang 3.20.0, Commons IO 2.21.0, SLF4J Simple 2.0.18 (runner).
PostgreSQL JDBC 42.7.12, langchain4j-pgvector 1.20.0-beta30, Testcontainers 1.21.4.
Image commune Compose/tests : pgvector/pgvector:0.8.1-pg17 (PostgreSQL 17).
Plugins : Compiler 3.15.0, Surefire/Failsafe 3.5.4, Shade 3.6.0. Pas de Spring local.
Les POM font foi ; ne pas rechercher les versions récentes pour une tâche ordinaire.

## Points d’entrée et invariants

Package RAG commun, sous `extension/src/main/java/` :
`com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/`.
Les tests sont sous le même package dans `src/test/java/`.

- `RagHSCodeAnalysisService` : `construct(properties, analysisDelegate, datasource)` ouvre
  l’index PostgreSQL ; `loadIndex(datasource)` l’ouvre sans client OpenAI.
  La DataSource et son pool appartiennent à l’appelant ; pas de lecture d’environnement dans le métier.
  `searchDetailed()` orchestre le parcours ; `toSearchResult()` adapte sans nouvel appel.
- `OpenAiRagClient` : modèles LangChain4j Responses/embeddings, injectables pour tests.
  `Config` : modèle, limite de sortie, température, effort de raisonnement.
  Défauts : `gpt-4.1-mini`, 2048, température/effort non renseignés ; `store=false`,
  schéma strict, embeddings `text-embedding-3-small`, `maxRetries(0)` côté embeddings.
  La factory utilise `OpenAIProperties.modelName()` ; délais via les modèles injectés.
- `PrecomputedEmbeddingIndex` / `RagCatalog` : recherche exacte PostgreSQL via
  PgVectorEmbeddingStore, catalogue en mémoire, contrôle du manifeste/dimensions/codes.
  Le compte de recherche a uniquement SELECT ; aucune création SQL au démarrage.
  `PrecomputedIndexResources` valide les fichiers ; `RagIndexImporter.importIndex`
  effectue un import explicite transactionnel, verrouillé et immuable (identique = aucune écriture).
  Le store en mémoire ne sert que dans les tests ; aucune solution de repli en production. Ne pas utiliser le store industriel `EmbeddingService`
  à leur place : format et descriptions différents.
- Ressources sous `extension/src/main/resources/` + package commun : `rag_v1.txt`,
  `rag_v1.schema.json`, `h6_2022/{catalog.jsonl,manifest.json,vectors.jsonl}`.
  Lecture par flux, compatible JAR, aucun recalcul implicite ; vecteurs lus uniquement à l’import.
  `db/bootstrap.sql` prépare extension, rôles et schéma avant import (administrateur, une seule fois). Données complètes déjà
  présentes localement, ignorées par Git ; fixtures autonomes dans `src/test/resources/rag-fixtures/`.
- `runner/.../local/lestr/sandbox/Main.java` et `RagMain.java` : CLI/assemblage local.
  `extension` ne doit ni importer le runner ni redéfinir les classes de `compat`.
- Codes HS textuels, zéros initiaux conservés : `toDigits()` pour les clés,
  `toString()` donne un format pointé. Scores fixes 2.5 détaillé / 3 industriel,
  ordre LLM conservé, rangs non renumérotés après filtrage, source `OpenAI_Hybrid`.
  `analyse()` délègue. Une liste vide arrête la chaîne industrielle de secours ;
  les erreurs techniques et le rejet de tous les codes proposés lèvent une exception.
  La projection industrielle perd questions et explications.
- Si une copie industrielle doit changer : examiner la référence, caractériser le
  changement et documenter le manifeste. Ne jamais changer une empreinte pour masquer un échec.

## Commandes (depuis `sandbox_java/`)

```bash
# Test ciblé extension ; #nomMethode peut être ajouté au nom de classe
./simplified_env/dev.sh test -o -pl extension -am \
  -Dtest=RagServiceTest -Dsurefire.failIfNoSpecifiedTests=false
# Test ciblé du socle / frontières
./simplified_env/dev.sh test -o -pl runner -am \
  -Dtest=SourceBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false
# Intégration autonome Docker / ajouter le jeu complet existant (sans API)
./simplified_env/dev.sh verify -Ppgvector-it
./simplified_env/dev.sh verify -Ppgvector-it -Drag.fullIndex=true
# IT ciblé, après validation des tests ordinaires
./simplified_env/dev.sh verify -Ppgvector-it -pl extension -am \
  '-Dit.test=PgVectorIndexIT#exactTiesCrossingLimitUseHsCodeOrder' -Dtest=NoUnitTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# Compiler sans tests / construire le JAR / suite complète
./simplified_env/dev.sh package -o -DskipTests
./simplified_env/dev.sh package -o
./simplified_env/dev.sh test -o
./simplified_env/dev.sh run --help
./simplified_env/dev.sh run demo
# Vérification des espaces ; aucun lint/formatter Java configuré
git diff --check
# Préparer les données si absentes/périmées, puis export (lance verify)
./scripts/prepare_rag_resources.sh
./simplified_env/export.sh -o
# Base Docker autonome + comparaison exhaustive, sans appel API (secret requis)
RUN_OPENAI_MT=true ./simplified_env/dev.sh test -o -pl extension -am \
  '-Dtest=RagHSCodeServiceMT#serviceCanBeInstantiated' -Dsurefire.failIfNoSpecifiedTests=false
# Un seul cas API payant, clé déjà chargée dans l’environnement
RUN_OPENAI_MT=true ./simplified_env/dev.sh test -o -pl extension -am \
  '-Dtest=RagHSCodeServiceMT#searchBanana' -Dsurefire.failIfNoSpecifiedTests=false
```

Retirer `-o` si une dépendance manque au cache. `dev.sh` cherche Maven dans le PATH
puis `~/.m2/wrapper/dists` ; override `MVN=/chemin/bin/mvn`. Il impose
`simplified_env/settings.xml` aux settings utilisateur et globaux (pas de dépôt privé).
`run` ne compile pas : reconstruire `runner/target/sandbox.jar` après modification.
Les arguments de chemins de la CLI sont relatifs au répertoire courant.

Choisir les tests : `RagServiceTest`/`ReferenceCasesTest` pour le métier,
`IndexValidationTest` pour index/catalogue, `OpenAiRagClientTest` pour le client,
`ClasspathLoadingTest`/`ResourceStreamTest` pour ressources et flux ; tests
`*CompatibilityTest` du runner pour le socle. `RagHSCodeServiceMT` est exclu des
sélections ordinaires et exige `RUN_OPENAI_MT=true` plus une sélection explicite.
Il crée sa propre base Docker, importe et vérifie chaque vecteur une seule fois en
`@BeforeAll`, puis détruit le conteneur à la fin. Aucun `RAG_DB_*` ni Compose requis.
Le test charge `new OpenAIProperties(Objects.requireNonNull(Utils.getSecret("OPENAI-API")))`,
comme le test industriel de completion, sans clé de secours. `serviceCanBeInstantiated`
nécessite aussi ce secret, mais ne fait aucun appel API. Le substitut local
`compat/.../com/semsoft/lestr/common/test/Utils.java` lit `OPENAI_API_KEY` uniquement
pour le bac à sable ; ne jamais l’exporter ni remplacer le Utils industriel.
Les tests ordinaires sont sans API payante ni Docker ; certains simulent HTTP en boucle locale.
`PgVectorIndexIT` exige Docker, crée/détruit sa propre base sans Compose, teste import/recherche/
droits/rollback/concurrence/JAR. Le profil explicite ne doit pas ignorer un Docker indisponible.

## Configuration, secrets et transfert

La configuration et les secrets sont déjà en place dans l’environnement de travail :
ne pas les recréer, les afficher, les passer en argument ou les committer.
La CLI lit `OPENAI_API_KEY`, `RAG_DB_{URL,USER,PASSWORD}` (lecture) et
`RAG_IMPORT_DB_{URL,USER,PASSWORD}` (import), sans chargement de `.env`.
La base locale et `simplified_env/.env` sont déjà initialisés. Sur un nouveau checkout,
copier `.env.example` vers `.env` ignoré, choisir les mots de passe et exporter les
variables. Ne pas écraser un `.env` existant.
Compose charge ce fichier ; le Java exige les variables exportées.
`docker compose -f simplified_env/compose.yaml up -d --wait`, puis JAR à jour et
`./simplified_env/dev.sh run rag-import`. `down` conserve le volume ; `down --volumes` le détruit.
Le service `rag` et `rag-replay VECTOR_JSON RESPONSE_JSON DESCRIPTION [TOP_K [RETRIEVAL_K]]`
utilisent la base importée. Les vecteurs de rejeu doivent correspondre à ses dimensions.
Aucun réimport implicite, aucune migration automatique d’un index différent.
Lors de l’audit, `OPENAI_API_KEY` n’était pas exportée dans le shell : vérifier seulement
sa présence avant un appel réel et utiliser le mécanisme existant de chargement ;
ne pas supposer qu’un secret configuré est déjà accessible au processus Java.

Le script de préparation copie `../data/processed/h6_2022/catalog.jsonl` et
`../artifacts/embeddings/h6_2022/{manifest.json,vectors.jsonl}` sans appeler d’API.
Seulement si une tâche demande la parité Python :
`../.venv/bin/python scripts/verify_rag_parity.py` (JAR à jour, base importée et variables RAG_DB exportées) ;
`../.venv/bin/python scripts/generate_rag_fixtures.py` régénère les fixtures suivies.

L’export produit `simplified_env/extension/target/transferable-sources.tar.gz`
(+ `.sha256`) avec `src/main`, `src/test`, `INTEGRATION.md`. Garder ce guide autonome.
Ne transférer ni `compat`, ni `runner`, ni POM locaux. L’assemblage Spring et les
appelants industriels restent à valider dans leur dépôt ; voir le README racine.
