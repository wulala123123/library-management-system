import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ list: vi.fn(), summary: vi.fn(), detail: vi.fn(), user: { isAdmin: true, isLibrarian: false }, route: { query: {} as Record<string, string> } }))
vi.mock('@/api/bookCopyQuery', () => ({ getBookCopies: api.list, getCatalogCopySummary: api.summary, getBookCopy: api.detail }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
vi.mock('vue-router', () => ({ useRoute: () => api.route }))
import Query from './index.vue'

const slot = defineComponent({ setup(_, { slots }) { return () => h('div', slots.default?.()) } })
const input = defineComponent({ props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], setup(props, { emit }) { return () => h('input', { value: props.modelValue, placeholder: props.placeholder, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) } })
const button = defineComponent({ setup(_, { slots, attrs }) { return () => h('button', attrs, slots.default?.()) } })
const tabs = defineComponent({ emits: ['update:modelValue', 'tab-change'], setup(_, { emit }) { return () => h('button', { 'data-test': 'copies', onClick() { emit('update:modelValue', 'copies'); emit('tab-change', 'copies') } }, '实体册') } })
const rowProvider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('row', computed(() => props.row)); return () => h('div', slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data || []).map((row: object) => h(rowProvider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop', 'label'], setup(props, { slots }) { const row = inject<ComputedRef<Record<string, unknown>>>('row')!; return () => h('span', [props.label, ': ', slots.default?.({ row: row.value }) || String(row.value?.[props.prop] ?? '')]) } })
const stubs = { ElForm: slot, ElFormItem: slot, ElInput: input, ElButton: button, ElSelect: true, ElOption: true, ElCheckbox: true, ElTag: slot, ElTabs: tabs, ElTabPane: true, ElTable: table, ElTableColumn: column, ElPagination: true, ElDialog: slot, ElDescriptions: slot, ElDescriptionsItem: slot, ElAlert: true, ElEmpty: true, CopyActionDialog: true, CopyActionHistory: true }
const wrappers: ReturnType<typeof mount>[] = []
function page() { const wrapper = mount(Query, { global: { stubs, directives: { loading: () => {} } } }); wrappers.push(wrapper); return wrapper }
beforeEach(() => { vi.clearAllMocks(); Object.assign(api.user, { isAdmin: true, isLibrarian: false }); api.route.query = {}; api.summary.mockResolvedValue({ records: [], total: 0 }); api.list.mockResolvedValue({ records: [], total: 0 }) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it('keeps exact barcode bytes including leading zeroes and spaces while switching to copy queries', async () => {
  const wrapper = page(); await flushPromises()
  expect(wrapper.find('input[placeholder="书名 / ISBN / 作者"]').exists()).toBe(true)
  expect(wrapper.find('input[placeholder="书名 / ISBN / 作者 / 条码"]').exists()).toBe(false)
  await wrapper.get('[data-test="copies"]').trigger('click'); await flushPromises()
  expect(wrapper.find('input[placeholder="书名 / ISBN / 作者 / 条码"]').exists()).toBe(true)
  await wrapper.get('input[placeholder="完整条码，保留前导零"]').setValue(' 0000123 ')
  await wrapper.findAll('button').find(button => button.text() === '查询')!.trigger('click'); await flushPromises()
  expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ barcode: ' 0000123 ', page: 1 }))
})

it('allows librarian queries but makes no requests from a reader', async () => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: true }); page(); await flushPromises()
  expect(api.summary).toHaveBeenCalledOnce()
  vi.clearAllMocks(); api.user.isLibrarian = false; page(); await flushPromises()
  expect(api.summary).not.toHaveBeenCalled(); expect(api.list).not.toHaveBeenCalled(); expect(api.detail).not.toHaveBeenCalled()
})

it('uses a valid linked book id and resets it with the other query filters', async () => {
  api.route.query = { bookId: '17' }; const wrapper = page(); await flushPromises()
  expect(api.summary).toHaveBeenLastCalledWith(expect.objectContaining({ bookId: 17 }))
  await wrapper.findAll('button').find(button => button.text() === '重置')!.trigger('click'); await flushPromises()
  expect(api.summary).toHaveBeenLastCalledWith(expect.objectContaining({ bookId: undefined }))
})

it('discards an old response after filters change', async () => {
  let finish!: (value: object) => void
  api.summary.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const wrapper = page(); await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '查询')!.trigger('click'); await flushPromises()
  finish({ records: [{ book: { title: '过期响应不应出现' } }], total: 1 }); await flushPromises()
  expect(wrapper.text()).not.toContain('过期响应不应出现')
})

it('renders original copy state, unassigned location and unlimited due date without inventing values', async () => {
  const copy = { id: 3, bookId: 17, barcode: '00017', status: 'LEGACY_STATE', location: null, book: { id: 17, title: '合成绘本', isbn: null, location: null, publishDate: null, pages: 0 } }
  api.list.mockResolvedValue({ records: [copy], total: 1 })
  api.detail.mockResolvedValue({ copy, currentLoans: [{ memberName: null, cardNo: '00008', borrowDate: '2026-10-15', dueDate: null, dueDateKind: 'UNLIMITED', status: 'BORROWING', bookMatches: false }], needsVerification: true })
  const wrapper = page(); await flushPromises(); await wrapper.get('[data-test="copies"]').trigger('click'); await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '查看单册')!.trigger('click'); await flushPromises()
  expect(api.detail).toHaveBeenCalledWith(3)
  for (const value of ['00017', '原状态待核实："LEGACY_STATE"', '未分配', '待补充 / 0', '不限制', '00008', '待核实']) expect(wrapper.text()).toContain(value)
})
it('starts only explicitly selected IDs and clears page selection when filters change', async () => {
  const wrapper = page(); await flushPromises(); await wrapper.get('[data-test="copies"]').trigger('click'); await flushPromises()
  wrapper.findComponent(table).vm.$emit('selection-change', [{ id: 7 }, { id: 8 }]); await flushPromises()
  expect(wrapper.text()).toContain('明确选择 2 册')
  await wrapper.findAll('button').find(button => button.text() === '办理所选实体册')!.trigger('click')
  expect(wrapper.findComponent({ name: 'CopyActionDialog' }).props('copyIds')).toEqual([7, 8])
  await wrapper.findAll('button').find(button => button.text() === '查询')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('明确选择 0 册')
})
it('uses provenance classification to distinguish identical legacy and controlled disabled labels', async () => {
  const book = { id: 1, title: '合成' }
  api.list.mockResolvedValue({ records: [{ id: 1, status: 'DISABLED', stateClassification: 'UNKNOWN', barcode: '0001', location: null, book }, { id: 2, status: 'DISABLED', stateClassification: 'DISABLED', barcode: '0002', location: null, book }], total: 2 })
  const wrapper = page(); await flushPromises(); await wrapper.get('[data-test="copies"]').trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('原状态待核实："DISABLED"'); expect(wrapper.text()).toContain('临时停用')
})
