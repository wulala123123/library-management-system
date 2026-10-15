"""Check indexed public documentation without network or runtime dependencies."""
import argparse
import collections
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


ENTRY_FILES = ('README.md', 'AGENTS.md', 'PROJECT.md', 'PLAN.md', 'WORKFLOW.md')
ENTRY_LIMITS = {'AGENTS.md': 100, 'PROJECT.md': 160, 'PLAN.md': 120, 'WORKFLOW.md': 220}
LINK = re.compile(r'!?\[[^\]\n]*\]\((<[^>\n]+>|[^)\s]+)(?:\s+"[^"]*")?\)')
TASK = re.compile(r'^- \[[ xX]\] (B\d{2,})\s', re.MULTILINE)


def prose(content):
    """Exclude fenced examples from link and heading checks."""
    result, fence = [], None
    for line in content.splitlines():
        match = re.match(r'^\s*(`{3,}|~{3,})', line)
        if match:
            marker = match[1][0]
            if fence is None:
                fence = marker
            elif marker == fence:
                fence = None
            continue
        if fence is None:
            result.append(line)
    return '\n'.join(result)


def anchors(content):
    found, counts = set(), collections.Counter()
    for line in prose(content).splitlines():
        match = re.match(r'^#{1,6}\s+(.+?)(?:\s+#+)?$', line)
        if not match:
            continue
        title = re.sub(r'[`*_]', '', match[1]).lower()
        base = ''.join(c for c in title if c.isalnum() or c in ' -_')
        base = re.sub(r'\s', '-', base)
        suffix = '' if counts[base] == 0 else '-' + str(counts[base])
        found.add(base + suffix)
        counts[base] += 1
    return found


def check(root):
    root = root.resolve()
    docs = root / 'docs'
    errors = []
    for name in ENTRY_FILES:
        if not (root / name).is_file():
            errors.append('Missing entry: ' + name)
    if not (docs / 'README.md').is_file():
        errors.append('Missing document index: docs/README.md')
    public_files = {p.resolve() for p in docs.rglob('*') if p.is_file()}
    markdown = {p for p in public_files if p.suffix == '.md'}
    markdown.update(p.resolve() for p in (root / '.agents' / 'skills').rglob('*.md'))
    markdown.update((root / name) for name in ENTRY_FILES if (root / name).is_file())
    contents = {p: p.read_text(encoding='utf-8-sig') for p in markdown}
    graph = collections.defaultdict(set)
    task_owners = collections.defaultdict(list)

    for path, content in contents.items():
        relative = path.relative_to(root).as_posix()
        archived = relative.startswith('docs/archive/')
        if not archived:
            limit = ENTRY_LIMITS.get(relative, 120 if path.name == 'README.md' else 400)
            if len(content.splitlines()) > limit:
                errors.append(f'{relative}: exceeds {limit} lines; split by subject')
            if re.search(r'\d{4}-\d{2}-\d{2}', relative):
                errors.append(relative + ': dated active document; use a stable topic path')
            if relative.startswith('docs/plans/'):
                for task in TASK.findall(content):
                    task_owners[task].append(relative)
        for match in LINK.finditer(prose(content)):
            target = match[1].strip('<>')
            url = urlsplit(target)
            if url.scheme or url.netloc:
                continue
            local = unquote(url.path)
            destination = ((root / local.lstrip('/')) if local.startswith('/')
                           else (path.parent / local) if local else path).resolve()
            if not destination.is_relative_to(root):
                errors.append(f'{relative}: link outside repository: {target}')
                continue
            if not destination.exists():
                errors.append(f'{relative}: missing link target: {target}')
                continue
            graph[path].add(destination)
            if url.fragment and destination in contents:
                if unquote(url.fragment) not in anchors(contents[destination]):
                    errors.append(f'{relative}: missing heading: {target}')

    for directory in {p.parent for p in public_files}:
        if not (directory / 'README.md').is_file():
            errors.append('Missing directory index: ' + directory.relative_to(root).as_posix())
    reachable, pending = set(), [docs / 'README.md']
    while pending:
        node = pending.pop()
        if node in reachable:
            continue
        reachable.add(node)
        pending.extend(graph[node] - reachable)
    for path in sorted(public_files - reachable):
        errors.append('Unindexed public file: ' + path.relative_to(root).as_posix())
    for task, owners in sorted(task_owners.items()):
        if len(owners) > 1:
            errors.append(f'Duplicate task definition {task}: ' + ', '.join(owners))
    return errors, {'markdown': len(markdown), 'public_files': len(public_files), 'tasks': len(task_owners)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    errors, summary = check(args.root)
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print('Documentation OK: ' + ', '.join(f'{key}={value}' for key, value in summary.items()))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
