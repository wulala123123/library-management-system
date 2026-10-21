import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ dashboard: vi.fn(), inventory: vi.fn(), summary: vi.fn(), reconciliation: vi.fn(), exportCsv: vi.fn(), books: vi.fn(), borrows: vi.fn(), mine: vi.fn(), user: { isReader: false, canManageBooks: true } }))
vi.mock('@/api/dashboard', () => ({
  getDashboardStats: api.dashboard, getInventoryStats: api.inventory, getStatistics: api.summary,
  getInventoryReconciliation: api.reconciliation, exportStatistics: api.exportCsv,
  getBorrowTrend: vi.fn().mockResolvedValue([]), getCategoryDistribution: vi.fn().mockResolvedValue([]), getHotBooks: vi.fn().mockResolvedValue([]),
}))
vi.mock('@/api/book', () => ({ getBookPage: api.books }))
vi.mock('@/api/managementReports', async original => ({ ...await original<object>(), createManagementReportsApi: () => ({ listPage: (_kind: string, query: object) => api.books(query) }) }))
vi.mock('@/api/borrow', () => ({ getBorrowPage: api.borrows, getMyBorrows: api.mine }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn() } }))
import Dashboard from '@/views/dashboard/index.vue'
import Inventory from '@/views/inventory/index.vue'
import Statistics from './index.vue'
import { projection } from '@/views/book-copy/components/copyActionFixtures'

