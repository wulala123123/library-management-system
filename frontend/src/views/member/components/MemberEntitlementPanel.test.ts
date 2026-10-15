import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import { emptyDefinition } from '@/views/membership-plan/planDefinition'
import type { EntitlementView } from '@/api/memberEntitlement'
const api = vi.hoisted(() => ({ current: vi.fn(), preview: vi.fn(), confirm: vi.fn(), history: vi.fn(), plans: vi.fn(), plan: vi.fn(), user: { isAdmin: true } }))
vi.mock('@/api/memberEntitlement', () => ({ getMemberEntitlement: api.current, previewMemberEntitlement: api.preview, confirmMemberEntitlement: api.confirm, getEntitlementHistory: api.history }))
vi.mock('@/api/membershipPlan', () => ({ getMembershipPlans: api.plans, getMembershipPlan: api.plan }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import Panel from './MemberEntitlementPanel.vue'
const current = (): EntitlementView => ({ member: { id: 5, username: 'synthetic', realName: '合成会员', phone: null, email: null, accountStatus: 1, profilePresent: true, revision: 3, cardNo: null, planName: '原套餐', serviceStart: '2020-01-02', serviceEnd: '2020-03-04', serviceStatus: 'EXPIRED', asOfDate: '2026-10-15', usageType: 'UNKNOWN', usedUses: 0, totalUses: 12, remainingUses: null, overused: false }, effectiveRules: emptyDefinition().rules, snapshot: null })
const wrappers: ReturnType<typeof mount>[] = []
const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup(props, { slots, emit, attrs }) { return () => h('select', { ...attrs, value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value || null) }, slots.default?.()) } })
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
const date = defineComponent({ props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], setup(props, { emit }) { return () => h('input', { type: 'date', value: props.modelValue, placeholder: props.placeholder, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value || null) }) } })
async function panel() {
  const wrapper = mount(Panel, { props: { memberId: 5 }, attachTo: document.body, global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, EntitlementRules: true, LimitEditor: true, ScopeEditor: true, ElSelect: select, ElOption: option, ElDatePicker: date, ElTable: true, ElPagination: true } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
async function click(wrapper: ReturnType<typeof mount>, label: string) { await wrapper.findAll('button').find(button => button.text().trim() === label)!.trigger('click'); await flushPromises() }
beforeEach(() => {
  vi.clearAllMocks(); api.user.isAdmin = true; api.current.mockResolvedValue(current()); api.plans.mockResolvedValue({ records: [], total: 0 })
  api.preview.mockImplementation((_id, body) => Promise.resolve({ previewHash: 'a'.repeat(64), current: current(), proposed: { actual: body.actual, rules: body.rules, planVersionId: body.planVersionId }, version: null, differences: [], historicalVersion: false }))
  api.confirm.mockResolvedValue({ snapshot: { id: 1 }, revision: 4, replayed: false })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })
it('previews explicit current facts without saving and invalidates preview after edits', async () => {
  const wrapper = await panel(); await click(wrapper, '核对权益与套餐依据')
  await wrapper.get('textarea[placeholder="本次权益核对或调整原因（必填）"]').setValue('核对来源')
  await click(wrapper, '生成只读核对预览')
  expect(api.preview).toHaveBeenCalledWith(5, expect.objectContaining({ revision: 3, planVersionId: null, actual: expect.objectContaining({ serviceStart: '2020-01-02', serviceEnd: '2020-03-04', totalUses: 12, usedUses: 0 }), rules: expect.objectContaining({ usageType: 'UNKNOWN', totalUses: null }) }))
  expect(api.confirm).not.toHaveBeenCalled()
  await wrapper.get('textarea[placeholder="本次权益核对或调整原因（必填）"]').setValue('修改核对依据')
  expect(wrapper.findAll('button').some(button => button.text() === '确认登记权益快照')).toBe(false)
})
it('requires review confirmation, retains input after failure and retries with identical key and body', async () => {
  api.confirm.mockRejectedValueOnce({ response: { status: 409 } })
  const wrapper = await panel(); await click(wrapper, '核对权益与套餐依据')
  await wrapper.get('textarea[placeholder="本次权益核对或调整原因（必填）"]').setValue('人工核对')
  await click(wrapper, '生成只读核对预览')
  expect(wrapper.findAll('button').find(button => button.text().trim() === '确认登记权益快照')!.attributes('disabled')).toBeDefined()
  await wrapper.get('input[type="checkbox"]').setValue(true)
  await click(wrapper, '确认登记权益快照')
  expect(wrapper.text()).toContain('输入已保留')
  expect(wrapper.get('textarea[placeholder="本次权益核对或调整原因（必填）"]').element).toHaveProperty('value', '人工核对')
  const first = api.confirm.mock.calls[0][1]
  await click(wrapper, '确认登记权益快照')
  expect(api.confirm.mock.calls[1][1]).toEqual(first)
  expect(wrapper.emitted('saved')).toHaveLength(1)
  expect(api.current).toHaveBeenCalledTimes(2)
})
it('does not read plans, current or history or render controls for non-admins', async () => {
  api.user.isAdmin = false
  const wrapper = await panel()
  expect(wrapper.text()).toBe(''); expect(api.current).not.toHaveBeenCalled(); expect(api.plans).not.toHaveBeenCalled(); expect(api.history).not.toHaveBeenCalled()
})
