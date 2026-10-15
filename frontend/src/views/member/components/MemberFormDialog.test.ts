import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({ create: vi.fn(), update: vi.fn(), source: vi.fn(), user: { isAdmin: true } }))
vi.mock('@/api/member', () => ({ createMember: api.create, updateMember: api.update, getMemberSourcePreview: api.source }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import MemberFormDialog from './MemberFormDialog.vue'
import type { MemberDetail } from '@/api/member'

const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup(props, { slots, emit, attrs }) { return () => h('select', { ...attrs, value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, slots.default?.()) } })
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
const date = defineComponent({ props: ['modelValue', 'placeholder'], emits: ['update:modelValue'], setup(props, { emit }) { return () => h('input', { type: 'date', value: props.modelValue, placeholder: props.placeholder, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value || null) }) } })
const wrappers: ReturnType<typeof mount>[] = []
const member: MemberDetail = { id: 5, username: 'reader_5', realName: '合成会员', phone: '13800138000', email: '', cardNo: '000005', planName: '原套餐', accountStatus: 0, profilePresent: true, revision: 3, serviceStart: '2026-10-15', serviceEnd: '2026-10-15', serviceStatus: 'ACTIVE', asOfDate: '2026-10-15', usageType: 'LIMITED', usedUses: 0, totalUses: 0, remainingUses: 0, overused: false, notes: '', address: '', children: [], financialFacts: { paidFee: 0, depositAmount: 0, depositStatus: '未退' } }
async function dialog(value: MemberDetail | null = null) {
  const wrapper = mount(MemberFormDialog, { props: { visible: false, member: value }, attachTo: document.body, global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, ElSelect: select, ElOption: option, ElDatePicker: date } } })
  wrappers.push(wrapper); await wrapper.setProps({ visible: true }); await flushPromises(); return wrapper
}
async function click(wrapper: ReturnType<typeof mount>, label: string) { await wrapper.findAll('button').find(button => button.text().trim() === label)!.trigger('click'); await flushPromises() }
async function required(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[placeholder="会员登录账号"]').setValue('new_reader')
  await wrapper.get('input[placeholder="初始密码（至少6位）"]').setValue('Reader123')
  await wrapper.get('input[placeholder="会员姓名"]').setValue('合成新会员')
  await wrapper.get('textarea[placeholder="说明创建或修订依据"]').setValue('按柜台确认资料新建')
}
beforeEach(() => { vi.clearAllMocks(); api.user.isAdmin = true; api.create.mockResolvedValue(member); api.update.mockResolvedValue({ ...member, revision: 4 }); api.source.mockResolvedValue({ sourceAvailable: false, sourceKey: null, archiveId: null, fields: [], unmappedFields: [] }) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

it('creates a reader profile with unknown values left null and without security or financial write fields', async () => {
  const wrapper = await dialog(); await required(wrapper); await click(wrapper, '保存会员')
  expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ username: 'new_reader', password: 'Reader123', realName: '合成新会员', usageType: 'UNKNOWN', usedUses: null, totalUses: null, cardNo: null, serviceStart: null, serviceEnd: null, children: [] }))
  for (const field of ['role', 'accountStatus', 'financialFacts', 'paidFee', 'depositAmount']) expect(api.create.mock.calls[0][0]).not.toHaveProperty(field)
  expect(wrapper.emitted('saved')).toHaveLength(1)
})

it('edits the same profile fields while retaining zero values, card leading zeroes and child IDs', async () => {
  const child = { id: 9, name: '宝宝', gender: null, birthDate: null, birthDatePrecision: 'YEAR' as const, sourceBirthText: '2020年', sourceKey: 'manual:child9', notes: null }
  const wrapper = await dialog({ ...member, children: [child] })
  await wrapper.get('textarea[placeholder="说明创建或修订依据"]').setValue('核对既有资料')
  await click(wrapper, '保存会员')
  expect(api.update).toHaveBeenCalledWith(5, expect.objectContaining({ revision: 3, cardNo: '000005', usageType: 'LIMITED', usedUses: 0, totalUses: 0, serviceStart: '2026-10-15', serviceEnd: '2026-10-15', children: [child] }))
  for (const field of ['username', 'password', 'role', 'accountStatus', 'financialFacts']) expect(api.update.mock.calls[0][1]).not.toHaveProperty(field)
})

it('preserves input and revision after HTTP409 and explains the need to refresh and review', async () => {
  api.update.mockRejectedValueOnce({ response: { status: 409 } })
  const wrapper = await dialog(member)
  await wrapper.get('input[placeholder="会员姓名"]').setValue('待保存姓名')
  await wrapper.get('textarea[placeholder="说明创建或修订依据"]').setValue('人工补充')
  await click(wrapper, '保存会员')
  expect(wrapper.emitted('saved')).toBeUndefined(); expect(wrapper.emitted('update:visible')).toBeUndefined()
  expect(wrapper.get('input[placeholder="会员姓名"]').element).toHaveProperty('value', '待保存姓名')
  expect(wrapper.text()).toContain('输入已保留')
  expect(wrapper.text()).toContain('刷新核对')
  expect(api.update.mock.calls[0][1].revision).toBe(3)
})

