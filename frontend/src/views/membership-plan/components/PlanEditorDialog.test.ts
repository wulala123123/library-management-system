import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import type { PlanDefinition, PlanDetail, PlanVersion } from '@/api/membershipPlan'
import { copyDefinition, emptyDefinition } from '../planDefinition'

const api = vi.hoisted(() => ({ get: vi.fn(), create: vi.fn(), save: vi.fn(), publish: vi.fn(), select: vi.fn(), user: { isAdmin: true } }))
vi.mock('@/api/membershipPlan', () => ({ getMembershipPlan: api.get, createMembershipPlan: api.create, saveMembershipPlanDraft: api.save, publishMembershipPlan: api.publish, selectMembershipPlanVersion: api.select }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import PlanEditorDialog from './PlanEditorDialog.vue'

const select = defineComponent({ props: { modelValue: { type: [String, Array] }, multiple: Boolean, placeholder: String }, emits: ['update:modelValue'], setup(props, { slots, emit, attrs }) {
  return () => props.multiple ? h('input', { ...attrs, placeholder: props.placeholder, value: Array.isArray(props.modelValue) ? props.modelValue.join(',') : '', onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value.split(',').filter(Boolean)) }) : h('select', { ...attrs, value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, slots.default?.())
} })
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
const provider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('row', computed(() => props.row)); return () => h('div', slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data ?? []).map((row: object) => h(provider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop', 'label'], setup(props, { slots }) { const row = inject<ComputedRef<Record<string, unknown>>>('row')!; return () => h('div', [props.label, ': ', slots.default?.({ row: row.value }) ?? String(row.value[props.prop] ?? '')]) } })

function complete(): PlanDefinition {
  const value = emptyDefinition(); value.name = '合成套餐'; value.sourceReferences = ['合成来源']; value.rules = { serviceDurationDays: 30, dayCountConvention: 'START_INCLUDED', perOrderLimit: { mode: 'LIMITED', value: 0 }, activeLoanLimit: { mode: 'LIMITED', value: 10 }, usageType: 'UNLIMITED', totalUses: null, dueDateType: 'DAYS', borrowDays: 20, renewalPermission: 'FORBIDDEN', renewalLimit: null, renewalDays: null, languages: { mode: 'ANY', values: [] }, pickupMethods: { mode: 'ALLOW_LIST', values: ['到馆自取'] } }; return value
}
const version = (id = 11, number = 1): PlanVersion => ({ ...complete(), id, planId: 5, versionNumber: number, confirmationBasis: '合成核实依据', createdAt: '2026-10-15T10:00:00', createdBy: 1 })
function detail(draft = complete(), revision = 3): PlanDetail { return { id: 5, name: draft.name, revision, enabled: false, selectedVersionId: null, versionCount: 0, updatedAt: null, updatedBy: 1, draft: copyDefinition(draft), versions: [] } }
const wrappers: ReturnType<typeof mount>[] = []
async function editor(id: number | null = 5) {
  const wrapper = mount(PlanEditorDialog, { props: { visible: false, planId: id }, attachTo: document.body, global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, ElSelect: select, ElOption: option, ElTable: table, ElTableColumn: column, VersionCompare: true } } })
  wrappers.push(wrapper); await wrapper.setProps({ visible: true }); await flushPromises(); return wrapper
}
function button(wrapper: ReturnType<typeof mount>, label: string) { return wrapper.findAll('button').find(button => button.text().trim() === label)! }
async function click(wrapper: ReturnType<typeof mount>, label: string) { await button(wrapper, label).trigger('click'); await flushPromises() }
async function publishInputs(wrapper: ReturnType<typeof mount>) { await wrapper.get('textarea[placeholder="逐项核实依据（发布必填）"]').setValue('逐项核对来源'); await wrapper.get('input[placeholder="本次发布原因（必填）"]').setValue('核实发布') }
beforeEach(() => {
  vi.clearAllMocks(); api.user.isAdmin = true; api.get.mockResolvedValue(detail())
  api.create.mockImplementation((value: PlanDefinition) => Promise.resolve(detail(value, 0)))
  api.save.mockImplementation((_id: number, value: PlanDefinition & { revision: number }) => Promise.resolve(detail(value, value.revision + 1)))
  api.publish.mockResolvedValue({ version: version(), revision: 4, replayed: false })
  api.select.mockImplementation((_id: number, value: { enabled: boolean; versionId: number; revision: number }) => Promise.resolve({ ...detail(complete(), value.revision + 1), versions: [version()], versionCount: 1, enabled: value.enabled, selectedVersionId: value.versionId }))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

it('saves an unknown draft with explicit null fields and keeps publication disabled', async () => {
  const wrapper = await editor(null)
  await wrapper.get('input[placeholder="套餐名称"]').setValue('合成草稿')
  await wrapper.get('textarea[placeholder="逐行填写来源名称、编号或链接"]').setValue('来源一\n来源二')
  await wrapper.get('input[placeholder="说明本次保存草稿的原因"]').setValue('待核实先存档')
  await click(wrapper, '保存草稿')
  expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ name: '合成草稿', sourceReferences: ['来源一', '来源二'], rules: expect.objectContaining({ serviceDurationDays: null, perOrderLimit: { mode: 'UNKNOWN', value: null }, activeLoanLimit: { mode: 'UNKNOWN', value: null }, usageType: 'UNKNOWN', totalUses: null }) }))
  expect(button(wrapper, '发布不可变版本').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('单次借书上限待核实'); expect(api.publish).not.toHaveBeenCalled(); expect(api.select).not.toHaveBeenCalled()
})

it('keeps independent finite zero limits and unlimited due dates, clearing inapplicable renewal settings', async () => {
  const wrapper = await editor()
  expect(wrapper.get('input[placeholder="单次借书上限数值"]').element).toHaveProperty('value', '0')
  expect(wrapper.get('input[placeholder="累计在借上限数值"]').element).toHaveProperty('value', '10')
  await wrapper.get('select[aria-label="总次数类型"]').setValue('LIMITED')
  await wrapper.get('input[placeholder="总次数数值"]').setValue('0'); await wrapper.get('input[placeholder="总次数数值"]').trigger('blur')
  await wrapper.get('select[aria-label="归还期限类型"]').setValue('UNLIMITED')
  await wrapper.get('select[aria-label="续借权限"]').setValue('ALLOWED')
  await wrapper.get('select[aria-label="续借次数类型"]').setValue('LIMITED')
  await wrapper.get('input[placeholder="续借次数数值"]').setValue('0'); await wrapper.get('input[placeholder="续借次数数值"]').trigger('blur')
  await wrapper.get('input[placeholder="续借延长天数"]').setValue('10'); await wrapper.get('input[placeholder="续借延长天数"]').trigger('blur')
  await wrapper.get('select[aria-label="续借权限"]').setValue('FORBIDDEN')
  await wrapper.get('input[placeholder="说明本次保存草稿的原因"]').setValue('核对零值和不限')
  await click(wrapper, '保存草稿')
  expect(api.save).toHaveBeenCalledWith(5, expect.objectContaining({ revision: 3, rules: expect.objectContaining({ perOrderLimit: { mode: 'LIMITED', value: 0 }, activeLoanLimit: { mode: 'LIMITED', value: 10 }, totalUses: 0, usageType: 'LIMITED', dueDateType: 'UNLIMITED', borrowDays: null, renewalPermission: 'FORBIDDEN', renewalLimit: null, renewalDays: null }) }))
})

it('does not treat an empty specified language scope as unrestricted', async () => {
  const wrapper = await editor()
  await wrapper.get('select[aria-label="语种类型"]').setValue('ALLOW_LIST')
  await wrapper.get('input[placeholder="说明本次保存草稿的原因"]').setValue('指定语种')
  await click(wrapper, '保存草稿')
  expect(api.save).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('语种范围须填写不重复的允许项')
  await wrapper.get('input[aria-label="语种允许项"]').setValue('中文,英文')
  await click(wrapper, '保存草稿')
  expect(api.save).toHaveBeenCalledWith(5, expect.objectContaining({ rules: expect.objectContaining({ languages: { mode: 'ALLOW_LIST', values: ['中文', '英文'] } }) }))
})

it('preserves draft input and revision on conflict instead of refreshing over edits', async () => {
  api.save.mockRejectedValueOnce({ response: { status: 409 } })
  const wrapper = await editor()
  await wrapper.get('input[placeholder="套餐名称"]').setValue('保留修改')
  await wrapper.get('input[placeholder="说明本次保存草稿的原因"]').setValue('人工修订')
  await click(wrapper, '保存草稿')
  expect(wrapper.text()).toContain('输入已保留'); expect(wrapper.get('input[placeholder="套餐名称"]').element).toHaveProperty('value', '保留修改')
  expect(api.get).toHaveBeenCalledOnce(); expect(api.save.mock.calls[0][1].revision).toBe(3)
  expect(wrapper.emitted('saved')).toBeUndefined()
})

it('retries a failed publication with the same body and key, then requires separate version selection and stop actions', async () => {
  api.publish.mockRejectedValueOnce(new Error('response lost'))
  const wrapper = await editor(); await publishInputs(wrapper)
  await click(wrapper, '发布不可变版本'); const first = api.publish.mock.calls[0][1]
  expect(wrapper.get('textarea[placeholder="逐项核实依据（发布必填）"]').element).toHaveProperty('value', '逐项核对来源')
  expect(api.select).not.toHaveBeenCalled()
  await click(wrapper, '发布不可变版本')
  expect(api.publish.mock.calls[1][1]).toEqual(first); expect(api.select).not.toHaveBeenCalled()
  await wrapper.get('input[placeholder="选择或停用的原因（必填）"]').setValue('核实后用于后续办理')
  await click(wrapper, '选为后续可用版本')
  expect(api.select).toHaveBeenLastCalledWith(5, { revision: 4, versionId: 11, enabled: true, changeReason: '核实后用于后续办理' })
  await wrapper.get('input[placeholder="选择或停用的原因（必填）"]').setValue('暂停后续使用')
  await click(wrapper, '停止后续使用')
  expect(api.select).toHaveBeenLastCalledWith(5, { revision: 5, versionId: 11, enabled: false, changeReason: '暂停后续使用' })
})

it('changes the publication key when its evidence changes and blocks publication of unsaved content', async () => {
  api.publish.mockRejectedValue(new Error('offline'))
  const wrapper = await editor(); await publishInputs(wrapper); await click(wrapper, '发布不可变版本')
  const firstKey = api.publish.mock.calls[0][1].idempotencyKey
  await wrapper.get('textarea[placeholder="逐项核实依据（发布必填）"]').setValue('补充核实依据'); await click(wrapper, '发布不可变版本')
  expect(api.publish.mock.calls[1][1].idempotencyKey).not.toBe(firstKey)
  await wrapper.get('input[placeholder="套餐名称"]').setValue('尚未保存的名称')
  expect(button(wrapper, '发布不可变版本').attributes('disabled')).toBeDefined()
})

it('does not expose controls or request plan details for non-admin users', async () => {
  api.user.isAdmin = false
  const wrapper = await editor()
  expect(wrapper.text()).toBe(''); expect(api.get).not.toHaveBeenCalled(); expect(api.publish).not.toHaveBeenCalled()
})
