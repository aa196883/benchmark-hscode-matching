# Compte rendu — évolution du classement HS

## Objectif

Remplacer la recherche fondée sur un prompt seul par une approche RAG : la description de la marchandise est vectorisée, rapprochée des descriptions du catalogue HS 2022, puis soumise au modèle de génération pour classer les candidats retenus.

Le périmètre reste le HS international 2022 à six chiffres, en anglais.

## Modèles et accès fournisseur

- Génération et classement : `gpt-4.1-mini` via l’API OpenAI Responses.
- Vectorisation des descriptions : `text-embedding-3-small`.
- La clé API OpenAI existante est réutilisée ; aucune nouvelle clé ni aucun nouveau fournisseur ne sont nécessaires.
- Une requête métier entraîne une vectorisation de la description, une recherche dans l’index, puis une génération.
- Les réponses incomplètes, invalides ou incohérentes sont remontées comme erreurs.

## PostgreSQL et index vectoriel

PostgreSQL avec l’extension `pgvector` stocke les vecteurs des descriptions HS ainsi que le code HS associé. L’index est pré-calculé à partir du catalogue.

Le service applicatif ouvre l’index en lecture seule. Le compte de lecture doit disposer uniquement des droits nécessaires à la lecture de l’index et de son manifeste.

Le provisionnement de la base et l’import initial sont des opérations distinctes du démarrage de l’application. L’import doit être réalisé dans une base dédiée, avec un compte d’import séparé. L’import valide la version HS, la langue, le modèle d’embedding, les dimensions, les descriptions et l’intégrité des vecteurs avant de rendre l’index utilisable. Un contenu différent ou incomplet est refusé.

Le raccordement de cette base au projet industriel global reste à réaliser : le code du service accepte une `DataSource` fournie par l’application, mais l'intégration doit être définie.

Le test manuel `RagHSCodeServiceMT` est autonome : il démarre une image Docker locale PostgreSQL/pgvector jetable, crée et remplit sa base de test, vérifie les vecteurs, puis exécute le service. Cette base n’est pas la base de production et ne constitue pas un branchement au projet global.

## Nouveau contrat de résultat

Le RAG est exposé par un contrat de recherche v2 indépendant du contrat historique. Le résultat contient :

- `source` : `OpenAI_Hybrid` ;
- `status` : `ok` ou `needs_info` ;
- `candidates` : codes HS, rang, description catalogue et explication textuelle ;
- `missing_information` : questions à poser lorsque la description ne permet pas de départager correctement les candidats.

Le classement est ordonné par rang. Aucun score de confiance n’est produit et aucune analyse séparée du résultat n’est appelée après la recherche. Les descriptions affichées viennent du catalogue industriel, et les explications sont générées à partir de la description fournie et des candidats récupérés.

Avec le status de réponse`needs_info`, les meilleurs candidats provisoires sont conservés et accompagnés de questions ciblées, par exemple sur la matière, la composition, la forme, la transformation ou l’usage. Une réponse valide ne peut pas introduire un code absent de l’index récupéré.

Les appelants doivent donc consommer le contrat v2 et gérer `needs_info`. Les contrats historiques restent disponibles pour l'instant.


