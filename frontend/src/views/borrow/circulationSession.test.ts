import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { CirculationError, type CirculationApi } from '@/api/circulation'
import { useCirculationSession } from './circulationSession'
import { borrowInput, borrowRequest, cancelledRequest, fixtureNow, requestReceipt } from './circulationFixtures'
const sessions:ReturnType<typeof useCirculationSession>[]=[]
const api={preview:vi.fn(),request:vi.fn(),confirm:vi.fn(),cancel:vi.fn(),orders:vi.fn(),order:vi.fn()}
const create=(owner=1,allowed=()=>true,storage:Storage=sessionStorage)=>{const value=useCirculationSession(owner,allowed,api as CirculationApi,storage);sessions.push(value);return value}
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(done=>resolve=done);return {promise,resolve}}
beforeEach(()=>{vi.resetAllMocks();sessionStorage.clear();localStorage.clear();vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(fixtureNow);api.preview.mockImplementation((key,input)=>Promise.resolve(borrowRequest(key,input)));api.request.mockRejectedValue(new CirculationError(404,'REQUEST_NOT_FOUND'));api.cancel.mockImplementation(key=>Promise.resolve(cancelledRequest(undefined,key)))})
afterEach(()=>{sessions.splice(0).forEach(value=>value.dispose());vi.useRealTimers();if(Object.prototype.hasOwnProperty.call(navigator,'locks'))delete (navigator as any).locks})

