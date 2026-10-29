# Intégration du RAG PostgreSQL

Reporter les sources, ressources et tests utiles dans le module industriel en
conservant les packages. Ne transférer ni `compat`, ni `runner`, ni les POM locaux.
Le service industriel reçu sous `lestr_sources/hs_references/trade-analysis/`
contient le câblage Spring et les migrations Flyway. La factory assemble déjà RAG
puis Verbatim. Le banc local utilise les contrats fusionnés, sans suffixe de version.
Le contexte de test confirmé est une base PostgreSQL dans un conteneur Docker
éphémère. Le YAML industriel (`src/main/docker/application.yml`) configure une
DataSource PostgreSQL/Hikari ; le test utilise sa propre `PGSimpleDataSource` et
Flyway pour les migrations. Aucun service Railway distant n’est requis.
`RagHSCodeServiceMT` initialise sa base avec Flyway et des comptes de test,
indépendamment de la base Compose persistante et des identifiants industriels.

## Dépendances et ressources

Java 21 ; LangChain4j / OpenAI 1.20.0 ; **langchain4j-pgvector 1.20.0-beta30** ;
pilote PostgreSQL 42.7.12 ; Jackson Databind avec BOM 2.21.4.
Tests : Flyway `flyway-core` et `flyway-database-postgresql` 12.4.0 ; JUnit Jupiter 5.12.2, Testcontainers `postgresql` et `junit-jupiter` 1.21.4.
Les tests locaux utilisent PostgreSQL 17 / pgvector 0.8.1.

Sous `src/main/resources/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/`,
conserver sans filtrage `rag_v4.txt`, `rag_v4.schema.json`,
`h6_2022/{manifest.json,vectors.jsonl}` et `db/bootstrap.sql`.
Les vecteurs servent uniquement à l’import ; le manifeste reste nécessaire au démarrage.
Les descriptions viennent du `HSCodeService` industriel : injecter son instance
`HSCodeServiceImpl` existante, également utilisée pour l’import. Son chargement exige
`com/semsoft/lestr/tradeanalysis/infra/service/hs_references/{H5.json,H6.json,conversionHS2022-HS2017.csv}`
sur le classpath, ainsi que ses dépendances habituelles (Spring Core, Guava, Jakarta JSON
et un fournisseur JSON-P). Ces ressources/classes existent dans le projet industriel ;
ne pas les dupliquer dans le module RAG. Le bac à sable les fournit via `compat`.
Toutes ces ressources sont lisibles depuis un JAR.

Seuls les codes à six chiffres de HS 2022, hors chapitres 98/99, sont candidats.
Le contexte assemble chapitre, position et sous-position. La normalisation des espaces
extérieurs et des guillemets conserve exactement les textes des embeddings existants :
aucun recalcul ni rechargement de la base existante n’est nécessaire. Le manifeste
refuse toute nomenclature incompatible.

## Préparation puis import explicite

1. Fournir une base dédiée avec pgvector installé. Exécuter `db/bootstrap.sql`
   **une seule fois**, comme administrateur, dans cette base vide. Ce SQL prépare
   l’extension, le schéma `rag`, les rôles `rag_import` et `rag_reader` et leurs droits.
   Adapter ces rôles au provisionnement industriel si nécessaire, en conservant les
   droits et privilèges par défaut des futures tables. Configurer les mots de passe
   via le mécanisme de secrets industriel, séparément du SQL.
2. Exécuter l’importeur avec une `DataSource` du propriétaire `rag_import` :

```java
boolean imported = RagIndexImporter.importIndex(
    importDataSource, PrecomputedIndexResources.packaged(hsCodeService));
```

