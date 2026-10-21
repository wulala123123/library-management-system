import { readCsvDownload, CsvDownloadError } from './csvDownload'
export type BorrowOrigin = 'IMPORTED' | 'COUNTER_ORDER' | 'SYSTEM' | 'UNVERIFIED'
export type BorrowOriginFilter = 'ALL' | BorrowOrigin
export type BorrowStatusFilter = 'ACTIVE' | 'BORROWING' | 'OVERDUE' | 'RETURNED' | null
export interface BorrowInquiryFilters { keyword: string; status: BorrowStatusFilter; origin: BorrowOriginFilter }
export interface BorrowInquiryQuery extends BorrowInquiryFilters { page: number; size: number }
export interface BorrowInquiryRecord {
  id: number; userId: number; bookId: number; copyId: number | null; counterOrderId: number | null
  userName: string | null; userRealName: string | null; bookTitle: string | null; bookIsbn: string | null; bookCoverImage: string | null; copyBarcode: string | null
  borrowDate: string | null; dueDate: string | null; returnDate: string | null; dueDateKind: string | null; status: string | null; remark: string | null
  renewCount: number; fineAmount: number; origin: BorrowOrigin
}
export interface BorrowInquirySummary { total: number; imported: number; counterOrder: number; system: number; unverified: number; unit: '条'; scope: string }
export interface BorrowInquiryPage {
  records: BorrowInquiryRecord[]; total: number; page: number; size: number; pages: number; asOf: string
  filters: BorrowInquiryFilters; summary: BorrowInquirySummary
}
export interface BorrowInquiryExport { blob: Blob; filename: string; total: number; asOf: string }
export interface BorrowInquiryApi {
  query(query: BorrowInquiryQuery, signal?: AbortSignal): Promise<BorrowInquiryPage>
  export(filters: BorrowInquiryFilters, signal?: AbortSignal): Promise<BorrowInquiryExport>
}
export const borrowOriginNames: Record<BorrowOriginFilter, string> = { ALL: '全部来源', IMPORTED: '迁入资料', COUNTER_ORDER: '新柜台订单', SYSTEM: '本系统', UNVERIFIED: '待核实' }
export const borrowStatusNames = { ACTIVE: '全部未还', BORROWING: '借阅中', OVERDUE: '已逾期', RETURNED: '已归还' }
export class BorrowInquiryError extends Error {
  constructor(public readonly status: number | null, message = '借阅查询失败，请重试；读取失败不表示没有借阅明细。') { super(message); this.name = 'BorrowInquiryError' }
}
const object = (value: unknown): value is Record<string, any> => value !== null && typeof value === 'object' && !Array.isArray(value)
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const id = (value: unknown) => count(value) && value > 0
const text = (value: unknown) => typeof value === 'string'
const nullable = (check: (value: unknown) => boolean) => (value: unknown) => value === null || check(value)
const origins: BorrowOriginFilter[] = ['ALL', 'IMPORTED', 'COUNTER_ORDER', 'SYSTEM', 'UNVERIFIED']
const statuses = [null, 'ACTIVE', 'BORROWING', 'OVERDUE', 'RETURNED']
const whole = (expression: RegExp, value: string) => expression.exec(value)?.[0] === value
const date = (value: unknown) => typeof value === 'string' && whole(/^[0-9]{4}-[0-9]{2}-[0-9]{2}$/, value)
const instant = (value: unknown): value is string => typeof value === 'string' && whole(/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{1,9})?Z$/, value) && Number.isFinite(Date.parse(value))
function invalid(): never { throw new BorrowInquiryError(null, '借阅查询返回内容不完整，请重新读取核对。') }
function params(filters: BorrowInquiryFilters) {
  if (!object(filters) || !text(filters.keyword) || !statuses.includes(filters.status) || !origins.includes(filters.origin)) throw new BorrowInquiryError(null, '借阅筛选条件无效，请核对。')
  const result = new URLSearchParams({ keyword: filters.keyword, origin: filters.origin })
  if (filters.status !== null) result.set('status', filters.status)
  return result
}
function validRecord(value: unknown): value is BorrowInquiryRecord {
  return object(value) && ['id', 'userId', 'bookId'].every(key => id(value[key])) && ['copyId', 'counterOrderId'].every(key => nullable(id)(value[key]))
    && ['userName', 'userRealName', 'bookTitle', 'bookIsbn', 'bookCoverImage', 'copyBarcode', 'dueDateKind', 'status', 'remark'].every(key => nullable(text)(value[key]))
    && ['borrowDate', 'dueDate', 'returnDate'].every(key => nullable(date)(value[key])) && count(value.renewCount)
    && typeof value.fineAmount === 'number' && Number.isFinite(value.fineAmount) && origins.includes(value.origin) && value.origin !== 'ALL'
    && (value.origin === 'COUNTER_ORDER' ? id(value.counterOrderId) : value.counterOrderId === null)
}
function sameFilters(left: BorrowInquiryFilters, right: BorrowInquiryFilters) { return left.keyword === right.keyword && left.status === right.status && left.origin === right.origin }

