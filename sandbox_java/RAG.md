# RAG Java : ressources embarquées, utilisation et intégration

`RagHSCodeAnalysisService` implémente `HSCodeAnalysisService` dans le module
`simplified_env/extension`. Le code de compatibilité industriel n’a pas été modifié.
Les types et ressources exportables résident sous le package
`com.semsoft.lestr.tradeanalysis.infra.service.analysis.ia`.

## Comportement et scores

Le service exécute le parcours suivant :

1. Charger une fois le catalogue HS 2022 anglais et son index précalculé.
2. Vectoriser uniquement la description demandée avec `text-embedding-3-small`.
3. Récupérer K voisins par cosinus (20 par défaut), avec K ≥ N.
4. Fournir leurs codes, descriptions et descriptions contextualisées au LLM,
   sans lui transmettre les scores cosinus. Le prompt `rag_v1` est chargé depuis les ressources du package. Le schéma restreint les codes aux voisins récupérés.
5. Valider la réponse et conserver l’ordre du LLM, les explications, les questions,
   l’abstention métier. Rejeter les codes invalides, inconnus, inadmissibles,
   hors récupération, dupliqués ou au-delà du rang N. Ne pas compléter la liste
   après rejet ; conserver les rangs originaux, même s’ils deviennent discontinus.

Les trois scores ont des rôles distincts :

| Emplacement | Valeur | Usage |
| --- | --- | --- |
| `RagResult.candidates[].score` | **2.5**, `score_type="constant"` | Valeur d’affichage demandée, sans signification de confiance. |
| `SearchResult.matchingHSCodes[].score` | **3**, entier | Adaptation approuvée au contrat `MatchingScore(int)`. |
| `metadata.retrieved_candidates[].score` | Cosinus de récupération | Diagnostic de recherche, jamais une confiance du LLM. |

Aucun de ces scores fixes ne provoque un tri. Le LLM ne reçoit pas de nouvelle
consigne de scoring et ne produit pas de pourcentage. L’explication par candidat
est le commentaire du service ; aucun champ « commentaires » supplémentaire n’est
inventé. `null` est accepté pour une explication comme dans les cas de référence.

`searchDetailed(description)` retourne au plus cinq candidats.
`searchDetailed(description, topK)` permet un N différent pour les expériences,
toujours avec N ≤ K. Les états sont `ok`, `needs_info`, `abstained`, `error`.
`missing_information` conserve les demandes de précision. Le résultat détaillé
expose aussi les candidats récupérés, les rejets, le prompt et les durées.
Il ne conserve pas l’enveloppe fournisseur, les usages ni les statuts techniques.

`searchFromDescription(description)` appelle le moteur puis l’adapte au contrat
industriel, limité à cinq résultats. `toSearchResult(resultatDetaille)` permet de
faire cette adaptation **sans rappeler les fournisseurs**. Les exceptions techniques remontent au secours industriel existant. Un résultat
métier `error` devient `HSCodeAnalysisException` lors de la projection.
Une abstention produit une liste vide ; un `needs_info` conserve ses candidats
provisoires s’il en contient. Le contrat existant ne peut pas exposer les questions
ni distinguer une abstention d’une liste vide : les appelants qui en ont besoin
doivent conserver le `RagResult`, pas seulement sa projection `SearchResult`.

La source RAG est la valeur existante `Source.OpenAI_Hybrid`. `analyse()` délègue
au service injecté et conserve sa source et son résultat ; ce n’est pas une
seconde analyse documentaire RAG.

## Données et chemins

La factory ne reçoit plus de chemins :

```java
RagHSCodeAnalysisService rag = RagHSCodeAnalysisService.construct(
        openAIProperties, analysisDelegate);
```

Le service charge les ressources relatives à son package :

```text
src/main/resources/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/
  rag_v1.txt
  rag_v1.schema.json
  h6_2022/
    catalog.jsonl
    manifest.json
    vectors.jsonl
```

Le catalogue et l’index sont lus par flux (`getResourceAsStream`), y compris quand
ils sont dans un JAR. Le dossier s’appelle `resources`, pas `ressources`.
L’index conserve **5 612 vecteurs, 1 536 dimensions**, modèle
`text-embedding-3-small`. Ses fichiers sont copiés à l’identique, sans recalcul.
Dans ce dépôt, les préparer avant packaging/export avec :

```bash
sandbox_java/scripts/prepare_rag_resources.sh
```

Ce script copie le catalogue de `data/processed/h6_2022/catalog.jsonl` et les deux
fichiers d’`artifacts/embeddings/h6_2022`. Les copies volumineuses sont ignorées par
Git mais incluses dans le JAR et dans l’archive exportée. Refaire la préparation
après un changement de données, puis reconstruire le JAR.

`RagCatalog` accepte les objets JSON successifs, y compris leur remise en forme
multiligne. Il applique l’éligibilité du service : code à six chiffres, niveau 6,
`is_candidate=true`, exclusion des codes spéciaux. Les zéros initiaux sont préservés.

