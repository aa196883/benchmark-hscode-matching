#!/usr/bin/env bash
set -euo pipefail
sandbox_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ -z "$(find "$sandbox_root/extension/src/main/java" -name '*.java' ! -name package-info.java -print -quit)" ]]; then
  echo 'Aucune implémentation transférable : ajouter le nouveau service dans extension/src/main/java avant export.' >&2
  exit 2
fi
"$sandbox_root/dev.sh" verify "$@"
mkdir -p "$sandbox_root/extension/target"
source_paths=(src/main)
if [[ -d "$sandbox_root/extension/src/test" ]]; then source_paths+=(src/test); fi
tar -czf "$sandbox_root/extension/target/transferable-sources.tar.gz" -C "$sandbox_root/extension" "${source_paths[@]}"
echo "Archive de sources à examiner : $sandbox_root/extension/target/transferable-sources.tar.gz"
echo 'Extraire dans un répertoire de revue puis copier uniquement les nouveaux fichiers dans le module industriel cible.'
