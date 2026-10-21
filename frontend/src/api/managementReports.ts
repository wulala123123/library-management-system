import { readCsvDownload, csvInstant, type CsvDownload } from './csvDownload'
import type { BorrowInquiryFilters, BorrowInquirySummary } from './borrowInquiry'
import type { PageResult } from '@/types'
export type ManagementList = 'members' | 'books' | 'inventory'
export type ManagementFilters = Record<string, string | number | string[] | null | undefined>
export interface BorrowReportFilters extends BorrowInquiryFilters { startDate: string | null; endDate: string | null }
export interface BorrowOperationsReport {
  asOf: string; asOfDate: string; filters: BorrowReportFilters
  totals: Record<'borrowDetails' | 'borrowedCopyEvents' | 'activeDetails' | 'returnedDetails' | 'overdueDetails' | 'knownCounterOrders', number>
  origins: BorrowInquirySummary
  trend: { date: string; label: string; borrowDetails: number; knownCounterOrders: number }[]
  hotBooks: { bookId: number; title: string; borrowDetails: number; knownCounterOrders: number }[]
  scope: string
}
export class ManagementReadError extends Error { constructor(public readonly status: number | null, message: string) { super(message); this.name = 'ManagementReadError' } }
const object = (v: unknown): v is Record<string, any> => v !== null && typeof v === 'object' && !Array.isArray(v)
const count = (v: unknown) => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const day = (v: unknown) => typeof v === 'string' && /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/.exec(v)?.[0] === v
function params(filters: ManagementFilters, allowed: string[]) {
  const result = new URLSearchParams()
  for (const [key, value] of Object.entries(filters)) {
    if (value == null || value === '') { if (key === 'keyword' && value === '') result.set(key, ''); continue }
    if (!allowed.includes(key) || (Array.isArray(value) ? !value.every(item => typeof item === 'string') : typeof value !== 'number' && typeof value !== 'string')) throw new ManagementReadError(null, '导出筛选条件无效，请核对。')
    if (Array.isArray(value)) value.forEach(item => result.append(key, item)); else result.set(key, String(value))
  }
  return result
}
const reportKeys = ['keyword', 'status', 'origin', 'startDate', 'endDate']
export function createManagementReportsApi(token: string) {
  async function read(path: string, signal?: AbortSignal) {
    let response: Response
    try { response = await fetch(`/api${path}`, { method: 'GET', headers: { Authorization: `Bearer ${token}` }, credentials: 'same-origin', cache: 'no-store', signal }) }
    catch (cause) { if (signal?.aborted) throw cause; throw new ManagementReadError(null, '统计或导出读取失败，请重试；失败不表示没有资料。') }
    if (!response.ok) {
      let message = response.status === 401 || response.status === 403 ? '管理员读取权限已失效，内容已清除。' : '统计或导出读取失败，请重试。'
      try { const body = await response.json(); if (object(body) && typeof body.message === 'string' && body.message) message = body.message } catch { /* Retain the failure. */ }
      throw new ManagementReadError(response.status, message)
    }
    return response
  }
  return {
    async listPage<T>(kind: ManagementList, query: ManagementFilters, signal?: AbortSignal): Promise<PageResult<T>> {
      const routes = { members: '/members', books: '/catalog-books', inventory: '/books' }
      const allowed = { members: ['page', 'size', 'keyword', 'accountStatus', 'serviceStatus'], books: ['page', 'size', 'keyword', 'categoryId', 'status', 'bindingCode', 'bindingState', 'ageLabels', 'ageState'], inventory: ['page', 'size', 'keyword', 'categoryId', 'status'] }
      const response = await read(`${routes[kind]}?${params(query, allowed[kind])}`, signal)
      let value: any
      try { const envelope = await response.json(); if (envelope.code === 200) value = envelope.data } catch { /* Validated below. */ }
      if (!object(value) || !Array.isArray(value.records) || !count(value.total)) throw new ManagementReadError(null, '列表返回内容不完整，请重新读取。')
      return value as PageResult<T>
    },
    async report(filters: BorrowReportFilters, signal?: AbortSignal): Promise<BorrowOperationsReport> {
      const response = await read(`/management-reports/borrows?${params({ ...filters }, reportKeys)}`, signal)
      let envelope: any
      try { envelope = await response.json() } catch { throw new ManagementReadError(null, '借阅报告返回内容不完整，请重新读取。') }
      const value = envelope?.data
      if (!object(envelope) || envelope.code !== 200 || !object(value) || !csvInstant(value.asOf) || !day(value.asOfDate) || !object(value.filters) || reportKeys.some(key => value.filters[key] !== filters[key as keyof BorrowReportFilters])
        || !object(value.totals) || !['borrowDetails', 'borrowedCopyEvents', 'activeDetails', 'returnedDetails', 'overdueDetails', 'knownCounterOrders'].every(key => count(value.totals[key]))
        || !object(value.origins) || !['total', 'imported', 'counterOrder', 'system', 'unverified'].every(key => count(value.origins[key])) || value.origins.total !== value.totals.borrowDetails
        || value.origins.imported + value.origins.counterOrder + value.origins.system + value.origins.unverified !== value.origins.total || value.origins.unit !== '条' || typeof value.origins.scope !== 'string'
        || !Array.isArray(value.trend) || !value.trend.every((item: any) => object(item) && day(item.date) && typeof item.label === 'string' && count(item.borrowDetails) && count(item.knownCounterOrders))
        || !Array.isArray(value.hotBooks) || !value.hotBooks.every((item: any) => object(item) && count(item.bookId) && item.bookId > 0 && typeof item.title === 'string' && count(item.borrowDetails) && count(item.knownCounterOrders)) || typeof value.scope !== 'string') throw new ManagementReadError(null, '借阅报告返回内容不完整，请重新读取。')
      return value as BorrowOperationsReport
    },
    async reportCsv(filters: BorrowReportFilters, signal?: AbortSignal): Promise<CsvDownload> { return readCsvDownload(await read(`/management-reports/borrows/export?${params({ ...filters }, reportKeys)}`, signal), /^management-borrows-[0-9]{8}T[0-9]{6}Z\.csv$/i, null, signal) },
    async listCsv(kind: ManagementList, filters: ManagementFilters, signal?: AbortSignal): Promise<CsvDownload> {
      const allowed = { members: ['keyword', 'accountStatus', 'serviceStatus'], books: ['keyword', 'categoryId', 'status', 'bindingCode', 'bindingState', 'ageLabels', 'ageState'], inventory: ['keyword', 'categoryId', 'status'] }
      if (!Object.prototype.hasOwnProperty.call(allowed, kind)) throw new ManagementReadError(null, '导出列表类型无效。')
      return readCsvDownload(await read(`/management-exports/${kind}?${params(filters, allowed[kind])}`, signal), new RegExp(`^management-${kind}-[0-9]{8}T[0-9]{6}Z\\.csv$`, 'i'), 20000, signal)
    },
  }
}
