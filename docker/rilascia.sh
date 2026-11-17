#!/usr/bin/env bash
# Compila un candidato SUBITO (senza aspettare il watcher). Poi lo approvi da /rilasci/.
# Uso:  docker/rilascia.sh 1.2.0 "Cosa cambia" [ref]
set -euo pipefail
cd "$(dirname "$0")/.."
exec docker/build.sh "${1:-}" "${3:-}" "${2:-}" "manuale (server)"
