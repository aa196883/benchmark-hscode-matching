# Environnement Java local et transfert industriel

Ce dossier contient le socle Java local et le portage RAG implémentant
`HSCodeAnalysisService`. La completion existante sert de référence de compatibilité.
Le [guide RAG](RAG.md) décrit les commandes, l’index précalculé, les scores fixes
(2.5 détaillé / 3 industriel), les tests de parité et l’export.

Les tests locaux établissent une compatibilité avec les extraits reçus. La procédure
vers le dépôt industriel est une **hypothèse d’intégration argumentée (« educated guess »)** :
ce dépôt, son assemblage Spring et ses appelants ne sont pas disponibles ici.

## 1. Organisation et choix

```text
sandbox_java/
  lestr_sources/                 extraits reçus, hors compilation Maven
  simplified_env/
    pom.xml                     agrégateur Maven, Java 21
    compat/                     contrats et services industriels reproduits
    extension/                  service RAG et tests transférables
    runner/                     référentiel local, doubles, CLI, tests
    reference-manifest.json     empreintes et écarts des copies
    dev.sh                      compilation, tests, lancement
    export.sh                   export des seules sources extension
```

Le découpage en trois petits modules impose une frontière de dépendances :
`extension` compile contre `compat` avec la portée Maven `provided` ; `runner`
dépend des deux. Le nouveau code ne peut pas utiliser le lanceur comme dépendance.
Un test vérifie également l’absence de références `local.lestr.sandbox` dans les
sources principales d’`extension`, l’absence de redéfinition des classes de `compat`
et la correspondance entre packages et chemins. Ce contrôle de sources n’est pas
une preuve complète d’indépendance : la revue des dépendances reste nécessaire.

Les noms de packages `com.semsoft.lestr...` sont conservés. Les classes de
compatibilité sont des remplacements **locaux**, jamais des classes à ajouter au
projet industriel. L’environnement n’exige ni Spring, ni Docker, ni base de données,
ni serveur web applicatif. L’implémentation industrielle de `HSCodeServiceImpl`
n’ayant pas été fournie, le référentiel local garantit les opérations décrites ici,
pas l’équivalence de ses données et de toutes ses règles métier. Le serveur HTTP éphémère des tests écoute uniquement
sur la boucle locale et simule les réponses du fournisseur.

Versions : Java 21, LangChain4j **1.20.0**, Jackson BOM **2.21.4**, conformément aux
informations reçues. Le BOM aligne notamment `jackson-core` et `jackson-databind` ;
`jackson-annotations` suit la version décidée par ce BOM, pas nécessairement le même
numéro. Les versions de Lombok, JSpecify, Commons et des plugins Maven sont des
choix locaux explicites dans les POM, à comparer au dependency management industriel.
Lombok est conservé pour ne pas réécrire les méthodes générées d’`HSCode`.

### Ce qui est conservé et ce qui est simplifié

| Élément | Traitement local |
| --- | --- |
| Contrats `HSCodeAnalysisService`, `HSCodeService`, records, enums, `HSCode`, scores | Copies des extraits, mêmes packages et signatures. |
| `CompletionHSCodeChatServiceImpl`, `ChatService`, `EmbeddingService`, comparateur, chaîne de secours | Copies des extraits. Les vrais types LangChain4j sont utilisés. |
| `OpenAIProperties` | Seules les annotations/imports Spring sont retirés ; constructeur et `modelName()` conservés. |
| `ResponseFormatUtils` | Copie intégrale, y compris parsing de `Candidates` et `NumberCandidates`. |
| `HSNomenclature`, `HSLevel`, `NumberCandidates`, `NumberCandidate` | Copies des types transmis dans la conversation, sans modification de comportement. |
| Référentiel | `InMemoryHSCodeService`, alimenté par les données fournies, sans inventer de correspondances d’édition. |
| Transport simulé | `ScriptedChatService` retourne le texte fourni ; les véritables parsing, conversion et tri restent exécutés. |
| Assemblage local | `LocalCompletionFactory` reprend les prompts de la factory et injecte le transport simulé par le constructeur existant. |
| Factory industrielle et verbatim | Non reproduits ; l’assemblage RAG sera effectué dans le module industriel concerné. |

