/** Synthetic test responses only. These are not actual HTTP/transaction evidence. */
import examples from './financeWriteContractFixtures.json'
import type { Operation, ReceiptInput, RecoveryRecord, Receipt, CancelReceipt } from '@/api/memberFinanceWrite'
export const fixtureValue=(name:string):any=>structuredClone(examples.cases.find(c=>c.name===name)!.value)
export const testFact='66666666-6666-4666-8666-666666666666',testEditor='11111111-1111-4111-8111-111111111111',testNow='2026-10-20T01:00:00.000000Z'
export function receiptInput():ReceiptInput{return {memberId:'1',memberRevision:'0',financeRevision:'0',fund:'MEMBER_FEE',currency:'CNY',action:'RECEIPT',reason:'合成已收款依据😀',evidenceRefs:[{type:'COUNTER_VOUCHER',reference:'SYNTHETIC-ONLY',digest:null}],payload:{factKey:testFact,amount:'123.45',actualAt:'2026-10-20T00:00:00.000000Z',methodCode:'CASH',paymentState:'RECORDED',businessRef:{type:'COUNTER_REGISTRATION',id:testFact}}}}
export function receiptOperation(state:Operation['state']='RESERVED',editorId=testEditor,key?:string):Operation{
  const op=fixtureValue('previewed_operation').data as Operation
  op.editor={editorId,epoch:'1'};op.serverNow=testNow;op.updatedAt=testNow;op.createdAt='2026-10-20T00:00:00.000000Z';op.draftExpiresAt='2026-10-21T00:00:00.000000Z';op.state=state
  if(key)op.operationKey=key
  if(state==='RESERVED'){op.input=null;op.request=null;op.draftHash=null;return op}
  op.input=receiptInput()
  if(state==='DRAFT_READY'){op.request=null;return op}
  const r=op.request!;r.operationKey=op.operationKey;r.action='RECEIPT';r.input=receiptInput();r.createdAt=op.createdAt;r.expiresAt='2026-10-20T01:10:00.000000Z'
  r.preview.policy={...r.preview.policy,paymentMethodsState:'CONFIRMED',paymentMethodCodes:['CASH','WECHAT'],datePolicyState:'CONFIRMED',cutoffAt:'2026-10-19T16:00:00.000000Z',financialTimeZone:'Asia/Shanghai',backdateRuleRef:'B40B_D03_SAME_SHANGHAI_DAY_V1',enabledActions:['RECEIPT']}
  r.preview.canConfirm=true;r.preview.blocks=[];r.preview.eligibility={applicability:'NOT_APPLICABLE',unreturnedCount:null,anomalyCount:null,debtState:'NOT_APPLICABLE',debtPolicyVersion:null,debtGuardVersion:null,coveredSources:[],asOf:null,items:[]}
  r.preview.impact.entries=[{ordinal:1,kind:'RECEIPT',amount:'123.45',openingReceived:null,openingRefunded:null,cutoffAt:null,actualAt:op.input.payload.actualAt,methodCode:'CASH',reversesEntryId:null,replacesEntryId:null,allocations:[]}]
  const unknown={state:'UNKNOWN' as const,amount:null,reasonCode:'OPENING_UNVERIFIED' as const}
  Object.assign(r.preview.impact.after,{openingState:'UNKNOWN',verifiedPaid:unknown,verifiedRefunded:unknown,balance:unknown,feeHeadroom:unknown,newReceipts:'123.45',newRefunds:'0.00',newNet:'123.45'})
  if(state==='COMMITTED'){
    const receipt=fixtureValue('receipt_future_shape') as Receipt;receipt.operationKey=op.operationKey;receipt.action='RECEIPT';receipt.confirmedAt=testNow;receipt.audit.action='RECEIPT';receipt.audit.reason=op.input.reason;receipt.audit.recordedAt=testNow;receipt.impact=structuredClone(r.preview.impact)
    const entry=receipt.entriesAtCommit[0]!;Object.assign(entry,{kind:'RECEIPT',amount:'123.45',openingReceived:null,openingRefunded:null,cutoffAt:null,actualAt:op.input.payload.actualAt,methodCode:'CASH',rootFactKey:testFact,businessRef:{type:'COUNTER_REGISTRATION',id:testFact},recordedAt:testNow,asOf:testNow})
    Object.assign(receipt.audit.after,{openingState:'UNKNOWN',openingEntryId:null,verifiedPaid:unknown,verifiedRefunded:unknown,balance:unknown,feeHeadroom:unknown,newReceipts:'123.45',newNet:'123.45'})
    op.result={state:'COMMITTED',reasonCode:'COMMITTED',resolvedAt:testNow,receipt};r.state='COMMITTED';r.result=structuredClone(op.result)
  }
  return op
}
export function recoveryRecord(op=receiptOperation()):RecoveryRecord{return {schemaVersion:1,actorId:op.actorId,memberId:op.memberId,operationKey:op.operationKey,stage:'ACTIVE',factKey:op.input?.action==='RECEIPT'?op.input.payload.factKey:testFact,draftHash:op.draftHash,previewDigest:op.request?.previewDigest??null,requestId:op.request?.requestId??null,lastEditor:op.editor,confirmIntent:null,cancelIntent:null,updatedAt:testNow}}
export function cancelResult(body:any,committed=false):CancelReceipt{return {...fixtureValue(committed?'cancel_commit_won':'cancel_tombstone').data,operationKey:body.operationKey,memberId:body.memberId,cancelKey:body.cancelKey,previewDigest:body.previewDigest,reason:body.reason,...committed?{receipt:receiptOperation('COMMITTED').result!.receipt}:{} }}
