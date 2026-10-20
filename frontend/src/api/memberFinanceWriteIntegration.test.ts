/** Repeatable synthetic response compatibility cases; no private logs or server needed. */
import { afterEach, expect, it, vi } from 'vitest'
import examples from './memberFinanceWriteIntegrationFixtures.json'
import { assertOperationBinding, createFinanceWriteApi, type Operation } from './memberFinanceWrite'
import { assertWriteDefinition, decodeWriteResponse, validWriteDefinition } from './memberFinanceWriteValidation'
import { FINANCE_ACCESS_REVOKED, getMemberFinance, getMemberFinanceEntries, getMemberFinanceSource } from './memberFinance'

const fixture=(name:string):any=>structuredClone(examples.cases.find(c=>c.name===name)!.value)
const envelope=(op:unknown)=>({schemaVersion:1,code:200,message:'合成兼容样例',data:op})
const response=(body:unknown,status=200)=>({status,arrayBuffer:async()=>new TextEncoder().encode(JSON.stringify(body)).buffer}) as Response
afterEach(()=>{vi.unstubAllGlobals();localStorage.clear()})

it.each(examples.cases.map(c=>[c.name,c.definition,c.value] as const))('accepts the captured synthetic %s shape',(_name,definition,value)=>{
  const decoded=decodeWriteResponse(new TextEncoder().encode(JSON.stringify(value)).buffer)
  expect(validWriteDefinition(definition,decoded)).toBe(true)
  expect(()=>assertWriteDefinition(definition,decoded)).not.toThrow()
  if(definition==='Operation'){
    const op=decoded as Operation
    expect(()=>assertOperationBinding(op,op.actorId,op.memberId,op.operationKey)).not.toThrow()
  }
})

const op=fixture('previewed') as Operation,base={schemaVersion:1 as const,operationKey:op.operationKey},key=op.operationKey
const reserve={...base,memberId:op.memberId,editorId:op.editor.editorId}
const editor={...op.editor}
const routes:any[]=[
  ['active','GET','/member-finance/operations/active',[],fixture('active')],
  ['byKey','GET','/member-finance/operations/by-key/'+key,[key],envelope(op)],
  ['reserve','POST','/member-finance/operations',[reserve],envelope(op)],
  ['claim','POST','/member-finance/operations/by-key/'+key+'/claim',[{...reserve,expectedEpoch:editor.epoch,claimKey:editor.editorId}],envelope(op)],
  ['draft','POST','/member-finance/operations/by-key/'+key+'/draft',[{...base,editor,input:op.input}],envelope(op)],
  ['preview','POST','/member-finance/operations/by-key/'+key+'/preview',[{...base,editor,draftHash:op.draftHash}],envelope(op)],
  ['confirm','POST','/member-finance/operations/by-key/'+key+'/confirm',[{...base,editor,previewDigest:op.request!.previewDigest}],envelope(fixture('committed'))],
  ['cancel','POST','/member-finance/operations/by-key/'+key+'/cancel',[{...base,memberId:op.memberId,editor,cancelKey:editor.editorId,previewDigest:op.request!.previewDigest,reason:'USER_CANCELLED'}],fixture('cancel_already_committed')],
  ['resolve','POST','/member-finance/operations/by-key/'+key+'/resolve',[{...base,memberId:op.memberId}],envelope(op)],
  ['request','GET','/member-finance/requests/'+op.request!.requestId,[op.request!.requestId],fixture('request')],
]
it.each(routes)('routes %s through the exact original API and captured token',async(name,method,path,args,body)=>{
  const fetchMock=vi.fn().mockResolvedValue(response(body));vi.stubGlobal('fetch',fetchMock)
  expect(await (createFinanceWriteApi('synthetic-captured') as any)[name](...args)).toEqual(body.data)
  expect(fetchMock).toHaveBeenCalledTimes(1)
  const [url,request]=fetchMock.mock.calls[0]!
  expect(url).toBe('/api'+path)
  expect(request).toMatchObject({method,cache:'no-store',credentials:'same-origin',headers:{Authorization:'Bearer synthetic-captured'}})
  if(method==='POST')expect(JSON.parse(request.body)).toEqual(args[0])
  else expect(request.body).toBeUndefined()
})

it.each(['finance','source','entries'])('keeps the original %s GET client compatible with new ledger response data',async name=>{
  const body=fixture(name),id=body.data.memberId
  localStorage.setItem('token','synthetic-read-token');vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(body)))
  const data=await (name==='finance'?getMemberFinance(id):name==='source'?getMemberFinanceSource(id):getMemberFinanceEntries(id,{fund:body.data.fund,currency:'CNY',size:body.data.size,beforeId:body.data.beforeId,asRecordedAt:body.data.asRecordedAt}))
  expect(data).toEqual(body.data)
})

it.each([401,403])('clears access on captured %i error and preserves the protocol error code',async status=>{
  const error={schemaVersion:1,code:status,message:'合成权限失败',data:{errorCode:status===401?'UNAUTHENTICATED':'ADMIN_REQUIRED',operationKey:null,requestId:null,state:null,certainty:'NO_FINANCIAL_WRITE',recovery:'NONE',retryable:false,fieldErrors:[],operation:null}}
  const revoked=vi.fn();window.addEventListener(FINANCE_ACCESS_REVOKED,revoked)
  try{vi.stubGlobal('fetch',vi.fn().mockResolvedValue(response(error,status)));await expect(createFinanceWriteApi('synthetic').active()).rejects.toMatchObject({status,code:error.data.errorCode});expect(revoked).toHaveBeenCalledOnce()}
  finally{window.removeEventListener(FINANCE_ACCESS_REVOKED,revoked)}
})

it.each([
  ['actor',(v:any):void=>{v.actorId='1'}],
  ['request input',(v:any):void=>{v.request.input.reason+='different'}],
  ['time order',(v:any):void=>{v.serverNow='2020-01-01T00:00:00.000000Z'}],
  ['receipt revision',(v:any)=>{v.result.receipt.afterRevision='4';v.request.result=structuredClone(v.result)}],
  ['receipt amount',(v:any)=>{v.result.receipt.entriesAtCommit[0].amount='2.00';v.request.result=structuredClone(v.result)}],
] as const)('rejects relationally inconsistent %s even if its schema is valid',(_name,mutate)=>{
  const original=fixture('committed'),changed=structuredClone(original);mutate(changed)
  expect(validWriteDefinition('Operation',changed)).toBe(true)
  expect(()=>assertOperationBinding(changed,original.actorId,original.memberId,original.operationKey)).toThrow()
})
