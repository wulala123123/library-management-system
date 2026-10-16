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
# BEGIN B35 IDENTITY SEED
cat > "$library_test/catalog_identity_acceptance.py" <<'B35_PY'
"""Embed verbatim in the public container acceptance script after API freeze.

Only synthetic acceptance deployments at 18089/18090 are accepted. All new
business uses real HTTP APIs. snapshot-sql prints SELECT-only byte projections.
"""
import argparse
import base64
import copy
import hashlib
import hmac
import json
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

IDENTITY_COLUMNS={
 'catalog_publisher_profile':'publisher_id normalized_name normalized_key revision updated_at',
 'catalog_publisher_alias':'id publisher_id alias_name normalized_name alias_key origin status revision created_at updated_at',
 'catalog_category_profile':'category_id normalized_name normalized_key dimension revision updated_at',
 'catalog_category_alias':'id category_id alias_name normalized_name alias_key origin status revision created_at updated_at',
 'catalog_book_publisher':'book_id publisher_id source_name relation_kind revision updated_at',
 'catalog_book_theme':'book_id category_id source_name relation_kind revision updated_at',
 'catalog_identity_request':'id operation_key book_id actor_id mode input_hash schema_version request_json preview_json preview_digest state created_at expires_at receipt_json committed_at',
 'catalog_identity_candidate':'id request_id book_id entity_kind raw_value normalized_value match_kind candidate_id evidence_json status created_at',
 'catalog_identity_event':'id request_id book_id entity_kind entity_id before_revision after_revision changed_fields_json before_json after_json evidence_json actor_id occurred_at reason',
}
def guard(base,env,mode):
    u=urllib.parse.urlsplit(base)
    assert os.environ.get('LIBRARY_ACCEPTANCE')=='1','Explicit isolated acceptance flag required'
    assert (u.scheme=='http' and u.hostname in ('localhost','127.0.0.1') and u.port in (18089,18090)
        and not u.username and not u.password and u.path in ('','/') and not u.query and not u.fragment
        and env.get('FRONTEND_PORT')==str(u.port) and env.get('APP_NETWORK_SUBNET')==('172.30.89.0/24' if u.port==18089 else '172.30.90.0/24')
        and env.get('FRONTEND_INTERNAL_IP')==('172.30.89.10' if u.port==18089 else '172.30.90.10')),'B35 fixture requires the exact isolated acceptance deployment'
    assert mode in ('seed','check','snapshot-sql') and (mode!='seed' or u.port==18089),'Never seed the recovered deployment'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None

class Client:
    def __init__(self,base,env):
        self.base=base
        enc=lambda value:base64.urlsafe_b64encode(value).rstrip(b'=')
        payload=dict(sub='1',role='ADMIN',version=0,iat=int(time.time()),exp=int(time.time())+3600)
        unsigned=enc(b'{"alg":"HS256","typ":"JWT"}')+b'.'+enc(json.dumps(payload).encode())
        self.token=(unsigned+b'.'+enc(hmac.new(env['JWT_SECRET'].encode(),unsigned,hashlib.sha256).digest())).decode()
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def call(self,path,method='GET',body=None):
        assert path.startswith('/api/') and method in ('GET','POST','PUT')
        headers={'Content-Type':'application/json','Authorization':'Bearer '+self.token}
        request=urllib.request.Request(self.base+path,None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers,method=method)
        with self.opener.open(request,timeout=120) as response:
            payload=response.read(2097153)
            assert response.status==200 and len(payload)<=2097152,'Unexpected B35 HTTP response'
            value=json.loads(payload)
        assert set(value)=={'code','message','data'} and value['code']==200,'Unexpected B35 envelope'
        return value['data']
    def denied(self,path,method,body,status,reasons):
        try:self.call(path,method,body)
        except urllib.error.HTTPError as error:
            payload=error.read(16385);assert error.code==status and len(payload)<=16384
            value=json.loads(payload);assert value['code']==status
            actual=value.get('data',{});reason=actual.get('reasonCode') if isinstance(actual,dict) else value.get('message')
            assert reason in reasons,'Unexpected B35 refusal reason'
        else:raise AssertionError('Expected B35 refusal')