`reference-manifest.json` indique l’empreinte de chaque extrait et de sa copie,
ainsi que les modifications explicites. Les tests détectent une modification non
répertoriée. Si `lestr_sources` existe, ils détectent aussi un changement de référence.
Ce dossier étant ignoré par Git, son absence dans un checkout neuf n’empêche pas
le build des copies autonomes. Les extraits transmis uniquement dans la conversation
ne sont pas supposés présents dans ce répertoire.

Une évolution de référence doit être examinée, portée dans `compat` si nécessaire,
caractérisée par les tests, puis enregistrée dans le manifeste. Ne pas mettre à
jour une empreinte uniquement pour faire disparaître un test en échec.

## 2. Compiler, tester et lancer

Prérequis : **JDK 21 et Maven 3.9+**, Bash pour les scripts. `dev.sh` cherche Maven
sur le PATH, puis une distribution déjà installée sous `~/.m2/wrapper/dists`.
`MVN=/chemin/vers/bin/mvn` permet de sélectionner explicitement l’exécutable.
Il ne télécharge pas de distribution Maven. Maven télécharge les dépendances
publiques au premier build ; aucune clé API n’est nécessaire pour les tests.

Depuis la racine du dépôt :

```bash
sandbox_java/simplified_env/dev.sh test
sandbox_java/simplified_env/dev.sh package
sandbox_java/simplified_env/dev.sh run demo
```

Le résultat de démonstration contient `010121` avec un score de 5, puis `010129`
avec un score de 3. Le candidat inconnu est filtré. Les réponses simulées ne
constituent pas une prédiction du modèle : elles vérifient le traitement Java.

Dans l’IDE, importer `simplified_env/pom.xml` comme projet Maven et sélectionner
un JDK 21 avec le traitement des annotations Lombok activé. Ne pas ajouter
`lestr_sources` comme racine de sources : cela dupliquerait les classes.

Le build produit `simplified_env/runner/target/sandbox.jar`, un JAR autonome réservé
au développement local. Après compilation :

```bash
java -jar sandbox_java/simplified_env/runner/target/sandbox.jar --help
```

Les scripts trouvent le projet depuis leur propre emplacement : ils peuvent être
appelés depuis un autre répertoire. Les **chemins de fichiers passés à la CLI** sont,
eux, résolus depuis le répertoire courant. Utiliser des chemins absolus lorsque le
lanceur est appelé depuis ailleurs. Les fixtures de `demo` sont chargées depuis le
classpath et fonctionnent également à l’intérieur du JAR.

Exemples avec des réponses enregistrées :

```bash
sandbox_java/simplified_env/dev.sh run search \
  sandbox_java/simplified_env/runner/src/main/resources/fixtures/catalog.jsonl \
  sandbox_java/simplified_env/runner/src/main/resources/fixtures/candidates.json \
  "Live breeding horses"

sandbox_java/simplified_env/dev.sh run analyse \
  sandbox_java/simplified_env/runner/src/main/resources/fixtures/catalog.jsonl \
  sandbox_java/simplified_env/runner/src/main/resources/fixtures/analysis.txt \
  "Live breeding horses" 010121
```

Le chargeur accepte aussi les objets JSON successifs du catalogue de référence, y compris
le premier objet remis en forme sur plusieurs lignes :

```bash
sandbox_java/simplified_env/dev.sh run search \
  data/processed/h6_2022/catalog.jsonl \
  sandbox_java/simplified_env/runner/src/main/resources/fixtures/candidates.json \
  "Live breeding horses"
```