`PrecomputedEmbeddingIndex` contrôle le manifeste, l’édition, la langue, le champ
contextualisé, le fournisseur/modèle, les dimensions, le nombre et l’ordre des codes,
l’empreinte des descriptions et celle des octets des vecteurs. Les vecteurs doivent
être numériques, finis, non nuls et représentables en float32. Les descriptions
font foi pour la compatibilité : le catalogue complet et le fichier des seuls
candidats peuvent avoir des empreintes brutes différentes mais la même empreinte
de descriptions admissibles, comme dans les cas de référence.

L’index est immuable après chargement et réutilisable par plusieurs requêtes. La
matrice float32 occupe environ 33 Mio, hors catalogue, objets et buffers temporaires.
Les vecteurs sont lus ligne par ligne. Aucun fichier d’index n’est modifié pendant
l’inférence et aucun index absent n’est reconstruit implicitement.

L’index est chargé une fois par construction de service, avec fermeture des flux,
sans extraction temporaire. Il n’est pas mis en cache dans un champ statique.
Un fichier manquant produit une `FileNotFoundException` indiquant son chemin de
classpath ; un contenu incompatible est rejeté par les mêmes validations.
`loadIndex()` expose ce chargement sans créer de client réseau. La métadonnée
`index_path` vaut désormais `classpath:/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/h6_2022/`.
Les constructeurs de bas niveau prenant des chemins restent disponibles pour les
outils de rejeu et les tests ; ils ne font plus partie de la factory du service.

## Lancer

Depuis la racine du dépôt, avec JDK 21 et Maven disponibles :

```bash
sandbox_java/scripts/prepare_rag_resources.sh
sandbox_java/simplified_env/dev.sh verify
```

Rejeu autonome sans API, sur les petits vecteurs **synthétiques de test** :

```bash
sandbox_java/simplified_env/dev.sh run rag-replay \
  sandbox_java/simplified_env/extension/src/test/resources/rag-fixtures/catalog.jsonl \
  sandbox_java/simplified_env/extension/src/test/resources/rag-fixtures \
  sandbox_java/simplified_env/extension/src/test/resources/rag-fixtures/query.json \
  sandbox_java/simplified_env/extension/src/test/resources/rag-fixtures/response.json \
  "Live horses" 2 2
```

La CLI imprime un objet JSON contenant `prediction` (résultat détaillé),
`search_result` (projection industrielle) et `replay`. Le code de sortie est 1
si le RAG produit `error`, 0 sinon. Un échec de chargement/configuration arrête la
commande avec une exception et un code non nul. Le rejeu reçoit un tableau JSON
de composantes du vecteur et le **JSON métier directement** (`status`, `missing_information`, `candidates`),
sans enveloppe Responses API.

Appel réel, après configuration de `OPENAI_API_KEY` dans l’environnement :

```bash
sandbox_java/simplified_env/dev.sh run rag \
  "Live pure-bred breeding horses" 5 20
```

Cette commande consomme une vectorisation de requête et une génération. Par défaut,
le LLM est `gpt-4.1-mini` et la limite de sortie 2048 tokens.
Température et effort de raisonnement ne sont pas envoyés par défaut. `OpenAiRagClient.Config` permet de les régler par injection Java.
Les dimensions de requête sont issues de `manifest.config.dimensions` ; une valeur
nulle signifie ne pas envoyer le paramètre et utiliser la dimension native du modèle.
Le vecteur reçu est ensuite contrôlé contre les 1 536 dimensions effectives de l’index.

Le client utilise **LangChain4j 1.20.0** : `OpenAiResponsesChatModel` pour la
génération et `OpenAiEmbeddingModel` pour la vectorisation, sans `ChatService`.
`generate()` retourne simplement `aiMessage().text()` ; `embed()` retourne le
vecteur. Le schéma dynamique est transmis avec `JsonRawSchema` et le mode strict.
Les instructions deviennent un message `system`, et le produit un message `user`.
`store=false` et les paramètres de génération sont conservés.

Il n’y a plus de décorateur HTTP, d’accès aux réponses HTTP brutes, de contrôle
de clé API ni de traduction personnalisée des exceptions. Les modèles gèrent
le transport. Le constructeur injectable reçoit un `ChatModel`, un
`EmbeddingModel` et les dimensions configurées ; il sert aussi aux tests.
`Config` contient le modèle, la limite de sortie, la température et l’effort de
raisonnement. Les délais suivent les valeurs par défaut de LangChain4j ; un
besoin de configuration réseau spécifique se règle sur les modèles injectés.
Les reprises embeddings restent désactivées (`maxRetries(0)`).

Le service désérialise le texte JSON avec Jackson. Il conserve les règles métier
sur les codes proposés et leurs rangs, sans valider à nouveau chaque champ du
schéma. Les exceptions du fournisseur remontent directement à l’appelant et
peuvent déclencher le secours industriel. Une erreur de parsing devient
`HSCodeAnalysisException`. Les refus et statuts techniques du fournisseur ne
sont plus interprétés séparément ; seule l’abstention exprimée dans le JSON
métier est conservée. Un texte de refus non JSON échoue donc au parsing.