const slot = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.header?.(), slots.default?.(), slots.footer?.()]) } })
const provider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('row', computed(() => props.row)); return () => h('div', slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data || []).map((row: object) => h(provider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop', 'label'], setup(props, { slots }) { const row = inject<ComputedRef<Record<string, unknown>>>('row')!; return () => h('span', [props.label, ': ', slots.default?.({ row: row.value }) || String(row.value?.[props.prop] ?? '')]) } })
const button = defineComponent({ setup(_, { slots, attrs }) { return () => h('button', attrs, slots.default?.()) } })
const stubs = { ElCard: slot, ElRow: slot, ElCol: slot, ElTable: table, ElTableColumn: column, ElButton: button, ElIcon: slot, ElEmpty: slot, ElProgress: true, ElPagination: true, ElTag: slot, RouterLink: slot, User: slot, Reading: slot }
const wrappers: ReturnType<typeof mount>[] = []
function page(component: any) { const wrapper = mount(component, { global: { stubs, directives: { loading: () => {} } } }); wrappers.push(wrapper); return wrapper }
const stats = {
  catalogTitles: 3, totalCopies: 7, availableCopies: 4, borrowedCopies: 3, availableTitles: 2, lowStockTitles: 1,
  accountCount: 5, memberCount: 3, validMemberCount: 1, unknownValidityMemberCount: 2,
  todayBorrows: 0, overdueCount: 0, asOfDate: '2026-10-15',
  statisticalScope: { memberCount: '全部读者，含禁用与到期；不含管理员', accountCount: '所有角色与启停账号', validMemberCount: '启用读者且起止日期覆盖当天', unknownValidityMemberCount: '缺任一期限日期，包含禁用读者' },
  totalBooks: 999, totalUsers: 998, availableBooks: 997,
}

beforeEach(() => {
  vi.clearAllMocks()
  api.user.isReader = false; api.user.canManageBooks = true
  api.dashboard.mockResolvedValue(stats); api.inventory.mockResolvedValue(stats); api.summary.mockResolvedValue(stats)
  api.books.mockResolvedValue({ records: [{ id: 1, title: '未设库位图书', stockTotal: 0, stockAvailable: 0, location: null }], total: 1 })
  api.borrows.mockResolvedValue({ records: [] }); api.mine.mockResolvedValue({ records: [] })
  api.reconciliation.mockResolvedValue({
    ...stats, registeredCopies: 6, registeredAvailableCopies: 4, registeredBorrowedCopies: 2, registeredOtherCopies: 0, mismatchedTitles: 1,
    statisticalScope: { reconciliation: '差额为汇总库存减登记册数；核对启用书目' },
    items: [{ bookId: 1, title: '差异书目', totalCopies: 7, availableCopies: 4, borrowedCopies: 3, registeredCopies: 6, registeredAvailableCopies: 4, registeredBorrowedCopies: 2, registeredOtherCopies: 0, totalDifference: 1, availableDifference: 0, borrowedDifference: 1, stockBalanceDifference: 0, consistent: false }],
  })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); vi.unstubAllGlobals() })

it('renders the same title, copy and membership metrics on the dashboard and statistics page', async () => {
  const dashboard = page(Dashboard), statistics = page(Statistics)
  await flushPromises()
  expect(dashboard.findAll('.stat-card').map(card => card.text())).toEqual(expect.arrayContaining([
    expect.stringContaining('图书种数3 种'), expect.stringContaining('实体册总数7 册'), expect.stringContaining('会员人数3 人'), expect.stringContaining('账号总数5 个'), expect.stringContaining('有效会员人数1 人'), expect.stringContaining('有效期待核实会员人数2 人'),
  ]))
  expect(statistics.text()).toContain('统计指标: 图书种数数值: 3单位: 种')
  expect(statistics.text()).toContain('统计指标: 实体册总数数值: 7单位: 册')
  for (const wrapper of [dashboard, statistics]) {
    expect(wrapper.text()).toContain('2026-10-15（Asia/Shanghai）')
    expect(wrapper.text()).toContain('全部读者，含禁用与到期；不含管理员')
    expect(wrapper.text()).toContain('所有角色与启停账号')
    expect(wrapper.text()).not.toContain('999')
    expect(wrapper.text()).not.toContain('较上月')
  }
})

it('shows copy registration differences, zero counts and an unset location in inventory', async () => {
  const wrapper = page(Inventory)
  await flushPromises()
  expect(wrapper.text()).toContain('图书种数')
  expect(wrapper.text()).toContain('可借种数')
  expect(wrapper.text()).toContain('原始登记口径：差异书目 1 种')
  expect(wrapper.text()).toContain('已登记其他状态实体册：0 册')
  expect(wrapper.text()).toContain('差额为汇总库存减登记册数')
  expect(wrapper.text()).toContain('总数差额（原始登记口径，册）: 1')
  expect(wrapper.text()).toContain('可借差额（原始登记口径，册）: 0')
  expect(wrapper.text()).toContain('库位: 未设置')
  const action = wrapper.findAll('button').find(button => button.text() === '刷新数据')!
  await action.trigger('click'); await flushPromises()
  expect(api.inventory).toHaveBeenCalledTimes(2)
  expect(api.reconciliation).toHaveBeenCalledTimes(2)
})

it('does not call the staff inventory reconciliation endpoint from a reader dashboard', async () => {
  api.user.isReader = true; api.user.canManageBooks = false
  const wrapper = page(Dashboard)
  await flushPromises()
  expect(api.reconciliation).not.toHaveBeenCalled()
  expect(api.mine).toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('实体册登记与库存核对')
})

it('downloads the backend CSV bytes without rebuilding metrics from page data', async () => {
  const blob = new Blob(['metric,label,value,unit,asOfDate,scope\r\ntotalCopies,实体册总数,7,册,2026-10-15,启用书目'], { type: 'text/csv' })
  api.exportCsv.mockResolvedValue(blob)
  const createUrl = vi.fn().mockReturnValue('blob:metrics'), revokeUrl = vi.fn()
  vi.stubGlobal('URL', { createObjectURL: createUrl, revokeObjectURL: revokeUrl })
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const wrapper = page(Statistics)
  await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '导出基础指标 CSV')!.trigger('click')
  await flushPromises()
  expect(api.exportCsv).toHaveBeenCalledOnce()
  expect(createUrl).toHaveBeenCalledWith(blob)
  expect(click).toHaveBeenCalledOnce()
  expect(revokeUrl).toHaveBeenCalledWith('blob:metrics')
  vi.unstubAllGlobals()
})

it('keeps unavailable metrics unknown instead of showing made-up zero counts', async () => {
  api.inventory.mockRejectedValue(new Error('offline'))
  const wrapper = page(Inventory)
  await flushPromises()
  expect(wrapper.findAll('.mini-stat .value').every(value => value.text().includes('待核实'))).toBe(true)
})
it('shows known disabled/written-off copies separately while preserving raw registration and explained differences', async () => {
  const old = await api.reconciliation()
  api.reconciliation.mockResolvedValue({ ...old, reconciliationV2: projection(), items: [{ ...old.items[0], reconciliationV2: projection() }] })
  const wrapper = page(Inventory); await flushPromises()
  for (const text of ['在库登记 2 册', '临时停用 1 册', '已报损保留 1 册', '原状态待核实 0 册', '在库 / 可借 / 在借差额：0 / 0 / 0', '全部历史登记数含已报损保留行']) expect(wrapper.text()).toContain(text)
  expect(wrapper.text()).toContain('原始登记口径：差异书目 1 种')
  expect(wrapper.text()).toContain('总数差额（原始登记口径，册）: 1')
  expect(wrapper.text()).toContain('已知停用或报损也可能计入')
  expect(wrapper.text()).not.toContain('已登记其他状态实体册：')
})
