import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h, inject, provide } from 'vue'
import { beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ login: vi.fn(), captcha: vi.fn(), code: vi.fn(), borrow: vi.fn(), verify: vi.fn(), push: vi.fn(), myBorrows: vi.fn(), returnBook: vi.fn(), renew: vi.fn(), orders: vi.fn(), instock: vi.fn(), role: 'READER' }))
vi.mock('@/api/auth', () => ({ loginApi: api.login, getCaptchaImage: api.captcha, registerApi: vi.fn() }))
vi.mock('@/api/catalogAttribute', () => ({ getCatalogBook: vi.fn().mockResolvedValue({ id: 7, catalogTitle: '测试图书', displayTitle: '测试图书', stockAvailable: 1, bindingCode: null, ageLabels: null }), getCatalogBooks: vi.fn().mockResolvedValue({ records: [] }) }))
vi.mock('@/api/book', () => ({ getBookById: vi.fn().mockResolvedValue({ id: 7, title: '测试图书', stockAvailable: 1 }), getBookPage: vi.fn() }))
vi.mock('@/api/borrowCode', () => ({ generateBorrowCode: api.code, verifyBorrowCode: api.verify, getMyBorrowCodes: vi.fn().mockResolvedValue({ records: [] }), getBorrowCodePage: vi.fn().mockResolvedValue({ records: [], total: 0 }), invalidateBorrowCode: vi.fn() }))
vi.mock('@/api/borrow', () => ({ borrowBook: api.borrow, getMyBorrows: api.myBorrows, selfReturnBook: api.returnBook, renewBook: api.renew }))
vi.mock('@/api/purchase', () => ({ getPurchaseOrders: api.orders, getPurchaseOrderById: vi.fn(), createPurchaseOrder: vi.fn(), updatePurchaseOrder: vi.fn(), deletePurchaseOrder: vi.fn(), submitPurchaseOrder: vi.fn(), approvePurchaseOrder: vi.fn() }))
vi.mock('@/api/dept', () => ({ getDeptList: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/category', () => ({ getCategoryList: vi.fn().mockResolvedValue([]) }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: api.push }), useRoute: () => ({ query: {} }) }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ logout: vi.fn(), setToken: vi.fn(), setUserInfo: vi.fn(), get role() { return api.role }, get isAdmin() { return api.role === 'ADMIN' }, get isLibrarian() { return api.role === 'LIBRARIAN' } }) }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }, ElMessageBox: { alert: vi.fn().mockResolvedValue(undefined), confirm: vi.fn().mockResolvedValue(undefined) } }))

import Login from './login/index.vue'
import BookDetail from './book-catalog/components/BookDetailDialog.vue'
import CodePage from './borrow/code.vue'
import MyBorrows from './borrow/my-borrows.vue'
import Purchases from './purchase/index.vue'

const form = defineComponent({ setup(_, { slots, expose }) { expose({ validate: () => Promise.resolve() }); return () => h('form', slots.default?.()) } })
const input = defineComponent({ props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], setup(props, { emit }) {
  return () => h('input', { placeholder: props.placeholder, value: props.modelValue, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) })
} })
const slot = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.default?.(), slots.footer?.()]) } })
const stubs = { ElForm: form, ElFormItem: slot, ElInput: input, ElDialog: slot, ElRow: slot, ElCol: slot, ElDescriptions: slot, ElDescriptionsItem: slot, ElButton: slot, ElTag: slot, ElTable: true, ElTableColumn: true, ElPagination: true, ElEmpty: true }

