# Intégration des sources RAG exportées

Ces sources implémentent `HSCodeAnalysisService` sans modifier les contrats existants.
La procédure est une hypothèse d’intégration à valider dans le dépôt industriel.

1. Extraire l’archive dans un dossier de revue. Copier `src/main/java` et
   `src/main/resources` dans le module qui possède les implémentations d’analyse.
   Conserver les packages et les chemins relatifs. Copier les tests/resources de
   `src/test` dans le module de test approprié.
2. Résoudre les contrats `com.semsoft.lestr.shared.kernel.goods.HSCode`, les modèles
   `com.semsoft.lestr.tradeanalysis.domain.model.*`, `HSCodeAnalysisService` et
   `OpenAIProperties` via les dépendances **industrielles existantes**. Ne pas ajouter
   `compat`, `runner` ou leur JAR. Aucun de ces types n’est redéfini par l’export.
3. Compiler en Java 21 ; déclarer Jackson Databind via le BOM industriel 2.21.4.
   Les tests nécessitent JUnit Jupiter 5.12.2 (ou sa version industrielle compatible).
   Le RAG utilise le client HTTP du JDK ; LangChain4j 1.20.0 reste utilisé par le
   service de completion auquel `analyse()` est délégué.
4. Fournir le catalogue HS 2022 anglais et le répertoire contenant `manifest.json`
   et `vectors.jsonl`, provenant du même index Python `text-embedding-3-small`.
   Ces données externes ne sont pas dans l’archive. Les chemins sont injectés via
   la configuration industrielle, jamais déduits du répertoire de lancement.
5. Au démarrage, construire une seule instance et réutiliser son index :

```java
HSCodeAnalysisService completion = CompletionHSCodeChatServiceImpl.construct(
    openAIProperties, hsCodeService, HSVersion.V_2022);
RagHSCodeAnalysisService rag = RagHSCodeAnalysisService.construct(
    catalogPath, indexDirectory, openAIProperties, completion);
```

Le constructeur complet permet d’injecter les clients `RagEmbeddingClient`,
`RagGenerationClient`, le service d’analyse et K. Le factory utilise le nom de modèle
fourni par `OpenAIProperties`, 2048 tokens et 60 secondes. Pour un autre réglage,
construire `OpenAiRagClient` avec `Config`, puis injecter la même instance pour
la vectorisation et la génération. Son autre constructeur accepte le client HTTP
et l’URI de base configurés par l’application.

6. Brancher `rag` à l’endroit voulu de la factory/chaîne de secours. Les résultats
   RAG ont `Source.OpenAI_Hybrid`. Les erreurs de recherche deviennent
   `HSCodeAnalysisException` ; l’abstention est un résultat vide et ne déclenche
   pas le secours de la chaîne existante. `analyse()` conserve la source du délégué.
7. Si l’appelant doit afficher les explications/questions/statuts, appeler
   `searchDetailed()` et conserver son `RagResult`. Utiliser `toSearchResult()`
   pour obtenir en plus la projection industrielle sans second appel réseau.
   Le score détaillé est 2.5 constant ; le score industriel 3 constant.
   L’ordre du LLM et les rangs originaux sont conservés ; ne pas reclasser par score.

À vérifier dans le projet cible : compilation/BOM, découverte et câblage Spring,
présence des ressources dans le JAR, accès au catalogue/index montés, cohérence avec
le vrai référentiel métier, interprétation de `Source.OpenAI_Hybrid`, affichage des
scores constants, abstention/erreur/secours, traitement des demandes de précision,
proxy/TLS/secrets, délais fournisseurs et charge concurrente. Les traces détaillées
incluent la description, les candidats et la réponse ; appliquer la politique de
journalisation de l’application avant de les persister.

Les tests exportés utilisent des données synthétiques de petite dimension et des
réponses figées (31 cas issus du Python), ainsi qu’un serveur HTTP local. Ils
n’appellent pas OpenAI. Aucun résultat de test local ne prouve l’intégration dans
le dépôt industriel auquel nous n’avons pas accès.