def identity(c,book):return c.call('/api/catalog-identities/books/'+str(book))
def current_input(c,book,changes):
    view=identity(c,book)
    return dict(operationKey=str(uuid.uuid4()),mode='MANUAL',reason='B35隔离合成生命周期验收',bookId=book,
        expectedRevision=view['identityRevision'],expectedCatalogDigest=view['catalogDigest'],sourceDigest=None,
        changes={key:changes.get(key,[] if key in ('aliases','categoryDimensions') else None) for key in ('publisher','themes','aliases','categoryDimensions')})
def preview(c,book,changes):
    body=current_input(c,book,changes);view=c.call('/api/catalog-identities/previews','POST',body)
    assert view['mode']=='MANUAL' and view['state']=='PREVIEWED' and view['receipt'] is None
    assert view['preview']['book']['sourceDigest'] is None
    assert c.call('/api/catalog-identities/previews','POST',body)==view,'Preview key replay differs'
    assert c.call('/api/catalog-identities/requests/by-key?key='+body['operationKey'])==view
    return dict(input=body,preview=view)
def confirm(c,saved):
    view=saved['preview'];body=dict(operationKey=saved['input']['operationKey'],previewDigest=view['previewDigest'])
    path='/api/catalog-identities/'+str(view['requestId'])+'/confirm'
    receipt=c.call(path,'POST',body)
    assert c.call(path,'POST',body)==receipt,'Original B35 receipt replay differs'
    request=c.call('/api/catalog-identities/'+str(view['requestId']))
    assert request['state']=='COMMITTED' and request['receipt']==receipt and request['preview']==view['preview']
    assert c.call('/api/catalog-identities/requests/by-key?key='+body['operationKey'])==request
    saved.update(confirmation=body,receipt=receipt,committed=request)
    return receipt

def category(c,name,parent=0,status=1):
    return c.call('/api/categories','POST',dict(name=name,parentId=parent,status=status,sortOrder=0))
def publisher(c,name):return c.call('/api/publishers','POST',dict(name=name,status=1))
def attribute(c,book):return c.call('/api/catalog-books/'+str(book)+'/attributes')
def stable_old(c,book):
    return dict(metadata=c.call('/api/books/'+str(book)+'/metadata'),attribute=attribute(c,book),
        catalog=c.call('/api/catalog-books/'+str(book)),events=c.call('/api/catalog-books/'+str(book)+'/attribute-events?page=1&size=100'))
def selected_lists(c,marker):
    suffix='?page=1&size=100&keyword='+urllib.parse.quote(marker,safe='')
    return {kind:c.call('/api/catalog-identities/'+kind+suffix) for kind in ('publishers','categories')}

