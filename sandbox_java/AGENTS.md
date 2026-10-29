# Périmètre et méthode

Ces consignes couvrent uniquement `sandbox_java/`, le portage Java du RAG de
classement HS : embedding de la description produit → voisins du catalogue HS 2022
→ sélection/explications par LLM → adaptation au contrat industriel.
Les sources du service industriel reçu sont sous
`lestr_sources/hs_references/trade-analysis/` (hors compilation locale et ignorées par Git).
Elles incluent le câblage Spring, les migrations Flyway et les tests ; les autres modules
industriels et le déploiement effectif restent externes. Ne jamais lire/afficher
`src/test/resources/secrets.properties` ni copier ses secrets.

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
| `simplified_env/runner/src/{main,test}/java/` | CLI RAG, référentiel en mémoire, copies de la chaîne de secours et Verbatim, tests de compatibilité |
| `lestr_sources/hs_references/trade-analysis/` | Service industriel reçu : sources, configuration, migrations et tests, hors compilation locale |
| `simplified_env/reference-manifest.json` | Empreintes des références/copies et écarts autorisés |
| `scripts/` | Copie des ressources ; outils Python de parité réservés aux demandes explicites |

Versions épinglées : Java 21, Maven requis 3.9+, LangChain4j 1.20.0,
Jackson BOM 2.21.4, JUnit Jupiter 5.12.2, Lombok 1.18.46, JSpecify 1.0.0,
Commons Lang 3.20.0, Commons IO 2.21.0, SLF4J Simple 2.0.18 (runner).
PostgreSQL JDBC 42.7.12, langchain4j-pgvector 1.20.0-beta30, Testcontainers 1.21.4.
Image commune Compose/tests : pgvector/pgvector:0.8.1-pg17 (PostgreSQL 17).
Plugins : Compiler 3.15.0, Surefire/Failsafe 3.5.4, Shade 3.6.0. Pas de contexte Spring local.
Compat utilise Spring Core 6.2.19, Guava 33.7.1-jre, Jakarta JSON 2.1.3 et Parsson 1.1.7
pour compiler/exécuter `HSCodeServiceImpl` sans modifier ses sources.
Les POM font foi ; ne pas rechercher les versions récentes pour une tâche ordinaire.

## Points d’entrée et invariants

Package RAG commun, sous `extension/src/main/java/` :
`com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/`.
Les tests sont sous le même package dans `src/test/java/`.

- `RagHSCodeAnalysisService` : `construct(properties, datasource, hsCodeService)` ouvre
  l’index PostgreSQL ; `loadIndex(datasource, hsCodeService)` l’ouvre sans client OpenAI.
  La DataSource et son pool appartiennent à l’appelant ; pas de lecture d’environnement dans le métier.
  `searchFromDescription()` et `searchDetailed()` retournent le même `SearchResult` complet.
- `OpenAiRagClient` : modèles LangChain4j Responses/embeddings, injectables pour tests.
  `Config` : modèle, limite de sortie, température, effort de raisonnement.
  Défauts : `gpt-4.1-mini`, 2048, température/effort non renseignés ; `store=false`,
  schéma strict, embeddings `text-embedding-3-small`, `maxRetries(0)` côté embeddings.
  La factory utilise `OpenAIProperties.modelName()` ; délais via les modèles injectés.
- `PrecomputedEmbeddingIndex` / `HSCodeService` : recherche exacte PostgreSQL via
  PgVectorEmbeddingStore, descriptions industrielles, données de la base supposées correctes.
  Le compte de recherche a uniquement SELECT ; aucune création SQL au démarrage.
  `PrecomputedIndexResources.packaged(hsCodeService)` valide nomenclature et fichiers ; `RagIndexImporter.importIndex`
  effectue un import local explicite transactionnel et verrouillé (table non vide = import ignoré sans comparaison).
  Le store en mémoire ne sert que dans les tests ; aucune solution de repli en production. Ne pas utiliser le store industriel `EmbeddingService`
  à leur place : format et descriptions différents.
- Ressources sous `extension/src/main/resources/` + package commun : `rag_v4.txt`,
  `rag_v4.schema.json`, `h6_2022/{manifest.json,vectors.jsonl}`.
  Lecture par flux, compatible JAR, aucun recalcul implicite ; vecteurs lus uniquement à l’import.
  `db/bootstrap.sql` prépare extension, rôles et schéma avant import (administrateur, une seule fois). Données complètes déjà
  présentes localement, ignorées par Git ; fixtures autonomes dans `src/test/resources/rag-fixtures/`.
