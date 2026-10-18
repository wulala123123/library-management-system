/** B40 read-only projection. IDs, revisions and money stay decimal strings. */
export type FinanceFund = 'MEMBER_FEE' | 'DEPOSIT'
export type FinanceAmount = { state: 'KNOWN'; amount: string; reasonCode: null } | { state: 'UNKNOWN'; amount: null; reasonCode: 'OPENING_UNVERIFIED' | 'SOURCE_LINK_UNVERIFIED' | 'UNEXPLAINED_HISTORY' | 'LEDGER_ANOMALY' | 'NOT_ASSESSED' }
export interface FinanceBalance {
  memberId: string; accountId: string | null; fund: FinanceFund; currency: 'CNY'; financeRevision: string
  scopeCutoffAt: string | null; openingEntryId: string | null; openingState: 'UNKNOWN' | 'VERIFIED'
  verifiedPaid: FinanceAmount; verifiedRefunded: FinanceAmount; balance: FinanceAmount; feeHeadroom: FinanceAmount | null
  newReceipts: string; newRefunds: string; newNet: string; asRecordedAt: string
}
export interface MemberFinance { memberId: string; memberRevision: string; currency: 'CNY'; accounts: [FinanceBalance, FinanceBalance]; asRecordedAt: string; historicalCoverage: 'COMPLETE' | 'PARTIAL' | 'UNKNOWN'; source: 'MEMBER_FINANCE_LEDGER' }
export interface FinanceEvidence { type: 'LEGACY_ARCHIVE' | 'COUNTER_VOUCHER' | 'VERIFICATION_NOTE'; reference: string; digest: string | null }
export interface FinanceEntry {
  id: string; accountId: string; requestId: string; ordinal: number; kind: 'OPENING' | 'RECEIPT' | 'REFUND' | 'REVERSAL'
  amount: string; openingReceived: string | null; openingRefunded: string | null; cutoffAt: string | null; actualAt: string | null
  methodCode: string | null; rootFactKey: string | null; reversesEntryId: string | null; replacesEntryId: string | null
  businessRef: { type: 'COUNTER_REGISTRATION' | 'VERIFIED_OPENING' | 'LEDGER_CORRECTION'; id: string }; policyDigest: string
  evidenceRefs: FinanceEvidence[]; reason: string; operatorId: string; recordedAt: string; asOf: string
  effectiveAtAsOf: boolean; reversalEntryId: string | null; allocations: { lotEntryId: string; amount: string }[]
}
export interface FinanceEntriesQuery { fund: FinanceFund; currency: 'CNY'; beforeId: string | null; size: number; asRecordedAt: string | null }
export interface FinanceEntries { memberId: string; fund: FinanceFund; currency: 'CNY'; asRecordedAt: string; beforeId: string | null; nextBeforeId: string | null; size: number; hasMore: boolean; entries: FinanceEntry[] }
export const financeSourceKeys = ['实际缴纳费用', '实际缴纳押金', '缴费状态', '押金缴纳状态', '押金'] as const
export interface FinanceSourceField { key: typeof financeSourceKeys[number]; presence: 'MISSING' | 'NULL' | 'EMPTY' | 'VALUE' | 'INVALID_TYPE' | 'TOO_LONG'; jsonType: 'MISSING' | 'NULL' | 'STRING' | 'NUMBER' | 'BOOLEAN' | 'ARRAY' | 'OBJECT'; reasonCode: string; rawText: string | null }
export interface FinanceSource {
  memberId: string; sourceState: 'NO_PROFILE' | 'MANUAL' | 'VERIFIED_LINK' | 'UNVERIFIED_LINK' | 'INVALID_ARCHIVE'; reasonCodes: string[]
  profile: { profilePresent: boolean; sourceKey: string | null; sourceSystem: string | null; sourceArchiveId: string | null; legacyPaidFee: string | null; legacyDepositAmount: string | null; legacyDepositStatus: string | null }
  archive: { archiveId: string; sourceSystem: 'huiben'; recordType: 'MEMBER'; sourceKey: string; memberId: string; archiveHash: string } | null
  fields: FinanceSourceField[]; proofDigest: string | null; asOf: string
}
export interface FinanceEnvelope<T> { schemaVersion: 1; code: 200; message: string; data: T }
export const FINANCE_ACCESS_REVOKED = 'member-finance-access-revoked'
export class FinanceReadError extends Error {
  constructor(public readonly status: number | null, public readonly reason: string) {
    super(status === 401 || status === 403 ? '财务读取权限已失效，内容已清除。' : reason === 'MEMBER_NOT_FOUND' ? '会员或历史台账不存在，请核对会员编号。' : reason === 'BAD_CURSOR' ? '账目分页位置已失效，请刷新重新读取。' : reason === 'INVALID_RESPONSE' ? '财务返回资料未通过校验，请刷新重试。' : '财务资料读取失败，请重试；本次失败不表示账本为空。')
    this.name = 'FinanceReadError'
  }
}

