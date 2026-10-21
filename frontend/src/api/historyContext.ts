import type { PageResult } from '@/types'

export type HistoryEntity = 'members' | 'books' | 'borrows'
export type HistoryRecordType = 'MEMBER' | 'ORDER' | 'BOOK' | 'PLAN'
export type HistoryFilter = 'ALL' | HistoryRecordType
export type HistoryIdentity = Record<'memberId' | 'username' | 'memberName' | 'cardNo' | 'bookId' | 'bookTitle' | 'isbn' | 'borrowId' | 'copyId' | 'barcode' | 'status' | 'borrowDate' | 'dueDate' | 'returnDate' | 'dueDateKind', string | null>
export interface HistoryContext {
  entityType: 'MEMBER' | 'BOOK' | 'BORROW'
  entityId: string
  identity: HistoryIdentity
  provenance: {
    classification: 'IMPORTED' | 'COUNTER_ORDER' | 'SYSTEM' | 'UNVERIFIED'
    sourceKey: string | null; sourceSystem: string | null
    associationState: 'VERIFIED' | 'NONE' | 'UNVERIFIED'
    basis: string[]; warnings: string[]
  }
  links: Record<'memberId' | 'bookId' | 'copyId' | 'counterOrderId' | 'memberArchiveId', string | null>
  archiveTypes: { recordType: string; total: number }[]
  readOnly: true
}
export interface HistoryRecord {
  id: string; sourceSystem: string; recordType: string; sourceKey: string
  displayName: string | null; summary: string | null; userId: string | null; bookId: string | null; sourceTime: string | null
  association: { scope: 'MEMBER_CONTEXT' | 'BOOK_CONTEXT' | 'COPY_CONTEXT'; basis: string; state: 'VERIFIED' | 'UNVERIFIED' }
}
export interface HistoryRecordDetail extends HistoryRecord { detailsJson: string }
export interface HistoryQuery { page: number; size: number; type: HistoryFilter }

export class HistoryReadError extends Error {
  constructor(public readonly status: number | null, message = '历史资料读取失败，请重试；读取失败不表示没有资料。') {
    super(message)
    this.name = 'HistoryReadError'
  }
}
const entityTypes = { members: 'MEMBER', books: 'BOOK', borrows: 'BORROW' } as const
const recordTypes = ['MEMBER', 'ORDER', 'BOOK', 'PLAN']
const identityKeys = ['memberId', 'username', 'memberName', 'cardNo', 'bookId', 'bookTitle', 'isbn', 'borrowId', 'copyId', 'barcode', 'status', 'borrowDate', 'dueDate', 'returnDate', 'dueDateKind']
const linkKeys = ['memberId', 'bookId', 'copyId', 'counterOrderId', 'memberArchiveId']
const object = (value: unknown): value is Record<string, any> => value !== null && typeof value === 'object' && !Array.isArray(value)
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const texts = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
const count = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0
export function historyId(value: unknown): string | null {
  if (typeof value === 'number' && (!Number.isSafeInteger(value) || value <= 0)) return null
  if (typeof value !== 'number' && typeof value !== 'string') return null
  const id = String(value), max = '9223372036854775807'
  return /^[1-9][0-9]{0,18}$/.exec(id)?.[0] === id && (id.length < max.length || id <= max) ? id : null
}
function invalid(): never { throw new HistoryReadError(null, '历史资料返回内容不完整，请重试核对。') }
function validRecord(value: unknown): value is HistoryRecord {
  return object(value) && historyId(value.id) === value.id && typeof value.sourceSystem === 'string' && typeof value.recordType === 'string'
    && typeof value.sourceKey === 'string' && ['displayName', 'summary', 'sourceTime'].every(key => nullableText(value[key]))
    && ['userId', 'bookId'].every(key => value[key] === null || historyId(value[key]) === value[key])
    && object(value.association) && ['MEMBER_CONTEXT', 'BOOK_CONTEXT', 'COPY_CONTEXT'].includes(value.association.scope)
    && typeof value.association.basis === 'string' && ['VERIFIED', 'UNVERIFIED'].includes(value.association.state)
}

