"""Restore upload bytes and exact metadata into a fresh isolated Compose volume."""
import argparse
from decimal import Decimal
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import tarfile


def inventory(archive):
    result = {}
    with tarfile.open(archive, 'r:*') as source:
        for item in source:
            name = str(PurePosixPath(item.name))
            path = PurePosixPath(name)
            if (path.is_absolute() or '..' in path.parts or not path.parts
                    or path.parts[0] != 'uploads' or '\\' in name or '\0' in name
                    or name in result or not (item.isdir() or item.isfile())):
                raise ValueError('UNSAFE_UPLOAD_ARCHIVE')
            timestamp = item.pax_headers.get('mtime', str(item.mtime))
            if not re.fullmatch(r'-?[0-9]+(?:\.[0-9]{1,9})?', timestamp):
                raise ValueError('UNSUPPORTED_UPLOAD_TIME_PRECISION')
            if item.uid < 0 or item.gid < 0 or item.mode < 0 or item.mode > 0o7777:
                raise ValueError('INVALID_UPLOAD_METADATA')
            digest = hashlib.sha256()
            if item.isfile():
                with source.extractfile(item) as contents:
                    for block in iter(lambda: contents.read(1024 * 1024), b''):
                        digest.update(block)
            result[name] = dict(kind='directory' if item.isdir() else 'file',
                                mode=item.mode, uid=item.uid, gid=item.gid,
                                size=item.size, mtime=Decimal(timestamp),
                                sha256=digest.hexdigest() if item.isfile() else None)
    if not result or result.get('uploads', {}).get('kind') != 'directory':
        raise ValueError('UPLOAD_ROOT_REQUIRED')
    for name in result:
        for parent in PurePosixPath(name).parents:
            if str(parent) in result and result[str(parent)]['kind'] != 'directory':
                raise ValueError('UPLOAD_PARENT_NOT_DIRECTORY')
    return result


def project_name(compose):
    if not compose or compose[:2] != ['docker', 'compose']:
        raise ValueError('COMPOSE_COMMAND_REQUIRED')
    options = compose[2:]
    project = None
    while options:
        flag, *remaining = options
        if flag not in ('-p', '--project-directory', '--env-file', '-f') or not remaining:
            raise ValueError('INVALID_COMPOSE_OPTIONS')
        value, *options = remaining
        if flag == '-p':
            if project is not None or not re.fullmatch(r'[a-z0-9][a-z0-9_-]+', value):
                raise ValueError('ISOLATED_PROJECT_REQUIRED')
            project = value
    if project is None:
        raise ValueError('COMPOSE_PROJECT_REQUIRED')
    return project


def upload_context(compose):
    project = project_name(compose)
    ids = []
    for service in ('backend', 'mysql'):
        value = subprocess.check_output(compose + ['ps', '-aq', service], timeout=30).decode('ascii').strip()
        if not re.fullmatch(r'[a-f0-9]{12,64}', value):
            raise ValueError('EXACT_UPLOAD_CONTAINER_REQUIRED')
        ids.append(value)
    items = json.loads(subprocess.check_output(['docker', 'inspect', *ids], timeout=30))
    if len(items) != 2:
        raise ValueError('EXACT_UPLOAD_CONTAINER_REQUIRED')
    for item, service in zip(items, ('backend', 'mysql')):
        labels = item['Config']['Labels']
        if labels.get('com.docker.compose.project') != project or labels.get('com.docker.compose.service') != service:
            raise ValueError('UPLOAD_CONTAINER_OWNERSHIP_CHANGED')
    mounts = [m for m in items[0]['Mounts'] if m['Destination'] == '/app/uploads']
    if len(mounts) != 1 or mounts[0]['Type'] != 'volume' or mounts[0]['Name'] != project + '_uploads':
        raise ValueError('EXACT_UPLOAD_VOLUME_REQUIRED')
    image = items[1]['Image']
    if not re.fullmatch(r'sha256:[a-f0-9]{64}', image):
        raise ValueError('EXACT_MYSQL_IMAGE_REQUIRED')
    # The existing MySQL image supplies GNU tar. Only the upload volume is
    # mounted; no database volume, credentials or application entrypoint runs.
    return ['docker', 'run', '--rm', '--network', 'none', '--read-only', '--user', '0:0',
            '--label', 'com.docker.compose.project=' + project,
            '--mount', 'type=volume,src=' + mounts[0]['Name'] + ',dst=/app/uploads',
            '--entrypoint', 'tar', image]


def capture(archive, compose):
    command = upload_context(compose)
    command[command.index('--mount') + 1] += ',readonly'
    with Path(archive).open('xb') as target:
        subprocess.run(command + ['--format=pax', '-czpf', '-', '-C', '/app', 'uploads'],
                       stdout=target, check=True, timeout=180)
    inventory(archive)


def restore(archive, compose):
    inventory(archive)  # Validate the whole archive before executing Docker.
    if project_name(compose) == 'library':
        raise ValueError('ISOLATED_PROJECT_REQUIRED')
    if subprocess.check_output(compose + ['ps', '-aq', 'backend'], timeout=30).strip():
        raise ValueError('FRESH_BACKEND_REQUIRED')
    # Backend is created stopped: no application or business initializer runs.
    subprocess.run(compose + ['up', '--no-start', '--no-deps', '--no-recreate', 'backend'],
                   check=True, timeout=180)
    command = upload_context(compose)
    command.insert(2, '-i')
    with Path(archive).open('rb') as contents:
        subprocess.run(command + ['-xzpf', '-', '--same-owner', '--delay-directory-restore', '-C', '/app'],
                       stdin=contents, check=True, timeout=180)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compare', action='store_true')
    parser.add_argument('--capture', action='store_true')
    parser.add_argument('archive')
    parser.add_argument('arguments', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.compare:
        if len(args.arguments) != 1 or inventory(args.archive) != inventory(args.arguments[0]):
            raise ValueError('UPLOAD_BYTES_OR_METADATA_CHANGED')
        print('Upload bytes, types, permissions, ownership and timestamps match')
    else:
        command = args.arguments[1:] if args.arguments[:1] == ['--'] else args.arguments
        (capture if args.capture else restore)(args.archive, command)


if __name__ == '__main__':
    main()