L’import crée `rag.embeddings` et `rag.index_manifest`, valide les fichiers et écrit
par lots dans une transaction verrouillée. Une erreur annule tout. Un réimport
identique vérifie puis retourne `false` sans écriture ; un contenu différent ou
incomplet est refusé. Le remplacement/versionnement d’index n’est pas implémenté.
Aucun appel fournisseur n’est nécessaire. Les paramètres industriels de connexion,
TLS, délais et pool appartiennent à la `DataSource` fournie.

## Service en lecture seule

```java
RagHSCodeAnalysisService rag = RagHSCodeAnalysisService.construct(
    openAIProperties, readOnlyDataSource, hsCodeService);
```

Réutiliser cette instance et injecter le compte `rag_reader`, limité à la lecture.
Le service vérifie le manifeste, les dimensions et les codes, sans importer ni lire
les vecteurs locaux. Il ne crée aucun objet SQL et ne ferme pas la `DataSource`.
La factory peut lever `IOException`, `SQLException` ou une erreur de validation.
`loadIndex(readOnlyDataSource, hsCodeService)` ouvre l’index sans construire de client OpenAI.

`RagHSCodeAnalysisService` implémente `HSCodeAnalysisService` : recherche uniquement,
aucun `analyse()` ni délégué Completion. Remplacer les contrats existants par
`domain.spi.HSCodeAnalysisService`, `domain.model.SearchResult` et
`infra.service.analysis.ia.model.Candidate` inclus dans les sources exportées.
Le service peut être inséré directement dans la chaîne industrielle RAG puis Verbatim.
`Candidate` ajoute `Double rating` ; son constructeur à quatre arguments est conservé
pour Verbatim, avec rating nul. Aucun changement de son classement par rang n’est fait.
Les copies de la chaîne et de Verbatim dans le runner servent uniquement à valider
cette compatibilité et ne doivent pas être transférées.

`searchFromDescription()` retourne au maximum cinq candidats ; `searchDetailed(description, topK)`
permet un nombre explicite, avec `retrievalK >= topK`. Les deux renvoient le même résultat :

```json
{
  "source": "OpenAI_Hybrid",
  "status": "needs_info",
  "candidates": [
    {"code": "010121", "rank": 1, "description": "Pure-bred breeding animals", "explanation": "Provisional match, conditional on breeding use", "rating": 2.5}
  ],
  "missing_information": ["Is the animal intended for breeding?"]
}
```

Les codes sont textuels, les descriptions viennent du catalogue, les rangs restent ceux
proposés par le LLM, même après filtrage. Le rating numérique indépendant est obligatoire pour le RAG, entre 1 et 5, décimales admises.
L’ordre doit être décroissant ; une violation est une erreur de validation.
`SearchResult.Status.OK` et `NEEDS_INFO` se sérialisent `ok` et `needs_info`.
Les explications générées doivent être des chaînes non vides. Les listes du résultat sont immuables.
`ok` exige des candidats et aucune question ; `needs_info` exige des questions non vides et peut avoir une liste de candidats vide. `abstained` et les champs JSON supplémentaires sont rejetés.
Une récupération vide, une réponse invalide ou le rejet de tous les codes proposés lève
une exception. Les erreurs techniques ne sont pas transformées en résultat métier.

`RagGenerationClient` retourne soit `Answer` (JSON), soit `Refusal`. Le client OpenAI
vérifie le statut Responses et ses contenus `refusal` via les métadonnées HTTP du modèle
LangChain4j. Un refus explicite d’une réponse terminée produit `needs_info`, une liste
vide de candidats et la question générique du POC sur les caractéristiques
de la marchandise. Une réponse incomplète ou vide reste une erreur. Les modèles injectés
pour les tests peuvent retourner du texte sans métadonnées HTTP ; leur motif de fin,
lorsqu’il est renseigné, doit être `STOP`.

Supprimer les anciens fichiers `*_v2`, `RagResult` et les ressources `rag_v1.*` /
`rag_v2.*` lors du remplacement. Le seul client de génération est `RagGenerationClient`
et les seules ressources de prompt sont `rag_v4.*`. Aucun secours implicite vers un
index en mémoire. Le changement de prompt/contrat ne nécessite aucun recalcul des vecteurs.

