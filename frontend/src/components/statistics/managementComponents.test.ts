import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ManagementReadError } from '@/api/managementReports'
import type { CsvDownload } from '@/api/csvDownload'
import type { BorrowOperationsReport as Report } from '@/api/managementReports'
import { managementFile, operationsReport, pendingManagement, reportFilters } from './managementFixtures'
const api = vi.hoisted(() => ({ report: vi.fn(), reportCsv: vi.fn(), listCsv: vi.fn(), download: vi.fn(), toast: vi.fn() }))
const user = reactive({ isAdmin: true, token: 'login-one', userInfo: { id: 1, role: 'ADMIN' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
vi.mock('@/api/managementReports', async original => ({ ...await original<object>(), createManagementReportsApi: () => api }))
vi.mock('@/utils/download', () => ({ downloadBlob: api.download }))
vi.mock('element-plus', () => ({ ElMessage: { success: api.toast } }))
import BorrowOperationsReport from './BorrowOperationsReport.vue'
import AdminListExport from './AdminListExport.vue'
const button = defineComponent({ props: ['disabled'], setup(props, { attrs, slots }) { return () => h('button', { ...attrs, disabled: props.disabled }, slots.default?.()) } })
const wrappers: VueWrapper[] = []
function page(component: any = BorrowOperationsReport, props: object = {}) { const w = mount(component, { props, global: { stubs: { ElButton: button } } }); wrappers.push(w); return w }
const list = () => page(AdminListExport, { kind: 'members', filters: { keyword: '000005', accountStatus: 0 }, invalidationKey: 1, ready: true, description: '卡号000005，禁用账号' })
async function click(w: VueWrapper, label: string) { const button = w.findAll('button').find(b => b.text() === label); expect(button).toBeDefined(); await button!.trigger('click'); await flushPromises() }
beforeEach(() => { vi.resetAllMocks(); Object.assign(user, { isAdmin: true, token: 'login-one', userInfo: { id: 1, role: 'ADMIN' } }); api.report.mockImplementation(filters => Promise.resolve(operationsReport(filters))); api.reportCsv.mockResolvedValue(managementFile('borrows', 30000)); api.listCsv.mockResolvedValue(managementFile()) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))
it('renders unified units, scope, actual trend/ranking values and safe titles', async () => {
  const w = page(); await flushPromises(); expect(w.text()).toContain('借阅明细37 条'); expect(w.text()).toContain('借出册次（按明细）37 册次'); expect(w.text()).toContain('明确柜台订单5 单'); expect(w.text()).toContain('2026-10-22（Asia/Shanghai）'); expect(w.text()).toContain('原系统累计、借阅明细和明确订单数不混加'); expect(w.findAll('tbody')[0]!.text()).toContain('13'); expect(w.findAll('tbody')[0]!.text()).toContain('24'); expect(w.findAll('tbody')[0]!.text()).not.toContain('%'); expect(w.text()).toContain('书目编号 7'); expect(w.text()).toContain('合成绘本 <img>'); expect(w.find('img').exists()).toBe(false)
})
it('applies report dates/status/source explicitly and exports the applied range with correct coverage wording', async () => {
  const w = page(); await flushPromises(); await w.get('input[placeholder="读者/书名/ISBN/完整馆内条码"]').setValue(' 000Aa '); await w.get('input[aria-label="借出开始日"]').setValue('2026-10-01'); await w.get('input[aria-label="借出结束日"]').setValue('2026-10-21'); await w.get('select[aria-label="报告借阅状态"]').setValue('ACTIVE'); await w.get('select[aria-label="报告借阅来源"]').setValue('IMPORTED'); expect(api.report).toHaveBeenCalledOnce(); await click(w, '查询报告'); const filters = { keyword: ' 000Aa ', status: 'ACTIVE', origin: 'IMPORTED', startDate: '2026-10-01', endDate: '2026-10-21' }; expect(api.report).toHaveBeenLastCalledWith(filters, expect.any(AbortSignal))
  await w.get('input[placeholder="读者/书名/ISBN/完整馆内条码"]').setValue('尚未应用'); expect(w.text()).toContain('输入尚未应用'); await click(w, '导出当前筛选报告 CSV'); expect(api.reportCsv).toHaveBeenCalledWith(filters, expect.any(AbortSignal)); expect(api.download).toHaveBeenCalledOnce(); expect(w.text()).toContain('覆盖 30000 条借阅明细（不是文件行数）'); expect(api.toast).toHaveBeenCalledWith(expect.stringContaining('非文件行数'))
})
it('keeps empty totals as zero, read failures distinct and does not offer a failed report as downloadable', async () => {
  api.report.mockRejectedValueOnce(Error('报告读取失败')); const w = page(); await flushPromises(); expect(w.text()).toContain('报告读取失败'); expect(w.findAll('tbody')).toHaveLength(0); expect(w.findAll('button').find(b => b.text() === '导出当前筛选报告 CSV')!.attributes('disabled')).toBeDefined()
  const empty = operationsReport(); empty.totals = { borrowDetails: 0, borrowedCopyEvents: 0, activeDetails: 0, returnedDetails: 0, overdueDetails: 0, knownCounterOrders: 0 }; empty.trend = []; empty.hotBooks = []; api.report.mockResolvedValueOnce(empty); await click(w, '查询报告'); expect(w.text()).toContain('借阅明细0 条'); expect(w.text()).toContain('当前筛选暂无按日趋势')
})
it.each(['LIBRARIAN', 'READER'])('does not render or read new admin features for %s', async role => {
  user.isAdmin = false; user.userInfo.role = role; const report = page(), exportList = list(); await flushPromises(); expect(report.text()).toBe(''); expect(exportList.text()).toBe(''); expect(api.report).not.toHaveBeenCalled(); expect(api.listCsv).not.toHaveBeenCalled()
})
it('downloads complete list data once with filters and visible count/time, including unapplied input wording', async () => {
  const w = list(); await w.setProps({ unapplied: true }); const file = pendingManagement<CsvDownload>(); api.listCsv.mockReturnValueOnce(file.promise); await click(w, '导出全部筛选结果'); await click(w, '导出全部筛选结果'); expect(api.listCsv).toHaveBeenCalledExactlyOnceWith('members', { keyword: '000005', accountStatus: 0 }, expect.any(AbortSignal)); expect(w.text()).toContain('输入尚未应用'); expect(api.download).not.toHaveBeenCalled(); file.resolve(managementFile()); await flushPromises(); expect(api.download).toHaveBeenCalledOnce(); expect(w.text()).toContain('已导出 21 位会员'); expect(w.text()).toContain('导出时点')
})
it.each(['input', 'filters', 'pagination', 'identity', 'token', 'role', 'unmount'] as const)('aborts list CSV and rejects late data on %s change', async kind => {
  const w = list(), pending = pendingManagement<CsvDownload>(); api.listCsv.mockReturnValueOnce(pending.promise); await click(w, '导出全部筛选结果'); const signal = api.listCsv.mock.calls[0]![2]
  if (kind === 'input') await w.setProps({ invalidationKey: { keyword: '输入改变' } })
  if (kind === 'filters') await w.setProps({ filters: { keyword: '新条件' } })
  if (kind === 'pagination') await w.setProps({ invalidationKey: { page: 2 } })
  if (kind === 'identity') user.userInfo.id = 2
  if (kind === 'token') user.token = 'login-two'
  if (kind === 'role') { user.isAdmin = false; user.userInfo.role = 'LIBRARIAN' }
  if (kind === 'unmount') w.unmount()
  await flushPromises(); expect(signal.aborted).toBe(true); pending.resolve(managementFile()); await flushPromises(); expect(api.download).not.toHaveBeenCalled()
})
it('reports limit errors without download and suppresses late export 403 after a different login', async () => {
  const w = list(); api.listCsv.mockRejectedValueOnce(new ManagementReadError(400, '超过20000条，请缩小筛选范围')); await click(w, '导出全部筛选结果'); expect(w.text()).toContain('请缩小筛选范围'); expect(api.download).not.toHaveBeenCalled()
  const old = pendingManagement<CsvDownload>(); api.listCsv.mockReturnValueOnce(old.promise); await click(w, '导出全部筛选结果'); user.token = 'login-two'; await flushPromises(); old.reject(new ManagementReadError(403, '旧权限失败')); await flushPromises(); expect(w.text()).not.toContain('旧权限失败'); await click(w, '导出全部筛选结果'); expect(api.download).toHaveBeenCalledOnce()
})
it('rejects late report data/403 on filter and login changes without replacing the current report', async () => {
  const old = pendingManagement<Report>(); api.report.mockReturnValueOnce(old.promise); const w = page(); user.token = 'login-two'; await flushPromises(); old.reject(new ManagementReadError(403, '迟到403')); await flushPromises(); expect(w.text()).toContain('借阅明细37 条'); expect(w.text()).not.toContain('迟到403')
  const prior = pendingManagement<Report>(); api.report.mockReturnValueOnce(prior.promise); await click(w, '查询报告'); await w.get('select[aria-label="报告借阅来源"]').setValue('SYSTEM'); await click(w, '查询报告'); const stale = operationsReport(); stale.scope = '迟到报告'; prior.resolve(stale); await flushPromises(); expect(w.text()).not.toContain('迟到报告'); expect(api.report).toHaveBeenLastCalledWith({ ...reportFilters(), origin: 'SYSTEM' }, expect.any(AbortSignal))
})
it('clears a current denied report and never downloads a denied or post-unmount aggregate CSV', async () => {
  const w = page(); await flushPromises(); api.reportCsv.mockRejectedValueOnce(new ManagementReadError(403, '当前权限失效')); await click(w, '导出当前筛选报告 CSV'); expect(w.text()).not.toContain('借阅明细37 条'); expect(api.download).not.toHaveBeenCalled()
  const next = page(); await flushPromises(); const pending = pendingManagement<CsvDownload>(); api.reportCsv.mockReturnValueOnce(pending.promise); await click(next, '导出当前筛选报告 CSV'); next.unmount(); pending.resolve(managementFile('borrows')); await flushPromises(); expect(api.download).not.toHaveBeenCalled()
})
