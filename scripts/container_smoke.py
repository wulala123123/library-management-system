"""HTTP smoke test against an isolated Compose deployment; never prints credentials."""
import argparse, base64, hashlib, hmac, json, re, time, uuid, urllib.request, urllib.error
from pathlib import Path

p=argparse.ArgumentParser()
p.add_argument('--base',required=True)
p.add_argument('--env',required=True)
p.add_argument('--state',required=True)
p.add_argument('--check-state',action='store_true')
args=p.parse_args()
env=dict(line.split('=',1) for line in Path(args.env).read_text().splitlines() if '=' in line and not line.startswith('#'))
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

def copy_action(copy_id, action, **changes):
    context=request('/api/book-copies/'+str(copy_id)+'/action-context')
    item={'copyId':copy_id,'expectedVersion':context['copy']['actionVersion'],**changes}
    key=str(uuid.uuid4())
    view=request('/api/book-copy-actions/previews','POST',{'idempotencyKey':key,'action':action,
        'reason':'隔离容器逐册动作验收','copies':[item]})
    confirmation={'idempotencyKey':key,'previewDigest':view['previewDigest']}
    path='/api/book-copy-actions/'+str(view['requestId'])+'/confirm'
    receipt=request(path,'POST',confirmation)
    assert request(path,'POST',confirmation)==receipt
    after=request('/api/book-copies/'+str(copy_id)+'/action-context')
    changed=receipt['copies'][0]
    assert after['copy']['actionVersion']==context['copy']['actionVersion']+1==changed['afterVersion']
    assert after['copy']['status']==changed['afterStatus']
    assert after['copy']['location']==changed['afterLocation']
    projection=after['reconciliationV2']
    assert projection['schema']=='copy-lifecycle-v1'
    assert not projection['hasHistoricalDifference'] and not projection['hasUnknownStatus']
    assert not projection['hasLoanLinkAnomaly']
    assert request('/api/book-copies/'+str(copy_id)+'/actions')['records'][0]['eventId']==changed['eventId']
    return receipt
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
    # Preserve both controlled unavailable states through recreate/update/restore.
    actions.append(copy_action(copy_id,'DISABLE'))
    other_copy=receipt['copies'][1]['copyId']
    actions.append(copy_action(other_copy,'WRITE_OFF'))
    cover=upload('/api/upload/book-cover/'+str(book['id']),'cover.png',png,'image/png')
    avatar=upload('/api/profile/avatar','avatar.png',png,'image/png')
    resource=upload('/api/book-resources/upload?bookId='+str(book['id'])+'&resourceType=DOCUMENT','resource.txt',b'persisted resource','text/plain')
    state={'book':book['id'],'cover':cover,'avatar':avatar,'resource':resource['id'],
        'intake':preview['intakeId'],'intakeKey':intake_key,'previewDigest':preview['previewDigest'],
        'receipt':receipt,'barcodes':sorted(copy['barcode'] for copy in receipt['copies']),
        'copyActions':actions,
        'copyContexts':{str(value):request('/api/book-copies/'+str(value)+'/action-context') for value in [copy_id,other_copy]},
        'copyActionDigests':{str(action['requestId']):request('/api/book-copy-actions/'+str(action['requestId']))['previewDigest'] for action in actions}}
    Path(args.state).write_text(json.dumps(state))
    assert request(cover,raw=True)==png
    assert request(avatar,raw=True)==png
print('HTTP and persisted-data checks passed')
