#!/usr/bin/env bash
# Exercise failure paths without touching a Docker daemon. Container acceptance is separate.
set -euo pipefail
library_repo="$(cd "$(dirname "$0")/.." && pwd -P)"
library_test="$(mktemp -d)"
library_test_cleanup() {
    local library_status=$?
    if [[ "$library_status" != 0 && -f "$library_test/output" ]]; then cat "$library_test/output" >&2; fi
    rm -rf -- "$library_test"
    exit "$library_status"
}
trap library_test_cleanup EXIT
mkdir -p "$library_test/bin" "$library_test/install/releases"
export LIBRARY_MOCK_EVENTS="$library_test/events"
export PATH="$library_test/bin:$PATH"
# Git Bash may have Python available only as python.exe.
if [[ "$OSTYPE" == msys* ]] || ! python3 -c 'import sys' >/dev/null 2>&1; then
    printf '#!/usr/bin/env bash\nexec python "$@"\n' > "$library_test/bin/python3"
    chmod +x "$library_test/bin/python3"
fi
cat > "$library_test/bin/docker" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == volume ]]; then exit 0; fi
if [[ "$1" == inspect ]]; then
    python3 - <<'PY'
import json, os
project = os.environ['LIBRARY_PROJECT_NAME']
print(json.dumps([{'Config': {'Labels': {'com.docker.compose.project': project, 'com.docker.compose.service': 'backend'}}, 'Mounts': [{'Destination': '/app/uploads', 'Type': 'volume', 'Name': project+'_uploads'}]}, {'Config': {'Labels': {'com.docker.compose.project': project, 'com.docker.compose.service': 'mysql'}}, 'Image': 'sha256:'+'c'*64}]))
PY
    exit 0
fi
if [[ "$1" == run ]]; then
    if [[ "$*" != *'--format=pax -czpf - -C /app uploads' ]]; then cat > /dev/null; exit 0; fi
    python3 - <<'PY'
import io, sys, tarfile
with tarfile.open(fileobj=sys.stdout.buffer, mode='w|gz') as archive:
    root = tarfile.TarInfo('uploads'); root.type = tarfile.DIRTYPE; root.mode = 0o755
    archive.addfile(root)
    item = tarfile.TarInfo('uploads/synthetic.txt'); raw = b'disposable upload bytes\n'
    item.size = len(raw); item.mode = 0o600; item.uid = 0; item.gid = 0
    archive.addfile(item, io.BytesIO(raw))
PY
    exit 0
fi
shift
project=''
release=''
while [[ $# -gt 0 ]]; do
    case "$1" in
        -p) project="$2"; shift 2;;
        --project-directory) release="$2"; shift 2;;
        --env-file|-f) shift 2;;
        *) break;;
    esac
done
printf '%s|%s|%s\n' "$project" "${APP_VERSION:-}" "$*" >> "$LIBRARY_MOCK_EVENTS"
case "$1" in
    stop) [[ "${LIBRARY_MOCK_FAILURE:-}" != stop ]];;
    exec)
        if [[ "$*" == *'sha256sum /app/app.jar' ]]; then
            if [[ "${LIBRARY_MOCK_FAILURE:-}" == artifact ]]; then printf '%064d  /app/app.jar\n' 0
            else sha256sum "$release/backend/target/library-backend.jar"; fi
            exit 0
        fi
        [[ "${LIBRARY_MOCK_FAILURE:-}" != dump ]] || exit 1
        printf 'disposable database dump\n';;
    ps)
        if [[ "$*" == 'ps -aq backend' && ( "$project" == library || -f "$LIBRARY_MOCK_EVENTS.backend-created" ) ]]; then printf '%064d\n' 1; fi
        if [[ "$*" == 'ps -aq mysql' ]]; then printf '%064d\n' 2; fi;;
    run) cat > /dev/null;;
    up)
        if [[ "$*" == 'up --no-start --no-deps --no-recreate backend' ]]; then touch "$LIBRARY_MOCK_EVENTS.backend-created"; exit 0; fi
        [[ "${LIBRARY_MOCK_FAILURE:-}" != resume ]] || exit 1
        if [[ "${LIBRARY_MOCK_FAILURE:-}" == broken && "$APP_VERSION" == *-broken ]]; then exit 1; fi;;
esac
SH
chmod +x "$library_test/bin/docker"
library_old="$library_test/install/releases/library-management-system-test-old"
library_new="$library_test/install/releases/library-management-system-test-broken"
for library_path in "$library_old" "$library_new"; do
    mkdir -p "$library_path/scripts" "$library_path/backend/target"
    printf 'disposable backend artifact: %s\n' "$library_path" > "$library_path/backend/target/library-backend.jar"
    cp "$library_repo/scripts/"{lib,backup,restore}.sh "$library_path/scripts/"
    cp "$library_repo/scripts/restore_uploads.py" "$library_path/scripts/"
    cp "$library_repo/compose.yaml" "$library_path/compose.yaml"
    cp "$library_repo/.env.example" "$library_path/.env.example"
done
printf 'DISPOSABLE_TEST_ENV=1\n' > "$library_test/install/.env"
export LIBRARY_ENV_FILE="$library_test/install/.env"
export LIBRARY_PROJECT_NAME=library
expect_failure() {
    if "$@" > "$library_test/output" 2>&1; then
        echo "Expected failure: $*" >&2; exit 1
    fi
}
expect_event() { grep -Fq -- "$1" "$LIBRARY_MOCK_EVENTS" || { echo "Missing operation: $1" >&2; exit 1; }; }
expect_no_start() { if grep -Fq '|up ' "$LIBRARY_MOCK_EVENTS"; then echo 'Unexpected application start' >&2; exit 1; fi; }

