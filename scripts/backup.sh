#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/lib.sh"
library_init
library_backup="${1:?Provide an absolute backup directory}"
[[ "$library_backup" == /* ]] || { echo 'Backup path must be absolute' >&2; exit 1; }
[[ ! -e "$library_backup" ]] || { echo 'Backup directory already exists' >&2; exit 1; }
umask 077
mkdir -p -- "$library_backup"
library_resume() {
    local library_status=$?
    trap - EXIT
    if [[ "$library_status" != 0 || "${LIBRARY_KEEP_STOPPED:-0}" != 1 ]]; then
        if ! "${library_dc[@]}" up -d --wait --wait-timeout 240 backend frontend; then
            echo 'Backup recovery failed; check application health before continuing' >&2
            library_status=1
        fi
    fi
    exit "$library_status"
}
trap library_resume EXIT
"${library_dc[@]}" stop frontend backend
"${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysqldump -u library_app --single-transaction --no-tablespaces --skip-triggers --column-statistics=0 --set-gtid-purged=OFF "$MYSQL_DATABASE"' > "$library_backup/database.sql"
python3 "$library_release/scripts/restore_uploads.py" --capture "$library_backup/uploads.tar.gz" -- "${library_dc[@]}"
cp -- "$library_env" "$library_backup/environment.env"
printf '%s\n' "$APP_VERSION" > "$library_backup/application-version.txt"
(cd "$library_backup" && sha256sum database.sql uploads.tar.gz environment.env application-version.txt > SHA256SUMS)
echo "Backup created: $library_backup"
