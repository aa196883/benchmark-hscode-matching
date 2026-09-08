# Préparation et prochaines étapes

Ce document complète le README avec les informations à collecter et les décisions nécessaires à l’implémentation. Sources consultées le **8 septembre 2026** ; aucun corpus n’a encore été importé.

## 1. Constituer le référentiel

Premier choix proposé : **HS 2022 à 6 chiffres**, catalogue anglais et requêtes FR/EN. Ajouter les libellés français après identification d’une source adaptée. Garder l’édition explicite : les [tables OMD 2022–2028](https://www.wcoomd.org/en/topics/nomenclature/instrument-and-tools/hs-nomenclature-2028-edition/correlation-tables-hs-2022-2028.aspx) comportent notamment des correspondances partielles ; une migration ne se réduit pas à renommer des codes.

| Source | Ce qui est disponible ou à examiner | Usage proposé |
| --- | --- | --- |
| [UNSD — classifications](https://unstats.un.org/unsd/classifications/econ), lien [JSON HS 2022 / H6](https://comtradeapi.un.org/files/v1/app/reference/H6.json) | JSON versionné avec `id`, `text`, `parent`, `aggrlevel`, `isLeaf`. La page propose aussi un XLSX de codes et descriptions. | Premier import de la structure et des libellés anglais. `H6` désigne ici l’édition 2022, pas le niveau de chaque ligne. |
| [OMD — nomenclature HS 2022](https://www.wcoomd.org/en/topics/nomenclature/instrument-and-tools/hs-nomenclature-2022-edition/hs-nomenclature-2022-edition.aspx) | Texte de référence, règles générales et notes légales. | Vérifier la structure et enrichir les cas où le libellé seul ne suffit pas. |
| [OMD — exports XML/CSV](https://www.wcoomdpublications.org/en/products/harmonized-system/harmonized-system-nomenclature-2022-xml-csv-formats) | Offre structurée en français, anglais et espagnol. | Examiner le contenu exact, le prix et les conditions d’utilisation avant acquisition. |
| [WCO Trade Tools](https://www.wcotradetools.org/en/harmonized-system/2022/en/06) | Consultation de la nomenclature et accès aux ressources explicatives selon les droits disponibles. | Examiner l’accès aux notes explicatives et avis de classement pour enrichir le RAG. |
| [Commission européenne — nomenclature combinée](https://taxation-customs.ec.europa.eu/customs/common-customs-tariff-cct/tariff-classification-goods/combined-nomenclature_en) | Extension européenne du HS, actualisée annuellement. | Extension ultérieure NC à 8 chiffres avec son propre millésime. |
| [Commission européenne — TARIC](https://taxation-customs.ec.europa.eu/online-services/online-services-and-databases-customs/eu-customs-tariff-taric_en) | Nomenclature et mesures tarifaires ; la page annonce des données brutes Excel gratuites. | Examiner les fichiers si le besoin descend au niveau TARIC ; conserver le périmètre UE et la date de validité. |

Pour chaque source retenue, relever : URL de téléchargement, édition, date de récupération, langue, couverture, format, conditions de réutilisation et empreinte du fichier. Vérifier séparément les possibilités de stockage, d’indexation et d’utilisation via une API LLM pour les contenus sous licence. La disponibilité en consultation ne suffit pas à établir ces droits.

**Premier import concret :** utiliser `results` du JSON H6, conserver les lignes aux niveaux 2, 4 et 6, convertir les parents de premier niveau en racines, exclure `TOTAL` des candidats. Contrôler les éventuels codes spéciaux et la couverture contre la nomenclature OMD ; ne pas supposer que toute feuille statistique est une sous-position HS admissible. Préserver le fichier brut et le texte source avant nettoyage.

## 2. Conserver table et hiérarchie

Une table de nœuds suffit au départ. Clé : `(nomenclature, edition, node_id, language)` ; un nœud sans code, tel qu’un intertitre, reçoit un identifiant interne.

| Champ | Rôle |
| --- | --- |
| `nomenclature`, `edition`, `jurisdiction` | Exemple : HS, 2022, international. |
| `node_id`, `parent_id`, `code` | Liens explicites ; code sous forme de chaîne, éventuellement nul pour un intertitre. |
| `level`, `is_predictable` | Section, chapitre, position, sous-position ou intertitre ; seuls les HS6 admissibles sont prédictibles au départ. |
| `description`, `language` | Libellé source et langue. |
| `path_text` | Texte dérivé des ancêtres et du libellé, destiné à la recherche. |
| `source_id`, `valid_from`, `valid_to` | Provenance et validité lorsqu’elles sont disponibles. |

La hiérarchie 2/4/6 du JSON est un point de départ. Ajouter les sections et les intertitres de sous-positions si une source plus riche les fournit : les préfixes numériques ne restituent pas tout le contexte. Contrôler unicité, parents existants, absence de cycles et zéros initiaux.

Stocker les notes et règles dans des documents séparés (`document_id`, type, texte, langue, édition, source), reliés aux nœuds concernés. Distinguer libellés, notes légales, notes explicatives et enrichissements générés. Le texte d’indexation combine le chemin et le libellé ; l’ajout de notes sera une variante expérimentale traçable.

## 3. Rendre les expériences comparables

- Une configuration identifie l’approche, les modèles, les paramètres, la langue, le catalogue, le prompt et les tailles K de recherche et N de sortie. La CLI et Flask utilisent le même moteur d’exécution.
- Chaque candidat expose `code`, `rank`, `description`, `score` nullable, `score_type`, `explanation` optionnelle et références documentaires éventuelles. Le résultat porte aussi `status` (`ok`, `needs_info`, `abstained`, `error`) et les informations manquantes.
- Valider les sorties contre le catalogue : format, existence, édition, niveau et doublons. Pour le RAG, contrôler aussi l’appartenance aux candidats fournis. Journaliser la réponse brute et les rejets sans masquer les erreurs par une correction silencieuse.
- Pour le RAG, vectoriser le catalogue une fois, puis chaque requête ; récupérer K candidats avec K ≥ N et fournir leurs contextes au LLM. Une sélection limitée aux candidats rend le rappel de recherche déterminant.
- Sauvegarder configuration effective, empreintes des données et artefacts, version du code si disponible, prompt effectif, candidats récupérés, réponse brute, prédictions finales, durées, usage de tokens et erreurs. Un coût estimé doit indiquer le tarif et sa date ; distinguer préparation, inférence et cache.
- Indexer les caches par contenu, version de modèle et paramètres. Les embeddings du catalogue et des requêtes doivent utiliser le même espace vectoriel. Répéter un sous-ensemble des appels LLM pour mesurer leur variabilité.

## 4. Trouver une vérité terrain utile

Le catalogue décrit les catégories ; il ne fournit pas à lui seul un benchmark de descriptions commerciales annotées. Rechercher d’abord des exemples métier validés, puis des décisions de classement publiques avec description exploitable, édition et provenance identifiables. Documenter les conditions d’accès et la qualité de chaque source.

Commencer par **100 à 300 exemples relus**, couvrant plusieurs chapitres, FR/EN, descriptions courtes, détaillées et ambiguës. Stocker `example_id`, `product_group_id`, `text`, `language`, `edition`, `expected_codes`, `answerability`, provenance et commentaire d’annotation. Plusieurs codes acceptables doivent être justifiés par l’annotation ; une liste de possibilités issue d’un texte trop vague ne constitue pas automatiquement une vérité terrain.

Séparer entraînement, développement et test par produit ou famille proche ; garder les variantes, traductions et paraphrases d’un même produit dans le même lot. Les exemples synthétiques peuvent servir à l’entraînement ou aux vérifications, mais ne doivent pas constituer seuls le test de précision. Exclure les annotations du test des exemples de prompt et des documents de récupération.

Mesures initiales sur les cas classables : **Hit@1/3/5** (au moins un code acceptable présent) et **MRR** (rang du premier code acceptable). Si plusieurs codes doivent réellement être retrouvés, ajouter Precision@k et Recall@k. Rapporter séparément les résultats aux niveaux 2/4/6, par langue et type de description, ainsi que le taux de codes invalides et d’erreurs. Pour le RAG, mesurer le rappel des candidats avant génération. Pour l’abstention, afficher couverture et précision sur les réponses émises, puis vérifier les demandes d’information sur les cas ambigus. Garder les échecs dans les dénominateurs appropriés et publier les effectifs.

## 5. Ordre d’implémentation

1. Importer et contrôler le catalogue H6 ; sauvegarder le manifeste de provenance et la table hiérarchique.
2. Constituer le premier jeu annoté et figer les lots ; écrire le calcul des métriques.
3. Implémenter contrat commun, registre, baseline lexicale et CLI de recherche/évaluation ; sauvegarder un premier run complet.
4. Ajouter embeddings, LLM direct et RAG ; comparer sur le même test et analyser les désaccords.
5. Ajouter Flask/Jinja : saisie, sélection multiple des approches, résultats côte à côte et export des prédictions.
6. Selon les erreurs observées, expérimenter notes, recherche hybride, reclassement, parcours hiérarchique ou entraînement supervisé. Sauvegarder jeux d’entraînement, paramètres et artefacts associés.

Le premier jalon est atteint lorsque les trois approches demandées et la baseline tournent sur les mêmes exemples, via CLI et GUI, avec validation des codes et rapport comparatif enregistré.

Décisions à confirmer avant d’élargir : secteurs prioritaires, disponibilité des annotations métier, besoin de libellés français officiels, budget par campagne et éventuelle cible nationale/UE. Ces choix ne bloquent pas le démarrage proposé en HS6/2022.
