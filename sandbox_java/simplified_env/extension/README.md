# Code transférable

Placer le futur service RAG dans `src/main/java/com/semsoft/lestr/tradeanalysis/infra/service/analysis/rag/`.
Le module est volontairement sans implémentation pour ce premier jalon.

La dépendance `compat` est `provided` : elle sert à compiler contre les contrats reproduits,
et ne doit jamais être installée dans l’application industrielle. Ne pas importer les classes
`local.lestr.sandbox` du module `runner`. Les tests nécessitant ces doubles restent dans `runner`.

`../export.sh` vérifie le projet puis prépare une archive contenant uniquement `src/main`
et `src/test` de ce module, lorsque du code transférable existe. Le script ne modifie jamais
le dépôt industriel. Voir [la procédure complète](../../README.md).