it('preserves edits after other API failures and allows a corrected retry', async () => {
  api.create.mockRejectedValueOnce(new Error('用户名已存在'))
  const wrapper = await dialog(); await required(wrapper); await click(wrapper, '保存会员')
  expect(wrapper.emitted('saved')).toBeUndefined()
  await wrapper.get('input[placeholder="会员登录账号"]').setValue('another_reader')
  await click(wrapper, '保存会员')
  expect(api.create).toHaveBeenCalledTimes(2); expect(wrapper.emitted('saved')).toHaveLength(1)
})

it('requires finite totals and rejects a reversed service date without clearing input', async () => {
  const wrapper = await dialog(); await required(wrapper)
  await wrapper.get('select[aria-label="次数类型"]').setValue('LIMITED')
  await click(wrapper, '保存会员'); expect(api.create).not.toHaveBeenCalled()
  await wrapper.get('input[placeholder="有限总次数"]').setValue('0')
  await wrapper.get('input[placeholder="有限总次数"]').trigger('blur')
  await wrapper.get('input[placeholder="开始日待核实"]').setValue('2026-10-16')
  await wrapper.get('input[placeholder="结束日待核实"]').setValue('2026-10-15')
  await click(wrapper, '保存会员'); expect(api.create).not.toHaveBeenCalled()
  expect(wrapper.findAll('.el-form-item.is-error').map(item => item.text())).toEqual(expect.arrayContaining([expect.stringContaining('服务结束日')]))
  await wrapper.get('input[placeholder="结束日待核实"]').setValue('2026-10-16')
  await click(wrapper, '保存会员')
  expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ usageType: 'LIMITED', totalUses: 0, serviceStart: '2026-10-16', serviceEnd: '2026-10-16' }))
})

it('saves explicit unlimited usage without inventing a used count or child birth date', async () => {
  const wrapper = await dialog(); await required(wrapper)
  await wrapper.get('select[aria-label="次数类型"]').setValue('UNLIMITED')
  await click(wrapper, '添加宝宝')
  await wrapper.get('input[placeholder="宝宝1姓名（选填）"]').setValue('宝宝')
  await wrapper.get('input[placeholder="保留原日期或年龄文本，不推算生日"]').setValue('来源登记为五岁')
  await click(wrapper, '保存会员')
  expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ usageType: 'UNLIMITED', usedUses: null, totalUses: null, children: [expect.objectContaining({ name: '宝宝', birthDate: null, birthDatePrecision: 'UNKNOWN', sourceBirthText: '来源登记为五岁' })] }))
})

it('previews source conflicts and applies only selected fields without saving automatically', async () => {
  api.source.mockResolvedValue({ sourceAvailable: true, sourceKey: 'huiben:5', archiveId: 21, fields: [
    { field: 'realName', sourceField: '会员名字', currentValue: '合成会员', sourceValue: '原姓名', conflict: true },
    { field: 'phone', sourceField: '手机号', currentValue: '13800138000', sourceValue: '13900139000', conflict: true },
    { field: 'totalUses', sourceField: '总次数', currentValue: null, sourceValue: '0', conflict: true, reviewOnly: true },
    { field: 'children', sourceField: '宝宝资料', currentValue: [], sourceValue: [{ name: '未确认资料' }], conflict: true },
  ], unmappedFields: ['原系统补充资料'] })
  const wrapper = await dialog(member)
  await click(wrapper, '读取来源预填预览')
  expect(api.source).toHaveBeenCalledWith(5); expect(wrapper.text()).toContain('与当前资料冲突')
  expect(wrapper.get('input[placeholder="手机号（选填，可共享）"]').element).toHaveProperty('value', '13800138000')
  const row = wrapper.findAll('.source-field').find(row => row.text().includes('手机号'))!
  await row.get('input[type="checkbox"]').setValue(true)
  await click(wrapper, '将已选来源值预填到表单')
  expect(wrapper.get('input[placeholder="手机号（选填，可共享）"]').element).toHaveProperty('value', '13900139000')
  expect(wrapper.get('input[placeholder="会员姓名"]').element).toHaveProperty('value', '合成会员')
  expect(api.update).not.toHaveBeenCalled(); expect(api.create).not.toHaveBeenCalled()
  expect(wrapper.findAll('.source-field').find(row => row.text().includes('宝宝资料'))!.get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
  expect(wrapper.findAll('.source-field').find(row => row.text().includes('总次数'))!.get('input[type="checkbox"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('不根据原导出零值或空值推断不限')
})

it('does not allow non-admin callers to save or read source previews', async () => {
  api.user.isAdmin = false
  const wrapper = await dialog(member)
  expect(wrapper.text()).not.toContain('读取来源预填预览')
  expect(api.source).not.toHaveBeenCalled(); expect(api.update).not.toHaveBeenCalled()
})
