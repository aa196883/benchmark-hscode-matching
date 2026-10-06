#!/usr/bin/env bash
set -euo pipefail
script_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd "$script_root/../.." && pwd)"
resources_root="$script_root/../simplified_env/extension/src/main/resources/com/semsoft/lestr/tradeanalysis/infra/service/analysis/ia/h6_2022"
for source in "$project_root/data/processed/h6_2022/catalog.jsonl" "$project_root/artifacts/embeddings/h6_2022/manifest.json" "$project_root/artifacts/embeddings/h6_2022/vectors.jsonl"; do
  if [[ ! -f "$source" ]]; then
    echo "Ressource absente : $source" >&2
    exit 1
  fi
done
mkdir -p "$resources_root"
cp "$project_root/data/processed/h6_2022/catalog.jsonl" "$resources_root/catalog.jsonl"
cp "$project_root/artifacts/embeddings/h6_2022/manifest.json" "$resources_root/manifest.json"
cp "$project_root/artifacts/embeddings/h6_2022/vectors.jsonl" "$resources_root/vectors.jsonl"
echo "Ressources préparées dans $resources_root"
