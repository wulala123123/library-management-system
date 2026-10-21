#!/usr/bin/env bash
set -euo pipefail
[[ "${CI:-}" == true || "${LIBRARY_ACCEPTANCE:-}" == 1 ]] || { echo 'Run only in an isolated CI or test host' >&2; exit 1; }
for library_project in library library-recovery; do
    [[ -z "$(docker ps -aq --filter "label=com.docker.compose.project=$library_project")" ]] || { echo 'Project already exists; refusing test' >&2; exit 1; }
    [[ -z "$(docker volume ls -q --filter "name=${library_project}_")" ]] || { echo 'Project volumes already exist; refusing test' >&2; exit 1; }
done
# BEGIN B40-B RECEIPTS ISOLATION
for library_receipts_project in library library-recovery; do
    [[ -z "$(docker network ls -q --filter "label=com.docker.compose.project=$library_receipts_project")" ]] || { echo 'Receipt project network already exists; refusing test' >&2; exit 1; }
    [[ -z "$(docker network ls -q --filter "name=^${library_receipts_project}_app$")" ]] || { echo 'Receipt network name already exists; refusing test' >&2; exit 1; }
done
# END B40-B RECEIPTS ISOLATION
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
    # BEGIN B40-B RECEIPTS CONFIG
    echo 'LIBRARY_FINANCE_RECEIPTS_ENABLED=true'
    echo "LIBRARY_FINANCE_RECEIPTS_START_DATE=$(TZ=Asia/Shanghai date +%F)"
    # END B40-B RECEIPTS CONFIG
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
# BEGIN B50-A CIRCULATION SEED
library_circulation_acceptance() {
    local library_circulation_base="$1" library_circulation_env="$2" library_circulation_mode="$3"
    shift 3
    LIBRARY_ACCEPTANCE=1 python3 "$library_release/scripts/container_circulation.py" \
        --base "$library_circulation_base" --env "$library_circulation_env" --root "$library_test" \
        --release "$library_release" --state "$library_test/circulation-state.json" --mode "$library_circulation_mode" "$@"
}
library_circulation_snapshot() {
    local library_circulation_project="$1" library_circulation_tag="$2" library_circulation_port=18089 library_circulation_env="$library_test/install/.env"
    if [[ "$library_circulation_project" == library-recovery ]]; then
        library_circulation_port=18090; library_circulation_env="$library_test/recovery/.env"
    fi
    library_circulation_acceptance "http://localhost:$library_circulation_port" "$library_circulation_env" guard
    docker exec -i "$library_circulation_project-mysql-1" sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names --skip-reconnect "$MYSQL_DATABASE"' \
        < "$library_test/circulation-persistence.sql" > "$library_test/circulation-$library_circulation_tag.tsv"
    library_circulation_acceptance "http://localhost:$library_circulation_port" "$library_circulation_env" verify-snapshot --snapshot "$library_test/circulation-$library_circulation_tag.tsv"
    if [[ "$library_circulation_tag" != before ]]; then
        cmp --silent "$library_test/circulation-before.tsv" "$library_test/circulation-$library_circulation_tag.tsv"
    fi
}
python3 "$library_release/scripts/test_container_circulation.py"
library_circulation_acceptance http://localhost:18089 "$library_test/install/.env" seed
library_circulation_acceptance http://localhost:18089 "$library_test/install/.env" capture
library_circulation_acceptance http://localhost:18089 "$library_test/install/.env" snapshot-sql > "$library_test/circulation-persistence.sql"
library_circulation_snapshot library before
# END B50-A CIRCULATION SEED
# BEGIN B34 SETS SEED
cat > "$library_test/container_set_acceptance.py" <<'B34_PY'
"""B34 formal HTTP synthetic lifecycle helper, exact isolated deployments only.

Seed BEFORE the original B33/B35/B36 seeds capture their own original snapshots.
Never refresh an old snapshot. Snapshot SQL protects only B34's five new tables
and the B34 identity guard on this helper's own books.
"""
import argparse
import base64
import copy
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

COLUMNS={
 'catalog_set':'id name normalized_name note status revision evidence_json created_at updated_at',
 'catalog_set_member':'id set_id book_id status revision evidence_json created_at updated_at',
 'catalog_set_request':'id operation_key actor_id schema_version mode operation set_id input_hash request_json preview_json preview_digest state created_at expires_at receipt_json committed_at cancelled_at cancel_reason',
 'catalog_set_request_book':'request_id book_id expected_book_revision expected_attribute_revision expected_book_digest expected_source_digest',
 'catalog_set_event':'id request_id set_id actor_id schema_version operation before_revision after_revision changed_fields_json before_json after_json evidence_json books_json reason occurred_at',
}
def require(v,message='B34 synthetic assertion failed'):
    if not v:raise RuntimeError(message)
