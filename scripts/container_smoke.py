"""HTTP smoke test against an isolated Compose deployment; never prints credentials."""
import argparse, base64, hashlib, hmac, json, re, time, uuid, urllib.request, urllib.error, urllib.parse
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--base',required=True)
p.add_argument('--env',required=True)
p.add_argument('--state',required=True)
p.add_argument('--check-state',action='store_true')
p.add_argument('--complete-recovered-link',action='store_true')
args=p.parse_args()
assert not args.complete_recovered_link or args.check_state
env=dict(line.split('=',1) for line in Path(args.env).read_text().splitlines() if '=' in line and not line.startswith('#'))
base_url=urllib.parse.urlsplit(args.base)
assert (base_url.scheme=='http' and base_url.hostname in ('localhost','127.0.0.1')
    and base_url.port in (18089,18090) and not base_url.username and not base_url.password
    and base_url.path in ('','/') and not base_url.query and not base_url.fragment
    and env.get('FRONTEND_PORT')==str(base_url.port)
    and env.get('APP_NETWORK_SUBNET')==('172.30.89.0/24' if base_url.port==18089 else '172.30.90.0/24')), \
    'Container fixture writes require the dedicated isolated acceptance deployment'
def enc(s):return base64.urlsafe_b64encode(s).rstrip(b'=')
payload={'sub':'1','role':'ADMIN','version':0,'iat':int(time.time()),'exp':int(time.time())+3600}
unsigned=enc(b'{"alg":"HS256","typ":"JWT"}')+b'.'+enc(json.dumps(payload).encode())
token=(unsigned+b'.'+enc(hmac.new(env['JWT_SECRET'].encode(),unsigned,hashlib.sha256).digest())).decode()
def request(path, method='GET', data=None, raw=False, content_type='application/json', authenticated=True):
    headers={'Content-Type':content_type}
    if authenticated:headers['Authorization']='Bearer '+token
    if data is not None and not isinstance(data,bytes):data=json.dumps(data).encode()
    req=urllib.request.Request(args.base+path,data,headers,method=method)
    with urllib.request.urlopen(req,timeout=120) as response: body=response.read()
    if raw:return body
    value=json.loads(body)
    if 'code' in value:assert value['code']==200,(path,value);return value.get('data')
    return value
def upload(path, filename, content, mime):
    boundary='repair-smoke-boundary'
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: {mime}\r\n\r\n').encode()+content+f'\r\n--{boundary}--\r\n'.encode()
    return request(path,'POST',body,content_type='multipart/form-data; boundary='+boundary)

def reject(path, body, reason):
    try:request(path,'POST',body)
    except urllib.error.HTTPError as error:
        value=json.loads(error.read())
        assert error.code==409 and value['code']==409 and value['data']['reasonCode']==reason
    else:raise AssertionError('Expected recoverable rejection: '+reason)

def copy_action(copy_id, action, schema_version=1, **changes):
    context=request('/api/book-copies/'+str(copy_id)+'/action-context')
    item={'copyId':copy_id,'expectedVersion':context['copy']['actionVersion'],**changes}
    key=str(uuid.uuid4())
    body={'idempotencyKey':key,'action':action,'reason':'隔离容器逐册动作验收','copies':[item]}
    if schema_version==2:body['schemaVersion']=2
    view=request('/api/book-copy-actions/'+('v2/' if schema_version==2 else '')+'previews','POST',body)
    confirmation={'idempotencyKey':key,'previewDigest':view['previewDigest']}
    path='/api/book-copy-actions/'+str(view['requestId'])+'/confirm'
    receipt=request(path,'POST',confirmation)
    assert request(path,'POST',confirmation)==receipt
    after=request('/api/book-copies/'+str(copy_id)+'/action-context')
    changed=receipt['copies'][0]
    assert after['copy']['actionVersion']==context['copy']['actionVersion']+1==changed['afterVersion']
    assert after['copy']['status']==changed['afterStatus']
    assert after['copy']['location']==changed['afterLocation']
    assert receipt['schemaVersion']==schema_version
    if schema_version==2:
        assert after['copy']['placement']==changed['afterPlacement']['placement']
        assert after['copy']['lastLocationActionId']==changed['afterLocationActionId']
        assert not after['copy']['locationReferenceAnomaly']
    projection=after['reconciliationV2']
    assert projection['schema']=='copy-lifecycle-v1'
    assert not projection['hasHistoricalDifference'] and not projection['hasUnknownStatus']
    assert not projection['hasLoanLinkAnomaly']
    assert request('/api/book-copies/'+str(copy_id)+'/actions')['records'][0]['eventId']==changed['eventId']
    return receipt

