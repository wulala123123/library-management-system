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
# BEGIN B36 PUBLICATION RESTORE
library_publication_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_publication_snapshot library-recovery restore
# END B36 PUBLICATION RESTORE
# BEGIN B34 SETS RESTORE
library_set_acceptance http://localhost:18090 "$library_test/recovery/.env" check
library_set_snapshot library-recovery restore
# END B34 SETS RESTORE
echo 'First install, recreate, update, failed-start rollback, backup and isolated restore passed'
