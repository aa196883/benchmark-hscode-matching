# Portage Java du classement de codes HS

Le RAG vectorise une description produit, recherche les voisins dans PostgreSQL /
pgvector, puis fait sélectionner et expliquer les codes par un LLM. Les embeddings
HS 2022 anglais sont précalculés : aucun recalcul du catalogue à l’import ou au démarrage.
Le service industriel reçu est disponible dans `lestr_sources/hs_references/trade-analysis/`.
Le câblage Spring et les migrations peuvent y être examinés ; le déploiement reste à valider.

## Développer et tester

Commandes depuis **`sandbox_java/`**. Prérequis : JDK 21, Maven 3.9+, Bash ;
Docker pour les tests PostgreSQL, Compose pour la base persistante. Importer
`simplified_env/pom.xml` dans l’IDE avec Lombok activé. Pas de contexte Spring local (Spring Core sert au chargement des ressources industrielles).

- `extension/` : code RAG, importeur, ressources et tests transférables.
- `compat/` : copies des contrats industriels à préserver.
- `runner/` : CLI, doubles et tests locaux. Ces trois modules sont dans `simplified_env/`.

```bash
# Test métier ciblé, sans Docker ni API
./simplified_env/dev.sh test -pl extension -am \
  -Dtest=RagServiceTest -Dsurefire.failIfNoSpecifiedTests=false
# Tests PostgreSQL autonomes : base temporaire créée et supprimée par Testcontainers
./simplified_env/dev.sh verify -Ppgvector-it
# Inclure l'import des 5 612 vecteurs réels et la comparaison à la référence Java
./simplified_env/dev.sh verify -Ppgvector-it -Drag.fullIndex=true
# JAR local et aide CLI sans API/base
./simplified_env/dev.sh package
./simplified_env/dev.sh run --help
```

`run` utilise `runner/target/sandbox.jar` **sans reconstruire**. Les tests ordinaires
restent sans Docker ; le profil explicite échoue si Docker est indisponible.
Ajouter `-o` si le cache Maven est prêt. `MVN=/chemin/bin/mvn` permet de choisir
Maven ; sinon le script cherche dans le PATH puis `~/.m2/wrapper/dists`.
Aucun lint Java dédié : `git diff --check` contrôle les espaces.

Le test manuel `RagHSCodeServiceMT` crée aussi sa propre base temporaire : import et
comparaison exhaustive des vecteurs une fois pour toute la classe, puis recherches
réelles. Aucun Compose ni identifiant PostgreSQL à fournir : Docker et les ressources
complètes suffisent côté base. La clé est chargée comme dans le test industriel :
`Utils.getSecret("OPENAI-API")`, transmis à `OpenAIProperties`. Le substitut local de
`Utils` utilise `OPENAI_API_KEY` : l’exporter avant de lancer le test :

```bash
RUN_OPENAI_MT=true ./simplified_env/dev.sh test -pl extension -am \
  -Dtest=RagHSCodeServiceMT -Dsurefire.failIfNoSpecifiedTests=false
```

Sélectionner `RagHSCodeServiceMT#serviceCanBeInstantiated` pour vérifier seulement
l’import et les vecteurs sans appel API ; le secret reste requis à l’initialisation.
La comparaison est exacte après la
conversion float32 et la normalisation L2 appliquées par l’import.

## Base locale : préparer, importer, lire

La base a deux comptes : `rag_import` pour le chargement explicite, `rag_reader`
pour le service. Une transaction publie l’index complet ; un import identique ne
modifie rien, un index différent est refusé. Aucun remplissage automatique au démarrage.

Les ressources réelles sont déjà présentes localement. Sur un checkout neuf,
`./scripts/prepare_rag_resources.sh` copie les données existantes du parent
(`artifacts/embeddings/h6_2022` : manifeste et vecteurs) sans appel API.
Ces copies, ignorées par Git, doivent être préparées avant packaging et export.

```bash
test -f simplified_env/.env || cp simplified_env/.env.example simplified_env/.env
# Sur une nouvelle installation, choisir les trois mots de passe ; puis exporter pour la CLI
set -a
source simplified_env/.env
set +a
docker compose -f simplified_env/compose.yaml up -d --wait
./simplified_env/dev.sh package
./simplified_env/dev.sh run rag-import
```

Compose prépare une base dédiée `trade_analysis`, l’extension `vector` et les rôles
lors du premier démarrage du volume. Connexion locale sur `127.0.0.1:55432` ; pour
changer ce port, modifier aussi les deux URL JDBC dans `.env`. Un volume existant
conserve ses mots de passe : modifier `.env` seul ne les change pas en base.

La CLI lit `RAG_IMPORT_DB_{URL,USER,PASSWORD}` pour l’import et
`RAG_DB_{URL,USER,PASSWORD}` pour la recherche. Elle ne charge aucun `.env`.
Les descriptions proviennent du service industriel `HSCodeServiceImpl` (HS 2022),
injecté via `HSCodeService`.
Le service vérifie descriptions, manifeste, dimensions et codes en base, sans relire
le fichier de vecteurs. Une base absente/incompatible provoque une erreur explicite.

