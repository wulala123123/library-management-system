// Fictional fixture data only. This module is imported by tests and the isolated UI server.
import type { FinanceBalance, FinanceEntries, FinanceEntry, FinanceEnvelope, FinanceFund, FinanceSource, MemberFinance } from '@/api/memberFinance'
import { financeSourceKeys } from '@/api/memberFinance'
export const financeSnapshot = '2026-10-19T08:00:00.123456Z'
export const financeHash = 'a'.repeat(64)
export function financeEnvelope<T>(data:T):FinanceEnvelope<T>{return{schemaVersion:1,code:200,message:'合成只读示例',data}}
export function unknownFinanceAccount(memberId='1',fund:FinanceFund='DEPOSIT'):FinanceBalance {
  const unknown={state:'UNKNOWN' as const,amount:null,reasonCode:'OPENING_UNVERIFIED' as const}
  return {memberId,accountId:null,fund,currency:'CNY',financeRevision:'0',scopeCutoffAt:null,openingEntryId:null,openingState:'UNKNOWN',verifiedPaid:{...unknown},verifiedRefunded:{...unknown},balance:{...unknown},feeHeadroom:fund==='MEMBER_FEE'?{...unknown}:null,newReceipts:'0.00',newRefunds:'0.00',newNet:'0.00',asRecordedAt:financeSnapshot}
}
export function financeFixture(memberId='1'):MemberFinance {
  const known=(amount:string)=>({state:'KNOWN' as const,amount,reasonCode:null})
  const fee:FinanceBalance={...unknownFinanceAccount(memberId,'MEMBER_FEE'),accountId:'10',financeRevision:'6',scopeCutoffAt:'2026-10-01T00:00:00.000000Z',openingEntryId:'9007199254740993',openingState:'VERIFIED',verifiedPaid:known('999999999999999.99'),verifiedRefunded:known('20.01'),balance:known('999999999999979.98'),feeHeadroom:known('999999999999979.98'),newReceipts:'100.01',newRefunds:'20.01',newNet:'80.00'}
  return{memberId,memberRevision:'12',currency:'CNY',accounts:[fee,unknownFinanceAccount(memberId)],asRecordedAt:financeSnapshot,historicalCoverage:'PARTIAL',source:'MEMBER_FINANCE_LEDGER'}
}
export function financeSourceFixture(memberId='1'):FinanceSource {
  return{memberId,sourceState:'VERIFIED_LINK',reasonCodes:[],profile:{profilePresent:true,sourceKey:'huiben:000012',sourceSystem:null,sourceArchiveId:'21',legacyPaidFee:'0.00',legacyDepositAmount:'0.00',legacyDepositStatus:'原状态：未核实'},archive:{archiveId:'21',sourceSystem:'huiben',recordType:'MEMBER',sourceKey:'000012',memberId,archiveHash:financeHash},fields:[{key:financeSourceKeys[0],presence:'VALUE',jsonType:'STRING',reasonCode:'READY',rawText:'0'},{key:financeSourceKeys[1],presence:'EMPTY',jsonType:'STRING',reasonCode:'EMPTY',rawText:''},{key:financeSourceKeys[2],presence:'NULL',jsonType:'NULL',reasonCode:'NULL',rawText:null},{key:financeSourceKeys[3],presence:'VALUE',jsonType:'STRING',reasonCode:'READY',rawText:'  已缴原文  '},{key:financeSourceKeys[4],presence:'VALUE',jsonType:'STRING',reasonCode:'READY',rawText:'原押金字段合成资料，不能推算已退'}],proofDigest:financeHash,asOf:financeSnapshot}
}
export function financeEntryFixture(id='9007199254741010',kind:FinanceEntry['kind']='RECEIPT'):FinanceEntry {
  return{id,accountId:'10',requestId:'12345678-1234-4123-8123-123456789abc',ordinal:1,kind,amount:'100.01',openingReceived:kind==='OPENING'?'120.02':null,openingRefunded:kind==='OPENING'?'20.01':null,cutoffAt:kind==='OPENING'?'2026-10-01T00:00:00.000000Z':null,actualAt:kind==='OPENING'?null:'2026-10-18T05:06:07.000001Z',methodCode:kind==='OPENING'||kind==='REVERSAL'?null:'SYNTHETIC_VOUCHER',rootFactKey:kind==='REVERSAL'?null:'12345678-1234-4123-8123-123456789abc',reversesEntryId:kind==='REVERSAL'?'9007199254741006':null,replacesEntryId:null,businessRef:{type:kind==='OPENING'?'VERIFIED_OPENING':kind==='REVERSAL'?'LEDGER_CORRECTION':'COUNTER_REGISTRATION',id:`合成事实${id}`},policyDigest:financeHash,evidenceRefs:[{type:'VERIFICATION_NOTE',reference:`合成凭据${id}😀`,digest:null}],reason:'合成台账核对说明',operatorId:'9',recordedAt:'2026-10-19T07:00:00.000001Z',asOf:financeSnapshot,effectiveAtAsOf:kind!=='REVERSAL',reversalEntryId:null,allocations:kind==='REFUND'?[{lotEntryId:'9007199254740993',amount:'100.01'}]:[]}
}
export function financeEntriesFixture(memberId='1',fund:FinanceFund='MEMBER_FEE',beforeId:string|null=null):FinanceEntries {
  const entries=fund==='DEPOSIT'?[]:beforeId===null?[financeEntryFixture('9007199254741010','REVERSAL'),financeEntryFixture('9007199254741009','REFUND')]:[financeEntryFixture('9007199254741006','RECEIPT'),financeEntryFixture('9007199254740993','OPENING')]
  if(entries[0]?.kind==='RECEIPT'){entries[0].effectiveAtAsOf=false;entries[0].reversalEntryId='9007199254741010'}
  return{memberId,fund,currency:'CNY',asRecordedAt:financeSnapshot,beforeId,nextBeforeId:fund==='MEMBER_FEE'&&beforeId===null?entries[entries.length-1]!.id:null,size:20,hasMore:fund==='MEMBER_FEE'&&beforeId===null,entries}
}
export function financeErrorFixture(code=403,errorCode='ADMIN_REQUIRED') {return{schemaVersion:1,code,message:'合成错误，不含原文',data:{errorCode,operationKey:null,requestId:null,state:null,certainty:code>=500?'UNKNOWN':'NO_FINANCIAL_WRITE',recovery:'NONE',retryable:code>=500,fieldErrors:[],operation:null}}}