def seed(c):
    marker='B35'+uuid.uuid4().hex[:16]
    p=publisher(c,marker+'出版社');root=category(c,marker+'主题父');child=category(c,marker+'主题子',root['id']);other=category(c,marker+'主题二',root['id']);legacy=category(c,marker+'原年龄分类')
    key=str(uuid.uuid4());body=dict(idempotencyKey=key,source=dict(type='MANUAL',purchaseOrderId=None),reason='B35隔离独立书目',lines=[dict(lineKey=str(uuid.uuid4()),target=dict(bookId=None,newBook=dict(title=marker+'原登记书名',publisher=p['name'],isbn=marker,categoryId=legacy['id']),purchaseItemId=None),copies=[dict(input='SCANNED',barcode='000'+marker+' ',location=None)])])
    intake=c.call('/api/inventory-intakes/previews','POST',body);confirmation=dict(idempotencyKey=key,previewDigest=intake['previewDigest'])
    receipt=c.call('/api/inventory-intakes/'+str(intake['intakeId'])+'/confirm','POST',confirmation);book=receipt['books'][0]['bookId']
    assert receipt['copies'][0]['barcode']=='000'+marker+' '
    # An independent B33 age label must survive all identity writes unchanged.
    attr=attribute(c,book);attr_key=str(uuid.uuid4())
    attr_body=dict(operationKey=attr_key,mode='MANUAL',reason='B35隔离独立年龄标签',bookId=book,expectedRevision=attr['profile']['revision'],expectedCatalogDigest=attr['catalogDigest'],sourceDigest=None,changes=dict(displayTitleOverride=None,bindingCode=None,ageLabels=dict(action='SET',value=['AGE_3_4']),adoptedSourceTitle=None))
    attr_view=c.call('/api/catalog-attributes/previews','POST',attr_body);attr_confirmation=dict(operationKey=attr_key,previewDigest=attr_view['previewDigest'])
    attr_receipt=c.call('/api/catalog-attributes/'+str(attr_view['requestId'])+'/confirm','POST',attr_confirmation)
    old=stable_old(c,book);initial=identity(c,book)
    assert initial['publisherId'] is None and initial['themes']==[] and initial['categoryId']==legacy['id'] and initial['categoryFieldKind']=='LEGACY'
    assert old['attribute']['profile']['ageLabels']==['AGE_3_4']
    # No name match or old category ID may become an accepted link automatically.
    assert any(v['entityId']==p['id'] and v['matchKind']=='EXACT' for v in initial['candidates'] if v['kind']=='PUBLISHER')
    bad=current_input(c,book,dict(themes=dict(action='REPLACE',categoryIds=[child['id']])))
    c.denied('/api/catalog-identities/previews','POST',bad,409,{'CATEGORY_TOPIC_REQUIRED'})
    bad=copy.deepcopy(bad);bad['operationKey']=str(uuid.uuid4());bad['mode']='SOURCE'
    c.denied('/api/catalog-identities/previews','POST',bad,409,{'CATALOG_IDENTITY_SOURCE_NOT_ENABLED'})
    bad=current_input(c,book,dict(publisher=dict(action='LINK',publisherId=p['id'])));bad['sourceDigest']='a'*64
    c.denied('/api/catalog-identities/previews','POST',bad,400,{'INVALID_INPUT'})
    # Formal MANUAL preview/confirm classifies parent + children and keeps age separate.
    dimensions=[dict(categoryId=v['id'],dimension='TOPIC') for v in (root,child,other)]+[dict(categoryId=legacy['id'],dimension='LEGACY_AGE')]
    operation=preview(c,book,dict(publisher=dict(action='LINK',publisherId=p['id']),themes=dict(action='REPLACE',categoryIds=[other['id'],child['id']]),aliases=[dict(kind='PUBLISHER',ownerId=p['id'],action='ADD',name=marker+'别名'),dict(kind='CATEGORY',ownerId=child['id'],action='ADD',name=marker+'主题别名')],categoryDimensions=dimensions))
    first=confirm(c,operation);assert first['book']['changed'] and first['book']['eventId'] is not None
    actual=identity(c,book)
    assert actual['publisherId']==p['id'] and [v['categoryId'] for v in actual['themes']]==sorted([child['id'],other['id']])
    assert all(v['dimension']=='TOPIC' and v['parentChainActive'] for v in actual['themes'])
    assert actual['categoryId']==legacy['id'] and stable_old(c,book)==old,'Identity writes changed old metadata, original category, B33 age or audit'
    # An age-classified category remains ineligible as a new theme.
    bad=current_input(c,book,dict(themes=dict(action='REPLACE',categoryIds=[legacy['id']])))
    c.denied('/api/catalog-identities/previews','POST',bad,409,{'CATEGORY_TOPIC_REQUIRED'})
    # A semantic no-op has no event or version increase. Original committed receipt wins.
    same=preview(c,book,dict(publisher=dict(action='LINK',publisherId=p['id']),themes=dict(action='REPLACE',categoryIds=[child['id'],other['id']])))
    nochange=confirm(c,same);assert not nochange['book']['changed'] and nochange['book']['eventId'] is None and nochange['book']['beforeRevision']==nochange['book']['afterRevision']
    # Retired and active aliases, two immutable committed receipts, and a pending
    # preview all remain nonempty across recreate/update/full backup restore.
    retire=preview(c,book,dict(aliases=[dict(kind='PUBLISHER',ownerId=p['id'],action='CLEAR',name=marker+'别名'),dict(kind='PUBLISHER',ownerId=p['id'],action='ADD',name=marker+'保留别名')]))
    confirm(c,retire)
    stale=preview(c,book,dict(publisher=dict(action='CLEAR',publisherId=None)))
    c.call('/api/categories','PUT',dict(id=root['id'],name=root['name'],parentId=0,status=0,sortOrder=0))
    bad=current_input(c,book,dict(themes=dict(action='REPLACE',categoryIds=[child['id']])))
    c.denied('/api/catalog-identities/previews','POST',bad,409,{'CATEGORY_PARENT_DISABLED'})
    c.call('/api/categories','PUT',dict(id=root['id'],name=root['name'],parentId=0,status=1,sortOrder=0))
    c.denied('/api/catalog-identities/'+str(stale['preview']['requestId'])+'/confirm','POST',dict(operationKey=stale['input']['operationKey'],previewDigest=stale['preview']['previewDigest']),409,{'CATALOG_IDENTITY_CHANGED'})
    pending=preview(c,book,dict(publisher=dict(action='CLEAR',publisherId=None)))
    assert stable_old(c,book)==old
    return dict(marker=marker,bookId=book,identity=identity(c,book),lists=selected_lists(c,marker),old=old,operations=[operation,same,retire],pending=pending,stale=stale,
        intake=dict(view=intake,confirmation=confirmation,receipt=receipt),attributeOperation=dict(view=attr_view,confirmation=attr_confirmation,receipt=attr_receipt))

