"""Query the public OSV database using the actual resolved runtime dependencies."""
import argparse, concurrent.futures, json, re, urllib.request
from pathlib import Path

parser=argparse.ArgumentParser()
parser.add_argument('dependencies', type=Path)
parser.add_argument('--output',type=Path,default=Path('backend/target/dependency-audit.json'))
args=parser.parse_args()
packages=[]
for line in args.dependencies.read_text(encoding='utf-8-sig').splitlines():
    parts=line.strip().split(':')
    if len(parts)>=5 and parts[2]=='jar':
        packages.append({'package':{'ecosystem':'Maven','name':':'.join(parts[:2])},'version':parts[-2]})
if not packages:raise SystemExit('No runtime dependency coordinates were found')
def http(url,data=None):
    request=urllib.request.Request(url,data,{'Content-Type':'application/json'})
    with urllib.request.urlopen(request,timeout=60) as response:return json.load(response)
results=http('https://api.osv.dev/v1/querybatch',json.dumps({'queries':packages}).encode())['results']
findings=[{'name':p['package']['name'],'version':p['version'],'ids':[v['id'] for v in r.get('vulns',[])]} for p,r in zip(packages,results) if r.get('vulns')]
ids=sorted({id for f in findings for id in f['ids']})
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:
    vulns=list(pool.map(lambda id:http('https://api.osv.dev/v1/vulns/'+id),ids))
root=Path(__file__).resolve().parent.parent
# Framework 6.2 has no published fix for these two optional view-rendering features.
# The application is a JSON REST API, without either feature. Fail when that assumption changes.
reviewed={'GHSA-j9f9-w8pj-32f8','GHSA-pc63-qcmh-9cmg'}
features=re.compile(r'XsltView|Fragments?Rendering|SseEmitter|ServerSentEvent|text/event-stream|TEXT_EVENT_STREAM')
source=root/'backend/src/main'
source_files=[p for p in source.rglob('*') if p.is_file() and p.suffix in {'.java','.xml','.yml','.yaml','.properties','.html','.xsl','.xslt'}]
if not source.exists() or not source_files or any(features.search(p.read_text(encoding='utf-8-sig')) for p in source_files):
    reviewed=set()
unreviewed=[id for id in ids if id not in reviewed]
args.output.parent.mkdir(parents=True,exist_ok=True)
args.output.write_text(json.dumps({'packageCount':len(packages),'findings':findings,'reviewedNotReachable':sorted(reviewed & set(ids)),
                                  'unreviewed':unreviewed,'vulnerabilities':vulns},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'packages':len(packages),'advisories':len(ids),'reviewedNotReachable':sorted(reviewed & set(ids)), 'unreviewed':unreviewed}))
if unreviewed:raise SystemExit('Dependency audit has unreviewed findings')
