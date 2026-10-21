import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, reactive, type ComputedRef } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ list: vi.fn(), detail: vi.fn(), me: vi.fn(), source: vi.fn(), create: vi.fn(), update: vi.fn(), push: vi.fn(), user: { isAdmin: true, isLibrarian: false, isReader: false, token: 'synthetic-admin', userInfo: { id: 2, role: 'ADMIN' } }, route: { query: {} as Record<string, string> } }))
vi.mock('@/api/member', () => ({ getMemberPage: api.list, getMemberById: api.detail, getMyMembership: api.me, getMemberSourcePreview: api.source, createMember: api.create, updateMember: api.update }))
vi.mock('@/api/memberEntitlement', () => ({ getMyEntitlement: api.me }))
vi.mock('@/api/managementReports', async original => ({ ...await original<object>(), createManagementReportsApi: () => ({ listPage: (_kind: string, query: object) => api.list(query) }) }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: api.push }), useRoute: () => api.route }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn(), error: vi.fn() } }))
import Members from './index.vue'
import MemberDetailDialog from './components/MemberDetailDialog.vue'
import MyMembershipCard from './components/MyMembershipCard.vue'
import MemberBenefits from './components/MemberBenefits.vue'
import type { MemberDetail } from '@/api/member'
import { emptyDefinition } from '@/views/membership-plan/planDefinition'

const slot = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.header?.(), slots.default?.(), slots.footer?.()]) } })
const provider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('row', computed(() => props.row)); return () => h('div', { class: 'test-row' }, slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data || []).map((row: object) => h(provider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop', 'label'], setup(props, { slots }) { const row = inject<ComputedRef<Record<string, unknown>>>('row')!; return () => h('span', [props.label, ': ', slots.default?.({ row: row.value }) || String(row.value?.[props.prop] ?? '')]) } })
const description = defineComponent({ props: ['label'], setup(props, { slots }) { return () => h('div', [props.label, ': ', slots.default?.()]) } })
const button = defineComponent({ setup(_, { slots, attrs }) { return () => h('button', attrs, slots.default?.()) } })
const input = defineComponent({ props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], setup(props, { emit, attrs }) { return () => h('input', { ...attrs, value: props.modelValue, placeholder: props.placeholder, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) } })
const stubs = { ElCard: slot, ElDialog: slot, ElDescriptions: slot, ElDescriptionsItem: description, ElTable: table, ElTableColumn: column, ElButton: button, ElForm: slot, ElFormItem: slot, ElInput: input, ElSelect: true, ElOption: true, ElTag: slot, ElAlert: true, ElPagination: true, ElRow: slot, ElCol: slot, ElInputNumber: true, ElDatePicker: true, ElEmpty: slot, MemberEntitlementPanel: true, HistoryContextDrawer: true }
const wrappers: ReturnType<typeof mount>[] = []
function page(component: any, props = {}) { const wrapper = mount(component, { props, global: { stubs, directives: { loading: () => {} } } }); wrappers.push(wrapper); return wrapper }
const member: MemberDetail = { id: 5, username: 'reader_5', realName: '合成会员', phone: '13800138000', email: null, cardNo: '000005', planName: '原套餐', accountStatus: 0, profilePresent: true, revision: 3, serviceStart: '2026-10-15', serviceEnd: '2026-10-15', serviceStatus: 'ACTIVE', asOfDate: '2026-10-15', usageType: 'LIMITED', usedUses: 0, totalUses: 0, remainingUses: 0, overused: false, notes: '家庭备注', address: '敏感家庭地址', children: [{ id: 9, name: '宝宝资料', gender: null, birthDate: null, birthDatePrecision: 'YEAR', sourceBirthText: '2020年', notes: null }], financialFacts: { paidFee: 0, depositAmount: 0, depositStatus: '未退' }, sourceKey: 'huiben:5', sourceSystem: 'huiben', borrowHistory: [{ id: 1, bookId: 1, bookTitle: '合成绘本', borrowDate: '2026-10-15', dueDate: null, dueDateKind: 'UNLIMITED', returnDate: null, status: 'BORROWING' }] }
beforeEach(() => {
  vi.clearAllMocks(); api.user = reactive({ isAdmin: true, isLibrarian: false, isReader: false, token: 'synthetic-admin', userInfo: { id: 2, role: 'ADMIN' } }); api.route.query = {}
  api.list.mockResolvedValue({ records: [member], total: 1 }); api.detail.mockResolvedValue(member); api.me.mockResolvedValue({ member, effectiveRules: emptyDefinition().rules, snapshotPresent: false })
})
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it('opens the same member details from the unified list or an account link and searches by the complete keyword', async () => {
  api.route.query = { userId: '5' }
  const wrapper = page(Members); await flushPromises()
  expect(api.detail).toHaveBeenCalledWith(5)
  expect(wrapper.text()).toContain('新增会员')
  expect(wrapper.text()).toContain('000005')
  await wrapper.get('input[placeholder="姓名/手机号/卡号/登录账号"]').setValue('13800138000')
  await wrapper.findAll('button').find(button => button.text() === '搜索')!.trigger('click'); await flushPromises()
  expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ keyword: '13800138000', page: 1 }))
})

