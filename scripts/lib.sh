#!/usr/bin/env bash
set -euo pipefail
library_init() {
    library_release="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
    library_env="${LIBRARY_ENV_FILE:-$library_release/.env}"
    if [[ ! -f "$library_env" && -f "$library_release/../../.env" ]]; then
        library_env="$(cd "$library_release/../.." && pwd)/.env"
    fi
    [[ -f "$library_env" ]] || { echo "Missing environment file: $library_env" >&2; exit 1; }
    library_project="${LIBRARY_PROJECT_NAME:-library}"
    [[ "$library_project" =~ ^[a-z0-9][a-z0-9_-]+$ ]] || exit 1
    library_dc=(docker compose -p "$library_project" --project-directory "$library_release" --env-file "$library_env" -f "$library_release/compose.yaml")
    if [[ -f "$(dirname "$library_env")/use-sub2api" ]]; then library_dc+=(-f "$library_release/compose.sub2api.yaml"); fi
    export APP_VERSION="$(basename "$library_release")"
    "${library_dc[@]}" config --quiet
}
