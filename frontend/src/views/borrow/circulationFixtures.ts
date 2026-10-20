import examples from '@/api/circulationContractExamples.json'
import type { CirculationInput, CirculationItem, CirculationReceipt, CirculationRequest, BorrowOrderDetail } from '@/api/circulation'
export const fixtureKey = examples.requestView.operationKey
export const fixtureDigest = examples.requestView.previewDigest
export const fixtureNow = '2026-10-20T02:00:00Z'
export const cloneCirculation = <T>(value:T):T => JSON.parse(JSON.stringify(value)) as T
export const borrowInput = ():CirculationInput => cloneCirculation(examples.requestView.input) as CirculationInput
export function borrowRequest(key=fixtureKey,input:CirculationInput=borrowInput()):CirculationRequest {
  const view = cloneCirculation(examples.requestView) as CirculationRequest; view.operationKey = key; view.input = cloneCirculation(input)
  if (input.operation === 'BORROW') {
    view.preview!.borrower!.userId = input.userId
    view.preview!.items = input.items.map((row,index) => ({...cloneCirculation(examples.requestView.preview.items[0]!) as CirculationItem,ordinal:index+1,userId:input.userId,bookId:row.bookId,copyId:row.copyId,barcode:index===0?'000Aa ':`000${row.copyId}`,dueDate:new Date(Date.parse('2026-10-20T00:00:00Z')+row.borrowDays*86400000).toISOString().slice(0,10)}))
    view.preview!.books = input.items.map(row => ({bookId:row.bookId,stockTotal:2,stockAvailable:2,deltaAvailable:-1,afterAvailable:1})); view.preview!.activeCountAfter = input.items.length
  }
  return view
}
export function returnRequest(key=fixtureKey):CirculationRequest {
  const view = borrowRequest(key); view.operation = 'RETURN'; view.input = {operation:'RETURN',recordIds:[501],remark:''}; view.preview = cloneCirculation(examples.returnPreview) as CirculationRequest['preview']; return view
}
export function requestReceipt(view:CirculationRequest):CirculationReceipt {
  return {schemaVersion:1,operationKey:view.operationKey,operation:view.operation!,actorId:view.actorId,confirmedAt:fixtureNow,businessDate:view.businessDate!,orderId:view.operation==='BORROW'?401:null,previewDigest:view.previewDigest!,items:view.preview!.items.map((row,index)=>({...row,recordId:row.recordId??501+index,stockFlowId:601+index})),books:cloneCirculation(view.preview!.books)}
}
export function cancelledRequest(view?:CirculationRequest,key=fixtureKey):CirculationRequest { return view ? {...cloneCirculation(view),state:'CANCELLED',cancelledAt:fixtureNow,receipt:null} : {...cloneCirculation(examples.cancelledUnknownRequestView),operationKey:key} as CirculationRequest }
export const orderDetail = ():BorrowOrderDetail => cloneCirculation(examples.orderDetail) as BorrowOrderDetail
export { examples as circulationExamples }
