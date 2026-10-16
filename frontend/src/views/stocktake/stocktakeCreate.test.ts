import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ list: vi.fn(), session: vi.fn(), snapshot: vi.fn(), write: vi.fn(), replace: vi.fn() }))
vi.mock('@/api/stocktake', () => ({ getStocktakes: api.list, getStocktake: api.session, getStocktakeSnapshot: api.snapshot, getStocktakeOperation: vi.fn(), writeStocktakeOperation: api.write }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ isAdmin: true, isLibrarian: false, userInfo: { id: 2 } }) }))
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ replace: api.replace }) }))
import Stocktakes from './index.vue'
import ScopePicker from './StocktakeScopePicker.vue'
import { fixtureReceipt, fixtureReview, fixtureSession } from './stocktakeFixtures'
import { addressRow } from '@/components/locations/locationFixtures'

const slot = defineComponent({ setup(_, { slots }) { return () => h('div', slots.default?.()) } })
const input = defineComponent({ props: ['modelValue', 'placeholder', 'disabled'], emits: ['update:modelValue'], setup(props, { emit }) { return () => h('input', { value: props.modelValue, placeholder: props.placeholder, disabled: props.disabled, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) } })
const button = defineComponent({ props: ['disabled'], setup(props, { slots, attrs }) { return () => h('button', { ...attrs, disabled: props.disabled }, slots.default?.()) } })
// Keep the real scope picker and its local selection lifecycle; only render Element Plus input controls natively.
const scopeKind = defineComponent({ props: ['modelValue', 'disabled'], emits: ['update:modelValue', 'change'], setup(props, { emit }) { return () => h('select', { 'aria-label': '范围类型', value: props.modelValue, disabled: props.disabled, onChange: (event: Event) => { const value = (event.target as HTMLSelectElement).value; emit('update:modelValue', value); emit('change', value) } }, [h('option', { value: 'ADDRESS_SET' }, '明确地址集合'), h('option', { value: 'COPY_SET' }, '明确实体册集合')]) } })
const addressPicker = defineComponent({ props: ['disabled'], emits: ['update:modelValue'], setup(props, { emit }) { return () => h('button', { disabled: props.disabled, onClick: () => emit('update:modelValue', addressRow()) }, '选择合成地址 #12') } })
const alert = defineComponent({ props: ['title'], setup(props) { return () => h('p', props.title) } })
const wrappers: ReturnType<typeof mount>[] = []
function page() { const wrapper = mount(Stocktakes, { global: { stubs: { ElForm: slot, ElFormItem: slot, ElInput: input, ElButton: button, ElTable: true, ElTableColumn: true, ElPagination: true, ElAlert: alert, ElEmpty: true, ElRadioGroup: scopeKind, LocationAddressPicker: addressPicker, CopyPlacement: true, StocktakeReview: true, StocktakeObservations: true, StocktakeDifferences: true, ElTabs: true, ElTabPane: true }, directives: { loading: () => {} } } }); wrappers.push(wrapper); return wrapper }
async function click(wrapper: ReturnType<typeof mount>, label: string) { await wrapper.findAll('button').find(value => value.text() === label)!.trigger('click'); await flushPromises() }
async function selectCopies(wrapper: ReturnType<typeof mount>, ids: string) { await wrapper.get('select[aria-label="范围类型"]').setValue('COPY_SET'); await wrapper.get('input[placeholder="正整数册 ID，逗号分隔"]').setValue(ids); await click(wrapper, '加入 ID') }
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear(); api.list.mockResolvedValue({ records: [], total: 0 }); api.session.mockResolvedValue(fixtureSession()); api.snapshot.mockResolvedValue(fixtureReview()); api.write.mockImplementation((_path, body) => { const receipt = fixtureReceipt(body.operationKey); receipt.result.session!.scope = body.scope; return Promise.resolve(receipt) }) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it.each(['COPY_SET', 'ADDRESS_SET'])('clears an old %s selection when the real create picker is closed and reopened', async (kind) => {
  const wrapper = page(); await flushPromises(); await click(wrapper, '新建有限范围盘点')
  if (kind === 'COPY_SET') await selectCopies(wrapper, '17,18')
  else { await click(wrapper, '选择合成地址 #12'); await click(wrapper, '加入本次范围') }
  expect(wrapper.find('.scope-summary').text()).toContain(kind === 'COPY_SET' ? 'COPY_SET [17, 18]' : 'ADDRESS_SET [12]')
  await wrapper.get('input[placeholder="范围选择和盘点依据"]').setValue('上一次的范围依据')
  await click(wrapper, '新建有限范围盘点'); expect(wrapper.findComponent(ScopePicker).exists()).toBe(false)
  await click(wrapper, '新建有限范围盘点'); expect(wrapper.find('.scope-summary').text()).toContain('ADDRESS_SET []')
  expect((wrapper.get('input[placeholder="范围选择和盘点依据"]').element as HTMLInputElement).value).toBe('')
  await wrapper.get('input[placeholder="范围选择和盘点依据"]').setValue('重新选择前不能创建')
  await click(wrapper, '固定范围并建立基线'); expect(api.write).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('请明确选择有限范围')
  await selectCopies(wrapper, '21'); expect(wrapper.find('.scope-summary').text()).toContain('COPY_SET [21]')
  await click(wrapper, '固定范围并建立基线'); expect(api.write).toHaveBeenCalledOnce(); expect(api.write.mock.calls[0]![1].scope).toEqual({ kind: 'COPY_SET', copyIds: [21] })
})

it('starts the next create form empty after successful creation and return, and sends only the new visible range', async () => {
  const wrapper = page(); await flushPromises(); await click(wrapper, '新建有限范围盘点'); await selectCopies(wrapper, '11,12')
  await wrapper.get('input[placeholder="范围选择和盘点依据"]').setValue('第一单元'); await click(wrapper, '固定范围并建立基线'); expect(api.write).toHaveBeenCalledOnce()
  await click(wrapper, '已查看回执，继续办理'); await click(wrapper, '返回盘点列表'); await click(wrapper, '新建有限范围盘点')
  expect(wrapper.find('.scope-summary').text()).toContain('ADDRESS_SET []')
  await wrapper.get('input[placeholder="范围选择和盘点依据"]').setValue('第二单元尚未选范围'); await click(wrapper, '固定范围并建立基线'); expect(api.write).toHaveBeenCalledOnce()
  await click(wrapper, '选择合成地址 #12'); await click(wrapper, '加入本次范围'); expect(wrapper.find('.scope-summary').text()).toContain('ADDRESS_SET [12]')
  await click(wrapper, '固定范围并建立基线'); expect(api.write).toHaveBeenCalledTimes(2)
  expect(api.write.mock.calls.map(value => value[1].scope)).toEqual([{ kind: 'COPY_SET', copyIds: [11, 12] }, { kind: 'ADDRESS_SET', locationIds: [12] }])
  expect(api.write.mock.calls[1]![1].operationKey).not.toBe(api.write.mock.calls[0]![1].operationKey)
})
