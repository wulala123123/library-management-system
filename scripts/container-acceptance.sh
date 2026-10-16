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
# BEGIN B33-A ATTRIBUTES SEED (independent synthetic module acceptance)
cat > "$library_test/catalog_attributes_acceptance.py" <<'B33A_PY'
"""Embedded verbatim in container-acceptance.sh; synthetic isolated fixtures only."""
import argparse
import base64
import hashlib
import hmac
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

p=argparse.ArgumentParser()
p.add_argument('--base',required=True)
p.add_argument('--env',required=True)
p.add_argument('--state',required=True)
p.add_argument('--mode',choices=('prepare-source','seed','check'),required=True)
args=p.parse_args()
env=dict(line.split('=',1) for line in Path(args.env).read_text().splitlines() if '=' in line and not line.startswith('#'))
url=urllib.parse.urlsplit(args.base)
assert (url.scheme=='http' and url.hostname in ('localhost','127.0.0.1') and url.port in (18089,18090)
    and not url.username and not url.password and url.path in ('','/') and not url.query and not url.fragment
    and env.get('FRONTEND_PORT')==str(url.port)
    and env.get('APP_NETWORK_SUBNET')==('172.30.89.0/24' if url.port==18089 else '172.30.90.0/24')), 'Attributes fixture requires the isolated acceptance deployment'
assert args.mode=='check' or url.port==18089
def enc(value):return base64.urlsafe_b64encode(value).rstrip(b'=')
payload={'sub':'1','role':'ADMIN','version':0,'iat':int(time.time()),'exp':int(time.time())+3600}
unsigned=enc(b'{"alg":"HS256","typ":"JWT"}')+b'.'+enc(json.dumps(payload).encode())
token=(unsigned+b'.'+enc(hmac.new(env['JWT_SECRET'].encode(),unsigned,hashlib.sha256).digest())).decode()
def call(path,method='GET',body=None,anonymous=False):
    headers={'Content-Type':'application/json'}
    if not anonymous:headers['Authorization']='Bearer '+token
    data=None if body is None else json.dumps(body,ensure_ascii=False).encode()
    request=urllib.request.Request(args.base+path,data,headers,method=method)
    with urllib.request.urlopen(request,timeout=120) as response:value=json.loads(response.read())
    assert value['code']==200,'Unexpected attributes API result'
    return value['data']
def denied(path,method,body,status,reasons=None,anonymous=False):
    try:call(path,method,body,anonymous)
    except urllib.error.HTTPError as error:
        value=json.loads(error.read())
        assert error.code==status and value['code']==status,'Unexpected attributes rejection'
        if reasons is not None:assert value['data']['reasonCode'] in reasons,'Unexpected attributes reason'
    else:raise AssertionError('Expected attributes rejection')
def save(value):Path(args.state).write_text(json.dumps(value,ensure_ascii=False,sort_keys=True),encoding='utf-8')
def qtext(value):return "CONVERT(UNHEX('"+value.encode().hex()+"') USING utf8mb4)"
def attribute(book_id):return call('/api/catalog-books/'+str(book_id)+'/attributes')
def catalog(book_id,anonymous=False):return call('/api/catalog-books/'+str(book_id),anonymous=anonymous)
def source(book_id):return call('/api/catalog-books/'+str(book_id)+'/source')
def audit(book_id):return call('/api/catalog-books/'+str(book_id)+'/attribute-events?page=1&size=100')
def preview(book_id,changes,mode='MANUAL'):
    current=attribute(book_id);evidence=source(book_id) if mode=='SOURCE' else None
    changes={key:changes.get(key) for key in ('displayTitleOverride','bindingCode','ageLabels','adoptedSourceTitle')}
    body={'operationKey':str(uuid.uuid4()),'mode':mode,'reason':'隔离容器图书属性持久化验收','bookId':book_id,
        'expectedRevision':current['profile']['revision'],'expectedCatalogDigest':current['catalogDigest'],
        'sourceDigest':None if evidence is None else evidence['sourceDigest'],'changes':changes}
    view=call('/api/catalog-attributes/previews','POST',body)
    assert call('/api/catalog-attributes/previews','POST',body)==view,'Original preview key changed'
    assert call('/api/catalog-attributes/by-key?key='+body['operationKey'])==view
    assert view['state']=='PREVIEWED' and view['receipt'] is None
    return {'input':body,'preview':view}