Il exige `code` textuel, `description` non vide, édition choisie et langue `en`.
`getHSNomenclature()` construit une nouvelle hiérarchie à partir des lignes disponibles
et échoue si un parent manque ; fournir `catalog.jsonl` pour utiliser cette méthode.
Il conserve les libellés simples et les niveaux présents dans le fichier : il
n’invente pas les parents absents de `candidates.jsonl`. Les champs supplémentaires
sont ignorés. Ce chargeur du référentiel de completion n’est **pas** le chargeur de
l’index RAG et ne remplace pas les descriptions par `contextual_description`.

Appels réels facultatifs, avec `OPENAI_API_KEY` déjà défini dans l’environnement :

```bash
sandbox_java/simplified_env/dev.sh run live-search \
  data/processed/h6_2022/catalog.jsonl "Live breeding horses"
sandbox_java/simplified_env/dev.sh run live-analyse \
  data/processed/h6_2022/catalog.jsonl "Live breeding horses" 010121
```

Ces deux commandes appellent le fournisseur et peuvent consommer des tokens. La
clé ne se passe pas en argument ; le lanceur ne lit pas automatiquement le `.env`
Python. Le modèle et les paramètres restent ceux des extraits (`gpt-4.1-mini`,
seed 0, température 0.2, limite de completion 1500). Aucun appel réel n’est requis
par `test`, `package`, `verify` ou `demo`.

`dev.sh` utilise un settings Maven vide explicite pour ne pas dépendre du dépôt
privé industriel. Il conserve le cache Maven utilisateur. Après un premier build
réussi, `dev.sh test -o` permet de vérifier le fonctionnement sans téléchargements.
Pour une installation exigeant un proxy ou miroir, utiliser Maven directement
avec le POM et les settings adaptés à cette installation.

## 3. Comportements établis et limites

La suite JUnit vérifie les codes à zéros initiaux et leur format pointé, les bornes
et le tri des scores, les conversions 2017→2022 sur fixtures, le parsing JSON, les
réponses nulles/malformées, l’analyse textuelle, le secours sur erreur, la construction
des messages et du schéma, les embeddings en mémoire et leur sérialisation. Un test
HTTP local exerce les vrais clients chat et embeddings avec les versions épinglées.
Le test de comparateur fourni est également exécuté sans modification.
Les cas d’erreur intentionnels peuvent écrire des logs WARN/ERROR : le bilan JUnit
indique si le comportement attendu a été vérifié.

Quelques comportements industriels à conserver en tête :

- `HSCode.toString()` produit par exemple `0101.21` ; utiliser `toDigits()` pour
  une clé du catalogue POC (`010121`). Ne jamais convertir les codes en nombres.
- Le score industriel vaut 0 à 5. La completion utilise `round(pourcentage / 20)`.
  Elle trie par score décroissant, conserve les doublons et n’applique pas la
  limite de cinq résultats après réception.
- Un texte nul ou un JSON syntaxiquement invalide produit une liste vide. Un JSON
  valide mais incomplet comme `{}` peut provoquer une exception. Le bac à sable
  conserve cette distinction, au lieu de normaliser toutes les erreurs.
- La chaîne `HSCodeAnalysisServiceImpl` intercepte même `Throwable`, passe au
  service suivant après une exception, mais s’arrête sur un résultat vide. Son
  `getSource()` échoue. Ces comportements sont caractérisés, pas recommandés pour
  une nouvelle conception.
- Les conversions locales entre éditions doivent être déclarées explicitement.
  Une conversion absente lève `UnsupportedOperationException`. Les fixtures de
  tests n’ont aucune valeur de table officielle de correspondance.
- Les particularités d’`HSCode.resolve()` sont conservées avec le source fourni ;
  elles ne sont pas corrigées dans ce chantier.

`EmbeddingService` est conservé pour pouvoir tester son usage, mais son stockage
n’est pas directement portable : `saveStore()` écrit dans `/tmp/store-small.ser`
ou `/tmp/store-large.ser`, alors que `loadStore()` cherche une ressource **relative
au package** `com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/`. Sauvegarder
puis charger ne désigne donc pas automatiquement le même fichier. Aucun test ne
lance un précalcul réel ni n’écrit ces fichiers fixes. L’aller-retour de sérialisation
est testé en mémoire, et l’absence de ressource est caractérisée.

