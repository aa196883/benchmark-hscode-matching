#!/usr/bin/env bash
set -euo pipefail
sandbox_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
command="${1:-test}"
if (( $# )); then shift; fi
case "$command" in
  run) exec java -jar "$sandbox_root/runner/target/sandbox.jar" "$@" ;;
  test|package|clean|verify) ;;
  *) echo "Usage: $0 {test|package|verify|clean|run} [arguments]" >&2; exit 2 ;;
esac
sandbox_maven="${MVN:-}"
if [[ -z "$sandbox_maven" ]]; then
  sandbox_maven="$(command -v mvn || true)"
fi
if [[ -z "$sandbox_maven" && -d "${HOME}/.m2/wrapper/dists" ]]; then
  sandbox_maven="$(find "${HOME}/.m2/wrapper/dists" -type f -path '*/bin/mvn' | sort | tail -n 1)"
fi
if [[ -z "$sandbox_maven" ]]; then
  echo 'Maven 3.9+ requis : installer Maven ou définir MVN=/chemin/vers/bin/mvn.' >&2
  exit 2
fi
exec "$sandbox_maven" -B -s "$sandbox_root/settings.xml" -gs "$sandbox_root/settings.xml" -f "$sandbox_root/pom.xml" "$command" "$@"
