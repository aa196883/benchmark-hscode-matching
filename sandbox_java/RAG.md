# RAG Java : utilisation, parité Python et intégration

`RagHSCodeAnalysisService` implémente `HSCodeAnalysisService` dans le module
`simplified_env/extension`. Le code de compatibilité industriel n’a pas été modifié.
Les types et ressources exportables résident sous le package
`com.semsoft.lestr.tradeanalysis.infra.service.analysis.rag`.

## Comportement et scores

Le service reprend le parcours de `hs_matching/approaches/rag.py` :

1. Charger une fois le catalogue HS 2022 anglais et son index précalculé.
2. Vectoriser uniquement la description demandée avec `text-embedding-3-small`.
3. Récupérer K voisins par cosinus (20 par défaut), avec K ≥ N.
4. Fournir leurs codes, descriptions et descriptions contextualisées au LLM,
   sans lui transmettre les scores cosinus. Le prompt `rag_v1` est copié à
   l’identique depuis Python. Le schéma restreint les codes aux voisins récupérés.
5. Valider la réponse et conserver l’ordre du LLM, les explications, les questions,
   l’abstention et les refus. Rejeter les codes invalides, inconnus, inadmissibles,
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
est le commentaire du POC ; aucun champ « commentaires » supplémentaire n’est
inventé. `null` est accepté pour une explication comme en Python.

`searchDetailed(description)` retourne au plus cinq candidats.
`searchDetailed(description, topK)` permet un N différent pour les expériences,
toujours avec N ≤ K. Les états sont `ok`, `needs_info`, `abstained`, `error`.
`missing_information` conserve les demandes de précision. Le résultat détaillé
expose aussi les candidats récupérés, les rejets, le prompt, la réponse brute,
les usages de tokens, la configuration et les durées.

`searchFromDescription(description)` appelle le moteur puis l’adapte au contrat
industriel, limité à cinq résultats. `toSearchResult(resultatDetaille)` permet de
faire cette adaptation **sans rappeler les fournisseurs**. Une erreur devient
`HSCodeAnalysisException`, donc peut activer le secours industriel existant.
Une abstention produit une liste vide ; un `needs_info` conserve ses candidats
provisoires s’il en contient. Le contrat existant ne peut pas exposer les questions
ni distinguer une abstention d’une liste vide : les appelants qui en ont besoin
doivent conserver le `RagResult`, pas seulement sa projection `SearchResult`.

La source RAG est la valeur existante `Source.OpenAI_Hybrid`. `analyse()` délègue
au service injecté et conserve sa source et son résultat ; ce n’est pas une
seconde analyse documentaire RAG.

## Données et chemins

L’index existant est lu directement depuis `artifacts/embeddings/h6_2022` :
**5 612 vecteurs, 1 536 dimensions**, modèle `text-embedding-3-small`. Il n’a pas été
copié ni recalculé. Il faut également fournir le catalogue contenant les libellés :
`data/processed/h6_2022/catalog.jsonl` ou `candidates.jsonl`. Les fichiers de vecteurs
ne contiennent pas les descriptions nécessaires au prompt.

`RagCatalog` accepte les objets JSON successifs, y compris leur remise en forme
multiligne. Il applique l’éligibilité du POC : code à six chiffres, niveau 6,
`is_candidate=true`, exclusion des codes spéciaux. Les zéros initiaux sont préservés.

`PrecomputedEmbeddingIndex` contrôle le manifeste, l’édition, la langue, le champ
contextualisé, le fournisseur/modèle, les dimensions, le nombre et l’ordre des codes,
l’empreinte des descriptions et celle des octets des vecteurs. Les vecteurs doivent
être numériques, finis, non nuls et représentables en float32. Les descriptions
font foi pour la compatibilité : le catalogue complet et le fichier des seuls
candidats peuvent avoir des empreintes brutes différentes mais la même empreinte
de descriptions admissibles, comme en Python.

L’index est immuable après chargement et réutilisable par plusieurs requêtes. La
matrice float32 occupe environ 33 Mio, hors catalogue, objets et buffers temporaires.
Les vecteurs sont lus ligne par ligne. Aucun fichier d’index n’est modifié pendant
l’inférence et aucun index absent n’est reconstruit implicitement.

Tous les chemins sont injectés. Les chemins de la CLI sont relatifs au répertoire
courant ; les ressources de prompt/schéma sont chargées par `InputStream` depuis
le classpath, donc également depuis le JAR. L’index externe reste hors de l’archive
Java à transférer. En production, configurer explicitement son emplacement et celui
du catalogue, et les rendre disponibles sur le système cible.