def stocktake_write(path, body, operations):
    """Save exact successful writes so all lifecycle checks replay their keys."""
    body={'operationKey':str(uuid.uuid4()),**body}
    result=request(path,'POST',body)
    assert request(path,'POST',body)==result
    assert result['schemaVersion']==1 and result['operationKey']==body['operationKey']
    operations.append({'path':path,'body':body,'receipt':result})
    return result['result']

def stocktake_seed(copy_ids, intake_receipt, actions):
    # Only this isolated deployment contains the generated books/copies. A stocktake
    # records observations; the ordinary MOVE below is a separate business action.
    operations=[]
    reason='隔离容器盘点持久化验收'
    scope={'kind':'COPY_SET','copyIds':copy_ids}
    books_before={item['bookId']:request('/api/books/'+str(item['bookId'])) for item in intake_receipt['copies']}
    def create(label):
        return stocktake_write('/api/stocktakes',{'scope':scope,'campaignKey':label,'reason':reason},operations)['session']
    def mutate(session, suffix, **body):
        return stocktake_write('/api/stocktakes/'+str(session['sessionId'])+'/'+suffix,
            {'expectedRevision':session['revision'],'reason':reason,**body},operations)
    draft=create('container-persisted-draft')
    draft=mutate(draft,'observations',events=[{
        'clientEventKey':str(uuid.uuid4()),'rawBarcode':intake_receipt['copies'][0]['barcode'],
        'observedPlacement':{'kind':'TEXT','text':'盘点实见位置'},'clientObservedAt':None}])['session']
    draft=mutate(draft,'coverage',items=[{'scopeItemId':copy_ids[0],'status':'PARTIAL'}])['session']
    # Same-address MOVE changes the event/version without changing the established
    # inventory or placement. Its changed signatures must be explicitly rechecked.
    address=request('/api/book-copies/'+str(copy_ids[0]))['copy']['placement']['registeredLocation']
    target=request('/api/location-addresses/'+str(address['locationId']))['address']
    actions.append(copy_action(copy_ids[0],'MOVE',schema_version=2,targetLocation={
        'kind':'REGISTERED','locationId':target['locationId'],'expectedAddressVersion':{
            'location':target['locationVersion'],'storey':target['storeyVersion'],'bookshelf':target['bookshelfVersion']}}))
    changes=mutate(draft,'change-checks')['review']['changes']
    assert any(change['entityKey']=='COPY:'+str(copy_ids[0]) for change in changes)
    pending=mutate(draft,'prepares',completionKind='PARTIAL')['review']
    reject('/api/stocktakes/'+str(draft['sessionId'])+'/confirm',{
        'operationKey':str(uuid.uuid4()),'expectedRevision':draft['revision'],'reason':reason,
        'reviewSnapshotId':pending['snapshotId'],'previewDigest':pending['digest']},'STOCKTAKE_CHANGED')
    assert request('/api/stocktakes/'+str(draft['sessionId']))['state']=='DRAFT'
    draft=mutate(draft,'rechecks',items=[{
        'entityKey':change['entityKey'],'changeToken':change['changeToken'],
        'expectedSignatureDigest':change['expectedSignatureDigest'],'decision':'CONFIRM_EXPLANATION',
        'evidence':'逐册重新实查：独立移位仍为原地址，观察位置差异保留待管理员核实'} for change in changes])['session']
    draft_review=mutate(draft,'prepares',completionKind='PARTIAL')['review']
    assert not draft_review['changes']

    report=create('container-finalized-report')
    events=[{'clientEventKey':str(uuid.uuid4()),'rawBarcode':item['barcode'],
        'observedPlacement':{'kind':'TEXT','text':'盘点实见位置'},'clientObservedAt':None}
        for item in intake_receipt['copies']]
    events.append({**events[0],'clientEventKey':str(uuid.uuid4())})
    events.append({'clientEventKey':str(uuid.uuid4()),'rawBarcode':'=SUM(1,2)',
        'observedPlacement':{'kind':'UNASSIGNED'},'clientObservedAt':None})
    report=mutate(report,'observations',events=events)['session']
    report=mutate(report,'coverage',items=[{'scopeItemId':copy_ids[0],'status':'COMPLETE'}])['session']
    review=mutate(report,'prepares',completionKind='PARTIAL')['review']
    assert not review['changes'] and review['differenceIds']
    final=mutate(report,'confirm',reviewSnapshotId=review['snapshotId'],previewDigest=review['digest'])['receipt']
    assert final['completionKind']=='PARTIAL' and final['finalDifferenceIds']==review['differenceIds']
    report=request('/api/stocktakes/'+str(report['sessionId']))
    assert report['receipt']==final
    assert {row['scopeItemId']:row['status'] for row in final['coverage']}=={
        copy_ids[0]:'COMPLETE',copy_ids[1]:'NOT_ATTEMPTED'}
    differences=request('/api/stocktakes/'+str(report['sessionId'])+'/differences')['records']
    assert any(row['type']=='UNKNOWN_BARCODE' for row in differences)
    assert any(row['type']=='DUPLICATE_SCAN' for row in differences)
    resolutions=[]
    difference=next(row for row in differences if row['copyId']==copy_ids[0] and row['type']=='MISPLACED_CANDIDATE')
    candidates=request('/api/stocktake-resolution-candidates?differenceId='+str(difference['differenceId'])+'&workflow=MOVE')['records']
    assert len(candidates)>=2 and all(row['copyId']==copy_ids[0] for row in candidates)
    for index,candidate in enumerate(candidates[:2]):
        intent=stocktake_write('/api/stocktake-resolutions',{
            'sessionId':report['sessionId'],'differenceIds':[difference['differenceId']],
            'decision':'BUSINESS_REQUIRED','workflow':'MOVE','candidateId':candidate['candidateId'],
            'reason':'关联既有成功移位作为核实依据，原报告差异不自动消除'},operations)['resolution']
        assert intent['state']=='AWAITING_LINK'
        if index==0:
            intent=stocktake_write('/api/stocktake-resolutions/'+str(intent['resolutionId'])+'/link',{
                'expectedVersion':intent['version'],'reason':reason},operations)['resolution']
            assert intent['state']=='LINKED'
        resolutions.append(intent)
    sessions=[]
    for session_id,expected_snapshot in [(draft['sessionId'],draft_review),(report['sessionId'],review)]:
        prefix='/api/stocktakes/'+str(session_id)
        snapshot=request(prefix+'/snapshots/'+str(expected_snapshot['snapshotId']))
        assert snapshot==expected_snapshot
        sessions.append({'session':request(prefix),'snapshot':snapshot,
            'observations':request(prefix+'/observations'),'differences':request(prefix+'/differences')})
    assert sessions[0]['session']['state']=='DRAFT' and sessions[1]['session']['state']=='FINALIZED'
    for book_id,before in books_before.items():
        after=request('/api/books/'+str(book_id))
        assert (after['stockTotal'],after['stockAvailable'])==(before['stockTotal'],before['stockAvailable'])
    return {'operations':operations,'sessions':sessions,'resolutions':resolutions,
        'pendingLink':{'path':'/api/stocktake-resolutions/'+str(resolutions[1]['resolutionId'])+'/link',
            'body':{'operationKey':str(uuid.uuid4()),'expectedVersion':resolutions[1]['version'],'reason':reason}}}

