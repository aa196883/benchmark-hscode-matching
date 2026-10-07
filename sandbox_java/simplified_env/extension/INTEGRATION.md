# Intégration du RAG PostgreSQL

Reporter les sources, ressources et tests utiles dans le module industriel en
conservant les packages. Ne transférer ni `compat`, ni `runner`, ni les POM locaux.
Le provisionnement réel de `trade-analysis` et son câblage Spring restent à définir
dans le dépôt industriel ; les extraits fournis ne contiennent pas ces éléments.

## Dépendances et ressources

Java 21 ; LangChain4j / OpenAI 1.20.0 ; **langchain4j-pgvector 1.20.0-beta30** ;
pilote PostgreSQL 42.7.12 ; Jackson Databind avec BOM 2.21.4.
Tests : JUnit Jupiter 5.12.2, Testcontainers `postgresql` et `junit-jupiter` 1.21.4.
Les tests locaux utilisent PostgreSQL 17 / pgvector 0.8.1.

Sous `src/main/resources/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/`,
conserver sans filtrage `rag_v1.txt`, `rag_v1.schema.json`,
`h6_2022/{catalog.jsonl,manifest.json,vectors.jsonl}` et `db/bootstrap.sql`.
Les vecteurs servent uniquement à l’import ; catalogue/manifeste restent nécessaires
au démarrage du service. Toutes ces ressources sont lisibles depuis un JAR.

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
    importDataSource, PrecomputedIndexResources.packaged());
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
    openAIProperties, completion, readOnlyDataSource);
```

Réutiliser cette instance et injecter le compte `rag_reader`, limité à la lecture.
Le service vérifie le manifeste, les dimensions et les codes, sans importer ni lire
les vecteurs locaux. Il ne crée aucun objet SQL et ne ferme pas la `DataSource`.
La factory peut lever `IOException`, `SQLException` ou une erreur de validation.
`loadIndex(readOnlyDataSource)` ouvre l’index sans construire de client OpenAI.

`searchFromDescription()` conserve cinq résultats maximum, l’ordre LLM, le score
fixe 3 et `OpenAI_Hybrid`. `searchDetailed()` conserve explications/questions et
score 2.5 ; `toSearchResult()` adapte sans nouvel appel. `analyse()` délègue au
service injecté. Les erreurs techniques déclenchent le secours existant ; une
abstention vide arrête cette chaîne. Aucun secours implicite vers un index en mémoire.

## Validation

Le profil Maven local `pgvector-it` utilise Failsafe 3.5.4 (`integration-test`,
`verify`) pour `PgVectorIndexIT`. Reprendre cette sélection dans le module cible :
les `*IT` ne sont pas exécutés par Surefire par défaut. Le test démarre sa propre
base Docker, importe des fixtures, vérifie recherches, droits, rollback et concurrence.
`-Drag.fullIndex=true` active aussi l’import complet et la comparaison Java.
Les tests ordinaires et PostgreSQL sont sans appels API.

Valider ensuite le contexte Spring, les contrats/dépendances réels et les droits du
compte applicatif. Pour un appel réel explicite, `RagHSCodeServiceMT#searchBanana`
utilise `RUN_OPENAI_MT=true`, `OPENAI_API_KEY` et `RAG_DB_URL`, `RAG_DB_USER`,
`RAG_DB_PASSWORD` sur une base déjà importée.