type Check = (value: any) => boolean
const eq = (...values: unknown[]): Check => value => values.includes(value)
/** Scalar validation always consumes the entire string; source text is never trimmed. */
const wholeMatch = (regex: RegExp, value: string): boolean => { const match=regex.exec(value);return match?.index===0 && match[0]===value }
const text = (min: number, max: number): Check => value => typeof value === 'string' && [...value].length >= min && [...value].length <= max && ![...value].some(char => wholeMatch(/^[\ud800-\udfff]$/u,char))
const nil = (check: Check): Check => value => value === null || check(value)
const list = (check: Check, min = 0, max = 100): Check => value => Array.isArray(value) && value.length >= min && value.length <= max && value.every(check)
const obj = (shape: Record<string, Check>): Check => value => value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === Object.keys(shape).length && Object.entries(shape).every(([key, check]) => Object.prototype.hasOwnProperty.call(value, key) && check(value[key]))
const pattern = (regex: RegExp): Check => value => typeof value === 'string' && wholeMatch(regex,value)
const bool: Check = value => typeof value === 'boolean'
const longMax = '9223372036854775807'
export const financeId: Check = value => typeof value === 'string' && wholeMatch(/^[1-9][0-9]{0,18}$/,value) && (value.length < longMax.length || value <= longMax)
const revision: Check = value => value === '0' || financeId(value)
const money = pattern(/^(0|[1-9][0-9]{0,64})\.[0-9]{2}$/)
const signedMoney: Check = value => typeof value === 'string' && wholeMatch(/^-?(0|[1-9][0-9]{0,64})\.[0-9]{2}$/,value) && value !== '-0.00'
const uuid = pattern(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
const hash = pattern(/^[0-9a-f]{64}$/), code = pattern(/^[A-Z][A-Z0-9_]{0,63}$/), method = pattern(/^[A-Z][A-Z0-9_]{0,31}$/)
export const financeUtc: Check = value => { if (typeof value !== 'string' || !wholeMatch(/^[1-9][0-9]{3}-[0-9]{2}-[0-9]{2}T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]\.[0-9]{6}Z$/,value)) return false; const date = new Date(value); return !isNaN(date.getTime()) && date.toISOString().slice(0,19) === value.slice(0,19) }
const fund = eq('MEMBER_FEE', 'DEPOSIT')
const amount = (state?: 'KNOWN' | 'UNKNOWN'): Check => value => (state !== 'UNKNOWN' && obj({state:eq('KNOWN'),amount:money,reasonCode:eq(null)})(value)) || (state !== 'KNOWN' && obj({state:eq('UNKNOWN'),amount:eq(null),reasonCode:eq('OPENING_UNVERIFIED','SOURCE_LINK_UNVERIFIED','UNEXPLAINED_HISTORY','LEDGER_ANOMALY','NOT_ASSESSED')})(value))
const balance: Check = value => {
  if (!obj({memberId:financeId,accountId:nil(financeId),fund,currency:eq('CNY'),financeRevision:revision,scopeCutoffAt:nil(financeUtc),openingEntryId:nil(financeId),openingState:eq('UNKNOWN','VERIFIED'),verifiedPaid:amount(),verifiedRefunded:amount(),balance:amount(),feeHeadroom:nil(amount()),newReceipts:money,newRefunds:money,newNet:signedMoney,asRecordedAt:financeUtc})(value)) return false
  const state = value.openingState === 'VERIFIED' ? 'KNOWN' : 'UNKNOWN'
  return ['verifiedPaid','verifiedRefunded','balance'].every(key => amount(state)(value[key])) && (value.fund === 'MEMBER_FEE' ? amount(state)(value.feeHeadroom) : value.feeHeadroom === null) && (value.openingState !== 'VERIFIED' || financeId(value.accountId) && financeId(value.openingEntryId) && financeUtc(value.scopeCutoffAt)) && (value.accountId !== null || value.openingEntryId === null && value.scopeCutoffAt === null && value.financeRevision === '0' && value.openingState === 'UNKNOWN')
}
const finance: Check = value => obj({memberId:financeId,memberRevision:revision,currency:eq('CNY'),accounts:list(balance,2,2),asRecordedAt:financeUtc,historicalCoverage:eq('COMPLETE','PARTIAL','UNKNOWN'),source:eq('MEMBER_FINANCE_LEDGER')})(value) && value.accounts[0].fund === 'MEMBER_FEE' && value.accounts[1].fund === 'DEPOSIT' && value.accounts.every((account: FinanceBalance) => account.memberId === value.memberId && account.asRecordedAt === value.asRecordedAt)
const evidence = obj({type:eq('LEGACY_ARCHIVE','COUNTER_VOUCHER','VERIFICATION_NOTE'),reference:text(1,128),digest:nil(hash)})
const entry: Check = value => {
  if (!obj({id:financeId,accountId:financeId,requestId:uuid,ordinal:eq(1,2),kind:eq('OPENING','RECEIPT','REFUND','REVERSAL'),amount:money,openingReceived:nil(money),openingRefunded:nil(money),cutoffAt:nil(financeUtc),actualAt:nil(financeUtc),methodCode:nil(method),rootFactKey:nil(text(1,128)),reversesEntryId:nil(financeId),replacesEntryId:nil(financeId),businessRef:obj({type:eq('COUNTER_REGISTRATION','VERIFIED_OPENING','LEDGER_CORRECTION'),id:text(1,128)}),policyDigest:hash,evidenceRefs:list(evidence,1,20),reason:value => text(1,1000)(value) && wholeMatch(/^\S(?:[\s\S]*\S)?$/u,value),operatorId:financeId,recordedAt:financeUtc,asOf:financeUtc,effectiveAtAsOf:bool,reversalEntryId:nil(financeId),allocations:list(obj({lotEntryId:financeId,amount:money}),0,100)})(value)) return false
  if (value.kind === 'OPENING' ? !money(value.openingReceived) || !money(value.openingRefunded) || !financeUtc(value.cutoffAt) || value.actualAt !== null || value.methodCode !== null : value.openingReceived !== null || value.openingRefunded !== null || value.cutoffAt !== null) return false
  if (['RECEIPT','REFUND'].includes(value.kind) && (value.amount === '0.00' || !financeUtc(value.actualAt) || !method(value.methodCode))) return false
  if (value.kind === 'REFUND' ? !value.allocations.length : value.allocations.length !== 0) return false
  return value.kind === 'REVERSAL' ? financeId(value.reversesEntryId) && value.replacesEntryId === null && value.methodCode === null && value.rootFactKey === null : value.reversesEntryId === null
}
const entries: Check = value => obj({memberId:financeId,fund,currency:eq('CNY'),asRecordedAt:financeUtc,beforeId:nil(financeId),nextBeforeId:nil(financeId),size:v => Number.isInteger(v) && v>=1 && v<=100,hasMore:bool,entries:list(entry,0,100)})(value)
const sourceField: Check = value => {
  if (!obj({key:eq(...financeSourceKeys),presence:eq('MISSING','NULL','EMPTY','VALUE','INVALID_TYPE','TOO_LONG'),jsonType:eq('MISSING','NULL','STRING','NUMBER','BOOLEAN','ARRAY','OBJECT'),reasonCode:code,rawText:nil(text(0,financeSourceKeys.slice(0,2).includes(value?.key) ? 64 : 300))})(value)) return false
  if (value.presence === 'MISSING') return value.jsonType === 'MISSING' && value.rawText === null
  if (value.presence === 'NULL') return value.jsonType === 'NULL' && value.rawText === null
  if (value.presence === 'EMPTY') return value.jsonType === 'STRING' && value.rawText === ''
  if (value.presence === 'VALUE') return text(1,300)(value.rawText) && (financeSourceKeys.slice(0,2).includes(value.key) ? ['STRING','NUMBER'].includes(value.jsonType) : value.jsonType === 'STRING')
  return value.rawText === null
}
const source: Check = value => obj({memberId:financeId,sourceState:eq('NO_PROFILE','MANUAL','VERIFIED_LINK','UNVERIFIED_LINK','INVALID_ARCHIVE'),reasonCodes:list(code),profile:obj({profilePresent:bool,sourceKey:nil(text(0,128)),sourceSystem:nil(text(0,50)),sourceArchiveId:nil(financeId),legacyPaidFee:nil(money),legacyDepositAmount:nil(money),legacyDepositStatus:nil(text(0,50))}),archive:nil(obj({archiveId:financeId,sourceSystem:eq('huiben'),recordType:eq('MEMBER'),sourceKey:text(1,128),memberId:financeId,archiveHash:hash})),fields:list(sourceField,5,5),proofDigest:nil(hash),asOf:financeUtc})(value) && value.fields.every((field: FinanceSourceField,index:number) => field.key === financeSourceKeys[index]) && (value.sourceState === 'VERIFIED_LINK' ? hash(value.proofDigest) && value.archive !== null && value.archive.memberId === value.memberId : value.proofDigest === null)
const envelope = (data: Check): Check => obj({schemaVersion:eq(1),code:eq(200),message:text(1,300),data})
export function assertFinance(value: unknown): asserts value is FinanceEnvelope<MemberFinance> { if (!envelope(finance)(value)) throw new FinanceReadError(null,'INVALID_RESPONSE') }
export function assertFinanceSource(value: unknown): asserts value is FinanceEnvelope<FinanceSource> { if (!envelope(source)(value)) throw new FinanceReadError(null,'INVALID_RESPONSE') }
export function assertFinanceEntries(value: unknown): asserts value is FinanceEnvelope<FinanceEntries> { if (!envelope(entries)(value)) throw new FinanceReadError(null,'INVALID_RESPONSE') }
const errorCodes = ['INVALID_JSON','INVALID_INPUT','DUPLICATE_FIELD','UNKNOWN_FIELD','UNAUTHENTICATED','ADMIN_REQUIRED','OWNER_REQUIRED','MEMBER_NOT_FOUND','OPERATION_NOT_FOUND','REQUEST_NOT_FOUND','ACTIVE_OPERATION_EXISTS','OPERATION_KEY_REUSED','EDITOR_CHANGED','CLAIM_KEY_REUSED','DRAFT_ALREADY_FROZEN','DRAFT_REQUIRED','DRAFT_HASH_MISMATCH','PREVIEW_REQUIRED','PREVIEW_DIGEST_MISMATCH','PREVIEW_BINDING_REQUIRED','CANCEL_KEY_REUSED','CANCEL_INTENT_FROZEN','CANCELLED','EXPIRED','REJECTED','DEPENDENCIES_CHANGED','BUSINESS_FACT_EXISTS','MONEY_PRECISION','STORAGE_RANGE','RULE_NOT_CONFIRMED','REFUND_BLOCKED','BAD_CURSOR','UNKNOWN_RESULT','INTERNAL_ERROR','OPERATION_APPEARED']
function readError(value: unknown, status: number): string {
  const shape=obj({schemaVersion:eq(1),code:eq(400,401,403,404,409,422,500,503),message:text(1,300),data:obj({errorCode:eq(...errorCodes),operationKey:eq(null),requestId:eq(null),state:eq(null),certainty:eq('NO_FINANCIAL_WRITE','SAVED_TERMINAL','UNKNOWN'),recovery:eq('NONE'),retryable:bool,fieldErrors:list(()=>false,0,0),operation:eq(null)})})
  const error=value as any
  if (!shape(value) || error.code !== status || error.data.certainty !== (status>=500?'UNKNOWN':'NO_FINANCIAL_WRITE')) return 'INVALID_RESPONSE'
  return error.data.errorCode
}