def confirm(saved):
    view=saved['preview'];body={'operationKey':saved['input']['operationKey'],'previewDigest':view['previewDigest']}
    path='/api/catalog-attributes/'+str(view['requestId'])+'/confirm'
    receipt=call(path,'POST',body)
    assert call(path,'POST',body)==receipt,'Confirm retry duplicated attribute work'
    committed=call('/api/catalog-attributes/'+str(view['requestId']))
    assert committed['state']=='COMMITTED' and committed['receipt']==receipt and committed['preview']==view['preview']
    assert call('/api/catalog-attributes/by-key?key='+body['operationKey'])==committed
    saved.update(confirmation=body,receipt=receipt,committed=committed)
    return receipt
def title(book_id,value):
    current=call('/api/books/'+str(book_id)+'/metadata')
    call('/api/books/'+str(book_id)+'/metadata','PUT',{'expectedMetadataDigest':current['metadataDigest'],'changes':{'title':value}})
def assert_public(value):
    assert set(value)==set('id isbn catalogTitle displayTitle adoptedSourceTitle author publisher publishDate categoryId categoryName price pages coverImage description location stockTotal stockAvailable status createTime updateTime bindingCode ageLabels'.split()),'Unsafe public catalog fields'
def search(text):return call('/api/catalog-books?page=1&size=100&keyword='+urllib.parse.quote(text,safe=''),anonymous=True)

if args.mode=='prepare-source':
    assert not Path(args.state).exists(),'Fresh attributes fixture required'
    marker=uuid.uuid4().hex
    barcode='000B33A-'+marker+' '
    initial_title='隔离登记书名-'+marker
    category=call('/api/categories','POST',{'name':'隔离属性分类-'+marker[:8],'parentId':0,'sortOrder':0,'status':1})
    key=str(uuid.uuid4())
    body={'idempotencyKey':key,'source':{'type':'MANUAL','purchaseOrderId':None},'reason':'隔离属性独立书目',
        'lines':[{'lineKey':str(uuid.uuid4()),'target':{'bookId':None,'newBook':{'title':initial_title,'isbn':marker[:20],'categoryId':category['id']},'purchaseItemId':None},
            'copies':[{'input':'SCANNED','barcode':barcode,'location':None}]}]}
    intake=call('/api/inventory-intakes/previews','POST',body)
    confirmation={'idempotencyKey':key,'previewDigest':intake['previewDigest']}
    receipt=call('/api/inventory-intakes/'+str(intake['intakeId'])+'/confirm','POST',confirmation)
    book_id=receipt['books'][0]['bookId'];copy_id=receipt['copies'][0]['copyId']
    assert receipt['copies'][0]['barcode']==barcode,'Synthetic barcode bytes changed'
    raw={'适读年龄':'10-12岁,0-2岁,10-12岁','装帧':'纸板书','自定义书名':'隔离来源展示-'+marker,
        '书籍名称':'隔离来源原书名-'+marker,'出版社':'仅核对出版社-'+marker,'套装类型':'套装中单本','不应公开的附加键':'仅合成，禁止从整份JSON公开'}
    state={'bookId':book_id,'copyId':copy_id,'barcode':barcode,'initialTitle':initial_title,'raw':raw,'intake':intake,'intakeInput':body,
        'intakeConfirmation':confirmation,'intakeReceipt':receipt,'operations':[]}
    save(state)
    # SQL only targets the newly created synthetic book/copy. No production input
    # or real source record is accepted by this embedded helper.
    document=json.dumps(raw,ensure_ascii=False,separators=(',',':'))
    print('START TRANSACTION;')
    print('INSERT INTO legacy_record(source_system,record_type,source_key,display_name,summary,details_json,book_id) SELECT '+
        "'huiben','BOOK',"+qtext(barcode)+','+qtext(initial_title)+",NULL,"+qtext(document)+','+str(book_id)+
        ' WHERE EXISTS(SELECT 1 FROM book b JOIN book_copy c ON c.book_id=b.id WHERE b.id='+str(book_id)+' AND c.id='+str(copy_id)+
        ' AND BINARY b.title=BINARY '+qtext(initial_title)+' AND c.barcode_key=UNHEX(\''+barcode.encode().hex()+"'))"+
        " AND NOT EXISTS(SELECT 1 FROM legacy_record WHERE BINARY source_system=BINARY 'huiben' AND BINARY record_type=BINARY 'BOOK' AND BINARY source_key=BINARY "+qtext(barcode)+');')
    print('COMMIT;')