it('persists only a minimal marker before first HTTP, freezes input in memory and blocks another operation',async()=>{
  const value=create(),input:any=borrowInput();input.remark='本地必要备注'
  api.preview.mockImplementation((key,body)=>{const raw=sessionStorage.getItem('circulation.pending.v1.1')!;expect(raw).toContain(key);for(const secret of ['userId','本地必要备注','userName','cardNo','recordIds','input','preview":'])expect(raw).not.toContain(secret);return Promise.resolve(borrowRequest(key,body))})
  await value.start(input);input.items[0].copyId=999;expect(value.input.value).toMatchObject({items:[{copyId:301}]});expect(Object.isFrozen(value.input.value)).toBe(true)
  await value.start(borrowInput());expect(api.preview).toHaveBeenCalledOnce();expect(value.phase.value,value.error.value).toBe('ready')
})
it('confirms exact original key/digest and clears marker only after a validated terminal acknowledgment',async()=>{
  const value=create();await value.start(borrowInput());const original=value.view.value!;api.confirm.mockResolvedValue(requestReceipt(original));await value.confirm()
  expect(api.confirm).toHaveBeenCalledWith(original.operationKey,original.previewDigest);expect(value.phase.value).toBe('committed');expect(value.receipt.value!.items[0]!.recordId).toBe(501)
  await value.acknowledge();expect(value.canStart.value).toBe(true);expect(sessionStorage.getItem('circulation.pending.v1.1')).toBeNull()
})
it('404 after unknown preview never releases key; reload uses GET/cancel without persisted member input',async()=>{
  api.preview.mockRejectedValueOnce(new CirculationError(null,'UNKNOWN_RESULT'));const first=create();await first.start(borrowInput());const key=first.operationKey.value
  const resumed=create();expect(resumed.input.value).toBeNull();await resumed.retryPreview();expect(api.preview).toHaveBeenCalledOnce();await resumed.recover();expect(resumed.phase.value).toBe('previewing');await resumed.start(borrowInput());expect(api.preview).toHaveBeenCalledOnce()
  await resumed.cancel();expect(api.cancel).toHaveBeenCalledWith(key);expect(resumed.phase.value).toBe('cancelled');await resumed.acknowledge();await resumed.start(borrowInput());expect(resumed.operationKey.value).not.toBe(key)
})
it('keeps unknown confirmation through PREVIEWED/404/reload and retries only exact original confirmation',async()=>{
  const first=create();await first.start(borrowInput());const original=first.view.value!;api.confirm.mockRejectedValueOnce(new CirculationError(null,'UNKNOWN_RESULT'));await first.confirm();expect(first.phase.value).toBe('confirming')
  const resumed=create();api.request.mockResolvedValue(original);await resumed.recover();await resumed.acknowledge();await resumed.start(borrowInput());expect(api.preview).toHaveBeenCalledOnce();expect(resumed.phase.value).toBe('confirming')
  api.confirm.mockResolvedValue(requestReceipt(original));await resumed.confirm();expect(api.confirm.mock.calls[1]).toEqual(api.confirm.mock.calls[0]);expect(resumed.phase.value).toBe('committed')
})
it('recovers committed timeout without repeating write and prevents stale GET from rolling back terminal state',async()=>{
  const first=create();await first.start(borrowInput());const view=first.view.value!,second=create(),late=deferred<any>();api.request.mockReturnValue(late.promise);const waiting=second.recover()
  await vi.waitFor(()=>expect(api.request).toHaveBeenCalledOnce());api.confirm.mockResolvedValue(requestReceipt(view));await first.confirm();late.resolve(view);await waiting
  expect(second.phase.value).toBe('committed');expect(second.receipt.value).toBeNull();api.request.mockResolvedValue({...view,state:'COMMITTED',receipt:requestReceipt(view)});await second.recover();expect(second.receipt.value!.orderId).toBe(401);expect(api.confirm).toHaveBeenCalledOnce()
})
it('freezes unknown cancellation across reload and displays commit if confirm won before cancel',async()=>{
  const first=create();await first.start(borrowInput());const view=first.view.value!,key=first.operationKey.value;api.cancel.mockRejectedValueOnce(new CirculationError(null,'UNKNOWN_RESULT'));await first.cancel()
  const resumed=create();api.request.mockResolvedValue(view);await resumed.recover();await resumed.confirm();expect(api.confirm).not.toHaveBeenCalled();expect(resumed.phase.value).toBe('cancelling')
  api.cancel.mockResolvedValue({...view,state:'COMMITTED',receipt:requestReceipt(view)});await resumed.cancel();expect(api.cancel.mock.calls).toEqual([[key],[key]]);expect(resumed.phase.value).toBe('committed')
})
it('coordinates original-key cancellation across windows and accepts the committed winner from a delayed confirmation',async()=>{
  const first=create();await first.start(borrowInput());const original=first.view.value!,pending=deferred<any>(),cancelled=deferred<any>();api.confirm.mockReturnValue(pending.promise);const sending=first.confirm();await vi.waitFor(()=>expect(api.confirm).toHaveBeenCalledOnce())
  const other=create();api.request.mockResolvedValue(original);await other.recover();api.cancel.mockReturnValue(cancelled.promise);const cancelling=other.cancel();await vi.waitFor(()=>expect(api.cancel).toHaveBeenCalledWith(original.operationKey));expect(other.phase.value).toBe('cancelling')
  const third=create();await third.recover();await third.confirm();expect(api.confirm).toHaveBeenCalledOnce();expect(third.phase.value).toBe('cancelling')
  const receipt=requestReceipt(original);pending.resolve(receipt);await sending;cancelled.resolve({...original,state:'COMMITTED',receipt});await cancelling;expect(other.phase.value).toBe('committed');expect(other.receipt.value).toEqual(receipt)
})
it.each(['actor','target','key','digest'])('rejects wrong %s receipt and keeps confirming recovery lock',async mismatch=>{
  const value=create();await value.start(borrowInput());const receipt=requestReceipt(value.view.value!)
  if(mismatch==='actor')receipt.actorId=2;if(mismatch==='target')receipt.items[0]!.copyId=999;if(mismatch==='key')receipt.operationKey='223e4567-e89b-42d3-a456-426614174000';if(mismatch==='digest')receipt.previewDigest='b'.repeat(64)
  api.confirm.mockResolvedValue(receipt);await value.confirm();expect(value.receipt.value).toBeNull();expect(value.phase.value).toBe('confirming');await value.acknowledge();expect(value.canStart.value).toBe(false)
})
it('permits cancellation after a first definite stale refusal but keeps earlier unknown confirmation frozen',async()=>{
  const value=create();await value.start(borrowInput());api.confirm.mockRejectedValueOnce(new CirculationError(409,'PREVIEW_STALE'));await value.confirm();expect(value.phase.value).toBe('ready')
  api.confirm.mockRejectedValueOnce(new CirculationError(null,'UNKNOWN_RESULT')).mockRejectedValueOnce(new CirculationError(409,'PREVIEW_STALE'));await value.confirm();await value.confirm();expect(value.phase.value).toBe('confirming')
})
it('rejects writes on storage failure/corruption, isolates owners and suppresses late unauthorized identity',async()=>{
  const failed=create(1,()=>true,{getItem:()=>null,setItem:()=>{throw Error('quota')}} as unknown as Storage);await failed.start(borrowInput());expect(api.preview).not.toHaveBeenCalled()
  sessionStorage.setItem('circulation.pending.v1.2','{bad');const corrupt=create(2);await corrupt.start(borrowInput());expect(api.preview).not.toHaveBeenCalled();expect(sessionStorage.getItem('circulation.pending.v1.2')).toBe('{bad')
  let allowed=true;const value=create(1,()=>allowed),late=deferred<any>();api.preview.mockImplementation(key=>{const view=borrowRequest(key);return late.promise.then(()=>view)});const sending=value.start(borrowInput());await vi.waitFor(()=>expect(api.preview).toHaveBeenCalledOnce());allowed=false;late.resolve(null);await sending
  expect(value.view.value).toBeNull();expect(value.input.value).toBeNull();expect(value.operationKey.value).not.toBe('');expect(create(3).operationKey.value).toBe('')
})
it('does not let an old failed confirmation change a later operation after another window acknowledged success',async()=>{
  const first=create();await first.start(borrowInput());const original=first.view.value!,old=deferred<any>();api.confirm.mockReturnValueOnce(old.promise);const oldSending=first.confirm();await vi.waitFor(()=>expect(api.confirm).toHaveBeenCalledOnce())
  const second=create();api.request.mockResolvedValue({...original,state:'COMMITTED',receipt:requestReceipt(original)});await second.recover();await second.acknowledge();await second.start(borrowInput());const fresh=second.view.value!,next=deferred<any>();api.confirm.mockReturnValueOnce(next.promise);const nextSending=second.confirm();await vi.waitFor(()=>expect(api.confirm).toHaveBeenCalledTimes(2))
  old.resolve({...requestReceipt(original),actorId:2});await oldSending
  const saved=JSON.parse(sessionStorage.getItem('circulation.pending.v1.1')!);expect(saved.operationKey).toBe(fresh.operationKey);expect(saved.confirmInFlight).toBe(1);expect(saved.confirmUncertain).toBe(false)
  next.resolve(requestReceipt(fresh));await nextSending;expect(second.phase.value).toBe('committed')
})
it('requires Web Locks for real origin storage and saves the phase within that lock before HTTP',async()=>{
  const blocked=create(1,()=>true,localStorage);await blocked.start(borrowInput());expect(api.preview).not.toHaveBeenCalled();expect(localStorage.getItem('circulation.pending.v1.1')).toBeNull()
  const lock=vi.fn(async(_key:string,work:()=>unknown)=>work());Object.defineProperty(navigator,'locks',{configurable:true,value:{request:lock}})
  const value=create(1,()=>true,localStorage);await value.start(borrowInput());expect(lock).toHaveBeenCalledWith('circulation.pending.v1.1',expect.any(Function));expect(value.phase.value).toBe('ready');expect(localStorage.getItem('circulation.pending.v1.1')).not.toContain('userName')
})
it('recovers a crashed confirming marker with inFlight=1 and expired uncommitted preview through a durable original-key cancel',async()=>{
  const original=borrowRequest();vi.setSystemTime('2026-10-20T02:30:00Z')
  localStorage.setItem('circulation.pending.v1.1',JSON.stringify({storageVersion:1,ownerId:1,operationKey:original.operationKey,phase:'confirming',previewDigest:original.previewDigest,confirmInFlight:1,confirmUncertain:false}))
  Object.defineProperty(navigator,'locks',{configurable:true,value:{request:async(_key:string,work:()=>unknown)=>work()}})
  const resumed=create(1,()=>true,localStorage);api.request.mockResolvedValue(original);await resumed.recover();expect(resumed.phase.value).toBe('confirming');expect(resumed.canStart.value).toBe(false)
  api.confirm.mockRejectedValue(new CirculationError(409,'PREVIEW_EXPIRED'));await resumed.confirm();expect(JSON.parse(localStorage.getItem('circulation.pending.v1.1')!).confirmInFlight).toBe(1)
  await resumed.acknowledge();await resumed.start(borrowInput());expect(api.preview).not.toHaveBeenCalled()
  api.cancel.mockResolvedValue(cancelledRequest(original));await resumed.cancel();expect(api.cancel).toHaveBeenCalledWith(original.operationKey);expect(resumed.phase.value).toBe('cancelled');await resumed.acknowledge();expect(resumed.canStart.value).toBe(true)
})
it('keeps cancellation pending through late confirm refusal and nonterminal cancel response until durable cancellation',async()=>{
  const first=create();await first.start(borrowInput());const original=first.view.value!,old=deferred<any>(),cancellation=deferred<any>();api.confirm.mockReturnValue(old.promise);const confirming=first.confirm();await vi.waitFor(()=>expect(api.confirm).toHaveBeenCalledOnce())
  const other=create();api.request.mockResolvedValue(original);await other.recover();api.cancel.mockReturnValueOnce(cancellation.promise);const cancelling=other.cancel();await vi.waitFor(()=>expect(api.cancel).toHaveBeenCalledOnce())
  old.resolve({});await confirming;expect(first.phase.value).toBe('cancelling');cancellation.resolve(original);await cancelling;expect(other.phase.value).toBe('cancelling');await other.acknowledge();expect(other.canStart.value).toBe(false)
  api.cancel.mockResolvedValue(cancelledRequest(original));await other.cancel();expect(other.phase.value).toBe('cancelled');await other.acknowledge();expect(other.canStart.value).toBe(true)
})
it('does not let a delayed old confirm overwrite cancellation or a new-key confirmation after acknowledgment',async()=>{
  const first=create();await first.start(borrowInput());const original=first.view.value!,old=deferred<any>();api.confirm.mockReturnValueOnce(old.promise);const confirming=first.confirm();await vi.waitFor(()=>expect(api.confirm).toHaveBeenCalledOnce())
  const other=create();api.cancel.mockResolvedValue(cancelledRequest(original));await other.cancel();expect(other.phase.value).toBe('cancelled');await other.acknowledge();await other.start(borrowInput());const fresh=other.view.value!,next=deferred<any>();api.confirm.mockReturnValueOnce(next.promise);const nextConfirm=other.confirm();await vi.waitFor(()=>expect(api.confirm).toHaveBeenCalledTimes(2))
  old.resolve(requestReceipt(original));await confirming;const saved=JSON.parse(sessionStorage.getItem('circulation.pending.v1.1')!);expect(saved.operationKey).toBe(fresh.operationKey);expect(saved.phase).toBe('confirming');expect(saved.confirmInFlight).toBe(1);expect(saved.confirmUncertain).toBe(false)
  next.resolve(requestReceipt(fresh));await nextConfirm;expect(other.phase.value).toBe('committed');expect(other.receipt.value?.operationKey).toBe(fresh.operationKey)
})