- `HSCodeServiceImpl` est copié sans modification dans `compat`, avec
  `.../infra/service/hs_references/{H5.json,H6.json,conversionHS2022-HS2017.csv}`.
  Le RAG injecte `HSCodeService`, garde les sous-positions 2022 hors 98/99 et reconstruit
  chapitre > position > sous-position ; strip/guillemets normalisés préservent l’empreinte
  des embeddings existants.
  Le `lombok.config` de `compat/.../shared/kernel/goods/` active les accesseurs fluents
  de `HSCode` attendus par l’implémentation fournie, sans modifier les Java industriels.
- `runner/.../local/lestr/sandbox/Main.java` et `RagMain.java` : CLI/assemblage local.
  `extension` ne doit ni importer le runner ni redéfinir les classes de `compat`.
- `extension/src/test/java/.../analysis/ia/Vectorizer.java` : programme autonome de génération
  explicite de `vectors.jsonl` et du manifeste depuis `HSCodeServiceImpl`, par lots de 64.
  Arguments : nouveau dossier de sortie, limite optionnelle ; clé `OPENAI_API_KEY`.
  Test de structure sans API dans le même package : `VectorizerTest`.
  Lancer le `main` avec le classpath de test ; inclus dans les sources exportées, pas dans le JAR. Ne pas lancer le catalogue complet pour tester.
- Contrats fusionnés sans suffixe : `domain.spi.HSCodeAnalysisService` (recherche
  uniquement), `domain.model.SearchResult` et `infra.service.analysis.ia.model.Candidate`.
  Ils appartiennent à `extension` et remplacent les anciens contrats industriels lors
  du transfert. Aucun `analyse()` ni parcours Completion local ; `RagResult` supprimé.
  `Candidate.rating` est un `Double` nullable pour les appelants non RAG ; le constructeur
  à quatre arguments est conservé pour Verbatim. Le RAG exige un nombre fini de 1 à 5.
  La chaîne de secours et Verbatim reçus sont copiés sans modification dans `runner`
  pour compiler leurs appelants contre ces contrats, sans cycle avec `compat`.
- Codes HS textuels, zéros initiaux conservés, ordre LLM validé par rating décroissant, rangs non renumérotés
  après filtrage, source `OpenAI_Hybrid`. Les résultats conservent description et explication
  non vide par candidat généré ; `missing_information` contient les questions globales.
  Statuts JSON : `ok` (candidats, aucune question) et `needs_info` (questions, candidats éventuellement vides).
  Aucun `abstained` ; récupération vide, JSON invalide ou rejet de tous les codes : exception.
- `RagGenerationClient` distingue réponse JSON et refus explicite du fournisseur.
  Un refus Responses terminé retourne une liste vide et
  la question générique du POC en `needs_info`. Une réponse incomplète ou vide reste une erreur.
  La CLI propose uniquement `rag`, `rag-replay`, `rag-import` et `--help` ; elle émet directement le résultat, sans enveloppes `prediction` / `search_result`.
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
# Vérification des espaces ; aucun lint/formatter Java configuré
git diff --check
# Préparer les données si absentes/périmées, puis export (lance verify)
./scripts/prepare_rag_resources.sh
./simplified_env/export.sh -o
# Base Docker autonome + construction du service, sans appel API (secret requis)
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
Il crée sa propre base Docker, applique V1 et importe les vecteurs une seule fois en
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

Le script de préparation copie uniquement
`../artifacts/embeddings/h6_2022/{manifest.json,vectors.jsonl}` sans appeler d’API.
Seulement si une tâche demande la parité Python :
`../.venv/bin/python scripts/verify_rag_parity.py` (JAR à jour, base importée et variables RAG_DB exportées) ;
`../.venv/bin/python scripts/generate_rag_fixtures.py` régénère les fixtures suivies.

L’export produit `simplified_env/extension/target/transferable-sources.tar.gz`
(+ `.sha256`) avec `src/main`, `src/test`, `INTEGRATION.md`. Garder ce guide autonome.
Ne transférer ni `compat`, ni `runner`, ni POM locaux. L’assemblage Spring et les
appelants industriels restent à valider dans leur dépôt ; voir le README racine.