# Even a partial stop and a failed dump must resume the prior healthy application.
for library_failure in stop dump; do
    : > "$LIBRARY_MOCK_EVENTS"
    expect_failure env LIBRARY_KEEP_STOPPED=1 LIBRARY_MOCK_FAILURE="$library_failure" \
        bash "$library_old/scripts/backup.sh" "$library_test/backup-$library_failure"
    expect_event 'up -d --wait --wait-timeout 240 backend frontend'
    [[ ! -f "$library_test/backup-$library_failure/SHA256SUMS" ]]
done
: > "$LIBRARY_MOCK_EVENTS"
LIBRARY_KEEP_STOPPED=1 bash "$library_old/scripts/backup.sh" "$library_test/backup-good" > "$library_test/output"
expect_no_start
grep -Fq 'application-version.txt' "$library_test/backup-good/SHA256SUMS"
: > "$LIBRARY_MOCK_EVENTS"
bash "$library_old/scripts/backup.sh" "$library_test/backup-normal" > "$library_test/output"
expect_event 'up -d --wait --wait-timeout 240 backend frontend'
expect_failure env LIBRARY_MOCK_FAILURE=resume bash "$library_old/scripts/backup.sh" "$library_test/backup-resume-failed"

# Reject another application version, altered backups, and recovery sharing the live proxy.
export LIBRARY_PROJECT_NAME=library-recovery
: > "$LIBRARY_MOCK_EVENTS"
expect_failure bash "$library_new/scripts/restore.sh" "$library_test/backup-good"
expect_no_start
grep -Fq 'application version' "$library_test/output"
cp -r "$library_test/backup-good" "$library_test/backup-tampered"
printf 'changed\n' >> "$library_test/backup-tampered/application-version.txt"
expect_failure bash "$library_old/scripts/restore.sh" "$library_test/backup-tampered"
expect_no_start
touch "$library_test/install/use-sub2api"
expect_failure bash "$library_old/scripts/restore.sh" "$library_test/backup-good"
expect_no_start
rm "$library_test/install/use-sub2api"
bash "$library_old/scripts/restore.sh" "$library_test/backup-good" > "$library_test/output"
expect_event 'up -d --wait --wait-timeout 180 mysql'
expect_event 'up -d --build --wait --wait-timeout 240'

# An update that fails health checks must keep current and restart the prior version.
export LIBRARY_PROJECT_NAME=library
python3 - "$library_new" "$library_test/release.zip" <<'PY'
import hashlib, sys
from pathlib import Path
from zipfile import ZipFile
release, target = map(Path, sys.argv[1:])
with ZipFile(target, 'w') as archive:
    for file in release.rglob('*'):
        if file.is_file(): archive.write(file, release.name + '/' + file.relative_to(release).as_posix())
target.with_suffix('.zip.sha256').write_text(hashlib.sha256(target.read_bytes()).hexdigest() + '  ' + target.name + '\n', newline='\n')
PY
rm -rf -- "$library_new"
ln -s "$library_old" "$library_test/install/current"
: > "$LIBRARY_MOCK_EVENTS"
expect_failure env LIBRARY_MOCK_FAILURE=broken bash "$library_repo/deploy.sh" "$library_test/release.zip" update "$library_test/install"
[[ "$(readlink -f "$library_test/install/current")" == "$library_old" ]]
expect_event 'library-management-system-test-old|up -d --wait --wait-timeout 240'
# A healthy process running stale artifact bytes must also roll back.
rm -rf -- "$library_test/install/backups"
mkdir "$library_test/install/backups"
: > "$LIBRARY_MOCK_EVENTS"
expect_failure env LIBRARY_MOCK_FAILURE=artifact bash "$library_repo/deploy.sh" "$library_test/release.zip" update "$library_test/install"
[[ "$(readlink -f "$library_test/install/current")" == "$library_old" ]]
grep -Fq 'Running backend JAR differs' "$library_test/output"
expect_event 'library-management-system-test-old|up -d --wait --wait-timeout 240'
# Retry identical immutable content after the failure is corrected.
# The production backup name uses seconds; remove only the test backup to avoid a name collision.
rm -rf -- "$library_test/install/backups"
mkdir "$library_test/install/backups"
bash "$library_repo/deploy.sh" "$library_test/release.zip" update "$library_test/install" > "$library_test/output"
[[ "$(readlink -f "$library_test/install/current")" == "$library_new" ]]
expect_failure bash "$library_repo/deploy.sh" "$library_test/release.zip" update "$library_test/install"
# Preparing an installation without credentials must not report successful deployment.
expect_failure bash "$library_repo/deploy.sh" "$library_test/release.zip" install "$library_test/unconfigured"
[[ -f "$library_test/unconfigured/.env" && ! -e "$library_test/unconfigured/current" ]]
# Private operator umasks must not make implicit frontend directories inaccessible.
mkdir "$library_test/private-install"
cp "$library_test/install/.env" "$library_test/private-install/.env"
(umask 077; bash "$library_repo/deploy.sh" "$library_test/release.zip" install "$library_test/private-install") > "$library_test/output"
if [[ "$OSTYPE" != msys* ]]; then
    [[ "$(stat -c %a "$library_test/private-install/current/scripts")" == 755 ]]
fi

echo 'Operations failure-path tests passed (mock Docker; no container acceptance claimed)'