elif args.mode=='seed':
    state=json.loads(Path(args.state).read_text(encoding='utf-8'));book_id=state['bookId'];raw=state['raw']
    before=attribute(book_id);evidence=source(book_id);book=catalog(book_id)
    assert before['profile']['revision']==0 and before['profile']['ageLabels'] is None and before['profile']['bindingCode'] is None
    assert evidence['sourceCount']==1 and evidence['associationState']=='VALID'
    assert evidence['records'][0]['sourceKey']==state['barcode']
    for key,label in [('ageLabels','适读年龄'),('bindingCode','装帧'),('displayTitleOverride','自定义书名'),('adoptedSourceTitle','书籍名称'),('publisher','出版社'),('setType','套装类型')]:
        assert evidence['records'][0]['cells'][key]['rawValue']==raw[label],'Original source bytes changed'
    assert not evidence['fields']['publisher']['canAdopt'] and not evidence['fields']['setType']['canAdopt']
    assert evidence['fields']['ageLabels']['proposedValue']==['AGE_0_2','AGE_10_12']
    assert book['displayTitle']==state['initialTitle'] and book['adoptedSourceTitle'] is None
    assert not search(raw['书籍名称'])['records'],'Unadopted source title became public search text'
    assert_public(catalog(book_id,True))
    denied('/api/catalog-books/'+str(book_id)+'/source','GET',None,401,anonymous=True)
    legacy_before=call('/api/books/'+str(book_id)+'/metadata')
    manual=preview(book_id,{'displayTitleOverride':{'action':'SET','value':'隔离手工展示'},'bindingCode':{'action':'SET','value':'PAPERBACK'},'ageLabels':{'action':'SET','value':['AGE_10_12','AGE_0_2']}})
    assert manual['preview']['preview']['book']['sourceEvidence'] is None
    confirm(manual);state['operations'].append(manual)
    after=attribute(book_id)
    assert after['profile']['displayOrigin']==after['profile']['bindingOrigin']==after['profile']['ageOrigin']=='MANUAL'
    assert after['profile']['ageLabels']==['AGE_0_2','AGE_10_12']
    assert call('/api/books/'+str(book_id)+'/metadata')==legacy_before,'Attributes changed old metadata/digest'
    adoption=preview(book_id,{key:{'action':'ADOPT'} for key in ('displayTitleOverride','bindingCode','ageLabels','adoptedSourceTitle')},'SOURCE')
    assert adoption['preview']['preview']['book']['sourceEvidence']==source(book_id)
    confirm(adoption);state['operations'].append(adoption)
    adopted=attribute(book_id);public=catalog(book_id,True);assert_public(public)
    assert public['catalogTitle']==state['initialTitle'] and public['displayTitle']==raw['自定义书名'] and public['adoptedSourceTitle']==raw['书籍名称']
    assert public['ageLabels']==['AGE_0_2','AGE_10_12'] and public['bindingCode']=='BOARD'
    assert adopted['profile']['displayOrigin']==adopted['profile']['bindingOrigin']==adopted['profile']['ageOrigin']=='SOURCE'
    assert book_id in {r['id'] for r in search(raw['书籍名称'])['records']}
    assert call('/api/books/'+str(book_id)+'/metadata')==legacy_before,'SOURCE adoption changed old Book or V8 metadata'
    # ANY filtering and UNKNOWN remain independent. Restrict each query to this
    # unique synthetic ISBN so existing fixtures cannot make these checks pass.
    query='/api/catalog-books?page=1&size=100&keyword='+urllib.parse.quote(state['intakeInput']['lines'][0]['target']['newBook']['isbn'],safe='')
    assert [r['id'] for r in call(query+'&ageLabels=AGE_0_2&ageLabels=AGE_3_4',anonymous=True)['records']]==[book_id]
    assert not call(query+'&ageLabels=AGE_3_4',anonymous=True)['records']
    assert not call(query+'&ageState=UNKNOWN',anonymous=True)['records']
    assert not call(query+'&bindingState=UNKNOWN',anonymous=True)['records']
    denied(query+'&ageState=UNKNOWN&ageLabels=AGE_0_2','GET',None,400,{'INVALID_INPUT'},True)
    # Same MANUAL value and origin is a no-op. SOURCE same-value adoption is
    # new evidence, while replaying either original key remains idempotent.
    manual_origin=preview(book_id,{'bindingCode':{'action':'SET','value':'BOARD'}});confirm(manual_origin);state['operations'].append(manual_origin)
    manual_same=preview(book_id,{'bindingCode':{'action':'SET','value':'BOARD'}})
    assert not manual_same['preview']['preview']['book']['willChange']
    receipt=confirm(manual_same);state['operations'].append(manual_same)
    assert not receipt['book']['changed'] and receipt['book']['eventId'] is None
    same_source=preview(book_id,{'bindingCode':{'action':'ADOPT'}},'SOURCE')
    assert same_source['preview']['preview']['book']['willChange']
    confirm(same_source);state['operations'].append(same_source)
    # Current title A -> B -> A must invalidate the original preview even though
    # both the title bytes and old metadataDigest return to their previous value.
    stale=preview(book_id,{'bindingCode':{'action':'SET','value':'HARDCOVER'}})
    rev=attribute(book_id)['profile']['revision']
    title(book_id,state['initialTitle']+'-B');title(book_id,state['initialTitle'])
    assert attribute(book_id)['profile']['revision']==rev+2
    assert call('/api/books/'+str(book_id)+'/metadata')['metadataDigest']==legacy_before['metadataDigest']
    denied('/api/catalog-attributes/'+str(stale['preview']['requestId'])+'/confirm','POST',
        {'operationKey':stale['input']['operationKey'],'previewDigest':stale['preview']['previewDigest']},409,{'CATALOG_CHANGED','REVISION_CONFLICT'})
    assert call('/api/catalog-attributes/by-key?key='+stale['input']['operationKey'])==stale['preview']
    latest=state['initialTitle']+'-最新';title(book_id,latest)
    assert catalog(book_id)['displayTitle']==raw['自定义书名'],'Legacy title write overwrote display override'
    clear=preview(book_id,{'displayTitleOverride':{'action':'CLEAR'}});confirm(clear);state['operations'].append(clear)
    public=catalog(book_id)
    assert public['displayTitle']==public['catalogTitle']==latest and public['adoptedSourceTitle']==raw['书籍名称']
    final=preview(book_id,{'displayTitleOverride':{'action':'SET','value':'隔离最终手工展示'}});confirm(final);state['operations'].append(final)
    pending=preview(book_id,{'bindingCode':{'action':'CLEAR'}})
    events=audit(book_id);assert events['total']>=7 and {'MANUAL','SOURCE','LEGACY_TITLE'}<={e['kind'] for e in events['records']}
    event=next(e for e in events['records'] if e['kind']=='SOURCE' and e['requestId']==adoption['preview']['requestId'])
    assert event['sourceEvidence']==adoption['preview']['preview']['book']['sourceEvidence']
    assert event['sourceEvidence']['records'][0]['cells']['ageLabels']['rawValue']==raw['适读年龄']
    assert event['before']['catalogTitle']==event['after']['catalogTitle']==state['initialTitle']
    state.update(attribute=attribute(book_id),catalog=catalog(book_id),source=source(book_id),events=events,stale=stale,pending=pending,
        legacyBook=call('/api/books/'+str(book_id)),metadata=call('/api/books/'+str(book_id)+'/metadata'))
    assert state['attribute']['profile']['displayOrigin']=='MANUAL' and state['attribute']['profile']['ageOrigin']=='SOURCE'
    save(state)
    print('Synthetic MANUAL/SOURCE, three titles, exact labels/evidence, original keys and legacy title ABA passed')
