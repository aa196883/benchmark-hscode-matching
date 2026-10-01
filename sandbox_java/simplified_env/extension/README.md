# Code RAG transférable

`src/main` contient le service `RagHSCodeAnalysisService`, le chargeur de l’index
Python, le client OpenAI Responses/embeddings et les ressources `rag_v1`.
`src/test` contient les tests portables et leurs fixtures, dont les références Python.

La dépendance `compat` reste `provided` et ne doit jamais être transférée dans
l’application industrielle. Le code de production n’importe pas le runner local.

- [Utilisation et parité Python](../../RAG.md)
- [Instructions d’intégration incluses dans l’export](INTEGRATION.md)
- [Guide général du socle Java](../../README.md)

`../export.sh` vérifie le projet puis archive `src/main`, `src/test` et
`INTEGRATION.md`. Il ne modifie jamais le dépôt industriel.

## Comprendre le code, fichier par fichier

Quelques termes Java : une **interface** définit les méthodes qu’une classe doit
fournir ; un **record** regroupe des données ; une **factory** est une méthode qui
crée un objet en assemblant ses dépendances.

### Code principal (`src/main/java/.../rag/`)

- **`RagHSCodeAnalysisService.java` — service principal et point d’entrée.** Cette classe implémente l’interface industrielle `HSCodeAnalysisService` et enchaîne vectorisation, recherche des voisins, appel au LLM et validation. `searchDetailed()` conserve les explications et questions ; `searchFromDescription()` adapte le résultat au contrat industriel. Sa méthode `construct()` sert de factory, et `analyse()` délègue au service existant.
- **`RagCatalog.java` — classe de lecture du catalogue.** Charge les codes HS et leurs descriptions, détermine les candidats admissibles et calcule les empreintes permettant de vérifier la cohérence avec l’index. Son record `Row` représente une entrée du catalogue.
- **`PrecomputedEmbeddingIndex.java` — classe de recherche vectorielle.** Charge et vérifie les embeddings précalculés, puis trouve les codes les plus proches d’un vecteur par similarité cosinus. Son record `Hit` décrit un voisin trouvé, avec son rang et sa similarité.
- **`RagEmbeddingClient.java` — interface de vectorisation.** Définit comment transformer une description en vecteur. Son record `Response` regroupe le vecteur, le modèle utilisé et les informations de consommation de tokens.
- **`RagGenerationClient.java` — interface de génération.** Définit comment envoyer le prompt, les candidats et le schéma de réponse au LLM, puis récupérer sa réponse complète, y compris un éventuel refus. Ces deux interfaces permettent de remplacer les appels réels par des réponses simulées dans les tests.
- **`OpenAiRagClient.java` — implémentation des deux interfaces précédentes.** Effectue les appels HTTP à OpenAI pour les embeddings et la génération. Son record `Config` porte les réglages du LLM, comme le modèle, la limite de tokens et le délai maximal.
- **`RagResult.java` — record du résultat détaillé.** Regroupe le statut, les candidats, les demandes de précision, l’erreur éventuelle et les informations de diagnostic. Son record `Candidate` contient notamment le code, le rang, l’explication et le score fixe de 2.5 ; le service adapte ce score à 3 pour la sortie industrielle.
- **`RagProviderException.java` — exception dédiée aux appels fournisseur.** Signale une erreur de configuration, de connexion ou de réponse OpenAI, avec des détails exploitables sans conserver la clé API ni le corps d’erreur HTTP.
- **`RagJson.java` — classe utilitaire interne.** Centralise la lecture et l’écriture JSON, quelques vérifications de données, le calcul des empreintes SHA-256 et la mesure des durées. Elle est utilisée par les autres classes, sans être un point d’entrée de l’application.
- **`package-info.java` — description du package.** Contient une courte documentation du groupe de classes `rag`, sans logique de traitement.

Pour commencer la lecture, suivre `RagHSCodeAnalysisService.searchDetailed()`,
puis ouvrir les classes qu’elle appelle : c’est là que le parcours RAG est assemblé.

### Ressources et configuration

- **`rag_v1.txt` (`src/main/resources/.../rag/`) — prompt.** Contient les consignes reprises du Python : classement, explications, demandes de précision et abstention.
- **`rag_v1.schema.json` (même dossier) — schéma JSON.** Décrit la structure attendue de la réponse du LLM. Le service y ajoute, pour chaque requête, la liste des codes autorisés.
- **`pom.xml` — configuration Maven.** Déclare les dépendances nécessaires à la compilation et aux tests du module.
- **`INTEGRATION.md` — guide de transfert.** Explique comment raccorder ces sources et leurs données au projet industriel.

### Tests (`src/test/java/.../rag/`)

- **`PythonParityTest.java` — tests de comparaison avec Python.** Rejoue les cas de référence pour vérifier les statuts, l’ordre, les explications, les questions et les rejets.
- **`IndexValidationTest.java` — tests du catalogue et de l’index.** Vérifie le classement et le rejet des fichiers incohérents, corrompus ou incompatibles.
- **`RagServiceTest.java` — tests du service.** Vérifie les scores, l’adaptation industrielle, la délégation d’analyse, les erreurs et les appels concurrents.
- **`OpenAiRagClientTest.java` — tests du client HTTP.** Utilise un serveur local pour contrôler les requêtes et simuler les réponses, erreurs et délais, sans appeler OpenAI.
- **`RagTestSupport.java` — aide aux tests.** Prépare les petits fichiers de test et fournit un service d’analyse simulé partagé par les tests.

Les fichiers de `src/test/resources/rag-fixtures/` sont les données de ces tests :
petit catalogue, vecteurs synthétiques, réponses simulées et résultats attendus.
