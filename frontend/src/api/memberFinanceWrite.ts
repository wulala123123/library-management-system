import { FINANCE_ACCESS_REVOKED } from './memberFinance'
import { assertWriteDefinition, decodeWriteResponse } from './memberFinanceWriteValidation'
import type { ActiveEnvelope, CancelBody, CancelEnvelope, ClaimBody, ConfirmBody, DraftBody, ErrorEnvelope, OperationEnvelope, PreviewBody, ReserveBody, ResolveBody, RequestEnvelope, Operation } from './memberFinanceWriteTypes'
export type * from './memberFinanceWriteTypes'
export class FinanceWriteError extends Error {
  constructor(public status: number | null, public code: string, public detail: ErrorEnvelope['data'] | null = null) { super(status===401||status===403?'财务权限已失效，内容已清除。':'办理结果尚需核对，请恢复原办理。');this.name='FinanceWriteError' }
}
export const stableFinanceJson = (value: unknown): string => JSON.stringify(value,(_key,v)=>v&&typeof v==='object'&&!Array.isArray(v)?Object.fromEntries(Object.entries(v).sort(([a],[b])=>a.localeCompare(b))):v)
export function assertOperationBinding(op: Operation, actorId: string, memberId?: string, key?: string) {
  assertWriteDefinition('Operation',op)
  const r=op.request, receipt=op.result?.receipt
  if(op.updatedAt>op.serverNow||op.createdAt>op.updatedAt||op.draftExpiresAt<=op.createdAt||r&&(r.createdAt>op.serverNow||r.expiresAt<=r.createdAt||r.input.memberId!==op.memberId||r.fund!==r.input.fund||r.action!==r.input.action||r.preview.identity.memberRevision!==r.input.memberRevision||r.preview.impact.before.memberId!==op.memberId||r.preview.impact.after.memberId!==op.memberId||r.preview.impact.before.fund!==r.fund||r.preview.impact.after.fund!==r.fund))throw new FinanceWriteError(null,'INVALID_RESPONSE')
  if(op.actorId!==actorId || memberId&&op.memberId!==memberId || key&&op.operationKey!==key || op.input&&op.input.memberId!==op.memberId || r&&(r.operationKey!==op.operationKey||r.memberId!==op.memberId||r.operationId!==op.operationId||r.inputHash!==op.draftHash||r.state!==op.state||stableFinanceJson(r.input)!==stableFinanceJson(op.input)||stableFinanceJson(r.result)!==stableFinanceJson(op.result)||r.preview.previewDigest!==r.previewDigest||r.preview.inputHash!==r.inputHash||r.preview.identity.memberId!==op.memberId) || receipt&&(receipt.memberId!==op.memberId||receipt.operationKey!==op.operationKey||receipt.requestId!==r?.requestId||receipt.previewDigest!==r?.previewDigest||receipt.audit.operatorId!==actorId)) throw new FinanceWriteError(null,'INVALID_RESPONSE')
  if(receipt&&(receipt.accountId!==r?.accountId||receipt.action!==r?.action||receipt.fund!==r?.fund||receipt.audit.requestId!==receipt.requestId||receipt.audit.accountId!==receipt.accountId||receipt.audit.afterRevision!==receipt.afterRevision||BigInt(receipt.afterRevision)!==BigInt(receipt.beforeRevision)+1n||receipt.entriesAtCommit.some(e=>e.accountId!==receipt.accountId||e.requestId!==receipt.requestId||e.operatorId!==actorId)))throw new FinanceWriteError(null,'INVALID_RESPONSE')
  if(receipt&&op.input?.action==='RECEIPT'){const input=op.input,e=receipt.entriesAtCommit[0];if(receipt.entriesAtCommit.length!==1||e.kind!=='RECEIPT'||e.amount!==input.payload.amount||e.actualAt!==input.payload.actualAt||e.methodCode!==input.payload.methodCode||e.rootFactKey!==input.payload.factKey||e.businessRef.type!=='COUNTER_REGISTRATION'||e.businessRef.id!==input.payload.factKey)throw new FinanceWriteError(null,'INVALID_RESPONSE')}
}
const base='/api/member-finance'
/** Explicit captured token: a delayed request cannot silently use a different login. */
export function createFinanceWriteApi(token: string, signal?: AbortSignal) {
  async function call<T>(path: string, output: string, input?: {definition:string;body:unknown}): Promise<T> {
    if(input)assertWriteDefinition(input.definition,input.body)
    const deadline=new AbortController(),relay=()=>deadline.abort(),timer=setTimeout(()=>deadline.abort(),20000)
    signal?.addEventListener('abort',relay,{once:true});if(signal?.aborted)deadline.abort()
    try {
    let response: Response
    try { response=await fetch(base+path,{method:input?'POST':'GET',credentials:'same-origin',cache:'no-store',signal:deadline.signal,headers:{Authorization:`Bearer ${token}`,...input?{'Content-Type':'application/json'}:{}},...input?{body:JSON.stringify(input.body)}:{}}) }
    catch {throw new FinanceWriteError(null,'UNKNOWN_RESULT')}
    if(response.status===401||response.status===403)window.dispatchEvent(new CustomEvent(FINANCE_ACCESS_REVOKED,{detail:response.status}))
    let payload:unknown
    try {const raw=await response.arrayBuffer();if(raw.byteLength>1024*1024)throw Error();payload=decodeWriteResponse(raw);assertWriteDefinition(response.status===200?output:'ErrorEnvelope',payload)}catch{throw new FinanceWriteError(response.status,'INVALID_RESPONSE')}
    if(response.status!==200){const error=payload as ErrorEnvelope;if(error.code!==response.status)throw new FinanceWriteError(response.status,'INVALID_RESPONSE');throw new FinanceWriteError(response.status,error.data.errorCode,error.data)}
    return (payload as {data:T}).data
    } finally {clearTimeout(timer);signal?.removeEventListener('abort',relay)}
  }
  const keyPath=(key:string)=>{assertWriteDefinition('Key',key);return '/operations/by-key/'+encodeURIComponent(key)}
  return {
    active:()=>call<ActiveEnvelope['data']>('/operations/active','ActiveEnvelope'),
    byKey:(key:string)=>call<OperationEnvelope['data']>(keyPath(key),'OperationEnvelope'),
    reserve:(body:ReserveBody)=>call<OperationEnvelope['data']>('/operations','OperationEnvelope',{definition:'ReserveBody',body}),
    claim:(body:ClaimBody)=>call<OperationEnvelope['data']>(keyPath(body.operationKey)+'/claim','OperationEnvelope',{definition:'ClaimBody',body}),
    draft:(body:DraftBody)=>{assertWriteDefinition('DraftBody',body);if(body.input.action!=='RECEIPT'||!['CASH','WECHAT'].includes(body.input.payload.methodCode)||body.input.payload.businessRef.id!==body.input.payload.factKey)throw new FinanceWriteError(null,'INVALID_INPUT');return call<OperationEnvelope['data']>(keyPath(body.operationKey)+'/draft','OperationEnvelope',{definition:'DraftBody',body})},
    preview:(body:PreviewBody)=>call<OperationEnvelope['data']>(keyPath(body.operationKey)+'/preview','OperationEnvelope',{definition:'PreviewBody',body}),
    confirm:(body:ConfirmBody)=>call<OperationEnvelope['data']>(keyPath(body.operationKey)+'/confirm','OperationEnvelope',{definition:'ConfirmBody',body}),
    cancel:(body:CancelBody)=>call<CancelEnvelope['data']>(keyPath(body.operationKey)+'/cancel','CancelEnvelope',{definition:'CancelBody',body}),
    resolve:(body:ResolveBody)=>call<OperationEnvelope['data']>(keyPath(body.operationKey)+'/resolve','OperationEnvelope',{definition:'ResolveBody',body}),
    request:(id:string)=>{assertWriteDefinition('Uuid',id);return call<RequestEnvelope['data']>('/requests/'+id,'RequestEnvelope')},
  }
}
export type FinanceWriteApi=ReturnType<typeof createFinanceWriteApi>