def check(c,state):
    book=state['bookId']
    def same():
        assert identity(c,book)==state['identity'] and selected_lists(c,state['marker'])==state['lists'],'B35 persisted identity or alias bytes changed'
        assert stable_old(c,book)==state['old'],'Old metadata/B33 values changed across lifecycle'
    same()
    for saved in state['operations']:
        route='/api/catalog-identities/'+str(saved['preview']['requestId'])
        assert c.call(route)==saved['committed']
        assert c.call('/api/catalog-identities/requests/by-key?key='+saved['input']['operationKey'])==saved['committed']
        assert c.call(route+'/confirm','POST',saved['confirmation'])==saved['receipt']
    for name in ('pending','stale'):
        pending=state[name];assert c.call('/api/catalog-identities/requests/by-key?key='+pending['input']['operationKey'])==pending['preview']
        assert c.call('/api/catalog-identities/'+str(pending['preview']['requestId']))==pending['preview']
    for key,prefix,idname in (('intake','/api/inventory-intakes/','intakeId'),('attributeOperation','/api/catalog-attributes/','requestId')):
        saved=state[key];assert c.call(prefix+str(saved['view'][idname])+'/confirm','POST',saved['confirmation'])==saved['receipt']
    same()

def snapshot_sql(state):
    """Per-row hashes avoid GROUP_CONCAT truncation; preserve JSON original bytes."""
    lines=[]
    for table,fields in sorted(IDENTITY_COLUMNS.items()):
        cells=["IF(`"+name+"` IS NULL,NULL,HEX(CAST(`"+name+"` AS BINARY)))" for name in fields.split()]
        lines.append("SELECT '"+table+"' AS t,SHA2(CAST(JSON_ARRAY("+','.join(cells)+") AS CHAR),256) AS digest FROM `"+table+"` ORDER BY digest;")
    for table,ids in (('book',[state['bookId']]),('publisher',[v['id'] for v in state['lists']['publishers']['records']]),('book_category',[v['id'] for v in state['lists']['categories']['records']])):
        assert ids and all(type(i) is int and i>0 for i in ids)
        lines.append("SELECT '"+table+"-revision' AS t,SHA2(CAST(JSON_ARRAY(id,catalog_identity_revision) AS CHAR),256) AS digest FROM `"+table+"` WHERE id IN ("+','.join(map(str,ids))+") ORDER BY id;")
    return '\n'.join(lines)+'\n'