```bash
# Recherche réelle : OPENAI_API_KEY doit aussi être exportée (appel payant)
./simplified_env/dev.sh run rag "Live pure-bred breeding horses" 5 20
# Rejeu sans API, contre la même base ; vecteur JSON de 1 536 dimensions
./simplified_env/dev.sh run rag-replay /chemin/query.json /chemin/response.json "Live horses" 5 20
# Arrêt conservant les données
docker compose -f simplified_env/compose.yaml down
```

`response.json` contient le JSON métier, sans enveloppe fournisseur. Les petites
fixtures à deux dimensions servent aux tests, pas à la base réelle. L’ancienne
commande `rag-replay-classpath` et les arguments catalogue/index de `rag-replay`
sont remplacés par ce rejeu PostgreSQL. `down --volumes` supprime explicitement la
base locale ; ne l’utiliser que pour repartir de zéro.

Défauts : `text-embedding-3-small`, `gpt-4.1-mini`, 2048 tokens de sortie, 20 voisins,
5 résultats. Recherche cosinus exacte, égalités départagées par code ; ordre LLM
validé par rating décroissant, source `OpenAI_Hybrid`.
`searchFromDescription()` et `searchDetailed()` retournent un `SearchResult` unique :
statut `ok` ou `needs_info`, candidats (`code`, `rank`, `description`, `explanation`, `rating`)
et questions globales `missing_information`. `needs_info` conserve les candidats
provisoires même si les correspondances sont faibles. Un refus explicite du fournisseur
retourne une liste vide et pose la question générique du POC ; une réponse
incomplète, une récupération vide ou une réponse invalide déclenche une exception.
Le prompt et le schéma sont ceux du Python `rag_v4`. Le rating est un nombre fini
entre 1 et 5 (décimales admises), indépendant du cosinus ; il est obligatoire pour
chaque candidat généré. Une réponse avec ratings croissants est rejetée, sans tri
correctif ni renumérotation des rangs. Les explications générées doivent être non vides.
`needs_info` accepte une liste vide si aucun candidat ne convient.
La CLI affiche directement cet objet JSON, sans `prediction` / `search_result`.
L’interface `HSCodeAnalysisService` ne propose pas `analyse()` et ne nécessite
aucun délégué Completion. Les contrats fusionnés remplacent les anciens contrats ;
`Candidate` conserve son package industriel `infra.service.analysis.ia.model`.
Le constructeur à quatre arguments reste disponible pour Verbatim avec `rating=null`.
Le RAG fournit toujours un rating. Les anciennes commandes `demo`, `search`, `analyse`
et leurs variantes `live-*` ont été retirées ; la CLI expose uniquement le parcours RAG.

## Intégration industrielle

`./simplified_env/export.sh` vérifie le projet puis produit
`extension/target/transferable-sources.tar.gz` et son SHA-256. Ajouter `-Ppgvector-it`
pour vérifier aussi PostgreSQL avant export. Le [guide d’intégration](simplified_env/extension/INTEGRATION.md)
accompagne les sources, ressources SQL/données et tests. **Ne transférer ni `compat`,
ni `runner`, ni leurs JAR/POM.**

Le module cible doit préparer sa base, lancer l’import avec son compte dédié, puis
injecter sa `DataSource` de lecture dans
`RagHSCodeAnalysisService.construct(openAIProperties, datasource, hsCodeService)`.
Le service ne possède pas le pool de connexions. Valider câblage Spring, ressources,
dépendances et appelants dans le dépôt industriel. Le service implémente désormais
l’interface fusionnée de la chaîne de secours. Les contrats inclus dans `extension`
sont à remplacer dans le module cible, notamment `Candidate` qui ajoute `rating`.
La chaîne de secours et Verbatim reçus sont compilés et testés dans `runner` ; leurs
copies locales ne sont pas exportées.

### État du service reçu

La factory `HSCodeServiceFactory.constructOpenAICompletionAndVerbatim` assemble déjà
RAG puis Verbatim avec les contrats sans suffixe. Le banc local est aligné sur ces
contrats et ne contient plus le parcours Completion historique.
`ApplicationConfiguration` injecte une DataSource ; le test manuel reçu utilise
Testcontainers et Flyway. La migration `V1__init.sql` reçue ne crée pas
`rag.index_manifest` : la migration complémentaire `V2__rag_index_manifest.sql` la crée.
Le test local exécute ces deux migrations avec Flyway ; V1 est copiée sans modification
et son empreinte est contrôlée dans `reference-manifest.json`.
Le contexte confirmé est une base PostgreSQL Docker temporaire réservée au test.
`src/main/docker/application.yml` configure PostgreSQL avec Hikari pour l’application ;
le test crée sa propre `PGSimpleDataSource` depuis Testcontainers et utilise Flyway
pour les migrations. Aucun service Railway distant ni identifiant de base externe
n’est nécessaire. Le test local utilise Flyway puis crée ses comptes de test,
puis détruit le conteneur à la fin ; il ne touche pas à la base Compose persistante.