it('shows admin family, financial facts and history while separating account and service states', async () => {
  const wrapper = page(MemberDetailDialog, { visible: true, memberId: 5 }); await flushPromises()
  expect(wrapper.text()).toContain('账号登录状态: 禁用')
  expect(wrapper.text()).toContain('服务状态: 有效')
  expect(wrapper.text()).toContain('宝宝资料')
  expect(wrapper.text()).toContain('2020年')
  expect(wrapper.text()).toContain('历史实缴费用（元）: 0')
  expect(wrapper.text()).toContain('历史实缴押金（元）: 0')
  expect(wrapper.text()).toContain('不代表当前应收金额或可退余额')
  expect(wrapper.text()).toContain('合成绘本')
  expect(wrapper.text()).toContain('不限制')
  expect(wrapper.text()).toContain('编辑会员')
})

it('opens the shared history drawer for the exact loaded member and closes it when member or parent visibility changes', async () => {
  const wrapper = page(MemberDetailDialog, { visible: true, memberId: 5 }); await flushPromises()
  await wrapper.findAll('button').find(button => button.text() === '历史资料')!.trigger('click'); await flushPromises()
  expect(wrapper.findComponent({ name: 'HistoryContextDrawer' }).props()).toMatchObject({ visible: true, entityType: 'members', entityId: 5 })
  api.detail.mockResolvedValueOnce({ ...member, id: 6 }); await wrapper.setProps({ memberId: 6 }); await flushPromises()
  expect(wrapper.findComponent({ name: 'HistoryContextDrawer' }).props()).toMatchObject({ visible: false, entityId: 6 })
  await wrapper.setProps({ visible: false }); expect(wrapper.findComponent({ name: 'HistoryContextDrawer' }).exists()).toBe(false)
})

it('keeps librarian views read-only and hides family, finance, source and admin actions even if extra payload fields arrive', async () => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: true })
  const wrapper = page(Members); await flushPromises()
  expect(wrapper.text()).not.toContain('新增会员')
  await wrapper.findAll('button').find(button => button.text() === '查看档案')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('000005'); expect(wrapper.text()).toContain('13800138000')
  for (const text of ['编辑会员', '家庭及宝宝资料', '敏感家庭地址', '宝宝资料', '历史费用与押金', '来源与修订', '读取来源预填预览', '历史资料']) expect(wrapper.text()).not.toContain(text)
  expect(api.source).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled(); expect(api.update).not.toHaveBeenCalled()
})

it('only reads the current reader membership and does not expose admin payload fields', async () => {
  Object.assign(api.user, { isAdmin: false, isReader: true })
  const card = page(MyMembershipCard); const management = page(Members); const detail = page(MemberDetailDialog, { visible: true, memberId: 6 })
  await flushPromises()
  expect(api.me).toHaveBeenCalledOnce(); expect(api.list).not.toHaveBeenCalled(); expect(api.detail).not.toHaveBeenCalled(); expect(api.source).not.toHaveBeenCalled()
  expect(card.text()).toContain('我的会员卡'); expect(card.text()).toContain('卡号: 000005')
  expect(card.text()).not.toContain('敏感家庭地址'); expect(card.text()).not.toContain('历史费用与押金')
  expect(management.text()).not.toContain('新增会员'); expect(detail.text()).not.toContain('合成会员')
})

it('never requests a reader-only membership from admin or librarian cards', async () => {
  const wrapper = page(MyMembershipCard); await flushPromises()
  expect(api.me).not.toHaveBeenCalled(); expect(wrapper.text()).toBe('')
})

it.each([['WAITING', '待生效'], ['ACTIVE', '有效'], ['EXPIRED', '已到期'], ['UNKNOWN', '待核实']])('renders service state %s exactly from the date-aware API', (state, label) => {
  const wrapper = page(MemberBenefits, { serviceState: state, usesType: 'UNKNOWN', usedUses: null, totalUses: null, remainingUses: null })
  expect(wrapper.text()).toContain(`服务状态: ${label}`)
  expect(wrapper.text()).toContain('已用次数: 待核实'); expect(wrapper.text()).toContain('总次数: 待核实'); expect(wrapper.text()).toContain('剩余次数: 待核实')
  expect(wrapper.text()).not.toContain('总次数: 不限')
})

it('preserves known zero and negative remaining uses, and distinguishes explicit unlimited', () => {
  const limited = page(MemberBenefits, { usesType: 'LIMITED', usedUses: 0, totalUses: 0, remainingUses: 0 })
  expect(limited.text()).toContain('已用次数: 0'); expect(limited.text()).toContain('总次数: 0'); expect(limited.text()).toContain('剩余次数: 0')
  const overused = page(MemberBenefits, { usesType: 'LIMITED', usedUses: 5, totalUses: 3, remainingUses: -2 })
  expect(overused.text()).toContain('剩余次数: -2')
  const unlimited = page(MemberBenefits, { usesType: 'UNLIMITED', usedUses: null, totalUses: null, remainingUses: null })
  expect(unlimited.text()).toContain('已用次数: 待核实'); expect(unlimited.text()).toContain('总次数: 不限'); expect(unlimited.text()).toContain('剩余次数: 不限')
})
