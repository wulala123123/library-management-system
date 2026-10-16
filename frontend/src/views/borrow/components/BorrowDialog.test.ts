import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ search: vi.fn(), borrow: vi.fn() }))
vi.mock('@/api/user', () => ({ searchUsers: api.search }))
vi.mock('@/api/catalogAttribute', () => ({ getCatalogBooks: vi.fn().mockResolvedValue({ records: [] }) }))
vi.mock('@/api/book', () => ({ getBookPage: vi.fn().mockResolvedValue({ records: [] }), getBookCopies: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/borrow', () => ({ adminBorrowBook: api.borrow }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn() } }))
import BorrowDialog from './BorrowDialog.vue'

const slot = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.default?.(), slots.footer?.()]) } })
const select = defineComponent({ props: ['modelValue', 'placeholder', 'remoteMethod'], emits: ['update:modelValue'], setup(props, { slots, emit }) { return () => h('div', [h('input', { placeholder: props.placeholder, onInput: (event: Event) => props.remoteMethod?.((event.target as HTMLInputElement).value) }), h('select', { 'aria-label': props.placeholder, value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', Number((event.target as HTMLSelectElement).value)) }, slots.default?.())]) } })
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
beforeEach(() => { vi.clearAllMocks(); api.search.mockResolvedValue({ records: [{ id: 5, username: 'reader5', realName: '家庭甲', phone: '13800138000', cardNo: '000005' }, { id: 6, username: 'reader6', realName: '家庭乙', phone: '13800138000', cardNo: '000006' }], total: 2 }) })

it('searches a shared phone, preserves both independent identities and keeps leading zero card numbers', async () => {
  const wrapper = mount(BorrowDialog, { props: { visible: true }, global: { stubs: { ElDialog: slot, ElForm: slot, ElFormItem: slot, ElSelect: select, ElOption: option, ElInputNumber: true, ElInput: true, ElButton: slot } } })
  await wrapper.get('input[placeholder="搜索姓名/手机号/卡号/登录账号"]').setValue('13800138000'); await flushPromises()
  expect(api.search).toHaveBeenCalledWith('13800138000')
  const options = wrapper.findAll('select[aria-label="搜索姓名/手机号/卡号/登录账号"] option')
  expect(options).toHaveLength(2)
  expect(options[0]!.attributes('value')).toBe('5'); expect(options[1]!.attributes('value')).toBe('6')
  expect(options[0]!.text()).toContain('reader5 · 卡号 000005'); expect(options[1]!.text()).toContain('reader6 · 卡号 000006')
  await wrapper.get('input[placeholder="搜索姓名/手机号/卡号/登录账号"]').setValue('')
  expect(wrapper.findAll('select[aria-label="搜索姓名/手机号/卡号/登录账号"] option')).toHaveLength(0)
  wrapper.unmount()
})

it('supports short names and ignores an older search result after the keyword changes', async () => {
  let finishFirst!: (value: unknown) => void
  api.search.mockImplementationOnce(() => new Promise(resolve => { finishFirst = resolve }))
  const wrapper = mount(BorrowDialog, { props: { visible: true }, global: { stubs: { ElDialog: slot, ElForm: slot, ElFormItem: slot, ElSelect: select, ElOption: option, ElInputNumber: true, ElInput: true, ElButton: slot } } })
  const input = wrapper.get('input[placeholder="搜索姓名/手机号/卡号/登录账号"]')
  await input.setValue('甲'); await input.setValue('000006'); await flushPromises()
  expect(api.search).toHaveBeenCalledWith('甲'); expect(api.search).toHaveBeenCalledWith('000006')
  finishFirst({ records: [{ id: 99, username: 'stale', realName: '旧结果' }] }); await flushPromises()
  expect(wrapper.text()).not.toContain('旧结果')
  wrapper.unmount()
})