Ce store JSON LangChain4j n’est pas le format `manifest.json` + `vectors.jsonl` du
POC. La recherche LangChain4j ne doit pas non plus être présumée exposer exactement
la même échelle de score que le cosinus brut du POC.

Le RAG Java utilise désormais l’index POC, conserve les résultats enrichis en
interne et les expose par `searchDetailed()`. Il adapte vers `SearchResult` avec un
score constant 3 et `Source.OpenAI_Hybrid`, sans reclasser ; le résultat détaillé
porte un score constant 2.5. `analyse()` délègue à la completion. Le chargement de
l’index précalculé est indépendant d’`EmbeddingService`, dont les libellés simples et
le format de stockage ne correspondent pas à ceux du POC. Voir [RAG.md](RAG.md).

## 4. Développer puis transférer du nouveau code

1. Ajouter les classes destinées à la production dans
   `simplified_env/extension/src/main/java/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/`.
   Utiliser les interfaces métier existantes et injecter les dépendances par
   constructeur. Les doubles et configurations locaux restent dans `runner`.
2. Placer les ressources transférables dans `extension/src/main/resources` et les
   tests autonomes transférables dans `extension/src/test/java`. Les tests qui
   emploient le référentiel ou transport local restent dans `runner/src/test/java`.
   Ajouter les dépendances de test à `extension/pom.xml` si nécessaire.
3. Préparer les ressources avec `sandbox_java/scripts/prepare_rag_resources.sh`.
   Câbler le nouveau service dans le lanceur local, puis exécuter `dev.sh verify`.
   Pour le RAG, comparer le classement sur des vecteurs et réponses LLM figés, pas
   sur deux appels réseau non déterministes. Ajouter les cas d’abstention,
   d’information manquante et de code hors candidats du POC.
4. Lancer `sandbox_java/simplified_env/export.sh`. Le script refuse un module sans
   implémentation, exécute `verify`, puis produit
   `extension/target/transferable-sources.tar.gz` avec `src/main`, `src/test`
   et le guide `INTEGRATION.md` du module, plus une empreinte SHA-256 adjacente. Il n’inclut ni POM local, ni `compat`, ni
   `runner`, ni dépendances binaires ; les données `h6_2022` sont incluses dans les ressources. Examiner les ressources ajoutées
   au module avant export. L’archive est un livrable de revue, pas un déploiement.
5. Extraire l’archive dans un répertoire de revue distinct du dépôt industriel.
   Identifier le module qui contient actuellement `CompletionHSCodeChatServiceImpl`.
   Reporter **uniquement les nouveaux fichiers**, après comparaison, sous ses
   `src/main/java`, `src/main/resources` et éventuellement `src/test/java`.
   Ne pas extraire aveuglément par-dessus des fichiers existants.

### Correspondances à vérifier dans le grand projet