## Validation

Le profil Maven local `pgvector-it` utilise Failsafe 3.5.4 (`integration-test`,
`verify`) pour `PgVectorIndexIT`. Reprendre cette sélection dans le module cible :
les `*IT` ne sont pas exécutés par Surefire par défaut. Le test démarre sa propre
base Docker, importe des fixtures, vérifie recherches, droits, rollback et concurrence.
`-Drag.fullIndex=true` active aussi l’import complet et la comparaison Java.
Les tests ordinaires et PostgreSQL sont sans appels API.

Valider ensuite le contexte Spring, les contrats/dépendances réels et les droits du
compte applicatif. `RagHSCodeServiceMT` est autonome : il crée un conteneur pgvector
neuf, prépare les rôles avec des identifiants de test, importe les ressources puis
compare chaque code et composante de vecteur à `vectors.jsonl` (après conversion
float32 et normalisation L2, sans tolérance). Cette préparation s’exécute une seule
fois pour toute la classe ; le conteneur est supprimé à la fin, même en cas d’échec.
Aucun Compose, base préexistante ni variable `RAG_DB_*` n’est nécessaire.

Sélectionner explicitement `-Dtest=RagHSCodeServiceMT` avec `RUN_OPENAI_MT=true`.
Le test charge la clé via le mécanisme industriel :
`new OpenAIProperties(Objects.requireNonNull(Utils.getSecret("OPENAI-API")))`, avec
`com.semsoft.lestr.common.test.Utils` du projet industriel. Ne pas transférer son
substitut local de `compat`, qui lit `OPENAI_API_KEY` uniquement dans le bac à sable. Le moteur Docker, les dépendances
Testcontainers et les ressources complètes sur le classpath suffisent côté base ;
Testcontainers récupère l’image automatiquement si elle manque.
`-Dtest=RagHSCodeServiceMT#serviceCanBeInstantiated` vérifie toute la préparation et
l’égalité des vecteurs sans appel payant ; le secret reste requis à l’initialisation.

## Régénérer les vecteurs explicitement

`src/test/java/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/Vectorizer.java`
est un programme autonome : lancer son `main` avec le classpath de test (par exemple
via l’IDE), `OPENAI_API_KEY` dans l’environnement et les arguments
`DOSSIER_SORTIE [LIMITE]`. Le dossier doit être inexistant, son parent doit exister.
Il utilise `HSCodeServiceImpl` et `text-embedding-3-small`, sans base PostgreSQL.
Une limite de `2` permet un essai payant ciblé ; sans limite, tout le catalogue est
vectorisé. `VectorizerTest` vérifie la structure sans appel API.
Seule une génération complète fournit un index importable : remplacer ensemble
`vectors.jsonl` et `manifest.json` dans les ressources `h6_2022`, puis reconstruire.
L’import refuse de remplacer un index différent déjà présent en base.

## Migrations du test manuel

`RagHSCodeServiceMT` exécute `classpath:db/migration` :
- `V1__init.sql` est la copie exacte de la migration industrielle (vecteurs de 1536 dimensions).
- `V2__rag_index_manifest.sql` ajoute le marqueur de fin d’import exigé par le RAG.

Conserver la V1 existante dans le dépôt industriel ; intégrer V2 avec un numéro libre
si cette version y est déjà occupée. Ne pas modifier une migration déjà appliquée.
L’importeur accepte les deux tables vides préparées par Flyway, puis importe vecteurs
et manifeste dans une transaction. Un index partiel ou différent reste refusé ; un
index identique ne provoque aucune écriture. Les rôles de test sont créés après les
migrations : import avec SELECT/INSERT, service avec SELECT uniquement.
Le bootstrap reste disponible pour la CLI/Compose et les tests de ce parcours.
