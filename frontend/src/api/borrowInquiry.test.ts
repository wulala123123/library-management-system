import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { BorrowInquiryError, createBorrowInquiryApi } from './borrowInquiry'
import { inquiryAsOf, inquiryFilters, inquiryPage, inquiryRecord } from '@/views/borrow/borrowInquiryFixtures'

const fetcher = vi.fn()
const json = (data: unknown, status = 200, message = 'synthetic') => new Response(JSON.stringify({ code: status, message, data }), { status })
const csvText = '\ufeff导出时点,2026-10-21T14:00:00.123Z\r\n关键词," 000Aa "\r\n借阅状态,全部\r\n来源,ALL\r\n总量,21\r\n单位,条\r\n统计口径,当前筛选的借阅明细\r\n\r\n借阅明细编号,馆内条码\r\n501," 000Aa "\r\n'
const csv = (headers: Record<string, string> = {}, body = csvText) => new Response(body, { status: 200, headers: { 'Content-Type': 'text/csv;charset=UTF-8', 'Content-Disposition': 'attachment; filename="borrow-inquiry-20261021T140000Z.csv"', 'X-Total-Count': '21', 'X-As-Of': inquiryAsOf, ...headers } })
const blobText = (blob: Blob) => new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsText(blob) })
beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); vi.stubGlobal('fetch', fetcher) })
afterEach(() => vi.unstubAllGlobals())

it('captures the token and sends exact applied keyword/status/origin/page with GET and cancellation', async () => {
  const filters = { keyword: ' 000Aa ', status: 'ACTIVE' as const, origin: 'IMPORTED' as const }, row = { ...inquiryRecord(), origin: 'IMPORTED' as const }
  fetcher.mockResolvedValue(json(inquiryPage([row], filters, 2, 21)))
  const client = createBorrowInquiryApi('original'); localStorage.setItem('token', 'later'); const signal = new AbortController().signal
  const result = await client.query({ ...filters, page: 2, size: 10 }, signal)
  const [url, options] = fetcher.mock.calls[0]!, search = new URL(url, 'https://synthetic.test').searchParams
  expect(search.get('keyword')).toBe(' 000Aa '); expect(search.get('status')).toBe('ACTIVE'); expect(search.get('origin')).toBe('IMPORTED'); expect(search.get('page')).toBe('2'); expect(search.get('size')).toBe('10')
  expect(options).toEqual({ method: 'GET', headers: { Authorization: 'Bearer original' }, credentials: 'same-origin', cache: 'no-store', signal }); expect(result.records[0]!.copyBarcode).toBe(' 000Aa ')
})

it('accepts safe nullable columns and keeps raw date precision and unknown state', async () => {
  const row = { ...inquiryRecord(), copyId: null, userName: null, bookTitle: null, borrowDate: null, status: 'UNKNOWN_OLD', dueDateKind: null }
  fetcher.mockResolvedValue(json(inquiryPage([row]))); expect((await createBorrowInquiryApi('login').query({ ...inquiryFilters(), page: 1, size: 10 })).records[0]).toEqual(row)
})

it('rejects inconsistent origin totals, a wrong filter scope or an unsafe ID instead of presenting false facts', async () => {
  const client = createBorrowInquiryApi('login'), query = { ...inquiryFilters(), page: 1, size: 10 }
  const bad = inquiryPage(); bad.summary.imported = 1; fetcher.mockResolvedValueOnce(json(bad)); await expect(client.query(query)).rejects.toBeInstanceOf(BorrowInquiryError)
  fetcher.mockResolvedValueOnce(json(inquiryPage([], { ...inquiryFilters(), origin: 'IMPORTED' }))); await expect(client.query(query)).rejects.toBeInstanceOf(BorrowInquiryError)
  fetcher.mockResolvedValueOnce(json(inquiryPage([{ ...inquiryRecord(), id: Number.MAX_SAFE_INTEGER + 1 }]))); await expect(client.query(query)).rejects.toBeInstanceOf(BorrowInquiryError)
})