const rowProvider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('row', props.row); return () => h('div', slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data || []).map((row: object) => h(rowProvider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop'], setup(props, { slots }) { const row = inject<Record<string, unknown>>('row')!; return () => h('div', slots.default?.({ row }) || String(row[props.prop] || '')) } })
const actionButton = defineComponent({ setup(_, { slots }) { return () => h('button', slots.default?.()) } })
const rowStubs = { ...stubs, ElTable: table, ElTableColumn: column, ElButton: actionButton, ElTabs: slot, ElTabPane: slot }

beforeEach(() => { vi.clearAllMocks(); api.role = 'READER'; api.captcha.mockResolvedValue({ image: 'image', captchaKey: 'challenge' }); api.login.mockResolvedValue({ token: 'token', userInfo: { username: 'reader' } }); api.code.mockResolvedValue({ code: '123456' }) })

it('submits credentials and captcha together and removes the recovery entry', async () => {
  const wrapper = mount(Login, { global: { stubs } }); await flushPromises()
  await wrapper.find('input[placeholder="用户名"]').setValue('reader')
  await wrapper.find('input[placeholder="密码"]').setValue('Password123')
  await wrapper.find('input[placeholder="验证码"]').setValue('ABCD')
  await wrapper.find('button.submit-btn').trigger('click'); await flushPromises()
  expect(api.login).toHaveBeenCalledWith({ username: 'reader', password: 'Password123', captchaCode: 'ABCD', captchaKey: 'challenge' })
  expect(wrapper.find('.forgot-link').exists()).toBe(false)
  expect(api.push).toHaveBeenCalledWith('/')
})

it('refreshes consumed captcha after failed login', async () => {
  api.login.mockRejectedValue(new Error('验证码错误'))
  const wrapper = mount(Login, { global: { stubs } }); await flushPromises()
  await wrapper.find('input[placeholder="验证码"]').setValue('ABCD')
  await wrapper.find('button.submit-btn').trigger('click'); await flushPromises()
  expect(api.captcha).toHaveBeenCalledTimes(2)
  expect((wrapper.find('input[placeholder="验证码"]').element as HTMLInputElement).value).toBe('')
})

it('catalog details generate a code without creating a borrow first', async () => {
  const wrapper = mount(BookDetail, { props: { visible: false, bookId: 7 }, global: { stubs } })
  await wrapper.setProps({ visible: true })
  await flushPromises()
  // Click through the rendered action, rather than calling component internals.
  const action = wrapper.findAllComponents(slot).find(c => c.text().trim() === '生成借阅码' || c.text().trim() === '立即借阅')
  if (action) await action.trigger('click')
  else await wrapper.find('[type="primary"]').trigger('click')
  await flushPromises()
  expect(api.code).toHaveBeenCalledWith(7)
  expect(api.borrow).not.toHaveBeenCalled()
  wrapper.unmount()
})

it('the code page redeems the entered code once and clears the input on success', async () => {
  api.verify.mockResolvedValue({ borrowCode: { code: '123456', bookTitle: '测试图书' }, borrowRecord: { id: 9 } })
  const wrapper = mount(CodePage, { global: { stubs: { ...stubs, ElTabs: slot, ElTabPane: slot } } })
  await wrapper.find('input[placeholder="请输入6位借阅码"]').setValue('123456')
  const action = wrapper.findAllComponents(slot).find(c => c.text().trim() === '验证并借阅')!
  await action.trigger('click'); await flushPromises()
  expect(api.verify).toHaveBeenCalledOnce()
  expect(api.verify).toHaveBeenCalledWith('123456')
  expect((wrapper.find('input[placeholder="请输入6位借阅码"]').element as HTMLInputElement).value).toBe('')
  wrapper.unmount()
})

it('overdue records remain visible and can be returned from the reader page', async () => {
  const overdue = { id: 42, bookTitle: '逾期图书', status: 'OVERDUE', dueDate: '2020-01-01', renewCount: 0 }
  api.myBorrows.mockImplementation(({ status }) => Promise.resolve({ records: status === 'ACTIVE' ? [overdue] : [] }))
  api.returnBook.mockResolvedValue({ ...overdue, status: 'RETURNED' })
  const wrapper = mount(MyBorrows, { global: { stubs: rowStubs } }); await flushPromises()
  expect(wrapper.text()).toContain('逾期图书')
  const button = wrapper.findAll('button').find(b => b.text().trim() === '归还')!
  await button.trigger('click'); await flushPromises()
  expect(api.returnBook).toHaveBeenCalledWith(42)
  expect(api.myBorrows).toHaveBeenCalledWith({ page: 1, size: 100, status: 'ACTIVE' })
  wrapper.unmount()
})

it('approved procurement opens explicit intake and refreshes only after a receipt', async () => {
  api.role = 'LIBRARIAN'
  api.orders.mockResolvedValue({ records: [{ id: 15, orderNo: 'PO15', status: 'APPROVED' }], total: 1 })
  const wrapper = mount(Purchases, { global: { stubs: { ...rowStubs, IntakeDialog: true } } }); await flushPromises()
  const button = wrapper.findAll('button').find(b => b.text().trim() === '登记入库')!
  await button.trigger('click'); await flushPromises()
  expect(api.instock).not.toHaveBeenCalled()
  expect(api.orders).toHaveBeenCalledTimes(1)
  const intake = wrapper.findComponent({ name: 'IntakeDialog' })
  expect(intake.props('visible')).toBe(true); expect(intake.props('purchaseOrderId')).toBe(15)
  intake.vm.$emit('success', { intakeId: 7 }); await flushPromises()
  expect(api.orders).toHaveBeenCalledTimes(2)
  wrapper.unmount()
})

it('readers do not issue procurement management requests', async () => {
  const wrapper = mount(Purchases, { global: { stubs: { ...rowStubs, IntakeDialog: true } } }); await flushPromises()
  expect(api.orders).not.toHaveBeenCalled(); expect(api.instock).not.toHaveBeenCalled(); wrapper.unmount()
})