## Sources industrielles reçues et déploiement

- `src/main/java/.../infra/configuration/ApplicationConfiguration.java` injecte une
  `DataSource` dans le bean `HSCodeAnalysisService`.
- `infra/service/analysis/HSCodeServiceFactory.java` conserve le nom
  `constructOpenAICompletionAndVerbatim`, mais assemble déjà **RAG puis Verbatim**.
  Les contrats fusionnés sont `domain.spi.HSCodeAnalysisService`,
  `domain.model.SearchResult` et `infra.service.analysis.ia.model.Candidate`.
- `src/test/java/.../analysis/ia/RagHSCodeServiceMT.java` reçu utilise Testcontainers
  et Flyway, puis importe et vérifie les vecteurs. Il est désactivé avec `@Disabled`.
  Le test local reste activable explicitement selon les commandes ci-dessus.
- `src/main/resources/db/migration/V1__init.sql` reçu crée l’extension vector, le
  schéma rag et embeddings. C’est la seule migration nécessaire ; aucune table de
  manifeste ni vérification de correspondance des données n’est utilisée.
- Contexte confirmé par l’utilisateur : PostgreSQL tourne dans un conteneur Docker
  temporaire uniquement pour le test. Aucun hébergement Railway distant n’est requis.
  `src/main/docker/application.yml` configure PostgreSQL (`org.postgresql.Driver`)
  avec Hikari ; le test construit sa propre `PGSimpleDataSource` depuis le conteneur.
  Flyway désigne ici l’outil de migration SQL utilisé par le test industriel.
  Le contexte local est autonome : image pgvector épinglée, port dynamique, comptes
  de test, initialisation une fois par classe et destruction à la fin. Ne pas reprendre
  les identifiants du YAML industriel ni utiliser la base Compose pour ce test.
- Refus fournisseur et absence de candidat compatible : suivre le Python, résultat
  `needs_info` avec questions et liste vide ; aucun rating inventé. Une réponse
  incomplète, une récupération vide ou le rejet de tous les codes proposés reste une erreur.
- Le portage du prompt vise les constantes `PROMPT`/`SCHEMA` de
  `../hs_matching/approaches/rag.py` (`rag_v4`). Le rating est numérique entre 1 et 5,
  décimales admises ; les réponses doivent être en ordre décroissant. Les alternatives
  contrefactuelles ont un plafond de 3 demandé par le prompt. Le rating n’est ni un
  cosinus ni une probabilité. Les fixtures Python utilisent des fournisseurs simulés.

### Suivi des références fusionnées

`reference-manifest.json` conserve les empreintes reçues et locales, le chemin
`reference_path` sous `lestr_sources` pour les sources fusionnées, et le motif des écarts.
`SourceBoundaryTest` vérifie ces empreintes ainsi que l’absence de doublons entre
`compat` et `extension`. Les anciennes copies Completion et leurs tests ont été retirés
avec l’accord de l’utilisateur. Les tests de transport, catalogue, RAG et secours restent.
Les noms de ressources `rag_v4.*` versionnent le prompt Python ; les classes Java ne
portent aucun suffixe de version. Aucune reconstruction des embeddings n’est nécessaire.

### Migration Flyway du test manuel

`RagHSCodeServiceMT` utilise Flyway 12.4.0 (dépendances de test) avec la seule
`extension/src/main/resources/db/migration/V1__init.sql`, copie industrielle exacte
contrôlée par le manifeste des références. Les rôles de test sont créés séparément.
L’unique table métier est `rag.embeddings` ; Flyway a aussi sa table technique
`public.flyway_schema_history`. Le service suppose les données correctes, sans scan
ni comparaison avec les fichiers locaux. Il utilise 1536 dimensions comme V1 et
ne lit ni manifeste ni vecteurs locaux au démarrage. L’importeur reste un outil
explicite de préparation locale ; une table non vide est laissée telle quelle.
Le bootstrap reste réservé au parcours CLI/Compose et à ses tests PostgreSQL.
`PgVectorIndexIT#flywayV1SupportsExternallyLoadedDataWithoutManifest` vérifie la
recherche sur des données chargées extérieurement, sans secret ni API.