## Vérifications et limites de parité

La simplification a été vérifiée par **31 tests ciblés**, dont **15 cas métier
issus du moteur de référence** : ordre, explications nullables, questions,
abstention, codes rejetés, doublons et rangs conservés. Les tests du client
utilisent des modèles LangChain4j simulés, sans serveur HTTP ni appel OpenAI.
Ils vérifient les messages, le schéma, les sorties et la propagation des erreurs.
Les tests du service et du chargement depuis un JAR ont également été exécutés.

Les anciens cas portant sur les enveloppes fournisseur et les validations
supplémentaires ont été retirés. La parité porte sur le parcours métier et la
recherche vectorielle ; elle ne porte plus sur les diagnostics fournisseur ou
sur le traitement détaillé de réponses qui ne respectent pas le schéma.

Recréer les références depuis le moteur de référence (opération de développement explicite) :

```bash
.venv/bin/python sandbox_java/scripts/generate_rag_fixtures.py
sandbox_java/simplified_env/dev.sh verify
```

Comparer le JAR au moteur de référence sur l’index réel, sans appeler d’API :

```bash
.venv/bin/python sandbox_java/scripts/verify_rag_parity.py --classpath
```

Ce script recharge l’index complet et compare quatre vecteurs de requête figés
issus de cet index, répartis entre les codes `010121`, `350219`, `721550` et
`970690`. Il compare les 20 voisins, le prompt, le schéma et le classement final,
avec une réponse LLM rejouée. Sur la vérification effectuée, **les 20 voisins et
leur ordre sont identiques dans les quatre cas** ; l’écart cosinus maximal est
`2.384185791015625e-7`. Le rapport est écrit dans
`simplified_env/extension/target/full-index-parity.json`.

La normalisation et le calcul de cosinus Java utilisent une matrice float32,
avec accumulation du produit scalaire en double puis arrondi float32. NumPy/BLAS
peut accumuler différemment. Les scores ne sont donc pas garantis identiques bit
à bit ; des valeurs extrêmement proches peuvent modifier un ordre de voisins.
Les égalités exactes sont départagées par code comme dans la configuration initiale. Le test utilise
une tolérance de `2e-6` sur les scores et exige le même ordre sur les cas vérifiés.
Il ne prétend pas prouver la parité pour toutes les requêtes possibles.

Les tests n’évaluent pas la pertinence métier d’un modèle réel : ils vérifient le
portage et les règles de traitement. Aucun appel OpenAI réel ni test dans le dépôt
industriel n’a été effectué. Les appels fournisseur sont simulés dans les tests.

## Test manuel OpenAI

`RagHSCodeServiceMT` utilise la factory sans chemins. Il lit la clé dans
`OPENAI_API_KEY` et ne s’exécute que sur demande explicite :

```bash
RUN_OPENAI_MT=true sandbox_java/simplified_env/dev.sh test \
  -Dtest=RagHSCodeServiceMT -Dsurefire.failIfNoSpecifiedTests=false
```

La clé doit déjà être définie dans l’environnement. Les recherches banana/toluene/
T-shirt appellent réellement OpenAI ; elles sont séparées des tests de build et
peuvent varier selon les réponses du modèle. Elles n’ont pas été lancées ici.

Pour vérifier le JAR et son index embarqué sans réseau, `rag-replay-classpath`
accepte `VECTOR_JSON RESPONSE_JSON DESCRIPTION [TOP_K [RETRIEVAL_K]]`.
Le vecteur fourni doit avoir les 1 536 dimensions de l’index réel ; les fixtures
synthétiques à deux dimensions servent uniquement à `rag-replay` et aux tests.

## Export et intégration

```bash
sandbox_java/simplified_env/export.sh -o
```

L’archive `simplified_env/extension/target/transferable-sources.tar.gz` contient
uniquement les nouvelles sources, leurs ressources, les tests RAG/fixtures et
`INTEGRATION.md`. Elle n’embarque ni contrats de compatibilité, ni runner,
ni dépendances binaires, ni clé. Le catalogue et l’index sont maintenant inclus dans
`src/main/resources/.../ia/h6_2022/` : l’archive est donc plus volumineuse. Son empreinte est fournie
dans le fichier adjacent `.sha256`.

Le guide court de transfert est [INTEGRATION.md](simplified_env/extension/INTEGRATION.md).
La procédure générale, les précautions sur les packages et les tests industriels
restent dans [README.md](README.md). Le transfert a été vérifié par extraction dans
une copie propre du socle local, remplacement intégral du module de sources par
l’archive et recompilation avec ses tests. Cela vérifie l’autonomie de l’export
vis-à-vis du workspace initial ; cela ne remplace pas la compilation industrielle.
