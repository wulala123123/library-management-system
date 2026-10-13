#!/usr/bin/env bash
set -euo pipefail
library_archive="${1:?Usage: deploy.sh release.zip [install|update] [installation-directory]}"
library_action="${2:-install}"
library_root="${3:-/opt/library-management-system}"
case "$library_action" in install|update) ;; *) echo 'Action must be install or update' >&2; exit 1;; esac
for library_tool in docker unzip sha256sum realpath python3; do command -v "$library_tool" >/dev/null || { echo "Missing: $library_tool" >&2; exit 1; }; done
library_archive="$(realpath "$library_archive")"
library_root="$(realpath -m "$library_root")"
[[ "$library_root" != / && "$library_root" != /opt && "$library_root" != /home && "$library_root" != /tmp ]] || { echo 'Choose a dedicated installation directory' >&2; exit 1; }
[[ -f "$library_archive.sha256" ]] || { echo 'Missing checksum sidecar' >&2; exit 1; }
(cd "$(dirname "$library_archive")" && sha256sum -c "$(basename "$library_archive").sha256")
python3 - "$library_archive" <<'PY'
import stat, sys
from pathlib import PurePosixPath
from zipfile import ZipFile
with ZipFile(sys.argv[1]) as archive:
    for entry in archive.infolist():
        path=PurePosixPath(entry.filename)
        mode=entry.external_attr >> 16
        if path.is_absolute() or '..' in path.parts or '\\' in entry.filename or stat.S_ISLNK(mode):
            raise SystemExit('Unsafe ZIP entry')
PY
mkdir -p -- "$library_root/releases" "$library_root/backups"
library_stage="$(mktemp -d "$library_root/.stage.XXXXXX")"
trap 'rm -rf -- "$library_stage"' EXIT
unzip -q "$library_archive" -d "$library_stage"
library_bundles=("$library_stage"/*)
[[ ${#library_bundles[@]} == 1 && -f "${library_bundles[0]}/compose.yaml" ]] || { echo 'Invalid release layout' >&2; exit 1; }
library_version="$(basename "${library_bundles[0]}")"
[[ "$library_version" =~ ^library-management-system-[A-Za-z0-9._-]+$ ]] || exit 1
library_release="$library_root/releases/$library_version"
library_previous=''
if [[ -L "$library_root/current" ]]; then library_previous="$(readlink -f "$library_root/current")"; fi
if [[ -e "$library_release" ]]; then
    [[ "$library_release" != "$library_previous" && -f "$library_release/.archive.sha256" ]] || { echo 'Release is already current or has no checksum' >&2; exit 1; }
    [[ "$(sha256sum "$library_archive" | cut -d ' ' -f 1)" == "$(cut -d ' ' -f 1 "$library_release/.archive.sha256")" ]] || { echo 'Release content differs' >&2; exit 1; }
else
    mv -- "${library_bundles[0]}" "$library_release"
    cp -- "$library_archive.sha256" "$library_release/.archive.sha256"
fi
if [[ ! -f "$library_root/.env" ]]; then
    umask 077
    cp -- "$library_release/.env.example" "$library_root/.env"
    echo "Release unpacked. Configure $library_root/.env, then repeat this deployment command." >&2
    exit 1
fi
library_compose() {
    local library_path="$1"; shift
    local library_cmd=(docker compose -p library --project-directory "$library_path" --env-file "$library_root/.env" -f "$library_path/compose.yaml")
    [[ ! -f "$library_root/use-sub2api" ]] || library_cmd+=(-f "$library_path/compose.sub2api.yaml")
    APP_VERSION="$(basename "$library_path")" "${library_cmd[@]}" "$@"
}
library_compose "$library_release" config --quiet
if [[ "$library_action" == update ]]; then
    [[ -n "$library_previous" && -f "$library_previous/compose.yaml" ]] || { echo 'Missing current release; use install' >&2; exit 1; }
    if ! LIBRARY_ENV_FILE="$library_root/.env" LIBRARY_KEEP_STOPPED=1 APP_VERSION="$(basename "$library_previous")" bash "$library_previous/scripts/backup.sh" "$library_root/backups/$(date -u +%Y%m%dT%H%M%SZ)"; then
        library_compose "$library_previous" up -d --wait --wait-timeout 240
        exit 1
    fi
elif [[ -n "$library_previous" ]]; then echo 'Already installed; use update' >&2; exit 1; fi
if ! library_compose "$library_release" up -d --build --wait --wait-timeout 240; then
    library_compose "$library_release" logs --tail 60 backend || true
    if [[ -n "$library_previous" ]]; then
        echo 'Start failed; restoring previous application. Additive migrations are retained.' >&2
        library_compose "$library_previous" up -d --wait --wait-timeout 240
    fi
    exit 1
fi
ln -s -- "$library_release" "$library_root/.next-current"
mv -Tf -- "$library_root/.next-current" "$library_root/current"
echo "Healthy release: $library_version"
