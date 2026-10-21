import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { BorrowInquiryError, type BorrowInquiryApi, type BorrowInquiryExport, type BorrowInquiryPage } from '@/api/borrowInquiry'
import { useBorrowInquiry } from './borrowInquiry'
import { deferred, inquiryFile, inquiryFilters, inquiryPage, inquiryRecord } from './borrowInquiryFixtures'

const actor = reactive({ id: 1 as number | undefined, role: 'ADMIN' as string | undefined, token: 'login-one' })
const query = vi.fn(), exportFile = vi.fn(), download = vi.fn(), makeApi = vi.fn((_token: string): BorrowInquiryApi => ({ query, export: exportFile }))
const wrappers: VueWrapper[] = []
function session() { let inquiry!: ReturnType<typeof useBorrowInquiry>; const wrapper = mount(defineComponent({ setup() { inquiry = useBorrowInquiry(() => ({ ...actor }), makeApi, download); return () => h('div') } })); wrappers.push(wrapper); return { inquiry, wrapper } }
beforeEach(() => { vi.resetAllMocks(); Object.assign(actor, { id: 1, role: 'ADMIN', token: 'login-one' }); query.mockImplementation(input => Promise.resolve(inquiryPage([inquiryRecord()], input, input.page, 21, input.size))); exportFile.mockResolvedValue(inquiryFile()) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it.each(['READER', undefined])('does not read/export for a nonstaff role %s', async role => {
  actor.role = role; const { inquiry } = session(); await flushPromises(); await inquiry.exportAll(); expect(query).not.toHaveBeenCalled(); expect(exportFile).not.toHaveBeenCalled(); expect(download).not.toHaveBeenCalled(); expect(inquiry.data.value).toBeNull()
})

it('owns an applied condition snapshot and uses it for paging/exports while input remains unapplied', async () => {
  const { inquiry } = session(); await flushPromises(); Object.assign(inquiry.draft, { keyword: ' 000Aa ', status: 'ACTIVE', origin: 'IMPORTED' }); inquiry.apply(); await flushPromises()
  expect(query).toHaveBeenLastCalledWith({ keyword: ' 000Aa ', status: 'ACTIVE', origin: 'IMPORTED', page: 1, size: 10 }, expect.any(AbortSignal))
  inquiry.draft.keyword = '未应用输入'; expect(inquiry.unapplied.value).toBe(true); inquiry.changePage(2); await flushPromises(); expect(query).toHaveBeenLastCalledWith({ keyword: ' 000Aa ', status: 'ACTIVE', origin: 'IMPORTED', page: 2, size: 10 }, expect.any(AbortSignal))
  await inquiry.exportAll(); expect(exportFile).toHaveBeenCalledExactlyOnceWith({ keyword: ' 000Aa ', status: 'ACTIVE', origin: 'IMPORTED' }, expect.any(AbortSignal)); expect(download).toHaveBeenCalledWith(expect.any(Blob), 'borrow-inquiry-20261021T140000Z.csv')
})

it('resets page on filter/size/reset and does not silently apply draft fields on refresh', async () => {
  const { inquiry } = session(); await flushPromises(); inquiry.changePage(3); await flushPromises(); inquiry.draft.keyword = '新输入'; await inquiry.reload(); expect(query).toHaveBeenLastCalledWith({ ...inquiryFilters(), page: 3, size: 10 }, expect.any(AbortSignal))
  inquiry.apply(); await flushPromises(); expect(inquiry.pagination.page).toBe(1); inquiry.changePage(2); await flushPromises(); inquiry.changeSize(50); await flushPromises(); expect(query).toHaveBeenLastCalledWith({ keyword: '新输入', status: null, origin: 'ALL', page: 1, size: 50 }, expect.any(AbortSignal))
  inquiry.reset(); await flushPromises(); expect(query).toHaveBeenLastCalledWith({ ...inquiryFilters(), page: 1, size: 50 }, expect.any(AbortSignal))
})

it('shows read errors distinctly from empty results and blocks export until a valid retry succeeds', async () => {
  query.mockRejectedValueOnce(Error('offline')); const { inquiry } = session(); await flushPromises(); expect(inquiry.error.value).toContain('读取失败不表示没有借阅明细'); expect(inquiry.data.value).toBeNull(); await inquiry.exportAll(); expect(exportFile).not.toHaveBeenCalled()
  query.mockResolvedValueOnce(inquiryPage([])); await inquiry.reload(); expect(inquiry.error.value).toBe(''); expect(inquiry.data.value?.total).toBe(0); expect(inquiry.canExport.value).toBe(true)
})

it('aborts obsolete page reads and rejects a late earlier filter response', async () => {
  const first = deferred<BorrowInquiryPage>(); query.mockReturnValueOnce(first.promise); const { inquiry } = session(); const firstSignal = query.mock.calls[0]![1]
  inquiry.draft.origin = 'COUNTER_ORDER'; const row = { ...inquiryRecord(502), origin: 'COUNTER_ORDER' as const, counterOrderId: 401 }; query.mockResolvedValueOnce(inquiryPage([row], { ...inquiryFilters(), origin: 'COUNTER_ORDER' })); inquiry.apply(); await flushPromises(); expect(firstSignal.aborted).toBe(true)
  first.resolve(inquiryPage([{ ...inquiryRecord(), userName: '迟到旧筛选' }])); await flushPromises(); expect(inquiry.records.value[0]!.id).toBe(502); expect(inquiry.records.value.some(record => record.userName === '迟到旧筛选')).toBe(false)
})

it.each(['id', 'token', 'role'] as const)('clears old query ownership on %s change and cannot apply its late rows', async key => {
  const first = deferred<BorrowInquiryPage>(); query.mockReturnValueOnce(first.promise); const { inquiry } = session(); const signal = query.mock.calls[0]![1]
  if (key === 'id') actor.id = 2
  if (key === 'token') actor.token = 'login-two'
  if (key === 'role') actor.role = 'LIBRARIAN'
  await flushPromises(); first.resolve(inquiryPage([{ ...inquiryRecord(), userName: '旧身份资料' }])); await flushPromises(); expect(signal.aborted).toBe(true); expect(inquiry.records.value.some(record => record.userName === '旧身份资料')).toBe(false)
})

it('ignores an old identity 403, but clears current denied query state and never globally revokes another module', async () => {
  const first = deferred<BorrowInquiryPage>(); query.mockReturnValueOnce(first.promise); const listener = vi.fn(); window.addEventListener('auth-access-invalidated', listener)
  try {
    const { inquiry } = session(); actor.token = 'login-two'; await flushPromises(); first.reject(new BorrowInquiryError(403)); await flushPromises(); expect(inquiry.records.value).toHaveLength(1); expect(inquiry.error.value).toBe(''); expect(listener).not.toHaveBeenCalled()
    query.mockRejectedValueOnce(new BorrowInquiryError(403)); await inquiry.reload(); expect(inquiry.data.value).toBeNull(); expect(inquiry.error.value).toContain('权限已失效'); expect(inquiry.canExport.value).toBe(false); await inquiry.reload(); expect(query).toHaveBeenCalledTimes(3)
  } finally { window.removeEventListener('auth-access-invalidated', listener) }
})

it('guards repeated export and downloads the complete response blob once with its own total/time', async () => {
  const { inquiry } = session(); await flushPromises(); const file = deferred<BorrowInquiryExport>(); exportFile.mockReturnValueOnce(file.promise)
  const first = inquiry.exportAll(); const second = inquiry.exportAll(); expect(inquiry.exportBusy.value).toBe(true); expect(exportFile).toHaveBeenCalledOnce(); expect(download).not.toHaveBeenCalled()
  const complete = inquiryFile(123); file.resolve(complete); await Promise.all([first, second]); expect(download).toHaveBeenCalledExactlyOnceWith(complete.blob, complete.filename); expect(inquiry.lastExport.value).toEqual({ total: 123, asOf: complete.asOf }); expect(inquiry.exportBusy.value).toBe(false)
})

it.each(['apply', 'input', 'page'] as const)('aborts export when %s changes and rejects a late CSV without download', async change => {
  const { inquiry } = session(); await flushPromises(); const file = deferred<BorrowInquiryExport>(); exportFile.mockReturnValueOnce(file.promise); const exporting = inquiry.exportAll(); const signal = exportFile.mock.calls[0]![1]
  if (change === 'apply') { inquiry.draft.origin = 'IMPORTED'; inquiry.apply() }
  if (change === 'input') inquiry.draft.keyword = '尚未应用'
  if (change === 'page') inquiry.changePage(2)
  await flushPromises(); file.resolve(inquiryFile()); await exporting; expect(signal.aborted).toBe(true); expect(download).not.toHaveBeenCalled(); expect(inquiry.lastExport.value).toBeNull()
})

it.each(['id', 'role', 'token', 'unmount'] as const)('does not download an old export after %s changes', async change => {
  const { inquiry, wrapper } = session(); await flushPromises(); const file = deferred<BorrowInquiryExport>(); exportFile.mockReturnValueOnce(file.promise); const exporting = inquiry.exportAll(); const signal = exportFile.mock.calls[0]![1]
  if (change === 'id') actor.id = 2
  if (change === 'role') actor.role = 'READER'
  if (change === 'token') actor.token = 'login-two'
  if (change === 'unmount') wrapper.unmount()
  await flushPromises(); file.resolve(inquiryFile()); await exporting; expect(signal.aborted).toBe(true); expect(download).not.toHaveBeenCalled(); expect(inquiry.lastExport.value).toBeNull()
})

it('does not download failures or over-limit responses and permits an explicit retry after narrowing filters', async () => {
  const { inquiry } = session(); await flushPromises(); exportFile.mockRejectedValueOnce(new BorrowInquiryError(400, '结果超过20000条，请缩小筛选范围')); await inquiry.exportAll(); expect(inquiry.exportError.value).toContain('请缩小筛选范围'); expect(download).not.toHaveBeenCalled(); expect(inquiry.lastExport.value).toBeNull(); expect(inquiry.records.value).toHaveLength(1)
  inquiry.draft.origin = 'SYSTEM'; inquiry.apply(); await flushPromises(); await inquiry.exportAll(); expect(download).toHaveBeenCalledOnce()
})

it('ignores an old export 403 after a new login and revokes only a current owner export', async () => {
  const { inquiry } = session(); await flushPromises(); const file = deferred<BorrowInquiryExport>(); exportFile.mockReturnValueOnce(file.promise); const exporting = inquiry.exportAll(); actor.token = 'login-two'; await flushPromises(); file.reject(new BorrowInquiryError(403)); await exporting; expect(inquiry.error.value).toBe(''); expect(inquiry.records.value).toHaveLength(1); expect(download).not.toHaveBeenCalled()
  exportFile.mockRejectedValueOnce(new BorrowInquiryError(401)); await inquiry.exportAll(); expect(inquiry.records.value).toHaveLength(0); expect(inquiry.error.value).toContain('权限已失效'); expect(download).not.toHaveBeenCalled()
})

it('aborts query/export on an auth invalidation event and suppresses late rows and downloads', async () => {
  const { inquiry } = session(); await flushPromises(); const file = deferred<BorrowInquiryExport>(); exportFile.mockReturnValueOnce(file.promise); const exporting = inquiry.exportAll(); window.dispatchEvent(new Event('auth-access-invalidated')); file.resolve(inquiryFile()); await exporting; expect(inquiry.data.value).toBeNull(); expect(inquiry.error.value).toContain('权限已失效'); expect(download).not.toHaveBeenCalled()
})