def main(argv=None):
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--env',required=True);p.add_argument('--state',required=True);p.add_argument('--mode',choices=('seed','check','snapshot-sql'),required=True)
    args=p.parse_args(argv);env=dict(line.split('=',1) for line in Path(args.env).read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))
    guard(args.base,env,args.mode);state_path=Path(args.state)
    assert not state_path.is_symlink()
    if args.mode=='seed':
        assert not state_path.exists(),'Fresh independent B35 fixture required'
        state=seed(Client(args.base,env))
        with state_path.open('x',encoding='utf-8') as stream:json.dump(state,stream,ensure_ascii=False,sort_keys=True)
        print('B35 isolated formal MANUAL confirmation, aliases, themes, dimensions and immutable receipts passed')
    else:
        state=json.loads(state_path.read_text(encoding='utf-8'))
        if args.mode=='snapshot-sql':print(snapshot_sql(state),end='')
        else:
            check(Client(args.base,env),state)
            print('B35 identity, alias, pending request and original receipt HTTP checks passed')
    return 0

if __name__=='__main__':raise SystemExit(main())
B35_PY
library_identity_acceptance() {
    LIBRARY_ACCEPTANCE=1 python3 "$library_test/catalog_identity_acceptance.py" --base "$1" --env "$2" --state "$library_test/identity-state.json" --mode "$3"
}
library_identity_snapshot() {
    local library_identity_project="$1" library_identity_tag="$2" library_identity_mysql
    [[ "$library_identity_project" == library || "$library_identity_project" == library-recovery ]]
    library_identity_mysql="$(docker ps -q --filter "label=com.docker.compose.project=$library_identity_project" --filter label=com.docker.compose.service=mysql)"
    [[ -n "$library_identity_mysql" && "$library_identity_mysql" != *$'\n'* ]]
    [[ "$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$library_identity_mysql")" == "$library_identity_project" ]]
    docker exec -i "$library_identity_mysql" sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names "$MYSQL_DATABASE"' < "$library_test/identity-persistence.sql" > "$library_test/identity-$library_identity_tag.tsv"
    if [[ "$library_identity_tag" != before ]]; then
        cmp --silent "$library_test/identity-before.tsv" "$library_test/identity-$library_identity_tag.tsv"
    fi
}
library_identity_acceptance http://localhost:18089 "$library_test/install/.env" seed
library_identity_acceptance http://localhost:18089 "$library_test/install/.env" snapshot-sql > "$library_test/identity-persistence.sql"
library_identity_snapshot library before
python3 - "$library_test/identity-before.tsv" <<'B35_NONEMPTY_PY'
import re,sys
from pathlib import Path
rows=[line.split('\t') for line in Path(sys.argv[1]).read_text().splitlines()]
assert rows and all(len(row)==2 and re.fullmatch('[0-9a-f]{64}',row[1]) for row in rows)
required=set('catalog_publisher_profile catalog_publisher_alias catalog_category_profile catalog_category_alias catalog_book_publisher catalog_book_theme catalog_identity_request catalog_identity_candidate catalog_identity_event book-revision publisher-revision book_category-revision'.split())
assert {row[0] for row in rows}==required,'Every B35 table and owned technical revision must be nonempty'
print('B35 all nine tables contain synthetic business; original JSON byte hashes captured')
B35_NONEMPTY_PY
# END B35 IDENTITY SEED
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
# BEGIN B35 IDENTITY RECREATE
library_identity_acceptance http://localhost:18089 "$library_test/install/.env" check
library_identity_snapshot library recreate
# END B35 IDENTITY RECREATE
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
# BEGIN B35 IDENTITY UPDATE
library_identity_acceptance http://localhost:18089 "$library_test/install/.env" check
library_identity_snapshot library update
# END B35 IDENTITY UPDATE
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
# BEGIN B35 IDENTITY FAILED_UPDATE
library_identity_acceptance http://localhost:18089 "$library_test/install/.env" check
library_identity_snapshot library failed_update
# END B35 IDENTITY FAILED_UPDATE
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
# BEGIN B35 IDENTITY RESTORE
library_identity_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_identity_snapshot library-recovery restore
# END B35 IDENTITY RESTORE
echo 'First install, recreate, update, failed-start rollback, backup and isolated restore passed'