else:
    state=json.loads(Path(args.state).read_text(encoding='utf-8'));book_id=state['bookId']
    assert attribute(book_id)==state['attribute'] and catalog(book_id)==state['catalog'] and catalog(book_id,True)==state['catalog']
    assert source(book_id)==state['source'] and audit(book_id)==state['events']
    assert call('/api/books/'+str(book_id))==state['legacyBook'] and call('/api/books/'+str(book_id)+'/metadata')==state['metadata']
    for saved in state['operations']:
        request_id=str(saved['preview']['requestId'])
        assert call('/api/catalog-attributes/by-key?key='+saved['input']['operationKey'])==saved['committed']
        assert call('/api/catalog-attributes/'+request_id)==saved['committed']
        assert call('/api/catalog-attributes/'+request_id+'/confirm','POST',saved['confirmation'])==saved['receipt']
    for name in ('pending','stale'):
        saved=state[name]
        assert call('/api/catalog-attributes/by-key?key='+saved['input']['operationKey'])==saved['preview']
    assert call('/api/inventory-intakes/'+str(state['intake']['intakeId'])+'/confirm','POST',state['intakeConfirmation'])==state['intakeReceipt']
    assert audit(book_id)==state['events'],'Replayed keys duplicated events'
    print('Nonempty V12 profile, labels, requests, SOURCE audit and original receipts survived lifecycle')
