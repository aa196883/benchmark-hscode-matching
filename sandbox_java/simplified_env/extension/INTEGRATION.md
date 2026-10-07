# Intégration du RAG Java

Cette archive contient les sources, ressources et tests du module `extension`.
L’application industrielle complète n’étant pas disponible dans le bac à sable,
son câblage et ses dépendances doivent être validés dans le module cible.

1. Comparer puis reporter `src/main` et les tests utiles dans le module qui contient
   la completion, en conservant les packages. Ne copier ni `compat`, ni `runner`,
   ni leurs JAR/POM : les contrats industriels existent déjà.
2. Aligner les dépendances avec le projet cible. Socle local : Java 21,
   LangChain4j 1.20.0 (`langchain4j`, `langchain4j-open-ai`), Jackson BOM 2.21.4 /
   Databind ; JUnit Jupiter 5.12.2 pour les tests.
3. Inclure sans filtrage les cinq ressources sous
   `src/main/resources/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/` :
   `rag_v1.txt`, `rag_v1.schema.json`, `h6_2022/catalog.jsonl`,
   `h6_2022/manifest.json`, `h6_2022/vectors.jsonl`.
4. Réutiliser la configuration/secrets industriels et assembler le service :

```java
HSCodeAnalysisService completion = CompletionHSCodeChatServiceImpl.construct(
    openAIProperties, hsCodeService, HSVersion.V_2022);
RagHSCodeAnalysisService rag = RagHSCodeAnalysisService.construct(
    openAIProperties, completion);
```

Réutiliser l’instance : l’index est chargé une fois par construction, via des flux du
classpath, sans extraction ni recalcul. La factory peut lever `IOException` ; les
données incompatibles sont rejetées. Les modèles réseau peuvent être configurés
via le constructeur injectable d’`OpenAiRagClient` (délais, etc.).

`searchFromDescription()` retourne au plus cinq codes, dans l’ordre LLM, avec score
fixe 3 et source `OpenAI_Hybrid`. `searchDetailed()` conserve explications/questions
et score 2.5 ; `toSearchResult()` adapte sans nouvel appel. `analyse()` délègue à la
completion. Les erreurs techniques et le rejet de tous les codes proposés lèvent
une exception ; une abstention produit une liste vide. La chaîne industrielle
existante poursuit après exception, mais s’arrête sur un résultat vide.

Valider dans le projet cible : compilation et tests avec les vrais contrats,
assemblage Spring et ordre du secours, ressources dans le JAR final, sérialisation
pour les appelants, puis quelques appels réels avec la configuration autorisée.
Les tests ordinaires exportés utilisent des modèles simulés ; `RagHSCodeServiceMT`
nécessite `OPENAI_API_KEY`, `RUN_OPENAI_MT=true` et une sélection Maven explicite
(`-Dtest=RagHSCodeServiceMT#searchBanana`).
