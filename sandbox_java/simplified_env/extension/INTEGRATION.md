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
conserver sans filtrage `rag_v2.txt`, `rag_v2.schema.json`,
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

`RagHSCodeAnalysisService` implémente `HSCodeAnalysisService_v2` : recherche uniquement,
aucun `analyse()` ni délégué Completion. Copier les nouveaux contrats `domain.spi.HSCodeAnalysisService_v2`,
`domain.model.SearchResult_v2` et `domain.model.Candidate_v2` avec les sources du service.
Les contrats industriels v1 sont conservés et ne doivent pas être écrasés. Le service
ne s’insère plus dans une collection de `HSCodeAnalysisService` v1 : adapter les appelants
et le câblage Spring au contrat v2 dans le dépôt industriel.

`searchFromDescription()` retourne au maximum cinq candidats ; `searchDetailed(description, topK)`
permet un nombre explicite, avec `retrievalK >= topK`. Les deux renvoient le même résultat :

```json
{
  "source": "OpenAI_Hybrid",
  "status": "needs_info",
  "candidates": [
    {"code": "010121", "rank": 1, "description": "Pure-bred breeding animals", "explanation": "Provisional match"}
  ],
  "missing_information": ["Is the animal intended for breeding?"]
}
```

Les codes sont textuels, les descriptions viennent du catalogue, les rangs restent ceux
proposés par le LLM, même après filtrage. Aucun score de confiance n’est ajouté.
`SearchResult_v2.Status.OK` et `NEEDS_INFO` se sérialisent `ok` et `needs_info`.
Les explications peuvent être nulles. Les listes du résultat sont immuables.
`ok` exige des candidats et aucune question ; `needs_info` exige des candidats provisoires
et des questions non vides. `abstained` et les champs JSON supplémentaires sont rejetés.
Une récupération vide, une réponse invalide ou le rejet de tous les codes proposés lève
une exception. Les erreurs techniques ne sont pas transformées en résultat métier.

`RagGenerationClient_v2` retourne soit `Answer` (JSON), soit `Refusal`. Le client OpenAI
vérifie le statut Responses et ses contenus `refusal` via les métadonnées HTTP du modèle
LangChain4j. Un refus explicite d’une réponse terminée produit `needs_info`, les premiers
voisins, des explications nulles et la question générique du POC sur les caractéristiques
de la marchandise. Une réponse incomplète ou vide reste une erreur. Les modèles injectés
pour les tests peuvent retourner du texte sans métadonnées HTTP ; leur motif de fin,
lorsqu’il est renseigné, doit être `STOP`.

`RagResult` et `RagGenerationClient` v1 restent disponibles mais ne sont plus utilisés
par le service. Aucun secours implicite vers un index en mémoire.

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
Le test charge la clé comme `CompletionHSCodeServiceMT` :
`new OpenAIProperties(Objects.requireNonNull(Utils.getSecret("OPENAI-API")))`, avec
`com.semsoft.lestr.common.test.Utils` du projet industriel. Ne pas transférer son
substitut local de `compat`, qui lit `OPENAI_API_KEY` uniquement dans le bac à sable. Le moteur Docker, les dépendances
Testcontainers et les ressources complètes sur le classpath suffisent côté base ;
Testcontainers récupère l’image automatiquement si elle manque.
`-Dtest=RagHSCodeServiceMT#serviceCanBeInstantiated` vérifie toute la préparation et
l’égalité des vecteurs sans appel payant ; le secret reste requis à l’initialisation.