/** Each instance uses the captured login token. Late failures never invalidate another login. */
export function createHistoryContextApi(token: string) {
  function path(entity: HistoryEntity, id: string) {
    if (!Object.prototype.hasOwnProperty.call(entityTypes, entity) || historyId(id) !== id) throw new HistoryReadError(null, '历史资料编号无效，请核对。')
    return `/api/history-context/${entity}/${id}`
  }
  async function read(url: string, signal?: AbortSignal): Promise<any> {
    let response: Response
    try { response = await fetch(url, { method: 'GET', headers: { Authorization: `Bearer ${token}` }, credentials: 'same-origin', cache: 'no-store', signal }) }
    catch (cause) { if (signal?.aborted) throw cause; throw new HistoryReadError(null) }
    if (response.status === 401 || response.status === 403) throw new HistoryReadError(response.status, '历史资料访问权限已失效，内容已清除。')
    if (!response.ok) throw new HistoryReadError(response.status, response.status === 404 ? '当前实体或来源档案不存在，请核对编号。' : undefined)
    let payload: any
    try { payload = await response.json() } catch { return invalid() }
    if (!object(payload) || payload.code !== 200 || !Object.prototype.hasOwnProperty.call(payload, 'data')) return invalid()
    return payload.data
  }
  return {
    async context(entity: HistoryEntity, id: string, signal?: AbortSignal): Promise<HistoryContext> {
      const value = await read(path(entity, id), signal)
      if (!object(value) || value.entityType !== entityTypes[entity] || value.entityId !== id || value.readOnly !== true
        || !object(value.identity) || !identityKeys.every(key => nullableText(value.identity[key]))
        || value.identity[entity === 'members' ? 'memberId' : entity === 'books' ? 'bookId' : 'borrowId'] !== id
        || !object(value.links) || !linkKeys.every(key => value.links[key] === null || historyId(value.links[key]) === value.links[key])
        || !object(value.provenance) || !['IMPORTED', 'COUNTER_ORDER', 'SYSTEM', 'UNVERIFIED'].includes(value.provenance.classification)
        || !nullableText(value.provenance.sourceKey) || !nullableText(value.provenance.sourceSystem)
        || !['VERIFIED', 'NONE', 'UNVERIFIED'].includes(value.provenance.associationState) || !texts(value.provenance.basis) || !texts(value.provenance.warnings)
        || !Array.isArray(value.archiveTypes) || !value.archiveTypes.every((item: unknown) => object(item) && typeof item.recordType === 'string' && count(item.total))) return invalid()
      return value as HistoryContext
    },
    async records(entity: HistoryEntity, id: string, query: HistoryQuery, signal?: AbortSignal): Promise<PageResult<HistoryRecord>> {
      if (!Number.isSafeInteger(query.page) || query.page < 1 || !Number.isInteger(query.size) || query.size < 1 || query.size > 100 || !['ALL', ...recordTypes].includes(query.type)) throw new HistoryReadError(null, '历史资料分页条件无效。')
      const params = new URLSearchParams({ page: String(query.page), size: String(query.size), type: query.type })
      const value = await read(`${path(entity, id)}/records?${params}`, signal)
      if (!object(value) || !Array.isArray(value.records) || value.records.length > query.size || !value.records.every(validRecord)
        || !count(value.total) || value.page !== query.page || value.size !== query.size || !count(value.pages)
        || new Set(value.records.map((item: HistoryRecord) => item.id)).size !== value.records.length
        || query.type !== 'ALL' && value.records.some((item: HistoryRecord) => item.recordType !== query.type)) return invalid()
      return value as PageResult<HistoryRecord>
    },
    async record(entity: HistoryEntity, id: string, recordId: string, signal?: AbortSignal): Promise<HistoryRecordDetail> {
      if (historyId(recordId) !== recordId) throw new HistoryReadError(null, '来源档案编号无效。')
      const value = await read(`${path(entity, id)}/records/${recordId}`, signal)
      if (!validRecord(value) || value.id !== recordId || typeof (value as HistoryRecordDetail).detailsJson !== 'string') return invalid()
      return value as HistoryRecordDetail
    },
  }
}