/** Reject duplicate properties, trailing tokens and malformed Unicode before DTO checking. */
export function parseFinanceJson(raw: string): unknown {
  let cursor=0
  const whitespace=()=>{while(/[ \t\r\n]/.test(raw[cursor] ?? '') && cursor<raw.length)cursor++}
  const string=()=>{const start=cursor++;while(cursor<raw.length){const char=raw[cursor++];if(char==='\\'){cursor++;continue}if(char==='"'){const result=JSON.parse(raw.slice(start,cursor));if(!text(0,raw.length)(result))throw Error();return result}}throw Error()}
  function value(depth=0): any {
    if(depth>100)throw Error();whitespace();const token=raw[cursor]
    if(token==='"')return string()
    if(token==='{'){cursor++;const result:Record<string,unknown>=Object.create(null),keys=new Set<string>();whitespace();if(raw[cursor]==='}'){cursor++;return result}while(cursor<raw.length){whitespace();if(raw[cursor]!=='"')throw Error();const key=string();if(keys.has(key))throw Error();keys.add(key);whitespace();if(raw[cursor++]!==':')throw Error();result[key]=value(depth+1);whitespace();const end=raw[cursor++];if(end==='}')return result;if(end!==',')throw Error()}throw Error()}
    if(token==='['){cursor++;const result:unknown[]=[];whitespace();if(raw[cursor]===']'){cursor++;return result}while(cursor<raw.length){result.push(value(depth+1));whitespace();const end=raw[cursor++];if(end===']')return result;if(end!==',')throw Error()}throw Error()}
    const match=/^(?:null|true|false|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)/.exec(raw.slice(cursor));if(!match)throw Error();cursor+=match[0].length;return JSON.parse(match[0])
  }
  try{const result=value();whitespace();if(cursor!==raw.length)throw Error();return result}catch{throw new FinanceReadError(null,'INVALID_RESPONSE')}
}
export const compareFinanceIds = (left: string, right: string) => left.length === right.length ? left === right ? 0 : left < right ? -1 : 1 : left.length < right.length ? -1 : 1
function assertQuery(query: FinanceEntriesQuery) {
  if(!obj({fund,currency:eq('CNY'),beforeId:nil(financeId),size:v=>Number.isInteger(v)&&v>=1&&v<=100,asRecordedAt:nil(financeUtc)})(query) || query.beforeId !== null && query.asRecordedAt === null) throw new FinanceReadError(null,'INVALID_INPUT')
}
async function read<T>(url: string, assert: (value: unknown) => void, signal?: AbortSignal): Promise<T> {
  const token=localStorage.getItem('token'), response=await fetch(`/api${url}`,{method:'GET',credentials:'same-origin',headers:token?{Authorization:`Bearer ${token}`}:{},signal,cache:'no-store'})
  if(response.status===401||response.status===403)window.dispatchEvent(new CustomEvent(FINANCE_ACCESS_REVOKED,{detail:response.status}))
  let payload:unknown
  try{payload=parseFinanceJson(new TextDecoder('utf-8',{fatal:true}).decode(await response.arrayBuffer()))}catch{throw new FinanceReadError(response.status,'INVALID_RESPONSE')}
  if(response.status!==200)throw new FinanceReadError(response.status,readError(payload,response.status))
  assert(payload)
  return (payload as FinanceEnvelope<T>).data
}
export function getMemberFinance(memberId: string, signal?: AbortSignal) { if(!financeId(memberId))throw new FinanceReadError(null,'INVALID_INPUT');return read<MemberFinance>(`/members/${memberId}/finance`,assertFinance,signal) }
export function getMemberFinanceSource(memberId: string, signal?: AbortSignal) { if(!financeId(memberId))throw new FinanceReadError(null,'INVALID_INPUT');return read<FinanceSource>(`/members/${memberId}/finance/source`,assertFinanceSource,signal) }
export function getMemberFinanceEntries(memberId: string, query: FinanceEntriesQuery, signal?: AbortSignal) {
  if(!financeId(memberId))throw new FinanceReadError(null,'INVALID_INPUT');assertQuery(query)
  const params=new URLSearchParams({fund:query.fund,currency:query.currency,size:String(query.size)})
  if(query.beforeId!==null)params.set('beforeId',query.beforeId)
  if(query.asRecordedAt!==null)params.set('asRecordedAt',query.asRecordedAt)
  return read<FinanceEntries>(`/members/${memberId}/finance/entries?${params}`,assertFinanceEntries,signal)
}