def stocktake_check(state):
    for operation in state['operations']:
        expected=operation['receipt']
        assert request('/api/stocktakes/operations/by-key?key='+operation['body']['operationKey'])==expected
        assert request(operation['path'],'POST',operation['body'])==expected
    for saved in state['sessions']:
        prefix='/api/stocktakes/'+str(saved['session']['sessionId'])
        assert request(prefix)==saved['session']
        assert request(prefix+'/snapshots/'+str(saved['snapshot']['snapshotId']))==saved['snapshot']
        assert request(prefix+'/observations')==saved['observations']
        assert request(prefix+'/differences')==saved['differences']
        if saved['session']['state']=='FINALIZED':
            export_path=prefix+'/export?reviewSnapshotId='+str(saved['snapshot']['snapshotId'])
            exported=request(export_path+'&format=json')
            assert exported['review']==saved['snapshot']
            assert exported['session']['receipt']==saved['session']['receipt']
            assert exported['differences']==saved['differences']['records']
            csv=request(export_path+'&format=csv',raw=True)
            assert csv.startswith(b'\xef\xbb\xbf') and "'=SUM(1,2)" in csv.decode('utf-8-sig')
    for resolution in state['resolutions']:
        prefix='/api/stocktake-resolutions/'+str(resolution['resolutionId'])
        assert request(prefix)==resolution
        assert request(prefix+'/recover')==resolution

