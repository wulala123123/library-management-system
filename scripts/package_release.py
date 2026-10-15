"""Package verified build outputs; exclude credentials and runtime data."""
import argparse
import hashlib
import re
from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED

parser = argparse.ArgumentParser()
parser.add_argument('version')
args = parser.parse_args()
if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]{0,63}', args.version):
    parser.error('Invalid version')
root = Path(__file__).resolve().parent.parent
name = 'library-management-system-' + args.version
files = ['backend/target/library-backend.jar', 'backend/Dockerfile', 'backend/nginx.conf',
         'frontend/Dockerfile', 'compose.yaml', 'compose.sub2api.yaml', '.dockerignore',
         '.env.example', 'deploy.sh', 'README.md', 'docs/deployment.md',
         'docs/database-migrations.md', 'docs/release-notes.md', 'docs/repair-results-2026-10-13.md',
         'docs/release-readiness-2026-10-13.md', 'docs/server-trial-2026-10-14.md',
         'docs/renewal-days-2026-10-14.md', 'docs/huiben-migration-2026-10-15.md']
for folder in ['frontend/dist', 'backend/docker', 'scripts']:
    files.extend(str(p.relative_to(root)).replace('\\', '/') for p in (root/folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
for file in files:
    if not (root/file).is_file(): raise SystemExit('Missing release input: ' + file)
if not (root/'frontend/dist/index.html').is_file(): raise SystemExit('Build frontend first')
out = root/'dist'; out.mkdir(exist_ok=True)
target = out/(name + '.zip')
with ZipFile(target, 'w', compression=ZIP_DEFLATED, compresslevel=6) as archive:
    for file in sorted(set(files)):
        info = ZipInfo(name + '/' + file, date_time=(2026, 10, 13, 0, 0, 0))
        info.create_system = 3  # Preserve Unix executable bits even when packaging on Windows.
        info.compress_type = ZIP_DEFLATED
        info.external_attr = (0o100755 if file.endswith('.sh') else 0o100644) << 16
        archive.writestr(info, (root/file).read_bytes())
digest = hashlib.sha256(target.read_bytes()).hexdigest()
target.with_suffix('.zip.sha256').write_text(digest + '  ' + target.name + '\n', encoding='ascii', newline='\n')
print(target)
