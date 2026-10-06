# Mise à jour du service RAG intégré

Les sources utilisent désormais directement le package industriel
`com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia`.
Les contrats métier, le classement et les scores restent inchangés.

## Copier les fichiers

Reporter les sources et tests de l’archive sous les mêmes chemins dans le module
industriel. Mettre également à jour `src/main/resources` : cette archive contient
les données complètes, en plus du prompt et du schéma.

```text
src/main/resources/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/
  rag_v1.txt
  rag_v1.schema.json
  h6_2022/
    catalog.jsonl
    manifest.json
    vectors.jsonl
```

Conserver ces fichiers sans filtrage Maven ni modification de leur contenu.
Les empreintes sont vérifiées au chargement. Le nom du dossier Maven est
`resources` (pas `ressources`). Aucune extraction du JAR vers un dossier temporaire
n’est nécessaire : les ressources sont lues comme des flux du classpath.

Ne pas ajouter les classes/JAR de compatibilité ou le runner local au projet
industriel. Les sources ont besoin de Java 21, Jackson Databind, LangChain4j 1.20.0
(`langchain4j-open-ai`) et des contrats
industriels existants. Les tests utilisent JUnit Jupiter ; la completion conserve
ses dépendances habituelles.

## Modifier l’instanciation

Supprimer les deux arguments `Path` des appels à la factory :

```java
HSCodeAnalysisService completion = CompletionHSCodeChatServiceImpl.construct(
    openAIProperties, hsCodeService, HSVersion.V_2022);
RagHSCodeAnalysisService rag = RagHSCodeAnalysisService.construct(
    openAIProperties, completion);
```

La factory peut lever `IOException` si une ressource manque ou ne peut être lue ;
un contenu incompatible lève `IllegalArgumentException`. Réutiliser l’instance
ainsi créée : l’index est chargé une fois par construction, pas à chaque recherche.
Il n’est pas chargé pendant l’initialisation statique de la classe, pour conserver
des erreurs de chargement explicites et permettre les tests avec un index injecté.

Le constructeur prenant un index et des clients reste disponible pour les tests
et l’injection de configuration. `loadIndex()` charge le même index embarqué sans
créer de client réseau. Les constructeurs de bas niveau acceptant des chemins
restent utilisables par les outils de rejeu ; la factory du service ne prend plus
de chemins. Le constructeur par flux de l’index reçoit uniquement un
`ResourceOpener` et le catalogue.

Le service utilise `Source.OpenAI_Hybrid`. Un rejet de tous les candidats lève
`HSCodeAnalysisException` ; une abstention retourne une liste vide. `analyse()`
reste déléguée. `searchDetailed()` conserve explications et questions avec le score
constant 2.5 ; `toSearchResult()` projette vers le score industriel 3 sans autre
appel réseau ni reclassement.

Le contrat `RagResult` contient uniquement `status`, `candidates` et
`missing_information`. Chaque candidat contient `code`, `rank`, `description`,
`score` et `explanation`. Adapter les éventuels consommateurs de `metadata`,
`error`, `references` et `score_type`, désormais supprimés. Les erreurs remontent
par exception dès la recherche. L’index retourne les lignes du catalogue triées,
sans DTO de diagnostic ni exposition du manifeste.

## Client fournisseur LangChain4j

`OpenAiRagClient` utilise directement les modèles LangChain4j, sans `ChatService`
ni code de transport HTTP. `RagGenerationClient.generate()` retourne le texte
JSON métier ; `RagEmbeddingClient.embed()` retourne uniquement un `double[]`.
Le constructeur injectable reçoit un `ChatModel`, un `EmbeddingModel` et les
dimensions configurées. `Config` ne contient plus de délai ; les modèles
utilisent leurs valeurs par défaut ou la configuration fournie à l’injection.

Le service désérialise le texte avec Jackson et conserve le filtrage des codes.
Les erreurs techniques remontent à la chaîne de secours. L’enveloppe brute,
les usages, les refus techniques et les diagnostics HTTP ne font plus partie
du résultat détaillé. Les fichiers de rejeu contiennent le JSON métier seul.

## Test manuel et vérifications

`RagHSCodeServiceMT` utilise `System.getenv("OPENAI_API_KEY")` à la place de
`Utils.getSecret`. Il est activé uniquement si `RUN_OPENAI_MT=true` et sélectionné
explicitement par Maven, par exemple depuis la racine de ce dépôt :

```bash
RUN_OPENAI_MT=true sandbox_java/simplified_env/dev.sh test \
  -Dtest=RagHSCodeServiceMT -Dsurefire.failIfNoSpecifiedTests=false
```

Définir préalablement `OPENAI_API_KEY` dans l’environnement. Ce test effectue de
vrais appels, avec les coûts et la variabilité associés ; il n’est pas exécuté
par les vérifications ordinaires. Dans le dépôt industriel, utiliser le wrapper
et la sélection de module habituels avec les mêmes options de test.

Vérifier dans le module cible la présence des cinq ressources dans le JAR final,
le démarrage depuis un autre répertoire, la configuration OpenAI et les appels
métier. Les tests exportés couvrent le chargement depuis un JAR isolé, les fichiers
absents/corrompus, la fermeture des flux, les cas métier et les appels à des modèles LangChain4j simulés. Aucun test OpenAI réel n’a été lancé lors de cette mise à jour.