## Lancer

Depuis la racine du dépôt, avec JDK 21 et Maven disponibles :

```bash
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
de composantes du vecteur et une **enveloppe Responses API complète**.

Appel réel, après configuration de `OPENAI_API_KEY` dans l’environnement :

```bash
sandbox_java/simplified_env/dev.sh run rag \
  data/processed/h6_2022/catalog.jsonl \
  artifacts/embeddings/h6_2022 \
  "Live pure-bred breeding horses" 5 20
```

Cette commande consomme une vectorisation de requête et une génération. Par défaut,
le LLM est `gpt-4.1-mini`, la limite de sortie 2048 tokens et le délai de chaque appel
60 secondes, comme dans le POC. Température et effort de raisonnement ne sont pas
envoyés par défaut. `OpenAiRagClient.Config` permet de les régler par injection Java.
Les dimensions de requête sont issues de `manifest.config.dimensions` ; une valeur
nulle signifie ne pas envoyer le paramètre et utiliser la dimension native du modèle.
Le vecteur reçu est ensuite contrôlé contre les 1 536 dimensions effectives de l’index.

Le nouveau client utilise `java.net.http.HttpClient` et Jackson 2.21.4, avec les
mêmes endpoints `/embeddings` et `/responses` et les mêmes corps de requête que
Python. Il préserve `status`, refus, usages et réponse brute, informations que le
`ChatService` existant ne retourne pas. Il envoie `store=false` et n’ajoute pas de
reprise automatique. Les erreurs HTTP sont assainies : code HTTP et identifiant
de requête sont conservés, mais pas le corps d’erreur ni les en-têtes d’autorisation.
La configuration industrielle peut injecter un `HttpClient` et une URI de base
pour son proxy/TLS et ses tests. La clé ne doit jamais être passée en argument CLI.

## Vérifications et limites de parité

La suite comprend **76 tests réussis** : 27 tests du socle et 49 tests RAG,
dont **31 cas de référence générés par le Python existant**. Les cas couvrent
classement, explication nullable, demandes de précision avec/sans candidats,
abstention, refus, réponses incomplètes, codes rejetés, doublons, rangs non
renumérotés et entrées invalides. Les autres tests couvrent les fichiers corrompus,
les empreintes, les dimensions, les erreurs fournisseurs, les deux scores,
la délégation d’analyse, les appels concurrents et le protocole HTTP réel contre
un serveur local. Les tests RAG sont inclus dans l’export et ne dépendent pas du
runner ni d’une installation Python.

Recréer les références depuis Python (opération de développement explicite) :

```bash
.venv/bin/python sandbox_java/scripts/generate_rag_fixtures.py
sandbox_java/simplified_env/dev.sh verify
```

Comparer le JAR au Python sur l’index réel, sans appeler d’API :

```bash
.venv/bin/python sandbox_java/scripts/verify_rag_parity.py
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
Les égalités exactes sont départagées par code comme dans le POC. Le test utilise
une tolérance de `2e-6` sur les scores et exige le même ordre sur les cas vérifiés.
Il ne prétend pas prouver la parité pour toutes les requêtes possibles.

Les tests n’évaluent pas la pertinence métier d’un modèle réel : ils vérifient le
portage et les règles de traitement. Aucun appel OpenAI réel ni test dans le dépôt
industriel n’a été effectué. Les délais, erreurs et formats HTTP sont exercés localement.

## Export et intégration

```bash
sandbox_java/simplified_env/export.sh -o
```

L’archive `simplified_env/extension/target/transferable-sources.tar.gz` contient
uniquement les nouvelles sources, leurs ressources, les tests RAG/fixtures et
`INTEGRATION.md`. Elle n’embarque ni contrats de compatibilité, ni runner,
ni dépendances binaires, ni gros catalogue/index, ni clé. Son empreinte est fournie
dans le fichier adjacent `.sha256`.

Le guide court de transfert est [INTEGRATION.md](simplified_env/extension/INTEGRATION.md).
La procédure générale, les précautions sur les packages et les tests industriels
restent dans [README.md](README.md). Le transfert a été vérifié par extraction dans
une copie propre du socle local, remplacement intégral du module de sources par
l’archive et recompilation avec ses tests. Cela vérifie l’autonomie de l’export
vis-à-vis du workspace initial ; cela ne remplace pas la compilation industrielle.
