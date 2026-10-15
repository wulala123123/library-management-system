import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { PlanVersion } from '@/api/membershipPlan'
import { emptyDefinition } from './planDefinition'

const api = vi.hoisted(() => ({ list: vi.fn(), get: vi.fn(), user: { isAdmin: true, isLibrarian: false, isReader: false, initUserInfo: vi.fn(), userInfo: null }, route: { path: '/membership-plans', meta: {} } }))
vi.mock('@/api/membershipPlan', () => ({ getMembershipPlans: api.list, getMembershipPlan: api.get, createMembershipPlan: vi.fn(), saveMembershipPlanDraft: vi.fn(), publishMembershipPlan: vi.fn(), selectMembershipPlanVersion: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => api.route }))
vi.mock('element-plus', () => ({ ElMessage: { success: vi.fn() }, ElMessageBox: { confirm: vi.fn() } }))
import Plans from './index.vue'
import VersionCompare from './components/VersionCompare.vue'
import Layout from '@/components/Layout/index.vue'

const slot = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.default?.(), slots.header?.(), slots.footer?.(), slots.dropdown?.()]) } })
const provider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('row', computed(() => props.row)); return () => h('div', { class: 'test-row' }, slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data ?? []).map((row: object) => h(provider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop', 'label'], setup(props, { slots }) { const row = inject<ComputedRef<Record<string, unknown>>>('row')!; return () => h('span', [props.label, ': ', slots.default?.({ row: row.value }) ?? String(row.value[props.prop] ?? '')]) } })
const button = defineComponent({ setup(_, { slots, attrs }) { return () => h('button', attrs, slots.default?.()) } })
const input = defineComponent({ props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], setup(props, { emit, attrs }) { return () => h('input', { ...attrs, placeholder: props.placeholder, value: props.modelValue, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) } })
const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup(props, { slots, emit, attrs }) { return () => h('select', { ...attrs, value: props.modelValue ?? '', onChange: (event: Event) => emit('update:modelValue', Number((event.target as HTMLSelectElement).value) || null) }, slots.default?.()) } })
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
const routerLink = defineComponent({ props: ['to'], setup(props, { slots }) { return () => h('a', { href: props.to }, slots.default?.()) } })
const stubs = { ElForm: slot, ElFormItem: slot, ElButton: button, ElInput: input, ElTable: table, ElTableColumn: column, ElTag: slot, ElSelect: select, ElOption: option, ElPagination: true, ElAlert: true, ElEmpty: slot, ElIcon: slot, ElBreadcrumb: slot, ElBreadcrumbItem: slot, ElAvatar: slot, ElDropdown: slot, ElDropdownMenu: slot, ElDropdownItem: slot, RouterLink: routerLink, RouterView: true, PlanEditorDialog: true }
const wrappers: ReturnType<typeof mount>[] = []
function page(component: any, props = {}) { const wrapper = mount(component, { props, global: { stubs, directives: { loading: () => {} } } }); wrappers.push(wrapper); return wrapper }
beforeEach(() => { vi.clearAllMocks(); Object.assign(api.user, { isAdmin: true, isLibrarian: false, isReader: false }); api.list.mockResolvedValue({ records: [{ id: 5, name: '合成套餐', revision: 3, enabled: false, selectedVersionId: null, versionCount: 0, updatedAt: '2026-10-15', updatedBy: 1 }], total: 1 }) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it('lists and searches plan drafts without creating or enabling any plan', async () => {
  const wrapper = page(Plans); await flushPromises()
  expect(wrapper.text()).toContain('合成套餐'); expect(wrapper.text()).toContain('后续使用: 已停用'); expect(wrapper.text()).toContain('所选版本 ID: 未选择')
  expect(wrapper.text()).toContain('已有会员的日期、次数和权益保持原样')
  await wrapper.get('input[placeholder="搜索套餐名称"]').setValue('合成')
  await wrapper.findAll('button').find(button => button.text() === '搜索')!.trigger('click'); await flushPromises()
  expect(api.list).toHaveBeenLastCalledWith({ page: 1, size: 10, keyword: '合成' })
})

it.each(['LIBRARIAN', 'READER'])('neither navigates nor requests plan data as %s', async role => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: role === 'LIBRARIAN', isReader: role === 'READER' })
  const pageWrapper = page(Plans), layout = page(Layout); await flushPromises()
  expect(pageWrapper.text()).not.toContain('新增套餐草稿'); expect(api.list).not.toHaveBeenCalled(); expect(api.get).not.toHaveBeenCalled()
  expect(layout.find('a[href="/membership-plans"]').exists()).toBe(false)
})

it('shows the admin-only plan navigation', async () => {
  const wrapper = page(Layout); await flushPromises()
  expect(wrapper.get('a[href="/membership-plans"]').text()).toContain('套餐版本')
})

it('compares immutable versions with zero, unlimited and unknown separately and does not modify the inputs', async () => {
  const older = { ...emptyDefinition(), id: 11, planId: 5, versionNumber: 1, name: '原版本', confirmationBasis: '原核实依据', createdAt: '2026-10-15', createdBy: 1 } as PlanVersion
  older.rules.perOrderLimit = { mode: 'LIMITED', value: 0 }; older.rules.usageType = 'UNLIMITED'
  const newer = JSON.parse(JSON.stringify(older)) as PlanVersion
  newer.id = 12; newer.versionNumber = 2; newer.name = '新版本'; newer.rules.perOrderLimit = { mode: 'UNLIMITED', value: null }; newer.rules.activeLoanLimit = { mode: 'LIMITED', value: 10 }
  const before = JSON.stringify([older, newer]), wrapper = page(VersionCompare, { versions: [older, newer] })
  await flushPromises()
  const rows = wrapper.findAll('.test-row')
  const perOrder = rows.find(row => row.text().includes('单次借书上限'))!
  expect(perOrder.text()).toContain('不限'); expect(perOrder.text()).toContain('0 册'); expect(perOrder.text()).toContain('有差异')
  expect(rows.find(row => row.text().includes('累计在借上限'))!.text()).toContain('待核实')
  expect(wrapper.text()).toContain('原核实依据')
  await wrapper.get('select[aria-label="版本A"]').setValue('11'); await wrapper.get('select[aria-label="版本B"]').setValue('12')
  expect(JSON.stringify([older, newer])).toBe(before)
})
