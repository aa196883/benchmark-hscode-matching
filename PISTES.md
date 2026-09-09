# Pistes de développement

Ce document rassemble deux pistes pour prolonger les approches actuelles. Il décrit des idées à expérimenter, sans figer leur implémentation. Les exemples illustrent des informations à rechercher ; ils ne constituent pas des décisions de classement douanier.

## 1. Une quatrième approche : le RAG hiérarchique

L’approche hybride actuelle recherche directement les sous-positions à six chiffres les plus proches de la description, puis demande au LLM de reclasser les candidats. Une quatrième approche pourrait suivre la hiérarchie des codes HS : **chapitre → position → sous-position**.

Le principe serait de procéder en trois passes :

1. **Choisir le chapitre (2 chiffres)** : rechercher les descriptions de chapitres les plus proches de la marchandise, puis utiliser le LLM pour reclasser ces candidats.
2. **Choisir la position (4 chiffres)** : reprendre le même principe parmi les positions des chapitres retenus.
3. **Choisir la sous-position (6 chiffres)** : rechercher puis reclasser les sous-positions des positions retenues pour produire les résultats finaux.

Chaque passe associerait donc une recherche par similarité sur embeddings et un reclassement par LLM. Cela suppose de précalculer hors ligne les embeddings des descriptions des chapitres et des positions, en complément de ceux des sous-positions déjà disponibles. Le contexte des niveaux parents resterait utile pour comprendre les libellés peu explicites.

Par exemple, pour une description comme « cotton knitted T-shirt », le système pourrait d’abord examiner les chapitres pertinents pour le vêtement, puis les positions correspondant au type de vêtement, avant de distinguer les sous-positions selon la matière décrite. Le chemin suivi rendrait le résultat plus facile à examiner.

Il serait intéressant de **conserver plusieurs branches plausibles**, plutôt que d’imposer un seul choix à chaque étape. Une erreur de chapitre risquerait sinon d’exclure immédiatement la bonne réponse, même si les étapes suivantes fonctionnent bien. Une description courte ou un produit composé pourrait justifier l’exploration de plusieurs branches.

À explorer : cette progression améliore-t-elle la pertinence et la compréhension des résultats par rapport au RAG direct à six chiffres ? Quel est son effet sur le nombre d’appels, la latence et le risque d’écarter trop tôt une bonne piste ?

## 2. Un RAG qui signale les informations manquantes

Une seconde piste consiste à enrichir le prompt de l’approche hybride pour que le LLM examine aussi **ce qui empêche de départager les candidats**. L’objectif est de ne pas forcer un choix lorsque la distinction repose sur une caractéristique absente de la description utilisateur.

Par exemple, avec **« turkey meat »**, les candidats récupérés pourraient distinguer des morceaux découpés et des produits excluant ces morceaux, ou encore une viande fraîche et une viande congelée. La description seule ne permet pas nécessairement de trancher. Le LLM devrait alors signaler les caractéristiques manquantes, plutôt que supposer un état ou une présentation du produit.

Quelques situations à explorer, selon les distinctions effectivement présentes dans les candidats :

| Description utilisateur | Distinctions possibles entre candidats | Question utile |
| --- | --- | --- |
| « turkey meat » | Présentation en morceaux ou autre présentation ; frais ou congelé | La viande est-elle découpée ? Est-elle fraîche, réfrigérée ou congelée ? |
| « cotton shirt » | Étoffe tissée ou tricotée | Le vêtement est-il en tissu tissé ou en maille ? |
| « fruit juice » | Jus d’un seul fruit ou mélange ; composition ou concentration différentes | De quels fruits s’agit-il ? Est-ce un mélange ou un jus concentré ? |
| « metal tube » | Matière et procédé de fabrication différents | Quel est le métal utilisé ? Le tube est-il soudé ou sans soudure ? |
| « plastic container » | Usages différents dans les descriptions proposées | À quoi sert le récipient : emballage, transport, usage domestique ou autre ? |

Le LLM pourrait formuler une question ciblée et expliquer brièvement pourquoi elle compte : « Les candidats proposés distinguent les produits frais et congelés, mais votre description ne précise pas cet état. » Il devrait s’appuyer sur les différences visibles dans les descriptions fournies, sans inventer une distinction ni introduire de règles externes.

Le contrat actuel prévoit déjà ce comportement : le statut **`needs_info`** et le champ **`missing_information`** peuvent porter ces demandes. Des candidats provisoires peuvent rester affichés, mais leur ordre ne devrait pas donner l’impression que l’ambiguïté est résolue. Il n’est pas nécessaire d’ajouter un score de « perplexité » : une incertitude formulée en termes d’informations manquantes est plus utile à l’utilisateur.

Cette vigilance devrait également porter sur les exclusions : « autre que… », « à l’exclusion de… » ou « non… » peuvent changer le sens d’un candidat pourtant très proche lexicalement. À l’inverse, si l’utilisateur a déjà précisé « frozen turkey cuts », le système ne devrait pas lui redemander si le produit est congelé ou découpé.

À explorer : comparer des descriptions courtes avec leurs versions enrichies, vérifier que les questions portent sur des différences décisives entre candidats, puis observer si les précisions apportées permettent de résoudre l’ambiguïté. Cette capacité pourrait servir aussi bien au RAG actuel qu’à sa variante hiérarchique.
