import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, inject, provide } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ detail: vi.fn(), books: vi.fn(), members: vi.fn() }))
vi.mock('@/api/book', () => ({ getBookById: api.detail, getBookPage: api.books, deleteBook: vi.fn(), createBook: vi.fn(), updateBook: vi.fn(), uploadBookCover: vi.fn() }))
vi.mock('@/api/category', () => ({ getCategoryList: vi.fn().mockResolvedValue([]) }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ isAdmin: true, isLibrarian: false }) }))
vi.mock('@/api/borrowCode', () => ({ generateBorrowCode: vi.fn() }))
vi.mock('@/api/legacyData', () => ({ getMigratedMembers: api.members, getMigrationSummary: vi.fn().mockResolvedValue({ batches: [], types: [] }), getLegacyRecord: vi.fn(), getLegacyRecords: vi.fn() }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, ElMessageBox: { confirm: vi.fn(), alert: vi.fn() } }))
import BookDetail from './book-catalog/components/BookDetailDialog.vue'
import BookManagement from './book/index.vue'
import BookCatalog from './book-catalog/index.vue'
import LegacyData from './legacy-data/index.vue'

const slot = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.default?.(), slots.footer?.()]) } })
const provider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('row', props.row); return () => h('div', { class: 'test-row' }, slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data || []).map((row: object) => h(provider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop', 'label'], setup(props, { slots }) { const row = inject<Record<string, unknown>>('row')!; return () => h('span', [props.label, ': ', slots.default?.({ row }) || String(row?.[props.prop] ?? '')]) } })
const stubs = { ElDialog: slot, ElButton: slot, ElForm: slot, ElFormItem: slot, ElInput: true, ElSelect: true, ElRadioGroup: true, ElRadioButton: true, ElImage: true, ElTag: slot, ElTable: table, ElTableColumn: column, ElPagination: true, ElTabs: slot, ElTabPane: slot, ElDescriptions: slot, ElDescriptionsItem: slot, ElEmpty: slot, BookDialog: true, BookDetailDialog: true }
const wrappers: ReturnType<typeof mount>[] = []
function page(component: any, props = {}) { const wrapper = mount(component, { props, global: { stubs, directives: { loading: () => {} } } }); wrappers.push(wrapper); return wrapper }
const book = { id: 1, title: '合成测试图书', isbn: '0000123', pages: 0, price: 0, publishDate: null, location: '  ', description: null, stockTotal: 0, stockAvailable: 0, createTime: '2026-10-15' }
beforeEach(() => {
  vi.clearAllMocks()
  api.detail.mockResolvedValue(book)
  api.books.mockResolvedValue({ records: [book], total: 1 })
  api.members.mockResolvedValue([
    { user_id: 1, real_name: '不限样例', card_no: '', used_uses: 0, total_uses: null },
    { user_id: 2, real_name: '零次样例', card_no: '000001', used_uses: 0, total_uses: 0 },
    { user_id: 3, real_name: '缺失样例', used_uses: undefined, total_uses: undefined },
  ])
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it('shows unset publication date, location and description while retaining zero pages and price in details', async () => {
  const wrapper = page(BookDetail, { visible: false, bookId: 1 })
  await wrapper.setProps({ visible: true }); await flushPromises()
  const items = wrapper.findAll('.info-item').map(item => item.text())
  expect(items).toContain('页数0')
  expect(items).toContain('价格¥0.00')
  expect(items).toContain('出版日期未设置')
  expect(items).toContain('馆藏位置未设置')
  expect(wrapper.find('.detail-desc').text()).toContain('待补充')
  expect(items).toContain('ISBN0000123')
})

it('shows missing source fields in book management and zero price in the catalog', async () => {
  const management = page(BookManagement), catalog = page(BookCatalog)
  await flushPromises()
  expect(management.text()).toContain('出版日期: 未设置')
  expect(management.text()).toContain('页数: 0')
  expect(management.text()).toContain('库位: 未设置')
  expect(management.text()).toContain('简介: 待补充')
  expect(catalog.find('.price').text()).toBe('¥0.00')
  expect(catalog.find('.avail').text()).toContain('可借 0 册')
})

it('distinguishes unlimited, zero and unknown uses and preserves a leading-zero member card', async () => {
  const wrapper = page(LegacyData)
  await flushPromises()
  const rows = wrapper.findAll('.test-row').map(row => row.text())
  expect(rows[0]).toContain('卡号: 未设置')
  expect(rows[0]).toContain('已用 / 总次数: 0 / 不限')
  expect(rows[1]).toContain('卡号: 000001')
  expect(rows[1]).toContain('已用 / 总次数: 0 / 0')
  expect(rows[2]).toContain('已用 / 总次数: 待核实 / 待核实')
})
