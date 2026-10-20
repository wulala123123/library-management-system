import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createCirculationApi } from './circulation'
import { useCirculationSession } from '@/views/borrow/circulationSession'
import { borrowInput, borrowRequest, circulationExamples, fixtureDigest, fixtureKey } from '@/views/borrow/circulationFixtures'
const response=(data:unknown,status=200)=>({status,arrayBuffer:async()=>new TextEncoder().encode(JSON.stringify({code:status,message:'合成协议响应',data})).buffer})
beforeEach(()=>vi.stubGlobal('fetch',vi.fn()))
afterEach(()=>vi.unstubAllGlobals())
it('sends exact path-key payload and captured token, preserving item order and empty remark',async()=>{
  vi.mocked(fetch).mockResolvedValue(response(circulationExamples.requestView) as any)
  const api=createCirculationApi('original-login');localStorage.setItem('token','different-login')
  await api.preview(fixtureKey,borrowInput())
  expect(fetch).toHaveBeenCalledOnce();const [url,options]=vi.mocked(fetch).mock.calls[0]!
  expect(url).toBe(`/api/circulation/requests/${fixtureKey}/preview`);expect(options).toMatchObject({method:'POST',cache:'no-store',headers:{Authorization:'Bearer original-login'}})
  expect(JSON.parse(options!.body as string)).toEqual(borrowInput());expect(options!.body).not.toContain('operationKey')
})
it('uses exact confirm body, direct receipt response, empty cancel body and queryless recovery',async()=>{
  const api=createCirculationApi('login');vi.mocked(fetch).mockResolvedValueOnce(response(circulationExamples.receipt) as any).mockResolvedValueOnce(response(circulationExamples.cancelledUnknownRequestView) as any).mockResolvedValueOnce(response(circulationExamples.requestView) as any)
  expect((await api.confirm(fixtureKey,fixtureDigest)).orderId).toBe(401);await api.cancel(fixtureKey);await api.request(fixtureKey)
  expect(JSON.parse(vi.mocked(fetch).mock.calls[0]![1]!.body as string)).toEqual({previewDigest:fixtureDigest})
  expect(vi.mocked(fetch).mock.calls[1]).toMatchObject([`/api/circulation/requests/${fixtureKey}/cancel`,{method:'POST',body:'{}'}])
  expect(vi.mocked(fetch).mock.calls[2]).toMatchObject([`/api/circulation/requests/${fixtureKey}`,{method:'GET'}])
})
it('supports only declared order list filters and exact full order detail',async()=>{
  vi.mocked(fetch).mockResolvedValueOnce(response(circulationExamples.orderPage) as any).mockResolvedValueOnce(response(circulationExamples.orderDetail) as any)
  const api=createCirculationApi('login');await api.orders({page:1,size:20,userId:101,state:'BORROWING'});await api.order(401)
  expect(vi.mocked(fetch).mock.calls[0]![0]).toBe('/api/circulation/orders?page=1&size=20&userId=101&state=BORROWING');expect(vi.mocked(fetch).mock.calls[1]![0]).toBe('/api/circulation/orders/401')
  expect(()=>api.orders({page:1,size:101})).toThrow();expect(()=>api.order(0)).toThrow();expect(()=>api.preview(fixtureKey+'\n',borrowInput())).toThrow()
})
it('retains structured conflicts and absent original request as errors for session recovery',async()=>{
  vi.mocked(fetch).mockResolvedValue(response({schemaVersion:1,reasonCode:'REQUEST_NOT_FOUND'},404) as any)
  await expect(createCirculationApi('login').request(fixtureKey)).rejects.toMatchObject({status:404,code:'REQUEST_NOT_FOUND'})
})
it.each(['{}','{"code":200,"message":"x","data":null} garbage','{"code":200,"message":"x","data":{},"extra":true}'])('rejects malformed response %s without accepting success',async raw=>{
  vi.mocked(fetch).mockResolvedValue({status:200,arrayBuffer:async()=>new TextEncoder().encode(raw).buffer} as any)
  await expect(createCirculationApi('login').request(fixtureKey)).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
it('keeps network failure uncertain and invalid UTF8 invalid',async()=>{
  vi.mocked(fetch).mockRejectedValueOnce(Error('lost'));await expect(createCirculationApi('login').cancel(fixtureKey)).rejects.toMatchObject({status:null,code:'UNKNOWN_RESULT'})
  vi.mocked(fetch).mockResolvedValueOnce({status:200,arrayBuffer:async()=>new Uint8Array([0xc3,0x28]).buffer} as any);await expect(createCirculationApi('login').request(fixtureKey)).rejects.toMatchObject({code:'INVALID_RESPONSE'})
})
it('does not revoke a new-account session when an old-token 403 arrives late, while current-token 403 still revokes it',async()=>{
  let finishOld!:(value:any)=>void
  vi.mocked(fetch).mockImplementationOnce(()=>new Promise(resolve=>finishOld=resolve))
  localStorage.setItem('token','old-account-login');const late=createCirculationApi('old-account-login').request(fixtureKey).catch(error=>error)
  localStorage.setItem('token','new-account-login')
  vi.mocked(fetch).mockImplementationOnce(async(url,options)=>{const key=String(url).split('/requests/')[1]!.split('/')[0]!,view=borrowRequest(key,JSON.parse(options!.body as string));view.actorId=2;return response(view) as any})
  const current=useCirculationSession(2,()=>localStorage.getItem('token')==='new-account-login',createCirculationApi('new-account-login'),sessionStorage)
  try {
    await current.start(borrowInput());expect(current.phase.value).toBe('ready')
    finishOld(response({schemaVersion:1,reasonCode:'ROLE_REQUIRED'},403));await late
    expect(current.denied.value).toBe(false);expect(current.view.value?.actorId).toBe(2);expect(current.phase.value).toBe('ready')
    vi.mocked(fetch).mockResolvedValueOnce(response({schemaVersion:1,reasonCode:'ROLE_REQUIRED'},403) as any);await current.recover();expect(current.denied.value).toBe(true);expect(current.view.value).toBeNull()
  } finally {current.dispose();sessionStorage.clear();localStorage.clear()}
})
