# Portage Java du classement de codes HS

Ce bac à sable porte l’approche RAG vers le contrat industriel `HSCodeAnalysisService` :
vectoriser une description produit, rechercher les voisins dans un index HS 2022
précalculé, puis faire sélectionner et expliquer les codes par un LLM.
Seuls les extraits industriels de `lestr_sources/` sont disponibles ; la validation
locale ne garantit pas l’intégration dans l’application complète.

## Développer et tester

Toutes les commandes ci-dessous partent de **ce dossier `sandbox_java/`**.
Prérequis : JDK 21, Maven 3.9+ et Bash. Importer `simplified_env/pom.xml` dans l’IDE
avec Lombok activé. Versions principales : LangChain4j 1.20.0, Jackson BOM 2.21.4,
JUnit Jupiter 5.12.2. Aucun Spring, Docker ou serveur de base de données requis.

- `simplified_env/extension/` : code RAG, ressources et tests transférables.
- `simplified_env/compat/` : contrats et utilitaires industriels, à préserver.
- `simplified_env/runner/` : CLI, doubles et tests de compatibilité locaux.
- `lestr_sources/` : références reçues, hors compilation et non suivies par Git.

```bash
# Test ciblé ; remplacer la classe selon le changement
./simplified_env/dev.sh test -pl extension -am \
  -Dtest=RagServiceTest -Dsurefire.failIfNoSpecifiedTests=false

# Construire le JAR (exécute aussi les tests), puis lancer sans API
./simplified_env/dev.sh package
./simplified_env/dev.sh run demo
./simplified_env/dev.sh run --help
```

`run` utilise le JAR existant, **sans reconstruire**. `test` lance toute la suite ;
`verify` teste et package. Ajouter `-o` pour Maven hors ligne si le cache est prêt.
`MVN=/chemin/bin/mvn` permet de choisir Maven ; sinon le script cherche dans le PATH
puis `~/.m2/wrapper/dists`. Il utilise les settings locaux, sans dépôt privé.
Aucun lint Java dédié n’est configuré ; `git diff --check` contrôle les espaces.

Pour rejouer le RAG sans API, après packaging :

```bash
fixtures=simplified_env/extension/src/test/resources/rag-fixtures
./simplified_env/dev.sh run rag-replay "$fixtures/catalog.jsonl" "$fixtures" \
  "$fixtures/query.json" "$fixtures/response.json" "Live horses" 2 2
```

## Données et appels réels

Les ressources complètes sont déjà préparées localement. Sur un nouveau checkout,
ou après changement des données, lancer **avant packaging/export** :

```bash
./scripts/prepare_rag_resources.sh
```

Ce script copie les données existantes de `../data/processed/h6_2022/` et
`../artifacts/embeddings/h6_2022/`, sans recalcul, vers les ressources d’`extension`.
Ces copies sont ignorées par Git, mais incluses dans le JAR et l’export.
Les tests ordinaires utilisent de petites fixtures et ne nécessitent ni clé ni API.

La CLI lit `OPENAI_API_KEY` dans l’environnement ; elle ne charge aucun `.env`.
Une fois la clé disponible et le JAR construit avec les ressources :

```bash
./simplified_env/dev.sh run rag "Live pure-bred breeding horses" 5 20
```

Un appel effectue une vectorisation et une génération payantes. Par défaut :
`text-embedding-3-small`, `gpt-4.1-mini`, 2048 tokens de sortie, 20 voisins et
5 résultats. L’ordre du LLM est conservé ; scores fixes 2.5 dans `RagResult` et
3 dans `SearchResult`, source `OpenAI_Hybrid`. `searchDetailed()` conserve les
explications et questions ; `analyse()` délègue à la completion existante.

## Intégrer dans l’application industrielle

```bash
./simplified_env/export.sh
```

L’export lance `verify`, puis produit
`simplified_env/extension/target/transferable-sources.tar.gz` et son SHA-256.
Il contient les sources, ressources, tests et le [guide d’intégration](simplified_env/extension/INTEGRATION.md).
Reporter les fichiers d’`extension` après comparaison dans le module industriel,
en conservant les packages. **Ne transférer ni `compat`, ni `runner`, ni leurs JAR/POM.**

Instancier `RagHSCodeAnalysisService.construct(openAIProperties, analysisDelegate)`
dans l’assemblage existant et réutiliser l’instance. Les données sont chargées depuis
le classpath ; aucun index n’est recalculé au démarrage. Dans le projet cible,
vérifier dépendances, câblage Spring, ressources du JAR et comportement du secours :
une exception déclenche le service suivant, une liste vide arrête la chaîne existante.