| Sujet | Passage local → industriel |
| --- | --- |
| Packages | Le chemin après `src/main/java` reste identique : aucune réécriture de package ne devrait être nécessaire. Le chemin du module Maven parent change. |
| Contrats | Remplacer la dépendance Maven locale `compat` par les modules industriels qui possèdent déjà ces types. Ne jamais copier les classes `compat`, même si leurs packages coïncident. |
| Dépendances | Déclarer les bibliothèques réellement utilisées par le nouveau code dans le module cible, en suivant le BOM parent. Comparer LangChain4j, Jackson, Commons, JSpecify et les annotations/processeurs. |
| Assemblage | Instancier le RAG dans la factory/configuration existante avec le vrai `HSCodeService`, le modèle et le service auquel déléguer `analyse()`. Le bean/fichier exact n’est pas connu ici. |
| Configuration | Réutiliser le mécanisme Spring/secrets industriel. Les annotations de `OpenAIProperties` sont déjà présentes là-bas ; ne pas exporter sa version simplifiée. |
| Données et chemins | La factory charge `h6_2022` depuis le classpath du package `analysis.ia`. Ne plus lui passer de chemins de fichiers. |
| Ressources embarquées | Utiliser un `InputStream` de classpath, sans conversion supposant un fichier disque. Tester après packaging, car une ressource peut être dans un JAR. |
| Index embarqué | Inclure `catalog.jsonl`, `manifest.json` et `vectors.jsonl` sous `src/main/resources/.../analysis/ia/h6_2022/`, sans filtrage. Vérifier leur présence dans le JAR final. Aucun recalcul implicite au démarrage. |
| Cycle de vie | Charger l’index une fois selon le cycle de vie industriel, éviter les états mutables partagés par requête, vérifier les appels concurrents et les délais/reprises du fournisseur. |
| Résultats et secours | Préserver le contrat `SearchResult` ; décider comment distinguer abstention métier et panne technique pour que la chaîne existante se comporte comme attendu. |
| Logs | Utiliser le logging industriel ; le backend `slf4j-simple` du runner n’est pas transféré. Définir la politique de traces des descriptions/réponses avec le projet cible. |

Ni le JAR autonome du runner ni le JAR `compat` ne doivent être livrés dans
l’application industrielle : ils introduiraient des classes concurrentes sous
les mêmes noms qualifiés. Le module `extension` est le seul réservoir de nouvelles
sources de production. Le POM local décrit une compilation de développement,
pas un nouveau parent Maven à imposer au projet cible.

### Tests d’intégration à effectuer dans le dépôt industriel

- Compiler et lancer les tests du module cible avec son wrapper, son parent Maven
  et ses dépendances réelles ; rechercher les classes dupliquées et conflits Jackson.
- Démarrer le contexte Spring concerné, contrôler la factory/le bean sélectionné,
  l’ordre RAG/completion/verbatim et la source retournée.
- Rejouer les tests de contrat avec le vrai `HSCodeService`, le référentiel réel,
  les niveaux 2/4/6 et les conversions d’édition autorisées.
- Vérifier la sérialisation réellement exposée aux appelants et leurs hypothèses
  sur le tri, les scores, les listes vides, les exceptions et les valeurs de `Source`.
  Le JSON de présentation de la CLI locale ne définit pas cette API industrielle.
- Avec le RAG : comparer les voisins et le classement sur un petit jeu figé
  partagé avec Python, puis contrôler les mauvaises dimensions, index périmé,
  fichier absent, catalogue discordant, codes rejetés et questions manquantes.
- Tester le packaging final depuis un autre répertoire, les ressources dans le JAR
  et les volumes de données montés comme en déploiement ; aucune dépendance au checkout.
- Exercer timeout, erreur fournisseur, JSON invalide, abstention et secours ; vérifier
  qu’une panne ne se transforme pas involontairement en succès vide.
- Effectuer un test fournisseur réel avec la configuration autorisée du projet,
  notamment schéma structuré, modèle, proxy/TLS et limites de tokens. Ce test n’a
  pas été exécuté ici.
- Mesurer initialisation, mémoire et latence sur l’index complet, puis lancer des
  requêtes concurrentes. Prévoir un choix de service/configuration permettant de
  revenir à la completion existante pendant la validation de l’intégration.

## 5. Vérifications effectuées

Le socle conserve ses tests de compatibilité. Le retrait des champs techniques du RAG
a été vérifié par **38 tests ciblés**, dont 15 cas métier de référence.
Les anciens tests des enveloppes fournisseur ont été retirés. Le JAR a été exécuté en démonstration,
en analyse simulée et en rejeu RAG ; les scripts fonctionnent depuis un autre
répertoire. L’index réel et l’archive exportée ont fait l’objet de vérifications
décrites dans [RAG.md](RAG.md).

Aucun appel OpenAI réel ni test du dépôt industriel n’a été effectué. Le code
Java ne sort pas de `sandbox_java/` ; le README global renvoie à ce dossier.
