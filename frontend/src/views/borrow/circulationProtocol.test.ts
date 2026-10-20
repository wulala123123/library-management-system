import { expect, it } from 'vitest'
import { assertCirculationInput, assertOrderDetail, assertOrderPage, assertReceiptBinding, assertReceiptShape, assertRequestShape, circulationCanonical, validKey } from './circulationProtocol'
import { borrowInput, borrowRequest, circulationExamples, cloneCirculation, orderDetail, requestReceipt, returnRequest } from './circulationFixtures'

it('accepts all frozen v2.1 synthetic response examples and exact empty-string input', () => {
  for (const value of [circulationExamples.requestView,circulationExamples.committedRequestView,circulationExamples.cancelledUnknownRequestView]) expect(()=>assertRequestShape(value)).not.toThrow()
  expect(()=>assertReceiptShape(circulationExamples.receipt)).not.toThrow(); expect(()=>assertReceiptShape(circulationExamples.returnReceipt)).not.toThrow()
  expect(()=>assertOrderPage(circulationExamples.orderPage)).not.toThrow(); expect(()=>assertOrderDetail(circulationExamples.orderDetail)).not.toThrow()
  expect(()=>assertCirculationInput(borrowInput())).not.toThrow()
})
it('keeps input order, exact Unicode and null identity without inventing historical copy/date values', () => {
  const view=returnRequest();view.preview!.items[0]!.userName=null
  Object.assign(view.preview!.items[0]!,{copyId:null,barcode:null,beforeCopyStatus:null,afterCopyStatus:null,copyVersionBefore:null,copyVersionAfter:null,dueDate:null,dueDateKind:'UNKNOWN',borrowDate:null})
  expect(()=>assertRequestShape(view)).not.toThrow();expect(()=>assertReceiptBinding(requestReceipt(view),view,1)).not.toThrow()
  const value=borrowInput();if(value.operation!=='BORROW')throw Error();value.items.push({bookId:202,copyId:302,borrowDays:1});value.items.reverse();value.remark=' 原文😀 '
  const request=borrowRequest(undefined,value);expect(()=>assertRequestShape(request)).not.toThrow();expect(request.preview!.items.map(row=>row.copyId)).toEqual([302,301]);expect(value.remark).toBe(' 原文😀 ')
})
it.each([null,undefined,30.5,'30',0,91])('rejects invalid explicit borrow days %j', days => {const value:any=borrowInput();value.items[0].borrowDays=days;expect(()=>assertCirculationInput(value)).toThrow()})
it('rejects extra fields, unsafe/string IDs, duplicate identities, oversized batches and illegal remarks',()=>{
  for(const mutate of [(v:any)=>v.operationKey='extra',(v:any)=>v.userId='101',(v:any)=>v.userId=Number.MAX_SAFE_INTEGER+1,(v:any)=>v.items.push({...v.items[0]}),(v:any)=>v.remark=null,(v:any)=>v.remark='bad\n',(v:any)=>v.remark='\ud800',(v:any)=>v.remark='😀'.repeat(501)]){const v:any=borrowInput();mutate(v);expect(()=>assertCirculationInput(v)).toThrow()}
  const value:any=borrowInput();value.items=Array.from({length:11},(_,i)=>({bookId:i+1,copyId:i+100,borrowDays:30}));expect(()=>assertCirculationInput(value)).toThrow()
  for(const ids of [[],[1,1],Array.from({length:101},(_,i)=>i+1),['1']])expect(()=>assertCirculationInput({operation:'RETURN',recordIds:ids,remark:''})).toThrow()
})
it('strictly binds every return target and ordinal in original order',()=>{
  const view=returnRequest();view.input={operation:'RETURN',recordIds:[502],remark:''};expect(()=>assertRequestShape(view)).toThrow()
  const missing=borrowRequest();missing.preview!.items=[];expect(()=>assertRequestShape(missing)).toThrow()
  const ordinal=borrowRequest();ordinal.preview!.items[0]!.ordinal=2;expect(()=>assertRequestShape(ordinal)).toThrow()
})
it.each(['actor','key','digest','target','count','title','date','flow','stock'])('rejects a mismatched %s success receipt',mismatch=>{
  const view=borrowRequest(),receipt:any=requestReceipt(view)
  if(mismatch==='actor')receipt.actorId=2;if(mismatch==='key')receipt.operationKey='223e4567-e89b-42d3-a456-426614174000';if(mismatch==='digest')receipt.previewDigest='b'.repeat(64)
  if(mismatch==='target')receipt.items[0].copyId=999;if(mismatch==='count')receipt.items=[];if(mismatch==='title')receipt.items[0].bookTitle='late title';if(mismatch==='date')receipt.items[0].dueDate='2026-12-01';if(mismatch==='flow')receipt.items[0].stockFlowId=0;if(mismatch==='stock')receipt.books[0].afterAvailable=2
  expect(()=>assertReceiptBinding(receipt,view,1)).toThrow()
})
it('rejects malformed terminal states, invalid date, key suffix and unknown protocol fields',()=>{
  for(const mutate of [(v:any)=>v.state='COMMITTED',(v:any)=>v.state='CANCELLED',(v:any)=>v.extra=true,(v:any)=>v.businessDate='2026-02-30',(v:any)=>v.preview.items[0].dueDate='2026-02-30',(v:any)=>v.previewDigest+='\n',(v:any)=>v.createdAt='2026-02-30T01:00:00Z']){const value:any=borrowRequest();mutate(value);expect(()=>assertRequestShape(value)).toThrow()}
  expect(validKey(borrowRequest().operationKey+'\n')).toBe(false)
})
it('requires complete order detail and preserves partial-return identity and original snapshots',()=>{
  const value=orderDetail();value.items.push({...value.items[0]!,ordinal:2,recordId:502,copyId:302,bookId:202,status:'RETURNED',returnDate:'2026-10-20'});value.itemCount=2;value.returnedCount=1;value.state='PARTIALLY_RETURNED';value.userName=null
  expect(()=>assertOrderDetail(value)).not.toThrow()
  const missing=cloneCirculation(value);missing.items.pop();expect(()=>assertOrderDetail(missing)).toThrow()
  const wrong=cloneCirculation(value);wrong.state='RETURNED';expect(()=>assertOrderDetail(wrong)).toThrow()
  expect(circulationCanonical({b:1,a:[2,1]})).toBe('{"a":[2,1],"b":1}')
})