B33A_PY
library_attributes_acceptance() {
    python3 "$library_test/catalog_attributes_acceptance.py" --base "$1" --env "$2" --state "$library_test/attributes-state.json" --mode "$3"
}
library_attributes_acceptance http://localhost:18089 "$library_test/install/.env" prepare-source > "$library_test/attributes-source-fixture.sql"
library_attributes_mysql="$("${library_dc[@]}" ps -q mysql)"
[[ -n "$library_attributes_mysql" && "$library_attributes_mysql" != *$'\n'* ]]
[[ "$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$library_attributes_mysql")" == library ]]
"${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 "$MYSQL_DATABASE"' < "$library_test/attributes-source-fixture.sql"
library_attributes_acceptance http://localhost:18089 "$library_test/install/.env" seed
# END B33-A ATTRIBUTES SEED
"${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u library_app -D "$MYSQL_DATABASE" -e "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank DESC LIMIT 1"'
if "${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u library_app -D "$MYSQL_DATABASE" -e "CREATE TABLE acceptance_ddl_probe (id INT)"' >/dev/null 2>&1; then
    echo 'Application account unexpectedly has schema-write privileges' >&2; exit 1
fi
"${library_dc[@]}" exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator -D "$MYSQL_DATABASE" -e "CREATE TABLE acceptance_ddl_probe (id INT); DROP TABLE acceptance_ddl_probe"'
echo 'Unpacked install, application DML-only grants and migration DDL grants passed'
"${library_dc[@]}" up -d --force-recreate --wait --wait-timeout 240 backend frontend
library_verify_backend "$library_release"
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18089 --env "$library_test/install/.env" --state "$library_test/state.json" --check-state
# BEGIN B33-A ATTRIBUTES RECREATE
library_attributes_acceptance http://localhost:18089 "$library_test/install/.env" check
# END B33-A ATTRIBUTES RECREATE
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
# BEGIN B33-A ATTRIBUTES UPDATE
library_attributes_acceptance http://localhost:18089 "$library_test/install/.env" check
# END B33-A ATTRIBUTES UPDATE
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
# BEGIN B33-A ATTRIBUTES FAILED_UPDATE
library_attributes_acceptance http://localhost:18089 "$library_test/install/.env" check
# END B33-A ATTRIBUTES FAILED_UPDATE
echo 'Failed-start rollback restored previous application and data'
APP_VERSION="$(basename "$library_release")" bash "$library_release/scripts/backup.sh" "$library_test/backup"
sed -e 's/18089/18090/g' -e 's/172.30.89/172.30.90/g' "$library_test/install/.env" > "$library_test/recovery/.env"
library_recovery_release="$library_release"
LIBRARY_PROJECT_NAME=library-recovery LIBRARY_ENV_FILE="$library_test/recovery/.env" bash "$library_release/scripts/restore.sh" "$library_test/backup"
library_verify_backend "$library_recovery_release" library-recovery
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18090 --env "$library_test/recovery/.env" --state "$library_test/state.json" --check-state --complete-recovered-link
# BEGIN B33-A ATTRIBUTES RESTORE
library_attributes_acceptance http://localhost:18090 "$library_test/recovery/.env" check
# END B33-A ATTRIBUTES RESTORE
echo 'First install, recreate, update, failed-start rollback, backup and isolated restore passed'
