# Préparation et prochaines étapes

Ce document complète le README avec les informations à collecter et les décisions nécessaires à l’implémentation. Sources consultées le **8 septembre 2026** ; le premier import local H6 est disponible via `scripts/preprocess_h6.py`, avec exports dans `data/processed/h6_2022/`. Les contrôles structurels sont implémentés ; la comparaison exhaustive avec l’OMD reste à réaliser.

## 1. Constituer le référentiel

Premier choix proposé : **HS 2022 à 6 chiffres**, catalogue anglais et requêtes EN. Garder l’édition explicite : les [tables OMD 2022–2028](https://www.wcoomd.org/en/topics/nomenclature/instrument-and-tools/hs-nomenclature-2028-edition/correlation-tables-hs-2022-2028.aspx) comportent notamment des correspondances partielles ; une migration ne se réduit pas à renommer des codes.

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

## 2. Rendre les expériences comparables

- Une configuration identifie l’approche, les modèles, les paramètres, la langue, le catalogue, le prompt et les tailles K de recherche et N de sortie. La CLI et Flask utilisent le même moteur d’exécution.
- Chaque candidat expose `code`, `rank`, `description`, `score` nullable, `score_type`, `explanation` optionnelle et références documentaires éventuelles. Le résultat porte aussi `status` (`ok`, `needs_info`, `abstained`, `error`) et les informations manquantes.
- Valider les sorties contre le catalogue : format, existence, édition, niveau et doublons. Pour le RAG, contrôler aussi l’appartenance aux candidats fournis. Journaliser la réponse brute et les rejets sans masquer les erreurs par une correction silencieuse.
- Pour le RAG, vectoriser le catalogue une fois, puis chaque requête ; récupérer K candidats avec K ≥ N et fournir leurs contextes au LLM. Une sélection limitée aux candidats rend le rappel de recherche déterminant.
- Sauvegarder configuration effective, empreintes des données et artefacts, version du code si disponible, prompt effectif, candidats récupérés, réponse brute, prédictions finales, durées, usage de tokens et erreurs. Un coût estimé doit indiquer le tarif et sa date ; distinguer préparation, inférence et cache.
- Indexer les caches par contenu, version de modèle et paramètres. Les embeddings du catalogue et des requêtes doivent utiliser le même espace vectoriel. Répéter un sous-ensemble des appels LLM pour mesurer leur variabilité.

## 4. Trouver une vérité terrain utile

Le catalogue décrit les catégories ; il ne fournit pas à lui seul un benchmark de descriptions commerciales annotées. Rechercher d’abord des exemples métier validés, puis des décisions de classement publiques avec description exploitable, édition et provenance identifiables. Documenter les conditions d’accès et la qualité de chaque source.

Commencer par **100 à 300 exemples relus**, couvrant plusieurs chapitres, descriptions courtes, détaillées et ambiguës. Stocker `example_id`, `product_group_id`, `text`, `edition`, `expected_codes`, `answerability`, provenance et commentaire d’annotation. Plusieurs codes acceptables doivent être justifiés par l’annotation ; une liste de possibilités issue d’un texte trop vague ne constitue pas automatiquement une vérité terrain.

Mesures initiales sur les cas classables : **Hit@1/3/5** (au moins un code acceptable présent) et **MRR** (rang du premier code acceptable). Si plusieurs codes doivent réellement être retrouvés, ajouter Precision@k et Recall@k. Rapporter séparément les résultats aux niveaux 2/4/6, par type de description, ainsi que le taux de codes invalides et d’erreurs. Pour le RAG, mesurer le rappel des candidats avant génération. Pour l’abstention, afficher couverture et précision sur les réponses émises, puis vérifier les demandes d’information sur les cas ambigus. Garder les échecs dans les dénominateurs appropriés et publier les effectifs.

## 5. Ordre d’implémentation

1. Importer et contrôler le catalogue H6 ; sauvegarder le manifeste de provenance et la table hiérarchique.
2. Constituer le premier jeu annoté et figer les lots ; écrire le calcul des métriques.
3. Implémenter contrat commun, registre, baseline lexicale et CLI de recherche/évaluation ; sauvegarder un premier run complet.
4. Ajouter embeddings, LLM direct et RAG ; comparer sur le même test et analyser les désaccords.
5. Ajouter Flask/Jinja : saisie, sélection multiple des approches, résultats côte à côte et export des prédictions.
6. Selon les erreurs observées, expérimenter notes, recherche hybride, reclassement, parcours hiérarchique ou entraînement supervisé. Sauvegarder jeux d’entraînement, paramètres et artefacts associés.

Le premier jalon est atteint lorsque les trois approches demandées et la baseline tournent sur les mêmes exemples, via CLI et GUI, avec validation des codes et rapport comparatif enregistré.

Décisions à confirmer avant d’élargir : secteurs prioritaires, disponibilité des annotations métier, besoin de libellés français officiels.