def stocktake_complete_recovered_link(state):
    """Run once after the final isolated restore, preserving earlier expectations."""
    saved=state['stocktake']
    before=saved['resolutions'][1]
    pending=saved['pendingLink']
    linked=stocktake_write(pending['path'],pending['body'],[])['resolution']
    assert linked['state']=='LINKED' and linked['version']==before['version']+1
    assert linked['candidate']==before['candidate']
    assert sum(event['kind']=='LINK' for event in linked['events'])==1
    prefix='/api/stocktake-resolutions/'+str(before['resolutionId'])
    assert request(prefix)==linked and request(prefix+'/recover')==linked
    for session in saved['sessions']:
        assert request('/api/stocktakes/'+str(session['session']['sessionId']))==session['session']
    for copy_id,context in state['copyContexts'].items():
        assert request('/api/book-copies/'+copy_id+'/action-context')==context
        assert request('/api/book-copies/'+copy_id+'/actions')==state['copyHistories'][copy_id]
    for receipt in state['copyActions']:
        assert request('/api/book-copy-actions/'+str(receipt['requestId']))['receipt']==receipt
    assert request('/api/inventory-intakes/'+str(state['intake']))['receipt']==state['receipt']
    book=request('/api/books/'+str(state['book']))
    assert (book['stockTotal'],book['stockAvailable'])==(1,0)
png=base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jX1kAAAAASUVORK5CYII=')
html=request('/',raw=True,authenticated=False).decode()
assets=re.findall(r'(?:src|href)="(/assets/[^\"]+)"',html)
assert assets, 'Built page must reference frontend assets'
for asset in assets:
    content=request(asset,raw=True,authenticated=False)
    assert content and not content.lstrip().lower().startswith((b'<!doctype html',b'<html')), 'Missing frontend asset: '+asset
assert request('/api/actuator/health',authenticated=False)['status']=='UP'
if args.check_state:
    state=json.loads(Path(args.state).read_text())
    assert request(state['cover'],raw=True)==png
    assert request(state['avatar'],raw=True)==png
    assert request('/api/book-resources/download/'+str(state['resource']),raw=True)==b'persisted resource'
    current_book=request('/api/books/'+str(state['book']))
    assert current_book['stockTotal']==1 and current_book['stockAvailable']==0
    intake=request('/api/inventory-intakes/'+str(state['intake']))
    assert intake['state']=='COMMITTED' and intake['receipt']==state['receipt']
    assert request('/api/inventory-intakes/'+str(state['intake'])+'/confirm','POST',
        {'idempotencyKey':state['intakeKey'],'previewDigest':state['previewDigest']})==state['receipt']
    assert sorted(copy['barcode'] for copy in request('/api/books/'+str(state['book'])+'/copies'))==state['barcodes']
    for receipt in state['copyActions']:
        assert request('/api/book-copy-actions/'+str(receipt['requestId']))['receipt']==receipt
        assert request('/api/book-copy-actions/'+str(receipt['requestId'])+'/confirm','POST',
            {'idempotencyKey':receipt['idempotencyKey'],
             'previewDigest':state['copyActionDigests'][str(receipt['requestId'])]})==receipt
    for copy_id, context in state['copyContexts'].items():
        assert request('/api/book-copies/'+copy_id+'/action-context')==context
        assert request('/api/book-copies/'+copy_id+'/actions')['total']==sum(
            1 for action in state['copyActions'] for copy in action['copies'] if str(copy['copyId'])==copy_id)
    address=state['structuredAddress']
    assert request('/api/location-addresses/'+str(address['address']['locationId']))==address
    assert address['linkedCopyCount']==2 and address['legacyBinding']['bookId'] is None
    assert request('/api/location-structure-events?entityType=BOOKSHELF&entityId='+str(address['address']['bookshelfId']))==state['shelfEvents']
    assert request('/api/location-structure-events?entityType=LOCATION&entityId='+str(address['address']['locationId']))==state['locationEvents']
    for history in state['copyHistories'].values():
        for event in history['records']:
            if event['schemaVersion']==2:
                assert event['locationChange'] is not None
                assert event['locationChange']['snapshotVersion']==1
    for copy_id, history in state['copyHistories'].items():
        assert request('/api/book-copies/'+copy_id+'/actions')==history
    stocktake_check(state['stocktake'])
    if args.complete_recovered_link:stocktake_complete_recovered_link(state)
