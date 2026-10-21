import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createManagementReportsApi, ManagementReadError } from './managementReports'
import { managementAsOf, operationsReport, reportFilters } from '@/components/statistics/managementFixtures'
const fetcher = vi.fn()
const json = (data: unknown, status = 200, message = 'synthetic') => new Response(JSON.stringify({ code: status, message, data }), { status })
const csv = (kind = 'members', total = '21', headers: Record<string, string> = {}, raw = '\ufeff序号,会员编号,卡号\r\n1,5,000005\r\n') => new Response(raw, { headers: { 'content-type': 'text/csv;charset=UTF-8', 'content-disposition': `attachment; filename="management-${kind}-20261021T160000Z.csv"`, 'x-total-count': total, 'x-as-of': managementAsOf, ...headers } })
beforeEach(() => { vi.resetAllMocks(); vi.stubGlobal('fetch', fetcher) })
afterEach(() => vi.unstubAllGlobals())
it('uses captured admin token, exact date/filter scope and a single GET for a unified report', async () => {
  const filters = { ...reportFilters(), keyword: ' 000Aa ', startDate: '2026-10-01', endDate: '2026-10-21' }; fetcher.mockResolvedValue(json(operationsReport(filters)))
  const signal = new AbortController().signal; const result = await createManagementReportsApi('captured').report(filters, signal)
  const [url, config] = fetcher.mock.calls[0]!, parsed = new URL(url, 'https://synthetic.test'); expect(parsed.pathname).toBe('/api/management-reports/borrows'); expect(parsed.searchParams.get('keyword')).toBe(' 000Aa '); expect(parsed.searchParams.get('startDate')).toBe('2026-10-01'); expect(parsed.searchParams.get('endDate')).toBe('2026-10-21'); expect(parsed.searchParams.has('page')).toBe(false)
  expect(config).toEqual({ method: 'GET', headers: { Authorization: 'Bearer captured' }, cache: 'no-store', credentials: 'same-origin', signal }); expect(result.totals.borrowDetails).toBe(37)
})
it('rejects mismatched filters and inconsistent source totals instead of showing false statistics', async () => {
  const client = createManagementReportsApi('token'), bad = operationsReport(); bad.origins.total = 500; fetcher.mockResolvedValueOnce(json(bad)); await expect(client.report(reportFilters())).rejects.toThrow('返回内容不完整')
  fetcher.mockResolvedValueOnce(json(operationsReport({ ...reportFilters(), startDate: '2026-01-01' }))); await expect(client.report(reportFilters())).rejects.toThrow('返回内容不完整')
})
it('accepts report aggregation CSV coverage above the list export limit', async () => {
  fetcher.mockResolvedValue(csv('borrows', '30000')); const result = await createManagementReportsApi('token').reportCsv(reportFilters()); expect(result.total).toBe(30000); expect(fetcher.mock.calls[0]![0]).toContain('/management-reports/borrows/export?')
})
it('exports complete member filters including zero account status and never accepts page parameters', async () => {
  fetcher.mockResolvedValue(csv()); await createManagementReportsApi('token').listCsv('members', { keyword: '000005', accountStatus: 0, serviceStatus: 'UNKNOWN' }); const params = new URL(fetcher.mock.calls[0]![0], 'https://synthetic.test').searchParams
  expect(params.get('accountStatus')).toBe('0'); expect(params.get('keyword')).toBe('000005'); expect(params.get('serviceStatus')).toBe('UNKNOWN'); expect(params.has('page')).toBe(false)
  await expect(createManagementReportsApi('token').listCsv('members', { page: 1 })).rejects.toThrow('筛选条件无效')
})
it('sends repeated book age labels, optional filters and the inventory real all-status scope', async () => {
  fetcher.mockResolvedValueOnce(csv('books')); await createManagementReportsApi('token').listCsv('books', { keyword: ' 00012 ', categoryId: 7, status: 0, ageLabels: ['AGE_0_2', 'AGE_5_6'], ageState: 'KNOWN', bindingCode: 'BOARD', bindingState: 'KNOWN' }); const params = new URL(fetcher.mock.calls[0]![0], 'https://synthetic.test').searchParams
  expect(params.getAll('ageLabels')).toEqual(['AGE_0_2', 'AGE_5_6']); expect(params.get('status')).toBe('0'); expect(params.get('bindingCode')).toBe('BOARD'); expect(params.has('size')).toBe(false)
  fetcher.mockResolvedValueOnce(csv('inventory')); await createManagementReportsApi('token').listCsv('inventory', {}); expect(new URL(fetcher.mock.calls[1]![0], 'https://synthetic.test').searchParams.has('status')).toBe(false)
})
it.each(['members', 'books', 'inventory'] as const)('reads existing %s pages through a local cancellable transport without changing the endpoint', async kind => {
  fetcher.mockResolvedValue(json({ records: [], total: 0, page: 2, size: 10, pages: 0 })); const result = await createManagementReportsApi('token').listPage(kind, { page: 2, size: 10 }); expect(result.total).toBe(0); expect(fetcher.mock.calls[0]![0]).toContain(({ members: '/api/members?', books: '/api/catalog-books?', inventory: '/api/books?' })[kind])
})
it.each([{ 'content-type': 'application/json' }, { 'content-disposition': 'attachment; filename="../unsafe.csv"' }, { 'x-total-count': '20001' }, { 'x-as-of': 'unknown' }] as Record<string, string>[])('rejects unsafe list file headers %j without yielding a blob', async headers => { fetcher.mockResolvedValue(csv('members', '21', headers)); await expect(createManagementReportsApi('token').listCsv('members', {})).rejects.toThrow('返回内容不完整') })
it('preserves exact BOM/file bytes, zero/null text and leading zeros without reconstructing CSV', async () => {
  const raw = '\ufeff序号,会员编号,卡号,总次数\r\n1,5,000005,0\r\n2,6,,未记录\r\n'; fetcher.mockResolvedValue(csv('members', '2', {}, raw)); const file = await createManagementReportsApi('token').listCsv('members', {}); expect(file.blob.size).toBe(new TextEncoder().encode(raw).length); expect(file.total).toBe(2)
  fetcher.mockResolvedValueOnce(csv('members', '0', {}, 'bad body')); await expect(createManagementReportsApi('token').listCsv('members', {})).rejects.toThrow('返回内容不完整')
})
it('reports over-limit and local auth failure without globally revoking a later login', async () => {
  const listener = vi.fn(); window.addEventListener('auth-access-invalidated', listener)
  try { fetcher.mockResolvedValueOnce(json(null, 400, '超过20000条，请缩小筛选范围')); await expect(createManagementReportsApi('old').listCsv('members', {})).rejects.toThrow('请缩小筛选范围'); fetcher.mockResolvedValueOnce(json(null, 403)); await expect(createManagementReportsApi('old').listCsv('members', {})).rejects.toBeInstanceOf(ManagementReadError); expect(listener).not.toHaveBeenCalled() } finally { window.removeEventListener('auth-access-invalidated', listener) }
})
