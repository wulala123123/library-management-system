#!/usr/bin/env bash
set -euo pipefail
[[ "${CI:-}" == true || "${LIBRARY_ACCEPTANCE:-}" == 1 ]] || { echo 'Run only in an isolated CI or test host' >&2; exit 1; }
for library_project in library library-recovery; do
    [[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$library_project")" ]] || { echo 'Project already exists; refusing test' >&2; exit 1; }
    [[ -z "$(docker volume ls -q --filter "name=${library_project}_")" ]] || { echo 'Project volumes already exist; refusing test' >&2; exit 1; }
done
library_repo="$(cd "$(dirname "$0")/.." && pwd -P)"
library_archive="$(realpath "${1:?Provide a release ZIP}")"
library_test="$(mktemp -d)"
mkdir "$library_test/install" "$library_test/recovery"
library_cleanup() {
    local library_status=$?
    set +e
    local library_cleanup_release=''
    if [[ -e "$library_test/install/current" ]]; then
        library_cleanup_release="$(readlink -f "$library_test/install/current")"
    else
        local library_candidates=("$library_test/install/releases"/*)
        if [[ -f "${library_candidates[0]}/compose.yaml" ]]; then library_cleanup_release="${library_candidates[0]}"; fi
    fi
    if [[ -n "$library_cleanup_release" ]]; then
        export LIBRARY_PROJECT_NAME=library LIBRARY_ENV_FILE="$library_test/install/.env"
        source "$library_cleanup_release/scripts/lib.sh"
        set +e
        library_init; "${library_dc[@]}" down -v --remove-orphans
    fi
    if [[ -f "$library_test/recovery/.env" && -n "${library_recovery_release:-}" ]]; then
        export LIBRARY_PROJECT_NAME=library-recovery LIBRARY_ENV_FILE="$library_test/recovery/.env"
        source "$library_recovery_release/scripts/lib.sh"
        set +e
        library_init; "${library_dc[@]}" down -v --remove-orphans
    fi
    rm -rf -- "$library_test"
    exit "$library_status"
}
trap library_cleanup EXIT
{
    echo "MYSQL_ROOT_PASSWORD=$(openssl rand -hex 32)"
    echo "MYSQL_PASSWORD=$(openssl rand -hex 32)"
    echo "MYSQL_MIGRATION_PASSWORD=$(openssl rand -hex 32)"
    echo "JWT_SECRET=$(openssl rand -hex 48)"
    echo 'ADMIN_USERNAME=acceptance_admin'
    echo "ADMIN_PASSWORD=$(openssl rand -hex 16)"
    echo 'FRONTEND_PORT=18089'
    echo 'CORS_ORIGINS=http://localhost:18089'
    echo 'APP_NETWORK_SUBNET=172.30.89.0/24'
    echo 'FRONTEND_INTERNAL_IP=172.30.89.10'
} > "$library_test/install/.env"
(umask 077; bash "$library_repo/deploy.sh" "$library_archive" install "$library_test/install")
library_release="$(readlink -f "$library_test/install/current")"
library_verify_backend() {
    local library_expected_release="$1" library_expected_project="${2:-library}"
    local library_backend_container library_expected_hash library_actual_hash
    library_backend_container="$(docker ps -q --filter "label=com.docker.compose.project=$library_expected_project" --filter label=com.docker.compose.service=backend)"
    [[ -n "$library_backend_container" && "$library_backend_container" != *$'\n'* ]]
    library_expected_hash="$(sha256sum "$library_expected_release/backend/target/library-backend.jar")"
    library_actual_hash="$(docker exec "$library_backend_container" sha256sum /app/app.jar)"
    [[ "${library_actual_hash%% *}" == "${library_expected_hash%% *}" ]] || { echo 'Running backend JAR differs from release package' >&2; exit 1; }
}
library_verify_backend "$library_release"
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18089 --env "$library_test/install/.env" --state "$library_test/state.json"
export LIBRARY_ENV_FILE="$library_test/install/.env"
source "$library_release/scripts/lib.sh"; library_init
"${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u library_app -D "$MYSQL_DATABASE" -e "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1"'
if "${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u library_app -D "$MYSQL_DATABASE" -e "CREATE TABLE acceptance_ddl_probe (id INT)"' >/dev/null 2>&1; then
    echo 'Application account unexpectedly has schema-write privileges' >&2; exit 1
fi
"${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator -D "$MYSQL_DATABASE" -e "CREATE TABLE acceptance_ddl_probe (id INT); DROP TABLE acceptance_ddl_probe"'
echo 'Unpacked install, application DML-only grants and migration DDL grants passed'
"${library_dc[@]}" up -d --force-recreate --wait --wait-timeout 240 backend frontend
library_verify_backend "$library_release"
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18089 --env "$library_test/install/.env" --state "$library_test/state.json" --check-state
echo 'Container recreate preserved data and files'
python3 "$library_repo/scripts/package_release.py" acceptance-update
# Reproduce a release whose JAR changes by one byte while keeping its size and
# ZIP timestamp. Reusing a stale COPY cache must not silently run the old JAR.
python3 - "$library_repo/dist/library-management-system-acceptance-update.zip" <<'PY'
import hashlib, io, sys
from pathlib import Path
from zipfile import ZipFile
path=Path(sys.argv[1])
with ZipFile(path) as archive: entries=[(entry,archive.read(entry)) for entry in archive.infolist()]
with ZipFile(path,'w') as archive:
    for entry,data in entries:
        if entry.filename.endswith('backend/target/library-backend.jar'):
            original=data
            with ZipFile(io.BytesIO(data)) as jar: central=jar.start_dir
            data=bytearray(data)
            assert data[central:central+4]==b'PK\x01\x02'
            data[central+38]^=1  # Change a ZIP directory attribute; class bytes stay valid.
            data=bytes(data)
            assert len(data)==len(original) and data!=original
            with ZipFile(io.BytesIO(data)) as jar: assert jar.testzip() is None
        archive.writestr(entry,data)
path.with_suffix('.zip.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name+'\n',newline='\n')
PY
bash "$library_repo/deploy.sh" "$library_repo/dist/library-management-system-acceptance-update.zip" update "$library_test/install"
library_release="$(readlink -f "$library_test/install/current")"
library_verify_backend "$library_release"
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18089 --env "$library_test/install/.env" --state "$library_test/state.json" --check-state
echo 'Application update preserved data and files'
python3 "$library_repo/scripts/package_release.py" acceptance-broken
python3 - "$library_repo/dist/library-management-system-acceptance-broken.zip" <<'PY'
import hashlib, sys
from pathlib import Path
from zipfile import ZipFile
path=Path(sys.argv[1])
with ZipFile(path) as z: entries=[(e,z.read(e)) for e in z.infolist()]
with ZipFile(path,'w') as z:
    for e,data in entries: z.writestr(e,b'intentionally invalid JAR' if e.filename.endswith('backend/target/library-backend.jar') else data)
path.with_suffix('.zip.sha256').write_text(hashlib.sha256(path.read_bytes()).hexdigest()+'  '+path.name+'\n', newline='\n')
PY
if bash "$library_repo/deploy.sh" "$library_repo/dist/library-management-system-acceptance-broken.zip" update "$library_test/install"; then
    echo 'Broken application unexpectedly passed' >&2; exit 1
fi
library_verify_backend "$library_release"
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18089 --env "$library_test/install/.env" --state "$library_test/state.json" --check-state
echo 'Failed-start rollback restored previous application and data'
APP_VERSION="$(basename "$library_release")" bash "$library_release/scripts/backup.sh" "$library_test/backup"
sed -e 's/18089/18090/g' -e 's/172.30.89/172.30.90/g' "$library_test/install/.env" > "$library_test/recovery/.env"
library_recovery_release="$library_release"
LIBRARY_PROJECT_NAME=library-recovery LIBRARY_ENV_FILE="$library_test/recovery/.env" bash "$library_release/scripts/restore.sh" "$library_test/backup"
library_verify_backend "$library_recovery_release" library-recovery
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18090 --env "$library_test/recovery/.env" --state "$library_test/state.json" --check-state --complete-recovered-link
echo 'First install, recreate, update, failed-start rollback, backup and isolated restore passed'