def canonical(v):return json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(',',':'))
def hash_value(v):return hashlib.sha256(canonical(v).encode()).hexdigest()
def exact(v,keys):require(type(v) is dict and set(v)==set(keys.split()),'Strict B34 response fields differ')
def integer(v,zero=False,nullable=False):require(v is None and nullable or type(v) is int and (0 if zero else 1)<=v<=9007199254740991,'Strict safe integer required')
def utc(v,nullable=False):require(v is None and nullable or type(v) is str and re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z',v),'Strict UTC seconds required')
def digest(v):require(type(v) is str and re.fullmatch('[0-9a-f]{64}',v),'Strict digest required')
def strict_evidence(v):
    exact(v,'kind reference');require(v['kind'] in ('COPYRIGHT_PAGE','PUBLISHER_MATERIAL','LEGACY_RECORD','MANUAL_REVIEW') and type(v['reference']) is str)
def strict_set(v):
    exact(v,'setId clientRef name note status revision activeMemberCount retiredMemberCount memberCompleteness physicalAssembly')
    integer(v['setId'],nullable=True);integer(v['revision'],zero=True)
    for k in ('activeMemberCount','retiredMemberCount'):integer(v[k],zero=True)
    require(v['clientRef'] is None or type(v['clientRef']) is str);require(type(v['name']) is str and (v['note'] is None or type(v['note']) is str) and v['status'] in ('ACTIVE','RETIRED') and v['memberCompleteness']==v['physicalAssembly']=='UNVERIFIED')
def strict_member(v):
    exact(v,'membershipId setId bookId status revision evidence');integer(v['membershipId'],nullable=True);integer(v['setId'],nullable=True);integer(v['bookId']);integer(v['revision'],zero=True);strict_evidence(v['evidence']);require(v['status'] in ('ACTIVE','RETIRED'))
def strict_context(v):
    exact(v,'book bookDigest source');exact(v['book'],'bookId isbn catalogTitle displayTitle revision attributeRevision deleted');b=v['book'];integer(b['bookId']);integer(b['revision'],zero=True);integer(b['attributeRevision'],zero=True);require(type(b['deleted']) is bool and all(type(b[k]) is str for k in ('isbn','catalogTitle','displayTitle')))
    digest(v['bookDigest']);require(v['bookDigest']==hash_value(dict(schemaVersion=1,book=b)));s=v['source'];exact(s,'parserVersion sourceCount associationState markerState reasons digest readAt');integer(s['sourceCount'],zero=True);digest(s['digest']);utc(s['readAt'])
    reasons='MEMBER_MARKER_NO_PARENT SOURCE_CONFLICT ASSOCIATION_INVALID UNMAPPED_MARKER NO_SOURCE INCOMPLETE_SOURCE_VALUE SOURCE_LIMIT_EXCEEDED'.split();require(s['parserVersion']=='huiben-book-set-v1' and s['associationState'] in ('NO_SOURCE','VALID','ASSOCIATION_INVALID') and s['markerState'] in 'NO_SOURCE CONFLICT MISSING NULL EMPTY WHITESPACE UNSET WRONG_TYPE MEMBER_MARKER UNMAPPED'.split() and s['reasons']==[r for r in reasons if r in s['reasons']])
def strict_before_after(v,nullable_set):
    exact(v,'set members');require(type(v['members']) is list)
    if v['set'] is not None:strict_set(v['set'])
    else:require(nullable_set)
    for m in v['members']:
        exact(m,'bookId member');integer(m['bookId'])
        if m['member'] is not None:strict_member(m['member']);require(m['member']['bookId']==m['bookId'])
        else:require(nullable_set)
    require([m['bookId'] for m in v['members']]==sorted({m['bookId'] for m in v['members']}))
def strict_impact(v):
    exact(v,'newSets addedMembers retiredMembers restoredMembers changedMembers unchangedActiveMembers unchangedRetiredMembers copies stockTotal stockAvailable loans charges uses')
    for x in v.values():integer(x,zero=True)
    for k in ('copies','stockTotal','stockAvailable','loans','charges','uses'):require(v[k]==0,'B34 escaped catalog scope')
def strict_fields(v):
    fields='set.create set.name set.note set.status members.add members.retire members.restore'.split();require(type(v) is list and v==[f for f in fields if f in v])
def strict_operation(v):require(v in 'CREATE UPDATE_METADATA ADD_MEMBERS RETIRE_MEMBERS RESTORE_MEMBERS RETIRE_SET RESTORE_SET'.split())
def strict_preview(v):
    exact(v,'schemaVersion operation mode reason createdAt expiresAt before after books applied changedFields willChange impact warnings');require(type(v['schemaVersion']) is int and v['schemaVersion']==1 and v['mode']=='MANUAL' and type(v['willChange']) is bool and type(v['reason']) is str)
    utc(v['createdAt']);utc(v['expiresAt']);strict_before_after(v['before'],True);strict_before_after(v['after'],False);strict_fields(v['changedFields']);strict_impact(v['impact']);require(type(v['books']) is list and type(v['applied']) is dict and type(v['warnings']) is list)
    strict_operation(v['operation'])
    for b in v['books']:strict_context(b)
    for w in v['warnings']:
        exact(w,'code setIds total truncated');require(w['code'] in ('SIMILAR_NAME','COMPLETENESS_UNVERIFIED','PHYSICAL_ASSEMBLY_UNVERIFIED') and type(w['setIds']) is list and type(w['truncated']) is bool);integer(w['total'],zero=True)
        for sid in w['setIds']:integer(sid)
def strict_receipt(v):
    exact(v,'schemaVersion requestId operationKey mode operation actorId confirmedAt changed eventIds before impact result previewDigest');require(type(v['schemaVersion']) is int and v['schemaVersion']==1 and type(v['changed']) is bool and v['mode']=='MANUAL' and type(v['eventIds']) is list);integer(v['requestId']);integer(v['actorId']);utc(v['confirmedAt']);digest(v['previewDigest']);strict_before_after(v['before'],True);strict_impact(v['impact'])
    strict_operation(v['operation'])
    for eid in v['eventIds']:integer(eid)
    r=v['result'];exact(r,'set members createdSet');strict_set(r['set']);require(r['set']['clientRef'] is None and type(r['members']) is list)
    for m in r['members']:strict_member(m)
    if r['createdSet'] is not None:exact(r['createdSet'],'clientRef setId');integer(r['createdSet']['setId']);require(type(r['createdSet']['clientRef']) is str and r['createdSet']['setId']==r['set']['setId'])
def strict_event(v):
    exact(v,'schemaVersion eventId requestId setId operation actorId occurredAt reason beforeRevision afterRevision changedFields before after evidence books');require(type(v['schemaVersion']) is int and v['schemaVersion']==1 and type(v['books']) is list and type(v['reason']) is str)
    strict_operation(v['operation'])
    for key in ('eventId','requestId','setId','actorId'):integer(v[key])
    integer(v['beforeRevision'],zero=True);integer(v['afterRevision']);require(v['afterRevision']==v['beforeRevision']+1);utc(v['occurredAt']);strict_fields(v['changedFields']);strict_before_after(v['before'],True);strict_before_after(v['after'],False);exact(v['evidence'],'set members')
    if v['evidence']['set'] is not None:strict_evidence(v['evidence']['set'])
    for m in v['evidence']['members']:exact(m,'bookId evidence');integer(m['bookId']);strict_evidence(m['evidence'])
    for b in v['books']:strict_context(b)
def guard(base,env,mode):
    u=urllib.parse.urlsplit(base);subnet='172.30.89' if u.port==18089 else '172.30.90'
    require(os.environ.get('LIBRARY_ACCEPTANCE')=='1','Explicit isolated acceptance flag required')
    require(u.scheme=='http' and u.hostname in ('localhost','127.0.0.1') and u.port in (18089,18090) and not u.username and not u.password and u.path in ('','/') and not u.query and not u.fragment,'Exact localhost acceptance URL required')
    require(env.get('FRONTEND_PORT')==str(u.port) and env.get('APP_NETWORK_SUBNET')==subnet+'.0/24' and env.get('FRONTEND_INTERNAL_IP')==subnet+'.10','Exact acceptance subnet/IP/port required')
    require(mode in ('seed','check','snapshot-sql') and (mode!='seed' or u.port==18089),'Never seed restored deployment')
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None
class Client:
    def __init__(self,base,env,actor=1,role='ADMIN',version=0):
        self.base=base;self.env=env;self.actor=actor;self.role=role;self.version=version
        enc=lambda b:base64.urlsafe_b64encode(b).rstrip(b'=')
        payload=dict(sub=str(actor),role=role,version=version,iat=int(time.time()),exp=int(time.time())+3600)
        value=enc(b'{"alg":"HS256","typ":"JWT"}')+b'.'+enc(json.dumps(payload).encode())
        self.token=(value+b'.'+enc(hmac.new(env['JWT_SECRET'].encode(),value,hashlib.sha256).digest())).decode()
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def call(self,path,method='GET',body=None,anonymous=False):
        require(path.startswith('/api/') and method in ('GET','POST','PUT'),'Synthetic route/method refused')
        headers={'Content-Type':'application/json'}
        if not anonymous:headers['Authorization']='Bearer '+self.token
        req=urllib.request.Request(self.base+path,None if body is None else json.dumps(body,ensure_ascii=False).encode(),headers,method=method)
        with self.opener.open(req,timeout=120) as response:
            raw=response.read(2097153);require(response.status==200 and len(raw)<=2097152,'Oversized/unexpected synthetic HTTP response');v=json.loads(raw)
        exact(v,'code message data');require(type(v['code']) is int and v['code']==200,'Unexpected synthetic API result');return v['data']
    def denied(self,path,status,reason,method='GET',body=None,anonymous=False):
        try:self.call(path,method,body,anonymous)
        except urllib.error.HTTPError as e:
            raw=e.read(16385);require(e.code==status and len(raw)<=16384,'Unexpected refusal HTTP');v=json.loads(raw);exact(v,'code message data')
            require(v['code']==status and v['data']['reasonCode']==reason,'Unexpected refusal reason')
            if path.startswith(('/api/catalog-sets','/api/catalog-set-reviews')):exact(v['data'],'schemaVersion reasonCode requestId setId bookId membershipId');require(type(v['data']['schemaVersion']) is int and v['data']['schemaVersion']==1)
        else:raise RuntimeError('Expected B34 synthetic rejection')
def view(v):
    exact(v,'schemaVersion requestId operationKey mode operation state createdAt expiresAt previewDigest preview receipt cancelledAt cancelReason')
    require(type(v['schemaVersion']) is int and v['schemaVersion']==1 and v['mode']=='MANUAL' and v['state'] in ('PREVIEWED','COMMITTED','CANCELLED'))
    require(type(v['requestId']) is int and 1<=v['requestId']<=9007199254740991 and re.fullmatch('[0-9a-f]{64}',v['previewDigest']))
    strict_preview(v['preview']);utc(v['createdAt']);utc(v['expiresAt']);require(v['previewDigest']==hash_value(v['preview']) and v['operation']==v['preview']['operation'],'Original preview digest/operation differs')
    if v['state']=='COMMITTED':strict_receipt(v['receipt'])
    else:require(v['receipt'] is None)
    if v['state']=='CANCELLED':require(v['receipt'] is None and v['cancelReason'] in ('EDIT','ABANDON') and type(v['cancelledAt']) is str);utc(v['cancelledAt'])
    else:require(v['cancelledAt'] is None and v['cancelReason'] is None)
def context(c,bid):
    v=c.call('/api/catalog-sets/books/contexts?bookId='+str(bid));exact(v,'schemaVersion records');require(v['schemaVersion']==1 and len(v['records'])==1)
    r=v['records'][0];strict_context(r)
    require(r['book']['bookId']==bid and r['bookDigest']==hash_value(dict(schemaVersion=1,book=r['book'])));return r
def expected_book(c,bid):
    r=context(c,bid);return dict(revision=r['book']['revision'],attributeRevision=r['book']['attributeRevision'],digest=r['bookDigest'],sourceDigest=r['source']['digest'])
def getset(c,sid):
    v=c.call('/api/catalog-sets/'+str(sid));exact(v,'schemaVersion set setDigest');strict_set(v['set']);require(v['schemaVersion']==1 and v['setDigest']==hash_value(dict(schemaVersion=1,set=v['set'])));return v
def evidence(reference):return dict(kind='MANUAL_REVIEW',reference=reference)
def newmember(c,bid):return dict(bookId=bid,expectedBook=expected_book(c,bid),evidence=evidence('B34合成逐边依据 '+str(bid)))
def page(c,path):
    records=[];p=1;size=2;total=None
    while True:
        v=c.call(path+('?' if '?' not in path else '&')+'page='+str(p)+'&size='+str(size));exact(v,'schemaVersion records total page size pages')
        require(type(v['schemaVersion']) is int and v['schemaVersion']==1 and v['page']==p and v['size']==size and v['pages']==(v['total']+size-1)//size)
        if total is None:total=v['total']
        require(v['total']==total and len(v['records'])==max(0,min(size,total-(p-1)*size)),'Stable synthetic pagination differs')
        for r in v['records']:
            if '/events' in path:strict_event(r)
            elif '/maintenance-members' in path:exact(r,'member memberDigest book');strict_member(r['member']);digest(r['memberDigest']);strict_context(r['book'])
            elif '/members' in path and '/memberships' not in path:
                exact(r,'membershipId setId bookId status revision effective book');exact(r['book'],'bookId isbn catalogTitle displayTitle deleted');require(type(r['effective']) is bool and type(r['book']['deleted']) is bool)
        records+=v['records']
        if p>v['pages']:return records
        p+=1
def input_for(c,operation,sid=None,**extra):
    value=dict(schemaVersion=1,operationKey=str(uuid.uuid4()),mode='MANUAL',reason='B34隔离合成核实；不代表实际套装完整',operation=operation)
    if operation!='CREATE':
        s=getset(c,sid);value.update(setId=sid,expectedSet=dict(revision=s['set']['revision'],digest=s['setDigest']))
    value.update(extra);return value
def preview(c,body):
    v=c.call('/api/catalog-sets/previews','POST',body);view(v);require(v['state']=='PREVIEWED' and v['receipt'] is None)
    normalized=copy.deepcopy(body)
    if 'members' in normalized:normalized['members'].sort(key=lambda row:row['bookId'])
    require(v['preview']['applied']==normalized,'Preview canonical applied input differs')
    require(c.call('/api/catalog-sets/previews','POST',body)==v and c.call('/api/catalog-sets/requests/by-key?key='+body['operationKey'])==v,'Original preview changed')
    return dict(input=body,preview=v,actor=c.actor)
def confirm(c,saved):
    v=saved['preview'];body=dict(schemaVersion=1,operationKey=saved['input']['operationKey'],previewDigest=v['previewDigest']);path='/api/catalog-sets/requests/'+str(v['requestId'])+'/confirm'
    r=c.call(path,'POST',body);strict_receipt(r);require(r['schemaVersion']==1 and r['actorId']==c.actor and c.call(path,'POST',body)==r,'Original receipt replay differs')
    for key in ('copies','stockTotal','stockAvailable','loans','charges','uses'):require(r['impact'][key]==0,'B34 escaped catalog scope')
    committed=c.call('/api/catalog-sets/requests/'+str(v['requestId']));view(committed);require(committed['state']=='COMMITTED' and committed['receipt']==r and committed['preview']==v['preview'] and c.call('/api/catalog-sets/requests/by-key?key='+body['operationKey'])==committed)
    saved.update(receipt=r,committed=committed,confirmation=body);return r
def cancel(c,saved,reason):
    v=saved['preview'];body=dict(schemaVersion=1,operationKey=saved['input']['operationKey'],previewDigest=v['previewDigest'],reason=reason);path='/api/catalog-sets/requests/'+str(v['requestId'])+'/cancel'
    r=c.call(path,'POST',body);view(r);require(r['state']=='CANCELLED' and c.call(path,'POST',body)==r and c.call('/api/catalog-sets/previews','POST',saved['input'])==r,'Cancelled original key resurrected')
    c.denied(path,409,'IDEMPOTENCY_KEY_REUSED','POST',{**body,'reason':'ABANDON' if reason=='EDIT' else 'EDIT'})
    c.denied('/api/catalog-sets/requests/'+str(v['requestId'])+'/confirm',409,'PREVIEW_CANCELLED','POST',{k:body[k] for k in ('schemaVersion','operationKey','previewDigest')})
    saved.update(cancelled=r,cancellation=body);return r
def intake(c,marker):
    categories=c.call('/api/categories');category=next(r['id'] for r in categories if r.get('status')==1)
    key=str(uuid.uuid4());body=dict(idempotencyKey=key,source=dict(type='MANUAL',purchaseOrderId=None),reason='B34合成入库',lines=[dict(lineKey=str(uuid.uuid4()),target=dict(bookId=None,newBook=dict(title=marker+str(i),isbn=marker+str(i),categoryId=category),purchaseItemId=None),copies=[dict(input='SCANNED',barcode='000'+marker+str(i),location=None)]) for i in range(4)])
    p=c.call('/api/inventory-intakes/previews','POST',body);r=c.call('/api/inventory-intakes/'+str(p['intakeId'])+'/confirm','POST',dict(idempotencyKey=key,previewDigest=p['previewDigest']))
    ids=[r['bookId'] for r in r['books']];require(len(ids)==4 and len(set(ids))==4);return ids
def create(c,name,ids):return preview(c,input_for(c,'CREATE',clientRef=uuid.uuid4().hex,name=name,note='合成📚\n目录依据\t原文',evidence=evidence('B34合成集合依据'),members=[newmember(c,bid) for bid in ids]))
def old_fields(c,bid):return dict(book=c.call('/api/books/'+str(bid)+'/metadata'),attribute=c.call('/api/catalog-books/'+str(bid)+'/attributes'),identity=c.call('/api/catalog-identities/books/'+str(bid)),publication=c.call('/api/catalog-publications/books/'+str(bid)))
def permission_matrix(c,state):
    actors={k:Client(c.base,c.env,**v) for k,v in state['actors'].items()};sid=state['setIds'][0];owner=state['operations'][0]
    safe=['/api/catalog-sets','/api/catalog-sets/'+str(sid),'/api/catalog-sets/'+str(sid)+'/members','/api/catalog-sets/books/'+str(state['ids'][0])+'/memberships']
    private=['/api/catalog-set-reviews','/api/catalog-set-reviews/sources?caseKey=SET_SOURCE_BOOK:'+str(state['ids'][0]),'/api/catalog-sets/books/contexts?bookId='+str(state['ids'][0]),'/api/catalog-sets/'+str(sid)+'/maintenance-members','/api/catalog-sets/'+str(sid)+'/events','/api/catalog-sets/requests/'+str(owner['preview']['requestId']),'/api/catalog-sets/requests/by-key?key='+owner['input']['operationKey']]
    for path in safe:
        actors['librarian'].call(path);actors['otherAdmin'].call(path)
    for path in safe+private:
        c.denied(path,403,'CATALOG_SET_ROLE_REQUIRED',anonymous=True);actors['reader'].denied(path,403,'CATALOG_SET_ROLE_REQUIRED')
    for path in private:actors['librarian'].denied(path,403,'CATALOG_SET_ROLE_REQUIRED')
    for path in private[-2:]:actors['otherAdmin'].denied(path,403,'OWNER_REQUIRED')
    require(page(actors['otherAdmin'],'/api/catalog-sets/'+str(sid)+'/events')==[],'Other admin received owner events')
    body=copy.deepcopy(owner['input']);body['operationKey']=str(uuid.uuid4())
    for actor in ('reader','librarian'):actors[actor].denied('/api/catalog-sets/previews',403,'CATALOG_SET_ROLE_REQUIRED','POST',body)
    if 'revoked' in state:
        actor=actors['downgraded'];saved=state['revoked']
        for path in ('/api/catalog-sets/requests/'+str(saved['preview']['requestId']),'/api/catalog-sets/requests/by-key?key='+saved['input']['operationKey'],'/api/catalog-sets/'+str(saved['receipt']['result']['set']['setId'])+'/events'):
            actor.denied(path,403,'CATALOG_SET_ROLE_REQUIRED')
def b36_preview(c,ids):
    rows=[]
    for bid in ids:
        b=c.call('/api/catalog-publications/books/'+str(bid))['book']
        rows.append(dict(bookId=bid,expectedRevision=b['revision'],expectedMetadataDigest=b['metadataDigest'],changes=dict(publishDate=None,pages=dict(action='SET',value=0),description=dict(action='SET',value='B34前置B36原操作者合成回执')),evidence=evidence('B36原操作者隔离合成依据')))
    body=dict(operationKey=str(uuid.uuid4()),mode='MANUAL',reason='B34旧B36持久化覆盖',entries=rows);v=c.call('/api/catalog-publications/previews','POST',body)
    receipt=c.call('/api/catalog-publications/'+str(v['requestId'])+'/confirm','POST',dict(operationKey=body['operationKey'],previewDigest=v['previewDigest']))
    committed=c.call('/api/catalog-publications/'+str(v['requestId']));require(committed['receipt']==receipt)
    return dict(input=body,preview=v,receipt=receipt,committed=committed)
def b36_check(c,state):
    for name,saved in state['b36'].items():
        account=state['actors']['librarian' if name=='single' else 'downgraded'];actor=Client(c.base,c.env,**account)
        paths=['/api/catalog-publications/'+str(saved['preview']['requestId']),'/api/catalog-publications/requests/by-key?key='+saved['input']['operationKey']]
        for path in paths:
            if name=='single':require(actor.call(path)==saved['committed'],'Original Librarian B36 receipt changed')
            else:actor.denied(path,403,'CATALOG_PUBLICATION_ROLE_REQUIRED')
        for entry in saved['input']['entries']:
            bid=entry['bookId'];v=actor.call('/api/catalog-publications/books/'+str(bid)+'/events?page=1&size=1');require(v['total']>=1 and len(v['records'])==1 and v['records'][0]['actorId']==actor.actor,'Original B36 owner events lost')
            empty=actor.call('/api/catalog-publications/books/'+str(bid)+'/events?page='+str(v['pages']+1)+'&size=1');require(empty['records']==[] and empty['total']==v['total'],'B36 own event pagination differs')
def source_pages(c,state):
    rows=page(c,'/api/catalog-set-reviews?scope=ALL&bookDeleted=ALL&keyword='+state['marker'])
    require({r['book']['bookId'] for r in rows}==set(state['ids']),'Synthetic source paging omitted owned books')
    for r in rows:
        exact(r,'caseKey caseKind book source relationshipState activeSetCount historicalMembershipCount catalogMembershipState sourceResolution physicalAssembly')
        require(r['sourceResolution']=='UNRESOLVED' and r['physicalAssembly']=='UNVERIFIED' and r['source']['sourceCount']==0 and r['source']['markerState']=='NO_SOURCE' and r['source']['reasons']==['NO_SOURCE'],'Manual links incorrectly resolve source evidence')
        v=c.call('/api/catalog-set-reviews/sources?caseKey='+r['caseKey']+'&page=2&size=2');exact(v,'schemaVersion caseKey records total page size pages evidenceDigest')
        require(v['schemaVersion']==1 and v['records']==[] and v['total']==v['pages']==0 and v['evidenceDigest']==r['source']['digest'],'Source detail empty page/digest differs')
        r['source'].pop('readAt')
    return rows
def seed(c):
    marker='B34'+uuid.uuid4().hex[:15];ids=intake(c,marker);operations=[];cancelled=[]
    state=dict(schemaVersion=1,synthetic=True,ids=ids,marker=marker,operations=operations,cancelled=cancelled,actors={})
    for key,role in [('otherAdmin','ADMIN'),('librarian','LIBRARIAN'),('reader','READER'),('downgraded','ADMIN')]:
        user=c.call('/api/users','POST',dict(username='b34_'+uuid.uuid4().hex[:12],password=uuid.uuid4().hex,realName='合成验收',role=role,status=1))
        state['actors'][key]=dict(actor=user['id'],role=role,version=0)
    # Identity A -> B -> A is legal, creates old B33/B36 events, and MUST happen
    # before those old modules capture their first immutable snapshots.
    stale=create(c,marker+'过时预览',[ids[0]]);before=context(c,ids[0])
    original=c.call('/api/books/'+str(ids[0])+'/metadata')['book']
    for field,temp in [('isbn','X'+original['isbn'][1:]),('title',original['title']+'临时')]:
        for value in (temp,original[field]):
            m=c.call('/api/books/'+str(ids[0])+'/metadata');c.call('/api/books/'+str(ids[0])+'/metadata','PUT',dict(expectedMetadataDigest=m['metadataDigest'],changes={field:value}))
    require(context(c,ids[0])['book']['revision']==before['book']['revision']+4,'Identity ABA guard failed')
    c.denied('/api/catalog-sets/requests/'+str(stale['preview']['requestId'])+'/confirm',409,'BOOK_IDENTITY_CHANGED','POST',dict(schemaVersion=1,operationKey=stale['input']['operationKey'],previewDigest=stale['preview']['previewDigest']))
    cancel(c,stale,'EDIT');cancelled.append(stale)
    librarian=Client(c.base,c.env,**state['actors']['librarian']);downgraded=Client(c.base,c.env,**state['actors']['downgraded'])
    state['b36']=dict(single=b36_preview(librarian,[ids[0]]),batch=b36_preview(downgraded,ids[1:3]))
    revoked=create(downgraded,marker+'角色撤销',[]);confirm(downgraded,revoked);state['revoked']=revoked
    user=c.call('/api/users/'+str(downgraded.actor));c.call('/api/users','PUT',dict(id=user['id'],username=user['username'],realName=user['realName'],role='LIBRARIAN',status=1))
    state['actors']['downgraded'].update(role='LIBRARIAN',version=1);b36_check(c,state)
    original={str(bid):old_fields(c,bid) for bid in ids}
    empty=create(c,marker+'空集合',[]);empty_id=confirm(c,empty)['result']['set']['setId'];operations.append(empty)
    first=create(c,marker+'第一套',ids[:2]);sid=confirm(c,first)['result']['set']['setId'];operations.append(first)
    require(c.call('/api/catalog-sets/requests/'+str(first['preview']['requestId'])+'/cancel','POST',dict(schemaVersion=1,operationKey=first['input']['operationKey'],previewDigest=first['preview']['previewDigest'],reason='EDIT'))==first['committed'],'Post-commit cancel must return original committed view')
    other=create(c,marker+'第二套',[ids[0]]);other_id=confirm(c,other)['result']['set']['setId'];operations.append(other)
    add=preview(c,input_for(c,'ADD_MEMBERS',sid,members=[newmember(c,ids[2])]));confirm(c,add);operations.append(add)
    def edge_action(op,bid):
        row=next(r for r in page(c,'/api/catalog-sets/'+str(sid)+'/maintenance-members?status=ALL') if r['member']['bookId']==bid)
        m=row['member'];member=dict(membershipId=m['membershipId'],bookId=bid,expectedMember=dict(revision=m['revision'],digest=row['memberDigest']),expectedBook=expected_book(c,bid),evidence=evidence('B34合成'+op+'逐边依据'))
        saved=preview(c,input_for(c,op,sid,members=[member]));result=confirm(c,saved);operations.append(saved);return result['result']['members'][0]
    retired=edge_action('RETIRE_MEMBERS',ids[0]);restored=edge_action('RESTORE_MEMBERS',ids[0]);require(restored['membershipId']==retired['membershipId'] and restored['revision']==retired['revision']+1,'Restore changed stable membership id')
    edge_action('RETIRE_MEMBERS',ids[1])
    for op in ('RETIRE_SET','RESTORE_SET'):
        s=preview(c,input_for(c,op,sid,evidence=evidence('B34合成整体状态依据')));confirm(c,s);operations.append(s)
    retire_empty=preview(c,input_for(c,'RETIRE_SET',empty_id,evidence=evidence('B34合成空集合退役')));confirm(c,retire_empty);operations.append(retire_empty)
    current=getset(c,sid)['set'];noop=preview(c,input_for(c,'UPDATE_METADATA',sid,changes=dict(name=dict(action='SET',value=current['name']),note=dict(action='KEEP')),evidence=evidence('B34合成无变化依据')))
    r=confirm(c,noop);require(not r['changed'] and r['eventIds']==[] and r['result']['set']['revision']==current['revision'],'No-change advanced entity/event');operations.append(noop)
    edit=create(c,marker+'取消后编辑',[ids[3]]);cancel(c,edit,'ABANDON');cancelled.append(edit)
    pending=create(c,marker+'持久未提交',[ids[3]])
    state.update(setIds=[sid,other_id,empty_id],pending=pending)
    permission_matrix(c,state)
    # Current roles, safe responses and frozen owner receipts survive lifecycle.
    for bid in ids:require(old_fields(c,bid)==original[str(bid)],'B34 changed publication/identity/stock/B33 fields')
    state.update(original=original,contexts={str(bid):context(c,bid) for bid in ids},sets={str(x):getset(c,x) for x in state['setIds']},members={str(x):page(c,'/api/catalog-sets/'+str(x)+'/members?status=ALL') for x in state['setIds']},events={str(x):page(c,'/api/catalog-sets/'+str(x)+'/events') for x in state['setIds']},sources=source_pages(c,state))
    require(any(context(c,bid)['book']['revision']>0 for bid in ids));return state
def check(c,state):
    require(state['schemaVersion']==1 and state['synthetic'] is True)
    permission_matrix(c,state)
    b36_check(c,state);require(source_pages(c,state)==state['sources'],'Read-only source cases changed after lifecycle')
    for bid in state['ids']:
        current=context(c,bid);saved=copy.deepcopy(state['contexts'][str(bid)])
        current['source'].pop('readAt');saved['source'].pop('readAt');require(current==saved and old_fields(c,bid)==state['original'][str(bid)],'Owned identity/context or old business changed')
    for sid in state['setIds']:
        require(getset(c,sid)==state['sets'][str(sid)] and page(c,'/api/catalog-sets/'+str(sid)+'/members?status=ALL')==state['members'][str(sid)] and page(c,'/api/catalog-sets/'+str(sid)+'/events')==state['events'][str(sid)],'Original set/member/event projection changed')
    for saved in state['operations']+state['cancelled']+[state['pending']]:
        expected=saved.get('committed',saved.get('cancelled',saved['preview']));v=c.call('/api/catalog-sets/requests/'+str(saved['preview']['requestId']));view(v)
        require(v==expected and c.call('/api/catalog-sets/requests/by-key?key='+saved['input']['operationKey'])==expected,'Original preview/receipt/cancel changed')
def snapshot_sql(state):
    for table,columns in COLUMNS.items():
        cells=','.join('IF(`'+v+'` IS NULL,NULL,HEX(CAST(`'+v+'` AS BINARY)))' for v in columns.split());order='request_id,book_id' if table=='catalog_set_request_book' else 'id'
        print("SELECT '"+table+"',SHA2(CAST(JSON_ARRAY("+cells+") AS CHAR),256) FROM `"+table+'` ORDER BY '+order+';')
    cells=','.join('IF(`'+v+'` IS NULL,NULL,HEX(CAST(`'+v+'` AS BINARY)))' for v in ('id','catalog_set_book_revision'))
    print("SELECT 'book-set-guard',SHA2(CAST(JSON_ARRAY("+cells+") AS CHAR),256) FROM book WHERE id IN ("+','.join(str(int(v)) for v in sorted(state['ids']))+') ORDER BY id;')
def main(argv=None):
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--env',required=True);p.add_argument('--state',required=True);p.add_argument('--mode',choices=('seed','check','snapshot-sql'),required=True);a=p.parse_args(argv)
    env=dict(line.split('=',1) for line in Path(a.env).read_text().splitlines() if line and not line.startswith('#') and '=' in line);guard(a.base,env,a.mode);path=Path(a.state)
    if a.mode=='seed':
        require(not path.exists() and not path.is_symlink(),'Never overwrite original fixture state');state=seed(Client(a.base,env));path.write_text(json.dumps(state,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('B34 synthetic HTTP business and original snapshots captured')
    else:
        state=json.loads(path.read_bytes())
        if a.mode=='snapshot-sql':snapshot_sql(state)
        else:check(Client(a.base,env),state);print('B34 five-table original JSON, owner receipts, states, guards and pages preserved')
    return 0
if __name__=='__main__':raise SystemExit(main())
B34_PY
library_set_acceptance() {
    LIBRARY_ACCEPTANCE=1 python3 "$library_test/container_set_acceptance.py" --base "$1" --env "$2" --state "$library_test/set-state.json" --mode "$3"
}
library_set_snapshot() {
    local library_set_project="$1" library_set_tag="$2" library_set_mysql
    [[ "$library_set_project" == library || "$library_set_project" == library-recovery ]]
    library_set_mysql="$(docker ps -q --filter "label=com.docker.compose.project=$library_set_project" --filter label=com.docker.compose.service=mysql)"
    [[ -n "$library_set_mysql" && "$library_set_mysql" != *$'\n'* ]]
    [[ "$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$library_set_mysql")" == "$library_set_project" ]]
    [[ "$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.service" }}' "$library_set_mysql")" == mysql ]]
    docker exec -i "$library_set_mysql" sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names "$MYSQL_DATABASE"' < "$library_test/set-persistence.sql" > "$library_test/set-$library_set_tag.tsv"
    if [[ "$library_set_tag" != before ]]; then
        cmp --silent "$library_test/set-before.tsv" "$library_test/set-$library_set_tag.tsv"
    fi
}
library_set_acceptance http://localhost:18089 "$library_test/install/.env" seed
library_set_acceptance http://localhost:18089 "$library_test/install/.env" snapshot-sql > "$library_test/set-persistence.sql"
library_set_snapshot library before
python3 - "$library_test/set-before.tsv" <<'B34_NONEMPTY_PY'
import re,sys
from pathlib import Path
rows=[line.split('\t') for line in Path(sys.argv[1]).read_text().splitlines()]
assert rows and all(len(row)==2 and re.fullmatch('[0-9a-f]{64}',row[1]) for row in rows)
required=set('catalog_set catalog_set_member catalog_set_request catalog_set_request_book catalog_set_event book-set-guard'.split())
assert {row[0] for row in rows}==required,'Every B34 table and owned technical guard must be nonempty'
print('B34 five nonempty tables, original previews/receipts/cancel, versions and owned guards captured')
B34_NONEMPTY_PY
# END B34 SETS SEED
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
# BEGIN B36 PUBLICATION SEED
cat > "$library_test/catalog_publication_acceptance.py" <<'B36_PY'
"""B36 synthetic HTTP lifecycle helper. Never connects to production.

snapshot-sql emits SELECTs only; no business SQL is executed by this helper.
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

COLUMNS={
 'catalog_publication_request':'id operation_key actor_id mode input_hash schema_version request_json preview_json preview_digest state created_at expires_at receipt_json committed_at',
 'catalog_publication_event':'id book_id request_id kind before_revision after_revision schema_version changed_fields_json before_json after_json evidence_json actor_id occurred_at reason',
}
def guard(base,env,mode):
    u=urllib.parse.urlsplit(base)
    assert os.environ.get('LIBRARY_ACCEPTANCE')=='1','Explicit acceptance flag required'
    assert u.scheme=='http' and u.hostname in ('localhost','127.0.0.1') and u.port in (18089,18090) and not u.username and not u.password and u.path in ('','/') and not u.query and not u.fragment,'Exact localhost acceptance URL required'
    subnet='172.30.89' if u.port==18089 else '172.30.90'
    assert env.get('FRONTEND_PORT')==str(u.port) and env.get('APP_NETWORK_SUBNET')==subnet+'.0/24' and env.get('FRONTEND_INTERNAL_IP')==subnet+'.10','Exact acceptance subnet/IP/port required'
    assert mode in ('seed','check','snapshot-sql') and (mode!='seed' or u.port==18089),'Never seed restored deployment'

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None

class Client:
    def __init__(self,base,env):
        self.base=base;enc=lambda x:base64.urlsafe_b64encode(x).rstrip(b'=')
        payload=dict(sub='1',role='ADMIN',version=0,iat=int(time.time()),exp=int(time.time())+3600)
        value=enc(b'{"alg":"HS256","typ":"JWT"}')+b'.'+enc(json.dumps(payload).encode())
        self.token=(value+b'.'+enc(hmac.new(env['JWT_SECRET'].encode(),value,hashlib.sha256).digest())).decode()
        self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def call(self,path,method='GET',body=None):
        assert path.startswith('/api/') and method in ('GET','POST','PUT')
        request=urllib.request.Request(self.base+path,None if body is None else json.dumps(body,ensure_ascii=False).encode(),{'Authorization':'Bearer '+self.token,'Content-Type':'application/json'},method=method)
        with self.opener.open(request,timeout=120) as response:
            raw=response.read(2097153);assert response.status==200 and len(raw)<=2097152
            value=json.loads(raw)
        assert set(value)=={'code','message','data'} and value['code']==200
        return value['data']
    def denied(self,path,body,status,reason):
        try:self.call(path,'POST',body)
        except urllib.error.HTTPError as error:
            raw=error.read(16385);assert error.code==status and len(raw)<=16384
            value=json.loads(raw);assert value['code']==status and value['data']['reasonCode']==reason
        else:raise AssertionError('Expected publication refusal')

def book(c,id):return c.call('/api/catalog-publications/books/'+str(id))['book']
def body(c,entries):
    rows=[]
    for id,changes,reference in entries:
        current=book(c,id);rows.append(dict(bookId=id,expectedRevision=current['revision'],expectedMetadataDigest=current['metadataDigest'],changes={k:changes.get(k) for k in ('publishDate','pages','description')},evidence=dict(kind='MANUAL_REVIEW',reference=reference)))
    return dict(operationKey=str(uuid.uuid4()),mode='MANUAL',reason='B36隔离合成验收',entries=rows)
def preview(c,input):
    value=c.call('/api/catalog-publications/previews','POST',input)
    assert value['state']=='PREVIEWED' and value['receipt'] is None
    assert [v['bookId'] for v in value['preview']['books']]==sorted(v['bookId'] for v in input['entries'])
    assert c.call('/api/catalog-publications/previews','POST',input)==value
    assert c.call('/api/catalog-publications/requests/by-key?key='+input['operationKey'])==value
    return dict(input=input,preview=value)
def confirm(c,saved):
    value=saved['preview'];confirmation=dict(operationKey=saved['input']['operationKey'],previewDigest=value['previewDigest']);path='/api/catalog-publications/'+str(value['requestId'])+'/confirm'
    result=c.call(path,'POST',confirmation);assert c.call(path,'POST',confirmation)==result
    committed=c.call('/api/catalog-publications/'+str(value['requestId']));assert committed['receipt']==result and committed['preview']==value['preview'] and committed['state']=='COMMITTED'
    assert c.call('/api/catalog-publications/requests/by-key?key='+saved['input']['operationKey'])==committed
    saved.update(confirmation=confirmation,receipt=result,committed=committed);return result
def unrelated(c,id):
    metadata=c.call('/api/books/'+str(id)+'/metadata')['book']
    for key in ('publishDate','pages','description','updateTime'):metadata.pop(key,None)
    return dict(metadata=metadata,attribute=c.call('/api/catalog-books/'+str(id)+'/attributes'),identity=c.call('/api/catalog-identities/books/'+str(id)))
def setv(value):return dict(action='SET',value=value)
def clear():return dict(action='CLEAR')
def intake(c,marker):
    categories=c.call('/api/categories');assert categories and all(type(row['id']) is int and row['id']>0 for row in categories)
    category_id=next(row['id'] for row in categories if row.get('status')==1)
    key=str(uuid.uuid4());input=dict(idempotencyKey=key,source=dict(type='MANUAL',purchaseOrderId=None),reason='B36合成书目',lines=[dict(lineKey=str(uuid.uuid4()),target=dict(bookId=None,newBook=dict(title=marker+str(i),isbn=marker+str(i),categoryId=category_id),purchaseItemId=None),copies=[dict(input='SCANNED',barcode='000'+marker+str(i),location=None)]) for i in range(3)])
    p=c.call('/api/inventory-intakes/previews','POST',input);r=c.call('/api/inventory-intakes/'+str(p['intakeId'])+'/confirm','POST',dict(idempotencyKey=key,previewDigest=p['previewDigest']))
    ids=[v['bookId'] for v in r['books']];assert len(ids)==3 and len(set(ids))==3;return ids
def seed(c):
    ids=intake(c,'B36'+uuid.uuid4().hex[:16]);original={str(id):unrelated(c,id) for id in ids};saved=[]
    # Each entry has explicit human supplied reference; NULL, empty and 0 differ.
    single=preview(c,body(c,[(ids[0],dict(publishDate=setv('2024-02-29'),pages=setv(0),description=setv('合成版权页📚\n第二行\t原文')),'合成版权页；人工填写的依据')]))
    r=confirm(c,single);assert r['books'][0]['changed'];saved.append(single)
    cleared=preview(c,body(c,[(ids[0],{k:clear() for k in ('publishDate','pages','description')},'合成未知资料，明确清空')]))
    r=confirm(c,cleared);assert all(r['books'][0]['publication'][k] is None for k in ('publishDate','pages','description'));saved.append(cleared)
    zero=preview(c,body(c,[(ids[0],dict(pages=setv(0),description=setv('')),'合成已知0页与显式空简介')]))
    confirm(c,zero);saved.append(zero)
    noop=preview(c,body(c,[(ids[0],dict(pages=setv(0),description=setv('')),'合成显式重复原值')]))
    r=confirm(c,noop);assert not r['books'][0]['changed'] and r['books'][0]['eventId'] is None and r['books'][0]['beforeRevision']==r['books'][0]['afterRevision'];saved.append(noop)
    batch=preview(c,body(c,[(ids[2],dict(publishDate=setv('2023-12-31'),description=setv('')),'合成书目三依据'),(ids[1],dict(pages=setv(0),description=setv('批量📚\n原文\t保留')),'合成书目二依据')]))
    assert len(confirm(c,batch)['books'])==2;saved.append(batch)
    stale=preview(c,body(c,[(ids[0],dict(pages=setv(7)),'合成失效预览')]))
    # Exercise the deployed metadata writer A -> B -> A, keeping text bytes.
    before=book(c,ids[0])
    for pages in (8,0):
        metadata=c.call('/api/books/'+str(ids[0])+'/metadata')
        c.call('/api/books/'+str(ids[0])+'/metadata','PUT',dict(expectedMetadataDigest=metadata['metadataDigest'],changes=dict(pages=pages)))
    after=book(c,ids[0]);assert after['pages']==0 and after['description']=='' and after['revision']==before['revision']+2
    c.denied('/api/catalog-publications/'+str(stale['preview']['requestId'])+'/confirm',dict(operationKey=stale['input']['operationKey'],previewDigest=stale['preview']['previewDigest']),409,'PUBLICATION_CHANGED')
    assert c.call('/api/catalog-publications/requests/by-key?key='+stale['input']['operationKey'])==stale['preview']
    # A committed receipt remains its original object after later changes.
    for s in saved:assert c.call('/api/catalog-publications/requests/by-key?key='+s['input']['operationKey'])==s['committed']
    for id in ids:assert unrelated(c,id)==original[str(id)],'Publication changed old B33/B35, stock or metadata'
    state=dict(schemaVersion=1,synthetic=True,ids=ids,operations=saved,stale=stale,books={str(id):book(c,id) for id in ids},unrelated=original,events={str(id):c.call('/api/catalog-publications/books/'+str(id)+'/events?page=1&size=100') for id in ids})
    assert all(state['events'][str(id)]['records'] for id in ids)
    return state
def check(c,state):
    assert state['schemaVersion']==1 and state['synthetic'] is True
    for id in state['ids']:
        assert book(c,id)==state['books'][str(id)] and unrelated(c,id)==state['unrelated'][str(id)]
        assert c.call('/api/catalog-publications/books/'+str(id)+'/events?page=1&size=100')==state['events'][str(id)]
    for operation in state['operations']:
        assert c.call('/api/catalog-publications/'+str(operation['preview']['requestId']))==operation['committed']
        assert c.call('/api/catalog-publications/requests/by-key?key='+operation['input']['operationKey'])==operation['committed']
    assert c.call('/api/catalog-publications/requests/by-key?key='+state['stale']['input']['operationKey'])==state['stale']['preview']
def snapshot_sql(state):
    for table,columns in COLUMNS.items():
        cells=','.join('IF(`'+v+'` IS NULL,NULL,HEX(CAST(`'+v+'` AS BINARY)))' for v in columns.split())
        print("SELECT '"+table+"',SHA2(CAST(JSON_ARRAY("+cells+") AS CHAR),256) FROM `"+table+'` ORDER BY id;')
    columns='id publish_date pages description publication_revision'.split();cells=','.join('IF(`'+v+'` IS NULL,NULL,HEX(CAST(`'+v+'` AS BINARY)))' for v in columns)
    print("SELECT 'book-publication',SHA2(CAST(JSON_ARRAY("+cells+") AS CHAR),256) FROM book WHERE id IN ("+','.join(str(int(v)) for v in sorted(state['ids']))+') ORDER BY id;')
def main(argv=None):
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--env',required=True);p.add_argument('--state',required=True);p.add_argument('--mode',choices=('seed','check','snapshot-sql'),required=True);a=p.parse_args(argv)
    env=dict(line.split('=',1) for line in Path(a.env).read_text().splitlines() if line and not line.startswith('#') and '=' in line);guard(a.base,env,a.mode);path=Path(a.state)
    if a.mode=='seed':
        assert not path.exists() and not path.is_symlink();state=seed(Client(a.base,env));path.write_text(json.dumps(state,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print('B36 nonempty synthetic HTTP fixture seeded')
    else:
        state=json.loads(path.read_text(encoding='utf-8'))
        if a.mode=='snapshot-sql':snapshot_sql(state)
        else:check(Client(a.base,env),state);print('B36 original receipts, fields, revisions, evidence and events preserved')
    return 0
if __name__=='__main__':raise SystemExit(main())
B36_PY
library_publication_acceptance() {
    LIBRARY_ACCEPTANCE=1 python3 "$library_test/catalog_publication_acceptance.py" --base "$1" --env "$2" --state "$library_test/publication-state.json" --mode "$3"
}
library_publication_snapshot() {
    local library_publication_project="$1" library_publication_tag="$2" library_publication_mysql
    [[ "$library_publication_project" == library || "$library_publication_project" == library-recovery ]]
    library_publication_mysql="$(docker ps -q --filter "label=com.docker.compose.project=$library_publication_project" --filter label=com.docker.compose.service=mysql)"
    [[ -n "$library_publication_mysql" && "$library_publication_mysql" != *$'\n'* ]]
    [[ "$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project" }}' "$library_publication_mysql")" == "$library_publication_project" ]]
    docker exec -i "$library_publication_mysql" sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names "$MYSQL_DATABASE"' < "$library_test/publication-persistence.sql" > "$library_test/publication-$library_publication_tag.tsv"
    if [[ "$library_publication_tag" != before ]]; then
        cmp --silent "$library_test/publication-before.tsv" "$library_test/publication-$library_publication_tag.tsv"
    fi
}
library_publication_acceptance http://localhost:18089 "$library_test/install/.env" seed
library_publication_acceptance http://localhost:18089 "$library_test/install/.env" snapshot-sql > "$library_test/publication-persistence.sql"
library_publication_snapshot library before
python3 - "$library_test/publication-before.tsv" <<'B36_NONEMPTY_PY'
import re,sys
from pathlib import Path
rows=[line.split('\t') for line in Path(sys.argv[1]).read_text().splitlines()]
assert rows and all(len(row)==2 and re.fullmatch('[0-9a-f]{64}',row[1]) for row in rows)
required=set('catalog_publication_request catalog_publication_event book-publication'.split())
assert {row[0] for row in rows}==required,'Every B36 table and owned technical revision must be nonempty'
print('B36 both tables contain synthetic business; original JSON byte hashes captured')
B36_NONEMPTY_PY
# END B36 PUBLICATION SEED
# BEGIN B40 FINANCE SEED
cat > "$library_test/container_finance_acceptance.py" <<'B40_PY'
"""B40-A exact isolated SQL fixtures and GET-only nonempty lifecycle checks."""
import argparse,base64,copy,datetime,hashlib,hmac,json,os,re,time,urllib.error,urllib.parse,urllib.request,uuid,zlib
from decimal import Decimal
from pathlib import Path

SCHEMA_SHA='2bd8b8070e809b59929ce970b3c2bed3590d62f5e895d58d9f2a969132e19961'
SCHEMA_BYTES=zlib.decompress(base64.b64decode('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'))
assert hashlib.sha256(SCHEMA_BYTES).hexdigest()==SCHEMA_SHA
DEFS=json.loads(SCHEMA_BYTES)['$defs']
COLUMNS={'member_finance_operation': ['id', 'actor_id', 'user_id', 'operation_key', 'reserve_hash', 'editor_id', 'editor_epoch', 'last_claim_key', 'last_claim_hash', 'last_claim_from_epoch', 'state', 'draft_hash', 'draft_input_json', 'request_id', 'preview_digest', 'cancel_key', 'cancel_hash', 'cancel_input_json', 'cancel_result_json', 'result_json', 'created_at', 'draft_expires_at', 'updated_at', 'resolved_at', 'active_actor_id'], 'member_finance_account': ['id', 'user_id', 'fund', 'currency', 'revision', 'opening_entry_id', 'scope_cutoff_at', 'created_at'], 'member_finance_request': ['id', 'account_id', 'operation_id', 'actor_id', 'operation_key', 'action', 'request_hash', 'policy_digest', 'preview_digest', 'input_json', 'dependencies_json', 'preview_json', 'state', 'result_json', 'created_at', 'expires_at', 'resolved_at'], 'member_finance_entry': ['id', 'account_id', 'request_id', 'ordinal', 'kind', 'amount', 'opening_received', 'opening_refunded', 'cutoff_at', 'actual_at', 'method_code', 'root_fact_key', 'reverses_entry_id', 'replaces_entry_id', 'business_type', 'business_key', 'policy_digest', 'evidence_json', 'reason', 'operator_id', 'recorded_at'], 'member_finance_allocation': ['refund_entry_id', 'lot_entry_id', 'account_id', 'amount'], 'member_finance_audit': ['id', 'request_id', 'account_id', 'operator_id', 'action', 'before_revision', 'after_revision', 'before_json', 'after_json', 'reason', 'recorded_at']}
TABLES=tuple(COLUMNS)
def require(v,message='B40 isolated acceptance check failed'):
    if not v:raise RuntimeError(message)
def j(v):return json.dumps(v,ensure_ascii=False,separators=(',',':'))
def canonical(v):return json.dumps(v,ensure_ascii=False,separators=(',',':'),sort_keys=True)
def sha(v):return hashlib.sha256(v).hexdigest()
def digest(v):return sha(canonical(v).encode())
def matches(value,schema):
    try:validate(value,schema);return True
    except (RuntimeError,ValueError):return False
def validate(v,s):
    if '$ref' in s:validate(v,DEFS[s['$ref'].split('/')[-1]])
    if 'type' in s:
        types={'object':dict,'array':list,'string':str,'integer':int,'boolean':bool,'null':type(None),'number':(int,float)}
        t=types[s['type']];require(type(v) in t if isinstance(t,tuple) else type(v) is t,'B40 schema type differs')
    if 'const' in s:require(type(v) is type(s['const']) and v==s['const'],'B40 schema const differs')
    if 'enum' in s:require(any(type(v) is type(x) and v==x for x in s['enum']),'B40 schema enum differs')
    for key in ('allOf','anyOf','oneOf'):
        if key in s:
            count=sum(matches(v,x) for x in s[key]);require(count==len(s[key]) if key=='allOf' else count>=1 if key=='anyOf' else count==1,'B40 schema union differs')
    if 'not' in s:require(not matches(v,s['not']),'B40 schema negative differs')
    if 'if' in s:validate(v,s.get('then',{}) if matches(v,s['if']) else s.get('else',{}))
    if type(v) is dict:
        require(set(s.get('required',[]))<=set(v),'B40 schema required field missing')
        if s.get('additionalProperties') is False:require(set(v)<=set(s.get('properties',{})),'B40 schema unexpected field')
        for k,child in s.get('properties',{}).items():
            if k in v:validate(v[k],child)
    if type(v) is list:
        require(s.get('minItems',0)<=len(v)<=s.get('maxItems',2**63),'B40 schema array length differs')
        for i,x in enumerate(v):
            if i<len(s.get('prefixItems',[])):validate(x,s['prefixItems'][i])
            if 'items' in s:validate(x,s['items'])
    if type(v) is str:
        require(s.get('minLength',0)<=len(v)<=s.get('maxLength',2**63),'B40 schema string length differs')
        if 'pattern' in s:require(re.search(s['pattern'],v) is not None,'B40 schema string pattern differs')
        if s.get('format')=='uuid':require(str(uuid.UUID(v))==v,'B40 UUID differs')
        if s.get('format')=='date-time':datetime.datetime.fromisoformat(v.replace('Z','+00:00'))
    if type(v) in (int,float):require(s.get('minimum',-float('inf'))<=v<=s.get('maximum',float('inf')),'B40 schema numeric bounds differ')
def check_definition(name,v):validate(v,DEFS[name])
def strict_json(raw):
    def pairs(items):
        d={}
        for k,v in items:require(k not in d,'Duplicate B40 response field');d[k]=v
        return d
    value=json.loads(raw.decode('utf-8','strict'),object_pairs_hook=pairs,parse_constant=lambda v:require(False,'Invalid JSON constant'))
    def strings(v):
        if type(v) is str:require(not any(0xd800<=ord(c)<=0xdfff for c in v),'Invalid JSON surrogate')
        elif type(v) is list:
            for x in v:strings(x)
        elif type(v) is dict:
            for k,x in v.items():strings(k);strings(x)
    strings(value);return value
def guard(base,env,mode):
    u=urllib.parse.urlsplit(base);require(os.environ.get('LIBRARY_ACCEPTANCE')=='1','Explicit isolated finance acceptance flag required')
    require(u.scheme=='http' and u.hostname in ('localhost','127.0.0.1') and u.port in (18089,18090) and not u.username and not u.password and u.path in ('','/') and not u.query and not u.fragment,'Exact isolated finance URL required')
    prefix='172.30.89' if u.port==18089 else '172.30.90'
    require(env.get('FRONTEND_PORT')==str(u.port) and env.get('APP_NETWORK_SUBNET')==prefix+'.0/24' and env.get('FRONTEND_INTERNAL_IP')==prefix+'.10','Exact finance deployment settings required')
    require(mode in ('seed-sql','bind','capture','check','snapshot-sql') and (mode not in ('seed-sql','bind','capture') or u.port==18089),'Restored finance deployment is check only')
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args):return None
class Client:
    def __init__(self,base,env):self.base,self.env=base,env;self.opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoRedirect())
    def token(self,actor,role):
        enc=lambda x:base64.urlsafe_b64encode(x).rstrip(b'=')
        payload=dict(sub=str(actor),role=role,version=0,iat=int(time.time()),exp=int(time.time())+180)
        body=enc(b'{"alg":"HS256","typ":"JWT"}')+b'.'+enc(j(payload).encode());return (body+b'.'+enc(hmac.new(self.env['JWT_SECRET'].encode(),body,hashlib.sha256).digest())).decode()
    def get(self,path,definition,actor,role='ADMIN',status=200,reason=None):
        require(path.startswith('/api/members/') and '/finance' in path and not any(c in path for c in '\r\n'),'B40 accepts only finance GET paths')
        headers={} if actor is None else {'Authorization':'Bearer '+self.token(actor,role)}
        request=urllib.request.Request(self.base+path,headers=headers,method='GET')
        try:
            with self.opener.open(request,timeout=60) as response:actual=response.status;raw=response.read(2097153)
        except urllib.error.HTTPError as e:actual=e.code;raw=e.read(16385)
        require(actual==status and len(raw)<=2097152,'B40 HTTP status or response length differs');v=strict_json(raw);check_definition(definition,v);require(v['code']==status)
        if reason is not None:require(v['data']['errorCode']==reason and v['data']['certainty']=='NO_FINANCIAL_WRITE' and v['data']['fieldErrors']==[],'B40 safe error differs')
        return v['data']

AT=['2026-01-01T00:00:0'+str(i)+'.123456Z' for i in range(9)]
CUTOFF='2025-01-01T00:00:00.000000Z'
EVIDENCE=[dict(type='VERIFICATION_NOTE',reference='B40_SYNTHETIC_ONLY',digest=None)]
def money(v):return format(Decimal(v),'.2f')
def amount(v):return dict(state='KNOWN',amount=money(v),reasonCode=None)
def unknown():return dict(state='UNKNOWN',amount=None,reasonCode='OPENING_UNVERIFIED')
def replace_tokens(v,bindings):
    if type(v) is str:
        for token,value in bindings.items():v=v.replace(token,str(value))
        return v
    if type(v) is list:return [replace_tokens(x,bindings) for x in v]
    if type(v) is dict:return {k:replace_tokens(x,bindings) for k,x in v.items()}
    return v
class Fixture:
    def __init__(self):
        self.sql=['SET NAMES utf8mb4','START TRANSACTION'];self.plan=[];self.vars={};self.accounts={};self.entries=[];self.operations=[];self.audits=[];self.requests=[];self.marker='b40_'+uuid.uuid4().hex[:14]
        for key,role in [('admin','ADMIN'),('clerk','LIBRARIAN'),('reader','READER'),('member','READER'),('unknown','READER'),('empty','READER'),('revoked','LIBRARIAN')]:
            self.insert('sys_user',dict(username=self.marker+'_'+key,password='ISOLATED-NO-LOGIN',real_name='合成验收',role=role,status=1),key)
        for key,member,fund in [('fee','member','MEMBER_FEE'),('deposit','member','DEPOSIT'),('unverified','unknown','MEMBER_FEE')]:
            self.insert('member_finance_account',dict(user_id=self.token(member),fund=fund,currency='CNY',revision=0,scope_cutoff_at=None,created_at=self.dt(AT[0])),key)
            self.accounts[key]=dict(id=self.token(key),member=self.token(member),fund=fund,revision=0,opening=None,cutoff=None)
        self.source_uid='000B40'+uuid.uuid4().hex
        self.archive_raw='{"实际缴纳费用":"001.230","实际缴纳押金":1e2,"缴费状态":"","押金缴纳状态":null,"押金":"原押金字段待核实","家庭宝宝电话":"B40_PRIVATE_SYNTHETIC_NEVER_RETURN"}'
        self.insert('legacy_record',dict(source_system='huiben',record_type='MEMBER',source_key=self.source_uid,display_name='合成财务来源',user_id=self.token('member'),details_json=self.archive_raw),'archive')
        self.insert('member_profile',dict(user_id=self.token('member'),source_key='huiben:'+self.source_uid,source_system=None,source_archive_id=self.token('archive'),paid_fee='77.00',deposit_amount=None,deposit_status='原值待核实'))
        self.policy=dict(digest='a'*64,currency='CNY',scale=2,rounding='REJECT_EXTRA_SCALE',businessAmountLimit=None,paymentMethodsState='CONFIRMED',paymentMethodCodes=['SYNTHETIC_CASH'],datePolicyState='CONFIRMED',cutoffAt=CUTOFF,financialTimeZone='UTC',backdateRuleRef='B40_ISOLATED_FIXTURE_ONLY',debtPolicyState='UNCONFIRMED',debtPolicyVersion=None,enabledActions=['OPENING','RECEIPT','REFUND','CORRECT'])
        self.policy['digest']=digest({k:v for k,v in self.policy.items() if k!='digest'})
        self.commit('fee','OPENING',[dict(kind='OPENING',amount='80.00',openingReceived='100.00',openingRefunded='20.00')],0)
        self.commit('fee','RECEIPT',[dict(kind='RECEIPT',amount='12.34')],1)
        self.commit('fee','REFUND',[dict(kind='REFUND',amount='15.01',allocations=[dict(lotEntryId=self.token('entry1'),amount='10.00'),dict(lotEntryId=self.token('entry2'),amount='5.01')])],2)
        self.commit('fee','CORRECT',[dict(kind='REVERSAL',amount='15.01',reversesEntryId=self.token('entry3')),dict(kind='REFUND',amount='3.33',replacesEntryId=self.token('entry3'),allocations=[dict(lotEntryId=self.token('entry1'),amount='3.33')])],3)
        self.commit('deposit','OPENING',[dict(kind='OPENING',amount='0.00',openingReceived='0.00',openingRefunded='0.00')],4)
        self.commit('unverified','RECEIPT',[dict(kind='RECEIPT',amount='900719925474099.91')],5)
        self.terminal_tombstone('CANCELLED',6);self.terminal_tombstone('EXPIRED',7);self.terminal_tombstone('REJECTED',8)
        self.sql.append('COMMIT')
        self.sql.append('SELECT JSON_OBJECT('+','.join("CONVERT(UNHEX('"+k.encode().hex()+"') USING utf8mb4),CAST("+v+' AS CHAR)' for k,v in self.vars.items())+')')
    def token(self,key):
        token='__B40_'+key+'__';self.vars.setdefault(token,'@b40_'+key);return token
    def dt(self,v):return v.replace('T',' ').removesuffix('Z')
    def quote(self,v):
        if v is None:return 'NULL'
        if type(v) is int:return str(v)
        value='CONVERT(UNHEX(\''+str(v).encode().hex()+'\') USING utf8mb4)'
        for token,var in self.vars.items():
            if token in str(v):value='REPLACE('+value+",'"+token+"',CAST("+var+' AS CHAR))'
        return value
    def json_sql(self,v):return self.quote(j(v))
    def insert(self,table,row,key=None):
        self.plan.append(dict(kind='insert',table=table,row=copy.deepcopy(row),generatedKey=key))
        self.sql.append('INSERT INTO `'+table+'`('+','.join('`'+k+'`' for k in row)+') VALUES ('+','.join(self.quote(v) for v in row.values())+')')
        if key:self.token(key);self.sql.append('SET '+self.vars[self.token(key)]+'=LAST_INSERT_ID()')
    def balance(self,key,at):
        a=self.accounts[key];entries=[x for x in self.entries if x['accountId']==a['id'] and x['recordedAt']<=at];reversed={x['reversesEntryId'] for x in entries if x['kind']=='REVERSAL'};active=[x for x in entries if x['kind']!='REVERSAL' and x['id'] not in reversed];opening=next((x for x in active if x['kind']=='OPENING'),None)
        r=sum((Decimal(x['amount']) for x in active if x['kind']=='RECEIPT'),Decimal(0));f=sum((Decimal(x['amount']) for x in active if x['kind']=='REFUND'),Decimal(0));paid=Decimal(opening['openingReceived'])+r if opening else None;refunded=Decimal(opening['openingRefunded'])+f if opening else None
        return dict(memberId=a['member'],accountId=a['id'],fund=a['fund'],currency='CNY',financeRevision=str(a['revision']),scopeCutoffAt=a['cutoff'],openingEntryId=a['opening'],openingState='VERIFIED' if opening else 'UNKNOWN',verifiedPaid=amount(paid) if opening else unknown(),verifiedRefunded=amount(refunded) if opening else unknown(),balance=amount(paid-refunded) if opening else unknown(),feeHeadroom=None if a['fund']=='DEPOSIT' else amount(paid-refunded) if opening else unknown(),newReceipts=money(r),newRefunds=money(f),newNet=money(r-f),asRecordedAt=at)
    def entry_view(self,e,at):
        v=copy.deepcopy(e);v['asOf']=at;v['reversalEntryId']=next((x['id'] for x in self.entries if x['reversesEntryId']==e['id'] and x['recordedAt']<=at),None);v['effectiveAtAsOf']=v['kind']!='REVERSAL' and v['reversalEntryId'] is None;return v
    def commit(self,key,action,specs,clock):
        a=self.accounts[key];at=AT[clock];before=self.balance(key,at);op,rid,operation_key,editor=[str(uuid.uuid4()) for _ in range(4)];self.vars[self.token('hash'+str(clock))]='@b40_hash'+str(clock);self.vars[self.token('preview'+str(clock))]='@b40_preview'+str(clock)
        new=[]
        for ordinal,s in enumerate(specs,1):
            eid=self.token('entry'+str(len(self.entries)+len(new)+1));kind=s['kind'];fact=None if kind=='REVERSAL' or s.get('replacesEntryId') else ('OPENING:'+a['id']+':'+CUTOFF if kind=='OPENING' else str(uuid.uuid4()))
            new.append(dict(id=eid,accountId=a['id'],requestId=rid,ordinal=ordinal,kind=kind,amount=s['amount'],openingReceived=s.get('openingReceived'),openingRefunded=s.get('openingRefunded'),cutoffAt=CUTOFF if kind=='OPENING' else None,actualAt=at if kind in ('RECEIPT','REFUND') else None,methodCode='SYNTHETIC_CASH' if kind in ('RECEIPT','REFUND') else None,rootFactKey=fact,reversesEntryId=s.get('reversesEntryId'),replacesEntryId=s.get('replacesEntryId'),businessRef=dict(type='VERIFIED_OPENING' if kind=='OPENING' else 'LEDGER_CORRECTION' if action=='CORRECT' else 'COUNTER_REGISTRATION',id=operation_key),policyDigest=self.policy['digest'],evidenceRefs=EVIDENCE,reason='B40 synthetic verified registration',operatorId=self.token('admin'),recordedAt=at,allocations=s.get('allocations',[])))
        payload={}
        e=new[-1]
        if action=='OPENING':payload=dict(receivedTotal=e['openingReceived'],refundedTotal=e['openingRefunded'],cutoffAt=CUTOFF,sourceProofDigest=None)
        else:
            payload=dict(amount=e['amount'],actualAt=e['actualAt'],methodCode=e['methodCode'],paymentState='RECORDED',businessRef=dict(type='COUNTER_REGISTRATION',id=e['businessRef']['id']))
            if action=='RECEIPT':payload['factKey']=e['rootFactKey']
            elif action=='REFUND':payload.update(factKey=e['rootFactKey'],allocations=e['allocations'])
            else:payload=dict(originalEntryId=new[0]['reversesEntryId'],originalKind='REFUND',replacement={k:v for k,v in {**payload,'allocations':e['allocations']}.items() if k!='businessRef'})
        inp=dict(memberId=a['member'],memberRevision='0',financeRevision=str(a['revision']),fund=a['fund'],currency='CNY',reason='B40 synthetic verified registration',evidenceRefs=EVIDENCE,action=action,payload=payload)
        self.sql.append('SET @b40_hash'+str(clock)+'=SHA2('+self.quote(canonical(dict(schemaVersion=1,actorId=self.token('admin'),memberId=a['member'],operationKey=operation_key,canonicalInput=inp)))+',256)')
        reserve=self.token('reserve'+str(clock));self.sql.append('SET '+self.vars[reserve]+'=SHA2('+self.quote(canonical(dict(schemaVersion=1,operationKey=operation_key,memberId=a['member'],editorId=editor)))+',256)')
        def lot_allocated(entries):
            reversed={x['reversesEntryId'] for x in entries if x['kind']=='REVERSAL'};out={}
            for x in entries:
                if x['kind']=='REFUND' and x['id'] not in reversed:
                    for alloc in x['allocations']:out[alloc['lotEntryId']]=out.get(alloc['lotEntryId'],Decimal(0))+Decimal(alloc['amount'])
            return out
        old_allocated=lot_allocated(self.entries)
        self.entries+=new;a['revision']+=1;a['cutoff']=CUTOFF
        if action=='OPENING':a['opening']=e['id']
        after=self.balance(key,at);projected={k:v for k,v in after.items() if k not in ('accountId','openingEntryId','asRecordedAt')};projected['calculatedAt']=at
        proposed=[{k:v for k,v in x.items() if k in ('ordinal','kind','amount','openingReceived','openingRefunded','cutoffAt','actualAt','methodCode','reversesEntryId','replacesEntryId','allocations')} for x in new]
        new_allocated=lot_allocated(self.entries);lot_changes=[]
        for lot in self.entries:
            if lot['kind'] in ('OPENING','RECEIPT') and old_allocated.get(lot['id'],Decimal(0))!=new_allocated.get(lot['id'],Decimal(0)):
                previous=old_allocated.get(lot['id'],Decimal(0));current=new_allocated.get(lot['id'],Decimal(0));lot_changes.append(dict(lotEntryId=lot['id'],beforeRemaining=money(Decimal(lot['amount'])-previous),refundDelta=money(current-previous),afterRemaining=money(Decimal(lot['amount'])-current)))
        impact=dict(before=before,after=projected,entries=proposed,lotChanges=lot_changes,cashExecution='NONE',entitlementEffect='NONE')
        preview=dict(previewDigest=self.token('preview'+str(clock)),inputHash=self.token('hash'+str(clock)),identity=dict(memberId=a['member'],realName='合成验收',cardNo=None,accountStatus=1,memberRevision='0',role='READER',deleted=False),policy=self.policy,sourceProofDigest=None,impact=impact,eligibility=dict(applicability='NOT_APPLICABLE',unreturnedCount=None,anomalyCount=None,debtState='NOT_APPLICABLE',debtPolicyVersion=None,debtGuardVersion=None,coveredSources=[],asOf=None,items=[]),canConfirm=True,blocks=[])
        self.sql.append('SET @b40_preview'+str(clock)+'=SHA2('+self.quote(canonical({k:v for k,v in preview.items() if k!='previewDigest'}))+',256)')
        audit_id=self.token('audit'+str(clock));audit=dict(id=audit_id,requestId=rid,accountId=a['id'],operatorId=self.token('admin'),action=action,beforeRevision=before['financeRevision'],afterRevision=after['financeRevision'],before=before,after=after,reason=inp['reason'],recordedAt=at)
        receipt=dict(requestId=rid,operationKey=operation_key,memberId=a['member'],accountId=a['id'],fund=a['fund'],currency='CNY',action=action,confirmedAt=at,beforeRevision=before['financeRevision'],afterRevision=after['financeRevision'],entriesAtCommit=[self.entry_view(x,at) for x in new],audit=audit,impact=impact,previewDigest=preview['previewDigest'],policyDigest=self.policy['digest'],entitlementEffect='NONE')
        terminal=dict(state='COMMITTED',resolvedAt=at,reasonCode='COMMITTED',receipt=receipt)
        created=self.dt(at);expires=self.dt(at.replace('00:00:','00:10:'));draft_expires=self.dt(at.replace('2026-01-01','2026-01-02'))
        self.insert('member_finance_operation',dict(id=op,actor_id=self.token('admin'),user_id=a['member'],operation_key=operation_key,reserve_hash=reserve,editor_id=editor,editor_epoch=1,state='RESERVED',created_at=created,draft_expires_at=draft_expires,updated_at=created))
        self.sql.append('UPDATE member_finance_operation SET state=\'DRAFT_READY\',draft_hash='+self.quote(preview['inputHash'])+',draft_input_json='+self.json_sql(inp)+' WHERE id='+self.quote(op))
        self.insert('member_finance_request',dict(id=rid,account_id=a['id'],operation_id=op,actor_id=self.token('admin'),operation_key=operation_key,action=action,request_hash=preview['inputHash'],policy_digest=self.policy['digest'],preview_digest=preview['previewDigest'],input_json=j(inp),dependencies_json=j(dict(policy=self.policy,syntheticOnly=True)),preview_json=j(preview),state='PREVIEWED',created_at=created,expires_at=expires))
        self.sql.append('UPDATE member_finance_operation SET state=\'PREVIEWED\',draft_input_json=NULL,request_id='+self.quote(rid)+',preview_digest='+self.quote(preview['previewDigest'])+' WHERE id='+self.quote(op))
        for x in new:
            row=dict(account_id=x['accountId'],request_id=rid,ordinal=x['ordinal'],kind=x['kind'],amount=x['amount'],opening_received=x['openingReceived'],opening_refunded=x['openingRefunded'],cutoff_at=self.dt(x['cutoffAt']) if x['cutoffAt'] else None,actual_at=self.dt(x['actualAt']) if x['actualAt'] else None,method_code=x['methodCode'],root_fact_key=x['rootFactKey'],reverses_entry_id=x['reversesEntryId'],replaces_entry_id=x['replacesEntryId'],business_type=x['businessRef']['type'],business_key=x['businessRef']['id'],policy_digest=self.policy['digest'],evidence_json=j(EVIDENCE),reason=x['reason'],operator_id=self.token('admin'),recorded_at=created)
            entrykey=x['id'][6:-2];self.insert('member_finance_entry',row,entrykey)
            for alloc in x['allocations']:self.insert('member_finance_allocation',dict(refund_entry_id=x['id'],lot_entry_id=alloc['lotEntryId'],account_id=a['id'],amount=alloc['amount']))
        self.sql.append('UPDATE member_finance_account SET revision='+str(a['revision'])+',scope_cutoff_at='+self.quote(self.dt(CUTOFF))+',opening_entry_id='+self.quote(a['opening'])+' WHERE id='+self.quote(a['id']))
        self.insert('member_finance_audit',dict(request_id=rid,account_id=a['id'],operator_id=self.token('admin'),action=action,before_revision=int(before['financeRevision']),after_revision=a['revision'],before_json=j(before),after_json=j(after),reason=inp['reason'],recorded_at=created),'audit'+str(clock))
        result=self.json_sql(terminal)
        self.sql.append("UPDATE member_finance_request SET state='COMMITTED',result_json="+result+',resolved_at='+self.quote(created)+' WHERE id='+self.quote(rid))
        self.sql.append("UPDATE member_finance_operation SET state='COMMITTED',result_json="+result+',resolved_at='+self.quote(created)+' WHERE id='+self.quote(op))
        self.requests.append(dict(input=inp,preview=preview,terminal=terminal));self.audits.append(audit)
    def terminal_tombstone(self,state,clock):
        at=AT[clock];op,key,editor=[str(uuid.uuid4()) for _ in range(3)];terminal=dict(state=state,resolvedAt=at,reasonCode=state,receipt=None)
        created=at.replace('2026-01-01','2025-12-31') if state=='EXPIRED' else at
        expiry=at if state=='EXPIRED' else at.replace('2026-01-01','2026-01-02')
        reserve=self.token('terminalReserve'+str(clock));self.sql.append('SET '+self.vars[reserve]+'=SHA2('+self.quote(canonical(dict(schemaVersion=1,operationKey=key,memberId=self.token('empty'),editorId=editor)))+',256)')
        row=dict(id=op,actor_id=self.token('admin'),user_id=self.token('empty'),operation_key=key,reserve_hash=reserve,editor_id=editor,editor_epoch=1,state=state,result_json=j(terminal),created_at=self.dt(created),draft_expires_at=self.dt(expiry),updated_at=self.dt(at),resolved_at=self.dt(at))
        if state=='CANCELLED':
            cancel_key=str(uuid.uuid4());body=dict(schemaVersion=1,operationKey=key,memberId=self.token('empty'),editor=dict(editorId=editor,epoch='1'),cancelKey=cancel_key,previewDigest=None,reason='USER_CANCELLED');cancelhash=self.token('cancelhash'+str(clock));self.sql.append('SET '+self.vars[cancelhash]+'=SHA2('+self.quote(canonical(body))+',256)');cancel=dict(operationKey=key,memberId=self.token('empty'),cancelKey=cancel_key,cancelHash=cancelhash,previewDigest=None,reason='USER_CANCELLED',outcome='CANCELLED',state='CANCELLED',resolvedAt=at,requestId=None,receipt=None)
            row.update(cancel_key=cancel_key,cancel_hash=cancelhash,cancel_input_json=j(body),cancel_result_json=j(cancel))
        self.insert('member_finance_operation',row);self.operations.append(terminal)
    def model(self):return dict(schemaVersion=1,syntheticOnly=True,marker=self.marker,ids={k:self.token(k) for k in ('admin','clerk','reader','member','unknown','empty','revoked','archive')},accounts=self.accounts,entries=self.entries,requests=self.requests,audits=self.audits,terminals=self.operations,archiveRaw=self.archive_raw,sourceUid=self.source_uid,policy=self.policy)

def validate_model(m):
    for x in m['requests']:
        check_definition('CanonicalFinancialInput',x['input']);check_definition('Preview',x['preview']);check_definition('Terminal',x['terminal'])
        require(x['terminal']['receipt']['audit']['afterRevision']==str(int(x['terminal']['receipt']['audit']['beforeRevision'])+1))
    for x in m['entries']:
        view=entry_at(m,x,AT[-1]);check_definition('Entry',view)
        require(Decimal(x['amount'])>=0 and Decimal(x['amount'])==Decimal(x['amount']).quantize(Decimal('.01')))
        if x['kind']=='REFUND':require(sum(Decimal(a['amount']) for a in x['allocations'])==Decimal(x['amount']))
        if x['kind']=='OPENING':require(Decimal(x['openingReceived'])-Decimal(x['openingRefunded'])==Decimal(x['amount']))
    for x in m['terminals']:check_definition('Terminal',x)
    require(len({e['id'] for e in m['entries']})==len(m['entries']) and len({r['terminal']['receipt']['requestId'] for r in m['requests']})==len(m['requests']))
    for account in m['accounts'].values():
        rows=[e for e in m['entries'] if e['accountId']==account['id']];reversed={e['reversesEntryId'] for e in rows if e['kind']=='REVERSAL'}
        require(account['revision']==sum(r['terminal']['receipt']['accountId']==account['id'] for r in m['requests']))
        for e in rows:
            if e['kind'] in ('RECEIPT','REFUND'):require(e['actualAt']>=account['cutoff'] and e['methodCode'] is not None)
            if e['kind']=='REVERSAL':
                original=next(x for x in rows if x['id']==e['reversesEntryId']);require(original['kind']!='REVERSAL' and e['amount']==original['amount'] and int(original['id'])<int(e['id']))
            if e['replacesEntryId']:
                original=next(x for x in rows if x['id']==e['replacesEntryId']);require(original['kind']==e['kind'] and original['id'] in reversed)
        for lot in rows:
            if lot['kind'] in ('OPENING','RECEIPT'):
                allocated=sum((Decimal(a['amount']) for e in rows if e['kind']=='REFUND' and e['id'] not in reversed for a in e['allocations'] if a['lotEntryId']==lot['id']),Decimal(0));require(allocated<=Decimal(lot['amount']))
        for e in rows:
            for a in e['allocations']:require(any(x['id']==a['lotEntryId'] and x['kind'] in ('OPENING','RECEIPT') and x['id'] not in reversed for x in rows))
def entry_at(m,e,at):
    v=copy.deepcopy(e);v['asOf']=at;v['reversalEntryId']=next((x['id'] for x in m['entries'] if x['reversesEntryId']==e['id'] and x['recordedAt']<=at),None);v['effectiveAtAsOf']=e['kind']!='REVERSAL' and v['reversalEntryId'] is None;return v
def model_balance(m,member,fund,at):
    a=next((a for a in m['accounts'].values() if a['member']==member and a['fund']==fund),None)
    if a is None:return dict(memberId=member,accountId=None,fund=fund,currency='CNY',financeRevision='0',scopeCutoffAt=None,openingEntryId=None,openingState='UNKNOWN',verifiedPaid=unknown(),verifiedRefunded=unknown(),balance=unknown(),feeHeadroom=None if fund=='DEPOSIT' else unknown(),newReceipts='0.00',newRefunds='0.00',newNet='0.00',asRecordedAt=at)
    fixture=object.__new__(Fixture);fixture.accounts={'a':a};fixture.entries=m['entries'];return fixture.balance('a',at)
def expected_source(m,member,at):
    profile=dict(profilePresent=member==m['ids']['member'],sourceKey=None,sourceSystem=None,sourceArchiveId=None,legacyPaidFee=None,legacyDepositAmount=None,legacyDepositStatus=None)
    fields=[dict(key=k,presence='MISSING',jsonType='MISSING',reasonCode='SOURCE_UNAVAILABLE',rawText=None) for k in ['实际缴纳费用','实际缴纳押金','缴费状态','押金缴纳状态','押金']]
    if not profile['profilePresent']:return dict(memberId=member,sourceState='NO_PROFILE',reasonCodes=['SOURCE_UNAVAILABLE'],profile=profile,archive=None,fields=fields,proofDigest=None,asOf=at)
    profile.update(sourceKey='huiben:'+m['sourceUid'],sourceArchiveId=m['ids']['archive'],legacyPaidFee='77.00',legacyDepositStatus='原值待核实')
    specs=[('VALUE','STRING','SOURCE_UNVERIFIED_VALUE','001.230'),('VALUE','NUMBER','SOURCE_UNVERIFIED_VALUE','100.0'),('EMPTY','STRING','SOURCE_FIELD_EMPTY',''),('NULL','NULL','SOURCE_FIELD_NULL',None),('VALUE','STRING','LEGACY_DEPOSIT_SEMANTICS_UNCONFIRMED','原押金字段待核实')]
    fields=[dict(key=f['key'],presence=p,jsonType=t,reasonCode=r,rawText=raw) for f,(p,t,r,raw) in zip(fields,specs)]
    archive=dict(archiveId=m['ids']['archive'],sourceSystem='huiben',recordType='MEMBER',sourceKey=m['sourceUid'],memberId=member,archiveHash=sha(m['archiveRaw'].encode()))
    proof=dict(profile=profile,legacyScalars=dict(sourceArchiveId=m['ids']['archive'],paidFee='77.00',depositAmount=None,depositStatus='原值待核实'),archive=archive,fields=fields)
    return dict(memberId=member,sourceState='VERIFIED_LINK',reasonCodes=[],profile=profile,archive=archive,fields=fields,proofDigest=sha(j(proof).encode()),asOf=at)
def read_snapshots(c,m,previous=None):
    snapshots={};admin=m['ids']['admin']
    for key in ('member','unknown','empty'):
        member=m['ids'][key];base='/api/members/'+member+'/finance';f=c.get(base,'FinanceEnvelope',admin);at=f['asRecordedAt'];expected=dict(memberId=member,memberRevision='0',currency='CNY',accounts=[model_balance(m,member,fund,at) for fund in ('MEMBER_FEE','DEPOSIT')],asRecordedAt=at,historicalCoverage='COMPLETE' if key=='member' else 'UNKNOWN',source='MEMBER_FINANCE_LEDGER');require(f==expected,'B40 independent finance projection differs')
        stable=copy.deepcopy(f);stable.pop('asRecordedAt');[x.pop('asRecordedAt') for x in stable['accounts']];snapshots[key+'.finance']=stable
        s=c.get(base+'/source','SourceEnvelope',admin);require(s==expected_source(m,member,s['asOf']),'B40 independent source projection differs');stable=copy.deepcopy(s);stable.pop('asOf');snapshots[key+'.source']=stable
        for fund in ('MEMBER_FEE','DEPOSIT'):
            for timekey,clock in [('current',None),('beforeCorrection',AT[2])]:
                name=key+'.'+fund+'.'+timekey;frozen=previous.get(name+'.asOf') if previous else None;asof=frozen or clock;cursor=None;collected=[];pages=[]
                while True:
                    q=dict(fund=fund,currency='CNY',size='2')
                    if asof:q['asRecordedAt']=asof
                    if cursor:q['beforeId']=cursor
                    v=c.get(base+'/entries?'+urllib.parse.urlencode(q),'EntriesEnvelope',admin);asof=v['asRecordedAt'];require(cursor is None or v['beforeId']==cursor)
                    a=next((a for a in m['accounts'].values() if a['member']==member and a['fund']==fund),None);expected_entries=[entry_at(m,x,asof) for x in m['entries'] if a and x['accountId']==a['id'] and x['recordedAt']<=asof and (cursor is None or int(x['id'])<int(cursor))];expected_entries.sort(key=lambda x:int(x['id']),reverse=True);page=expected_entries[:2];more=len(expected_entries)>2
                    require(v==dict(memberId=member,fund=fund,currency='CNY',asRecordedAt=asof,beforeId=cursor,nextBeforeId=page[-1]['id'] if more else None,size=2,hasMore=more,entries=page),'B40 historical keyset page differs')
                    pages.append(v);collected+=v['entries']
                    if not more:break
                    cursor=v['nextBeforeId'];require(len(pages)<100,'Unexpected finance pagination loop')
                snapshots[name]=pages;snapshots[name+'.asOf']=asof
    for suffix in ('','/source','/entries?fund=MEMBER_FEE&currency=CNY'):
        route='/api/members/'+m['ids']['member']+'/finance'+suffix
        c.get(route,'ErrorEnvelope',None,status=401,reason='UNAUTHENTICATED')
        for key,role in [('clerk','LIBRARIAN'),('reader','READER'),('revoked','ADMIN')]:c.get(route,'ErrorEnvelope',m['ids'][key],role,status=403,reason='ADMIN_REQUIRED')
    base='/api/members/'+m['ids']['member']+'/finance'
    for query,reason in [('size=01','INVALID_INPUT'),('fund=MEMBER_FEE&currency=CNY&size=1&size=2','DUPLICATE_FIELD'),('fund=MEMBER_FEE&currency=CNY&unknown=1','UNKNOWN_FIELD'),('fund=MEMBER_FEE&currency=CNY&beforeId='+m['entries'][0]['id'],'BAD_CURSOR'),('fund=DEPOSIT&currency=CNY&beforeId='+m['entries'][0]['id']+'&asRecordedAt='+AT[8],'BAD_CURSOR')]:c.get(base+'/entries?'+query,'ErrorEnvelope',admin,status=400,reason=reason)
    if previous:require(snapshots==previous,'B40 original GET snapshots changed')
    return snapshots
def snapshot_sql(m):
    for table,columns in COLUMNS.items():
        cells=','.join('IF(`'+col+'` IS NULL,NULL,HEX(CAST(`'+col+'` AS BINARY)))' for col in columns)
        order='refund_entry_id,lot_entry_id' if table=='member_finance_allocation' else 'id'
        print("SELECT '"+table+"',SHA2(CAST(JSON_ARRAY("+cells+") AS CHAR),256) FROM `"+table+'` ORDER BY '+order+';')
    for tag,table,cols,where in [('b40-source-profile','member_profile','user_id source_key source_system source_archive_id paid_fee deposit_amount deposit_status', 'user_id='+m['ids']['member']),('b40-source-archive','legacy_record','id source_system record_type source_key user_id details_json','id='+m['ids']['archive'])]:
        cells=','.join('IF(`'+col+'` IS NULL,NULL,HEX(CAST(`'+col+'` AS BINARY)))' for col in cols.split());print("SELECT '"+tag+"',SHA2(CAST(JSON_ARRAY("+cells+") AS CHAR),256) FROM `"+table+'` WHERE '+where+';')
def main(argv=None):
    p=argparse.ArgumentParser();p.add_argument('--base',required=True);p.add_argument('--env',required=True);p.add_argument('--state',required=True);p.add_argument('--mode',choices=['seed-sql','bind','capture','check','snapshot-sql'],required=True);p.add_argument('--bindings');a=p.parse_args(argv)
    env=dict(line.split('=',1) for line in Path(a.env).read_text().splitlines() if line and not line.startswith('#') and '=' in line);guard(a.base,env,a.mode);state=Path(a.state)
    if a.mode=='seed-sql':
        require(not state.exists() and not state.is_symlink(),'Never replace B40 original state');f=Fixture();model=f.model();state.write_text(j(model),encoding='utf-8');print(';\n'.join(f.sql)+';');return 0
    m=json.loads(state.read_bytes())
    if a.mode=='bind':
        require('snapshots' not in m and '__B40_' in j(m),'Do not rebind original finance state');bindings=json.loads(Path(a.bindings).read_text().strip());require(type(bindings) is dict and all(v is not None for v in bindings.values()));m=replace_tokens(m,bindings);validate_model(m);require('__B40_' not in j(m));state.write_text(j(m),encoding='utf-8');return 0
    if a.mode=='snapshot-sql':snapshot_sql(m);return 0
    validate_model(m);client=Client(a.base,env)
    if a.mode=='capture':require('snapshots' not in m,'Never refresh B40 original GET snapshots');m['snapshots']=read_snapshots(client,m);state.write_text(j(m),encoding='utf-8')
    else:read_snapshots(client,m,m['snapshots'])
    print('B40 six-table synthetic ledger and three read-only projections verified')
    return 0
if __name__=='__main__':
    try:raise SystemExit(main())
    except Exception:raise SystemExit('B40 isolated finance acceptance failed; private evidence retained')
B40_PY
library_finance_acceptance() {
    LIBRARY_ACCEPTANCE=1 python3 "$library_test/container_finance_acceptance.py" --base "$1" --env "$2" --state "$library_test/finance-state.json" --mode "$3" "${@:4}"
}
library_finance_owner() {
    local library_finance_project="$1" library_finance_port="$2" library_finance_env="$3" library_finance_action="$4" library_finance_service library_finance_container
    [[ -d "$library_test" && ! -L "$library_test" && "$(realpath "$library_test")" == "$library_test" ]] || return 1
    if [[ "$library_finance_action" == seed ]]; then
        [[ "$library_finance_project" == library && "$library_finance_port" == 18089 && "$library_finance_env" == "$library_test/install/.env" ]] || return 1
        [[ "$(readlink -f "$library_test/install/current")" == "$library_release" ]] || return 1
        [[ "$library_release" == "$library_test/install/releases/"* && -f "$library_release/compose.yaml" ]] || return 1
        [[ ! -e "$library_test/finance-state.json" && ! -e "$library_test/finance-before.tsv" ]] || return 1
    else
        [[ "$library_finance_action" == check ]] || return 1
        [[ "$library_finance_project:$library_finance_port:$library_finance_env" == "library:18089:$library_test/install/.env" || "$library_finance_project:$library_finance_port:$library_finance_env" == "library-recovery:18090:$library_test/recovery/.env" ]] || return 1
    fi
    for library_finance_service in mysql backend frontend; do
        library_finance_container="$library_finance_project-$library_finance_service-1"
        [[ "$(docker inspect --format '{{.Name}}|{{ index .Config.Labels "com.docker.compose.project" }}|{{ index .Config.Labels "com.docker.compose.service" }}|{{.State.Running}}' "$library_finance_container")" == "/$library_finance_container|$library_finance_project|$library_finance_service|true" ]] || return 1
        [[ "$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' "$library_finance_container")" == "$library_test/install/releases/"* ]] || return 1
    done
    [[ "$(docker inspect --format '{{(index (index .NetworkSettings.Ports "80/tcp") 0).HostIp}}|{{(index (index .NetworkSettings.Ports "80/tcp") 0).HostPort}}' "$library_finance_project-frontend-1")" == "127.0.0.1|$library_finance_port" ]] || return 1
}
library_finance_snapshot() {
    local library_finance_project="$1" library_finance_tag="$2" library_finance_env library_finance_port
    if [[ "$library_finance_project" == library ]]; then library_finance_env="$library_test/install/.env"; library_finance_port=18089; else library_finance_env="$library_test/recovery/.env"; library_finance_port=18090; fi
    library_finance_owner "$library_finance_project" "$library_finance_port" "$library_finance_env" check
    docker exec -i "$library_finance_project-mysql-1" sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names "$MYSQL_DATABASE"' < "$library_test/finance-persistence.sql" > "$library_test/finance-$library_finance_tag.tsv"
    if [[ "$library_finance_tag" != before ]]; then cmp --silent "$library_test/finance-before.tsv" "$library_test/finance-$library_finance_tag.tsv"; fi
}
library_finance_owner library 18089 "$library_test/install/.env" seed
[[ "$(docker exec -i library-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --batch --skip-column-names "$MYSQL_DATABASE" -e "SELECT (SELECT COUNT(*) FROM member_finance_operation)+(SELECT COUNT(*) FROM member_finance_account)+(SELECT COUNT(*) FROM member_finance_request)+(SELECT COUNT(*) FROM member_finance_entry)+(SELECT COUNT(*) FROM member_finance_allocation)+(SELECT COUNT(*) FROM member_finance_audit)"')" == 0 ]]
library_finance_acceptance http://localhost:18089 "$library_test/install/.env" seed-sql > "$library_test/finance-seed.sql"
docker exec -i library-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names --skip-reconnect "$MYSQL_DATABASE"' < "$library_test/finance-seed.sql" > "$library_test/finance-bindings.json" 2> "$library_test/finance-seed-private.log"
library_finance_acceptance http://localhost:18089 "$library_test/install/.env" bind --bindings "$library_test/finance-bindings.json"
# BEGIN B40-B RECEIPTS SEED
# After original finance-bind and before the original all-row before snapshot.
library_receipts_acceptance() {
    local library_receipts_base="$1" library_receipts_env="$2" library_receipts_mode="$3"
    LIBRARY_ACCEPTANCE=1 python3 "$library_release/scripts/container_finance_receipts.py" \
        --base "$library_receipts_base" --env "$library_receipts_env" --root "$library_test" \
        --release "$library_release" --state "$library_test/receipts-state.json" \
        --legacy-helper "$library_test/container_finance_acceptance.py" --mode "$library_receipts_mode" "${@:4}"
}
library_receipts_snapshot() {
    local library_receipts_project="$1" library_receipts_tag="$2" library_receipts_env library_receipts_port
    if [[ "$library_receipts_project" == library ]]; then
        library_receipts_env="$library_test/install/.env"; library_receipts_port=18089
    else
        [[ "$library_receipts_project" == library-recovery ]] || return 1
        library_receipts_env="$library_test/recovery/.env"; library_receipts_port=18090
    fi
    [[ "$library_receipts_tag" =~ ^(before|install|recreate|update|failed_update|restore)$ ]] || return 1
    [[ ! -e "$library_test/receipts-$library_receipts_tag.tsv" ]] || return 1
    library_receipts_acceptance "http://localhost:$library_receipts_port" "$library_receipts_env" guard
    docker exec -i "$library_receipts_project-mysql-1" sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names --skip-reconnect "$MYSQL_DATABASE"' \
        < "$library_test/receipts-persistence.sql" > "$library_test/receipts-$library_receipts_tag.tsv"
    library_receipts_acceptance "http://localhost:$library_receipts_port" "$library_receipts_env" verify-snapshot --snapshot "$library_test/receipts-$library_receipts_tag.tsv"
    if [[ "$library_receipts_tag" != before ]]; then
        cmp --silent "$library_test/receipts-before.tsv" "$library_test/receipts-$library_receipts_tag.tsv"
    fi
}
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" guard
[[ "$(docker exec -i library-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --batch --skip-column-names "$MYSQL_DATABASE" -e "SELECT COUNT(*) FROM member_finance_activation"')" == 0 ]]
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" activation-sql > "$library_test/receipts-activation.sql"
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" guard
docker exec -i library-mysql-1 sh -c 'MYSQL_PWD="$MYSQL_MIGRATION_PASSWORD" exec mysql -u library_migrator --default-character-set=utf8mb4 --batch --raw --skip-column-names --skip-reconnect "$MYSQL_DATABASE"' \
    < "$library_test/receipts-activation.sql" > "$library_test/receipts-activation.json"
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" seed --activation "$library_test/receipts-activation.json"
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" snapshot-sql > "$library_test/receipts-persistence.sql"
library_receipts_snapshot library before
# END B40-B RECEIPTS SEED
library_finance_acceptance http://localhost:18089 "$library_test/install/.env" snapshot-sql > "$library_test/finance-persistence.sql"
library_finance_snapshot library before
python3 - "$library_test/finance-before.tsv" <<'B40_NONEMPTY_PY'
import re,sys
from pathlib import Path
rows=[line.split('\t') for line in Path(sys.argv[1]).read_text().splitlines()]
required=set('member_finance_operation member_finance_account member_finance_request member_finance_entry member_finance_allocation member_finance_audit b40-source-profile b40-source-archive'.split())
assert rows and all(len(row)==2 and row[0] in required and re.fullmatch('[0-9a-f]{64}',row[1]) for row in rows)
assert {row[0] for row in rows}==required,'Every B40 table must contain original nonempty fixture rows'
B40_NONEMPTY_PY
library_finance_acceptance http://localhost:18089 "$library_test/install/.env" capture
library_finance_snapshot library after_get
# END B40 FINANCE SEED
# BEGIN B40-B RECEIPTS INSTALL
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" check
library_receipts_snapshot library install
# END B40-B RECEIPTS INSTALL
# BEGIN B50-A CIRCULATION INSTALL
library_circulation_acceptance http://localhost:18089 "$library_test/install/.env" check
library_circulation_snapshot library install
# END B50-A CIRCULATION INSTALL
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
# BEGIN B36 PUBLICATION RECREATE
library_publication_acceptance http://localhost:18089 "$library_test/install/.env" check
library_publication_snapshot library recreate
# END B36 PUBLICATION RECREATE
# BEGIN B34 SETS RECREATE
library_set_acceptance http://localhost:18089 "$library_test/install/.env" check
library_set_snapshot library recreate
# END B34 SETS RECREATE
# BEGIN B40 FINANCE RECREATE
library_finance_owner library 18089 "$library_test/install/.env" check
library_finance_acceptance http://localhost:18089 "$library_test/install/.env" check
library_finance_snapshot library recreate
# END B40 FINANCE RECREATE
# BEGIN B40-B RECEIPTS RECREATE
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" check
library_receipts_snapshot library recreate
# END B40-B RECEIPTS RECREATE
# BEGIN B50-A CIRCULATION RECREATE
library_circulation_acceptance http://localhost:18089 "$library_test/install/.env" check
library_circulation_snapshot library recreate
# END B50-A CIRCULATION RECREATE
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
# BEGIN B36 PUBLICATION UPDATE
library_publication_acceptance http://localhost:18089 "$library_test/install/.env" check
library_publication_snapshot library update
# END B36 PUBLICATION UPDATE
# BEGIN B34 SETS UPDATE
library_set_acceptance http://localhost:18089 "$library_test/install/.env" check
library_set_snapshot library update
# END B34 SETS UPDATE
# BEGIN B40 FINANCE UPDATE
library_finance_owner library 18089 "$library_test/install/.env" check
library_finance_acceptance http://localhost:18089 "$library_test/install/.env" check
library_finance_snapshot library update
# END B40 FINANCE UPDATE
# BEGIN B40-B RECEIPTS UPDATE
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" check
library_receipts_snapshot library update
# END B40-B RECEIPTS UPDATE
# BEGIN B50-A CIRCULATION UPDATE
library_circulation_acceptance http://localhost:18089 "$library_test/install/.env" check
library_circulation_snapshot library update
# END B50-A CIRCULATION UPDATE
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
# BEGIN B36 PUBLICATION FAILED_UPDATE
library_publication_acceptance http://localhost:18089 "$library_test/install/.env" check
library_publication_snapshot library failed_update
# END B36 PUBLICATION FAILED_UPDATE
# BEGIN B34 SETS FAILED_UPDATE
library_set_acceptance http://localhost:18089 "$library_test/install/.env" check
library_set_snapshot library failed_update
# END B34 SETS FAILED_UPDATE
# BEGIN B40 FINANCE FAILED_UPDATE
library_finance_owner library 18089 "$library_test/install/.env" check
library_finance_acceptance http://localhost:18089 "$library_test/install/.env" check
library_finance_snapshot library failed_update
# END B40 FINANCE FAILED_UPDATE
# BEGIN B40-B RECEIPTS FAILED_UPDATE
library_receipts_acceptance http://localhost:18089 "$library_test/install/.env" check
library_receipts_snapshot library failed_update
# END B40-B RECEIPTS FAILED_UPDATE
# BEGIN B50-A CIRCULATION FAILED_UPDATE
library_circulation_acceptance http://localhost:18089 "$library_test/install/.env" check
library_circulation_snapshot library failed_update
# END B50-A CIRCULATION FAILED_UPDATE
echo 'Failed-start rollback restored previous application and data'
# Synthetic attachment metadata includes a root-owned 0600 file, distinct
# directory ownership/mode and nanosecond mtimes. The existing MySQL image's
# GNU tar preserves PAX times; the backend tar writer rounds to seconds.
library_upload_backend="$(docker ps -q --filter label=com.docker.compose.project=library --filter label=com.docker.compose.service=backend)"
[[ "$library_upload_backend" =~ ^[a-f0-9]{12,64}$ ]]
library_upload_tar_image="$(docker inspect library-mysql-1 --format '{{.Image}}')"
python3 - <<'UPLOAD_SEED' | docker run --rm -i --network none --read-only --user 0:0 --mount type=volume,src=library_uploads,dst=/app/uploads --entrypoint tar "$library_upload_tar_image" -xpf - --same-owner --delay-directory-restore -C /app
import io, sys, tarfile
with tarfile.open(fileobj=sys.stdout.buffer, mode='w|', format=tarfile.PAX_FORMAT) as archive:
    directory = tarfile.TarInfo('uploads/metadata-acceptance'); directory.type = tarfile.DIRTYPE
    directory.mode = 0o750; directory.pax_headers = {'mtime': '1700000002.234567891'}
    archive.addfile(directory)
    item = tarfile.TarInfo('uploads/metadata-acceptance/root.txt'); raw = b'synthetic root-only bytes\n'
    item.mode = 0o600; item.size = len(raw); item.pax_headers = {'mtime': '1700000003.345678912'}
    archive.addfile(item, io.BytesIO(raw))
UPLOAD_SEED
python3 "$library_release/scripts/restore_uploads.py" --capture "$library_test/uploads-before.tar.gz" -- docker compose -p library --project-directory "$library_release" --env-file "$library_test/install/.env" -f "$library_release/compose.yaml"
python3 - "$library_release/scripts" "$library_test/uploads-before.tar.gz" <<'UPLOAD_ASSERT'
import sys
from decimal import Decimal
sys.path.insert(0, sys.argv[1])
from restore_uploads import inventory
items = inventory(sys.argv[2])
directory, item = (items['uploads/metadata-acceptance'], items['uploads/metadata-acceptance/root.txt'])
assert (directory['uid'], directory['gid'], directory['mode'], directory['mtime']) == (0, 0, 0o750, Decimal('1700000002.234567891'))
assert (item['uid'], item['gid'], item['mode'], item['mtime']) == (0, 0, 0o600, Decimal('1700000003.345678912'))
UPLOAD_ASSERT
APP_VERSION="$(basename "$library_release")" bash "$library_release/scripts/backup.sh" "$library_test/backup"
python3 "$library_release/scripts/restore_uploads.py" --compare "$library_test/uploads-before.tar.gz" "$library_test/backup/uploads.tar.gz"
sed -e 's/18089/18090/g' -e 's/172.30.89/172.30.90/g' "$library_test/install/.env" > "$library_test/recovery/.env"
library_recovery_release="$library_release"
LIBRARY_PROJECT_NAME=library-recovery LIBRARY_ENV_FILE="$library_test/recovery/.env" bash "$library_release/scripts/restore.sh" "$library_test/backup"
library_verify_backend "$library_recovery_release" library-recovery
library_upload_recovery="$(docker ps -q --filter label=com.docker.compose.project=library-recovery --filter label=com.docker.compose.service=backend)"
[[ "$library_upload_recovery" =~ ^[a-f0-9]{12,64}$ ]]
python3 "$library_release/scripts/restore_uploads.py" --capture "$library_test/uploads-restored.tar.gz" -- docker compose -p library-recovery --project-directory "$library_release" --env-file "$library_test/recovery/.env" -f "$library_release/compose.yaml"
python3 "$library_release/scripts/restore_uploads.py" --compare "$library_test/uploads-before.tar.gz" "$library_test/uploads-restored.tar.gz"
python3 "$library_release/scripts/container_smoke.py" --base http://localhost:18090 --env "$library_test/recovery/.env" --state "$library_test/state.json" --check-state --complete-recovered-link
# BEGIN B33-A ATTRIBUTES RESTORE
library_attributes_acceptance http://localhost:18090 "$library_test/recovery/.env" check
# END B33-A ATTRIBUTES RESTORE
# BEGIN B35 IDENTITY RESTORE
library_identity_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_identity_snapshot library-recovery restore
# END B35 IDENTITY RESTORE
# BEGIN B36 PUBLICATION RESTORE
library_publication_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_publication_snapshot library-recovery restore
# END B36 PUBLICATION RESTORE
# BEGIN B34 SETS RESTORE
library_set_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_set_snapshot library-recovery restore
# END B34 SETS RESTORE
# BEGIN B40 FINANCE RESTORE
library_finance_owner library-recovery 18090 "$library_test/recovery/.env" check
library_finance_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_finance_snapshot library-recovery restore
# END B40 FINANCE RESTORE
# BEGIN B40-B RECEIPTS RESTORE
library_receipts_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_receipts_snapshot library-recovery restore
# END B40-B RECEIPTS RESTORE
# BEGIN B50-A CIRCULATION RESTORE
library_circulation_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_circulation_snapshot library-recovery restore
# END B50-A CIRCULATION RESTORE
echo 'First install, recreate, update, failed-start rollback, backup and isolated restore passed'