else:
    assert request('/api/captcha/image',authenticated=False)['image']
    category=request('/api/categories','POST',{'name':'验收分类','parentId':0,'sortOrder':0,'status':1})
    intake_key=str(uuid.uuid4())
    preview=request('/api/inventory-intakes/previews','POST',{
        'idempotencyKey':intake_key,'source':{'type':'MANUAL','purchaseOrderId':None},'reason':'隔离容器验收',
        'lines':[{'lineKey':str(uuid.uuid4()),'target':{'bookId':None,'newBook':{
            'isbn':uuid.uuid4().hex[:20],'title':'验收图书','categoryId':category['id']},'purchaseItemId':None},
            'copies':[{'input':'GENERATE','barcode':None,'location':None} for _ in range(2)]}]})
    confirmation={'idempotencyKey':intake_key,'previewDigest':preview['previewDigest']}
    receipt=request('/api/inventory-intakes/'+str(preview['intakeId'])+'/confirm','POST',confirmation)
    assert len(receipt['copies'])==2 and len(receipt['books'])==1
    assert request('/api/inventory-intakes/'+str(preview['intakeId'])+'/confirm','POST',confirmation)==receipt
    book=request('/api/books/'+str(receipt['books'][0]['bookId']))
    copy_id=receipt['copies'][0]['copyId']
    actions=[copy_action(copy_id,'MOVE',targetLocation={'kind':'TEXT','text':'隔离位置 A-01'})]
    actions.append(copy_action(copy_id,'DISABLE'))
    assert request('/api/books/'+str(book['id']))['stockAvailable']==1
    actions.append(copy_action(copy_id,'ENABLE'))
    actions.append(copy_action(copy_id,'WRITE_OFF'))
    assert request('/api/books/'+str(book['id']))['stockTotal']==1
    actions.append(copy_action(copy_id,'RESTORE',restoreOfActionId=actions[-1]['copies'][0]['eventId'],
        restoreTo='DISABLED',targetLocation={'kind':'UNASSIGNED'}))
    assert request('/api/books/'+str(book['id']))['stockAvailable']==1
    actions.append(copy_action(copy_id,'ENABLE'))
    assert request('/api/books/'+str(book['id']))['stockTotal']==2
    code=request('/api/borrow-codes/generate?bookId='+str(book['id']),'POST',{})
    assert request('/api/books/'+str(book['id']))['stockAvailable']==2
    record=request('/api/borrow-codes/verify?code='+code['code'],'POST',{})['borrowRecord']
    assert request('/api/books/'+str(book['id']))['stockAvailable']==1
    request('/api/borrows/'+str(record['id'])+'/return','PUT',{})
    # A one-capacity legacy layout is an address, not a limit on linked copies.
    # Retain original V1 receipts and the original nullable raw location while
    # V2 positions, path edits and explicit legacy unbinding survive recovery.
    reason='隔离容器结构化库位验收'
    shelf=request('/api/bookshelves','POST',{'name':'恢复前书架','code':'SMOKE-'+uuid.uuid4().hex[:12],
        'deptId':None,'location':None,'capacity':1,'status':1,'reason':reason})
    storey=request('/api/bookshelves/storeys','POST',{'bookshelfId':shelf['id'],'name':'第一层',
        'levelNum':1,'capacity':1,'status':1,'reason':reason})
    address=request('/api/bookshelves/locations','POST',{'storeyId':storey['id'],
        'code':'SMOKE-'+uuid.uuid4().hex[:12],'status':'EMPTY','reason':reason})
    address_id=address['id']
    request('/api/bookshelves/locations/'+str(address_id)+'/assign/'+str(book['id']),'PUT',
        {'expectedVersion':address['layoutVersion'],'reason':reason})
    target=request('/api/location-addresses/'+str(address_id))['address']
    assert target['selectable']
    registered={'kind':'REGISTERED','locationId':address_id,'expectedAddressVersion':{
        'location':target['locationVersion'],'storey':target['storeyVersion'],'bookshelf':target['bookshelfVersion']}}
    other_copy=receipt['copies'][1]['copyId']
    actions.append(copy_action(copy_id,'MOVE',schema_version=2,targetLocation=registered))
    actions.append(copy_action(other_copy,'MOVE',schema_version=2,targetLocation=registered))
    assert request('/api/location-addresses/'+str(address_id))['linkedCopyCount']==2
    shelf=request('/api/bookshelves','PUT',{'id':shelf['id'],'expectedVersion':shelf['layoutVersion'],
        'name':'恢复后书架','code':shelf['code'],'deptId':shelf.get('deptId'),'location':shelf.get('location'),
        'capacity':shelf['capacity'],'status':shelf['status'],'reason':reason})
    assert request('/api/book-copies/'+str(copy_id))['copy']['placement']['registeredLocation']['bookshelfName']=='恢复后书架'
    assert actions[-2]['copies'][0]['afterPlacement']['placement']['registeredLocation']['bookshelfName']=='恢复前书架'
    current_address=request('/api/location-addresses/'+str(address_id))
    request('/api/bookshelves/locations/'+str(address_id)+'/clear','PUT',
        {'expectedVersion':current_address['address']['locationVersion'],'reason':reason})
    current_address=request('/api/location-addresses/'+str(address_id))
    assert current_address['legacyBinding']=={'bookId':None,'rawStatus':'EMPTY','needsReview':False}
    assert current_address['linkedCopyCount']==2
    # Preserve both controlled unavailable states through recreate/update/restore.
    actions.append(copy_action(copy_id,'DISABLE',schema_version=2))
    actions.append(copy_action(other_copy,'WRITE_OFF',schema_version=2))
    actions.append(copy_action(other_copy,'RESTORE',schema_version=2,
        restoreOfActionId=actions[-1]['copies'][0]['eventId'],restoreTo='DISABLED',
        targetLocation={'kind':'TEXT','text':'找回检查位置'}))
    restored=request('/api/book-copies/'+str(other_copy))['copy']
    assert restored['placement']=={'kind':'TEXT','text':'找回检查位置','registeredLocation':None}
    assert request('/api/location-addresses/'+str(address_id))['linkedCopyCount']==1
    target=request('/api/location-addresses/'+str(address_id))['address']
    registered['expectedAddressVersion']={'location':target['locationVersion'],
        'storey':target['storeyVersion'],'bookshelf':target['bookshelfVersion']}
    actions.append(copy_action(other_copy,'MOVE',schema_version=2,targetLocation=registered))
    assert request('/api/book-copies/'+str(other_copy))['copy']['location']=='找回检查位置'
    actions.append(copy_action(other_copy,'WRITE_OFF',schema_version=2))
    stocktake=stocktake_seed([copy_id,other_copy],receipt,actions)
    cover=upload('/api/upload/book-cover/'+str(book['id']),'cover.png',png,'image/png')
    avatar=upload('/api/profile/avatar','avatar.png',png,'image/png')
    resource=upload('/api/book-resources/upload?bookId='+str(book['id'])+'&resourceType=DOCUMENT','resource.txt',b'persisted resource','text/plain')
    state={'book':book['id'],'cover':cover,'avatar':avatar,'resource':resource['id'],
        'intake':preview['intakeId'],'intakeKey':intake_key,'previewDigest':preview['previewDigest'],
        'receipt':receipt,'barcodes':sorted(copy['barcode'] for copy in receipt['copies']),
        'copyActions':actions,
        'stocktake':stocktake,
        'structuredAddress':request('/api/location-addresses/'+str(address_id)),
        'shelfEvents':request('/api/location-structure-events?entityType=BOOKSHELF&entityId='+str(shelf['id'])),
        'locationEvents':request('/api/location-structure-events?entityType=LOCATION&entityId='+str(address_id)),
        'copyHistories':{str(value):request('/api/book-copies/'+str(value)+'/actions') for value in [copy_id,other_copy]},
        'copyContexts':{str(value):request('/api/book-copies/'+str(value)+'/action-context') for value in [copy_id,other_copy]},
        'copyActionDigests':{str(action['requestId']):request('/api/book-copy-actions/'+str(action['requestId']))['previewDigest'] for action in actions}}
    Path(args.state).write_text(json.dumps(state))
    assert request(cover,raw=True)==png
    assert request(avatar,raw=True)==png
print('HTTP and persisted-data checks passed')