/** Read-only client; the token is fixed for the lifetime of this request owner. */
export function createBorrowInquiryApi(token: string): BorrowInquiryApi {
  async function read(url: string, signal?: AbortSignal) {
    let response: Response
    try { response = await fetch(url, { method: 'GET', headers: { Authorization: `Bearer ${token}` }, credentials: 'same-origin', cache: 'no-store', signal }) }
    catch (cause) { if (signal?.aborted) throw cause; throw new BorrowInquiryError(null) }
    if (response.status === 401 || response.status === 403) throw new BorrowInquiryError(response.status, '借阅查询权限已失效，已清除查询内容，请重新登录核对权限。')
    if (!response.ok) {
      let message = response.status === 400 ? '借阅查询条件无效或结果超过导出上限，请缩小筛选范围后重试。' : '借阅查询失败，请重试；本次失败不表示没有明细。'
      try { const body = await response.json(); if (object(body) && typeof body.message === 'string' && body.message.length > 0) message = body.message } catch { /* Retain the visible failure. */ }
      throw new BorrowInquiryError(response.status, message)
    }
    return response
  }
  return {
    async query(query, signal) {
      const search = params(query)
      if (!id(query.page) || !id(query.size) || query.size > 100) throw new BorrowInquiryError(null, '借阅分页条件无效。')
      search.set('page', String(query.page)); search.set('size', String(query.size))
      const response = await read(`/api/borrow-inquiry?${search}`, signal)
      let payload: any
      try { payload = await response.json() } catch { return invalid() }
      const value = payload?.data
      if (!object(payload) || payload.code !== 200 || !object(value) || !Array.isArray(value.records) || value.records.length > query.size || !value.records.every(validRecord)
        || !count(value.total) || value.page !== query.page || value.size !== query.size || value.pages !== Math.ceil(value.total / query.size) || !instant(value.asOf)
        || !object(value.filters) || !sameFilters(value.filters as BorrowInquiryFilters, query) || !object(value.summary)
        || !['total', 'imported', 'counterOrder', 'system', 'unverified'].every(key => count(value.summary[key]))
        || value.summary.total !== value.total || value.summary.imported + value.summary.counterOrder + value.summary.system + value.summary.unverified !== value.total
        || value.summary.unit !== '条' || typeof value.summary.scope !== 'string' || new Set(value.records.map((row: BorrowInquiryRecord) => row.id)).size !== value.records.length
        || query.origin !== 'ALL' && value.records.some((row: BorrowInquiryRecord) => row.origin !== query.origin)) return invalid()
      return value as BorrowInquiryPage
    },
    async export(filters, signal) {
      const response = await read(`/api/borrow-inquiry/export?${params(filters)}`, signal)
      try { return await readCsvDownload(response, /^borrow-inquiry-[0-9]{8}T[0-9]{6}Z\.csv$/i, 20000, signal) }
      catch (cause) { if (!(cause instanceof CsvDownloadError)) throw cause; if (cause.message.includes('读取失败')) throw new BorrowInquiryError(null, cause.message); return invalid() }
    },
  }
}