it('rejects malformed counter links and fabricated timestamp dates', async () => {
  const client = createBorrowInquiryApi('login'), query = { ...inquiryFilters(), page: 1, size: 10 }
  fetcher.mockResolvedValueOnce(json(inquiryPage([{ ...inquiryRecord(), counterOrderId: 401 }]))); await expect(client.query(query)).rejects.toBeInstanceOf(BorrowInquiryError)
  fetcher.mockResolvedValueOnce(json(inquiryPage([{ ...inquiryRecord(), borrowDate: '2026-10-21T00:00:00' }]))); await expect(client.query(query)).rejects.toBeInstanceOf(BorrowInquiryError)
})

it('requests complete CSV without pagination and retains the BOM, headers metadata and exact blob bytes', async () => {
  fetcher.mockResolvedValue(csv()); const result = await createBorrowInquiryApi('login').export({ ...inquiryFilters(), keyword: ' 000Aa ' })
  const [url, options] = fetcher.mock.calls[0]!, search = new URL(url, 'https://synthetic.test').searchParams
  expect(url).toContain('/api/borrow-inquiry/export?'); expect(search.get('keyword')).toBe(' 000Aa '); expect(search.has('page')).toBe(false); expect(search.has('size')).toBe(false); expect(search.has('status')).toBe(false); expect(options.method).toBe('GET')
  expect(result).toMatchObject({ filename: 'borrow-inquiry-20261021T140000Z.csv', total: 21, asOf: inquiryAsOf }); expect(result.blob.type).toBe('text/csv;charset=utf-8'); expect(result.blob.size).toBe(new TextEncoder().encode(csvText).length)
  expect(await blobText(result.blob)).toBe(csvText.slice(1))
})

const invalidHeaders: Record<string, string>[] = [{ 'Content-Type': 'application/json' }, { 'X-Total-Count': '20001' }, { 'X-Total-Count': '021' }, { 'X-As-Of': 'unknown' }, { 'Content-Disposition': 'inline' }, { 'Content-Disposition': 'attachment; filename="../secret.csv"' }]
it.each(invalidHeaders)('does not return a download for invalid export headers %j', async headers => {
  fetcher.mockResolvedValue(csv(headers)); await expect(createBorrowInquiryApi('login').export(inquiryFilters())).rejects.toBeInstanceOf(BorrowInquiryError)
})

it('rejects a non-BOM/error body and reports an over-limit failure without yielding a file', async () => {
  fetcher.mockResolvedValueOnce(csv({}, '<html>gateway error</html>')); await expect(createBorrowInquiryApi('login').export(inquiryFilters())).rejects.toThrow('返回内容不完整')
  fetcher.mockResolvedValueOnce(json(null, 400, '结果超过20000条，请缩小筛选范围')); await expect(createBorrowInquiryApi('login').export(inquiryFilters())).rejects.toThrow('请缩小筛选范围')
})

it('keeps auth errors local and propagates read cancellation without any global auth event', async () => {
  const listener = vi.fn(); window.addEventListener('auth-access-invalidated', listener)
  try {
    fetcher.mockResolvedValueOnce(json(null, 403)); await expect(createBorrowInquiryApi('old').query({ ...inquiryFilters(), page: 1, size: 10 })).rejects.toMatchObject({ status: 403 }); expect(listener).not.toHaveBeenCalled()
    const controller = new AbortController(), aborted = new DOMException('aborted', 'AbortError'); controller.abort(); fetcher.mockRejectedValueOnce(aborted)
    await expect(createBorrowInquiryApi('login').export(inquiryFilters(), controller.signal)).rejects.toBe(aborted)
  } finally { window.removeEventListener('auth-access-invalidated', listener) }
})

it('rejects invalid filter/page values before requesting and reports transport failure clearly', async () => {
  const client = createBorrowInquiryApi('login')
  await expect(client.query({ ...inquiryFilters(), page: 0, size: 10 })).rejects.toThrow('分页条件无效'); await expect(client.export({ ...inquiryFilters(), origin: 'GUESS' as any })).rejects.toThrow('筛选条件无效'); expect(fetcher).not.toHaveBeenCalled()
  fetcher.mockRejectedValueOnce(Error('network')); await expect(client.query({ ...inquiryFilters(), page: 1, size: 10 })).rejects.toThrow('读取失败不表示没有借阅明细')
})
