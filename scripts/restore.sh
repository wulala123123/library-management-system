#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"
library_init
library_backup="${1:?Provide a verified backup directory}"
[[ "$library_project" != library ]] || { echo 'Set LIBRARY_PROJECT_NAME to a fresh recovery project first' >&2; exit 1; }
[[ ! -f "$(dirname "$library_env")/use-sub2api" ]] || { echo 'Recovery must not join the production proxy network' >&2; exit 1; }
(cd "$library_backup" && sha256sum -c SHA256SUMS)
[[ "$(cat "$library_backup/application-version.txt")" == "$APP_VERSION" ]] || { echo 'Run restore.sh from the application version recorded in this backup' >&2; exit 1; }
[[ -z "$("${library_dc[@]}" ps -aq)" ]] || { echo 'Recovery project already has containers; refusing overwrite' >&2; exit 1; }
[[ -z "$(docker volume ls -q --filter "label=com.docker.compose.project=$library_project")" ]] || { echo 'Recovery project already has volumes; refusing overwrite' >&2; exit 1; }
[[ -z "$(docker volume ls -q --filter "name=${library_project}_")" ]] || { echo 'Recovery volume names already exist; refusing overwrite' >&2; exit 1; }
"${library_dc[@]}" up -d --wait --wait-timeout 180 mysql
"${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator "$MYSQL_DATABASE"' < "$library_backup/database.sql"
"${library_dc[@]}" run --rm --no-deps -T --entrypoint tar backend -xzf - -C /app < "$library_backup/uploads.tar.gz"
"${library_dc[@]}" up -d --build --wait --wait-timeout 240
echo "Restored into isolated project: $library_project"
