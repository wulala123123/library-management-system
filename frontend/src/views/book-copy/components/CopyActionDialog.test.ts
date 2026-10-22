import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import type { CopyActionReceipt, AnyCopyActionView } from '@/api/bookCopyAction'
const api = vi.hoisted(() => ({ preview: vi.fn(), confirm: vi.fn(), get: vi.fn(), byKey: vi.fn(), context: vi.fn(), history: vi.fn(), user: { isAdmin: true, isLibrarian: false } }))
vi.mock('@/api/bookCopyAction', () => ({ previewCopyAction: api.preview, previewCopyActionV2: api.preview, confirmCopyAction: api.confirm, getCopyAction: api.get, getCopyActionByKey: api.byKey, getCopyActionContext: api.context, getCopyActionHistory: api.history }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import CopyActionDialog from './CopyActionDialog.vue'
import { useCopyActionSession } from './copyActionSession'
import { actionContext, actionReceipt, actionRequest, actionView, actionViewV2, actionReceiptV2, writeOffEvent } from './copyActionFixtures'
import { addressRow } from '@/components/locations/locationFixtures'
const wrappers: ReturnType<typeof mount>[] = []
const select = defineComponent({ props: ['modelValue', 'disabled', 'placeholder'], emits: ['update:modelValue'], setup(props, { slots, emit }) { return () => h('select', { value: props.modelValue, disabled: props.disabled, 'aria-label': props.placeholder, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, [h('option', { value: '' }, props.placeholder || '请选择'), slots.default?.()]) } })
const option = defineComponent({ props: ['value', 'label', 'disabled'], setup(props) { return () => h('option', { value: props.value, disabled: props.disabled }, props.label) } })
const addressPicker = defineComponent({ props: ['modelValue', 'disabled'], emits: ['update:modelValue'], setup(props,{emit}) { return () => h('button',{disabled:props.disabled,onClick:()=>emit('update:modelValue',addressRow(12))},'合成选择登记地址') } })
let latest: AnyCopyActionView = actionView()
async function dialog(ids = [7]) { const wrapper = mount(CopyActionDialog, { props: { visible: true, copyIds: ids }, attachTo: document.body, global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, ElSelect: select, ElOption: option, LocationAddressPicker: addressPicker } } }); wrappers.push(wrapper); await flushPromises(); return wrapper }
async function click(wrapper: ReturnType<typeof mount>, text: string) { await wrapper.findAll('button').find(button => button.text().trim() === text)!.trigger('click'); await flushPromises() }
async function draft(wrapper: ReturnType<typeof mount>, action = 'MOVE', location = ' A区 😀 ') {
  await wrapper.get('select[aria-label="办理动作"]').setValue(action)
  await wrapper.get('textarea').setValue('合成现场依据 ')
  if (action === 'MOVE' || action === 'RESTORE') { await wrapper.get('select[aria-label="位置登记方式"]').setValue('TEXT'); await wrapper.get('input[placeholder="本册实际位置，不继承书目位置"]').setValue(location) }
  await flushPromises()
}
beforeEach(() => { vi.resetAllMocks(); Object.assign(api.user, { isAdmin: true, isLibrarian: false }); latest = actionView(); api.context.mockImplementation(async id => actionContext(id)); api.history.mockImplementation(async id => ({ records: [writeOffEvent(id)], total: 1, page: 1, size: 20, intakeSource: null })); api.preview.mockImplementation(async data => { latest = 'schemaVersion' in data ? actionViewV2(data) : actionView(data); return latest }); api.confirm.mockImplementation(async () => latest.schemaVersion === 2 ? actionReceiptV2(latest, api.preview.mock.calls[api.preview.mock.calls.length - 1]![0].idempotencyKey) : actionReceipt(latest)); api.get.mockImplementation(async () => latest); api.byKey.mockImplementation(async () => latest) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })
it('loads only explicitly selected contexts and makes no preview until action/reason/location are explicit', async () => {
  const wrapper = await dialog(); expect(api.context).toHaveBeenCalledWith(7); expect(api.preview).not.toHaveBeenCalled()
  expect(wrapper.find('input[placeholder="本册实际位置，不继承书目位置"]').exists()).toBe(false)
  await wrapper.get('select[aria-label="办理动作"]').setValue('MOVE'); await wrapper.get('textarea').setValue('核实')
  await click(wrapper, '预览本次办理'); expect(api.preview).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('明确选择文本位置')
})
it('preserves raw text/version, previews without success and confirms only the frozen key/digest', async () => {
  const wrapper = await dialog(); await draft(wrapper); await click(wrapper, '预览本次办理')
  const request = api.preview.mock.calls[0]![0]
  expect(request.copies).toEqual([{ copyId: 7, expectedVersion: 3, targetLocation: { kind: 'TEXT', text: ' A区 😀 ' } }])
  expect(request.reason).toBe('合成现场依据 '); expect(wrapper.text()).toContain('此预览尚未办理'); expect(wrapper.find('img').exists()).toBe(false)
  expect(wrapper.find('fieldset').attributes('disabled')).toBeDefined(); expect(wrapper.emitted('success')).toBeUndefined()
  await click(wrapper, '确认本次办理'); expect(api.confirm).toHaveBeenCalledWith(21, request.idempotencyKey, 'a'.repeat(64))
  expect(wrapper.text()).toContain('本次办理成功'); expect(wrapper.emitted('success')).toHaveLength(1); expect(wrapper.find('img').exists()).toBe(false)
})
it('clears a position only through explicit UNASSIGNED and never sends text or inherited book location', async () => {
  const wrapper = await dialog(); await draft(wrapper); await wrapper.get('select[aria-label="位置登记方式"]').setValue('UNASSIGNED'); await click(wrapper, '预览本次办理')
  expect(api.preview.mock.calls[0]![0].copies[0].targetLocation).toEqual({ kind: 'UNASSIGNED' })
})
it('creates V2 registered MOVE with ID and all three versions, freezes target and requires a new preview after editing', async () => {
  const wrapper = await dialog();await draft(wrapper);await wrapper.get('select[aria-label="位置登记方式"]').setValue('REGISTERED');await click(wrapper,'合成选择登记地址');await click(wrapper,'预览本次办理')
  const request=api.preview.mock.calls[0]![0];expect(request.schemaVersion).toBe(2);expect(request.copies[0]).toEqual({copyId:7,expectedVersion:3,targetLocation:{kind:'REGISTERED',locationId:12,expectedAddressVersion:{location:3,storey:4,bookshelf:5}}})
  expect(wrapper.text()).toContain('登记地址 #12');expect(wrapper.findAll('button').find(button=>button.text()==='合成选择登记地址')!.attributes('disabled')).toBeDefined()
  await click(wrapper,'返回修改，重新核对');await wrapper.get('select[aria-label="位置登记方式"]').setValue('UNASSIGNED');await click(wrapper,'预览本次办理')
  expect(api.preview.mock.calls[1]![0].idempotencyKey).not.toBe(request.idempotencyKey);expect(api.preview.mock.calls[1]![0].copies[0].targetLocation).toEqual({kind:'UNASSIGNED'})
})
it('does not offer registered RESTORE and keeps V2 unknown confirmation on original schema/key/digest', async () => {
  api.context.mockResolvedValue(actionContext(7,'WRITTEN_OFF'));const wrapper=await dialog();await draft(wrapper,'RESTORE');expect(wrapper.findAll('option').some(option=>option.attributes('value')==='REGISTERED')).toBe(false)
  api.confirm.mockRejectedValueOnce(new Error('timeout'));await click(wrapper,'预览本次办理');const key=api.preview.mock.calls[0]![0].idempotencyKey;await click(wrapper,'确认本次办理');expect(wrapper.findAll('button').find(button=>button.text()==='关闭')!.attributes('disabled')).toBeDefined()
  await click(wrapper,'使用原请求重试确认');expect(api.confirm.mock.calls).toEqual([[21,key,'a'.repeat(64)],[21,key,'a'.repeat(64)]]);expect(api.preview).toHaveBeenCalledTimes(1)
})
it('builds a batch in selected order with each copy version and independently explicit location', async () => {
  api.context.mockImplementation(async id => { const context = actionContext(id, id === 8 ? 'DISABLED' : 'AVAILABLE'); context.copy.actionVersion = id === 8 ? 5 : 3; return context })
  const wrapper = await dialog([8, 7])
  expect(api.context.mock.calls).toEqual([[8], [7]])
  await wrapper.get('select[aria-label="办理动作"]').setValue('MOVE')
  await wrapper.get('textarea').setValue(' 分别核实两册实际位置 ')
  const locations = wrapper.findAll('select[aria-label="位置登记方式"]')
  await locations[0]!.setValue('TEXT')
  await wrapper.get('input[placeholder="本册实际位置，不继承书目位置"]').setValue(' 第二层 😀 ')
  await locations[1]!.setValue('UNASSIGNED')
  await click(wrapper, '预览本次办理')
  expect(api.preview.mock.calls[0]![0].copies).toEqual([
    { copyId: 8, expectedVersion: 5, targetLocation: { kind: 'TEXT', text: ' 第二层 😀 ' } },
    { copyId: 7, expectedVersion: 3, targetLocation: { kind: 'UNASSIGNED' } },
  ])
})
it('requires every selected copy to permit the same batch action', async () => {
  api.context.mockImplementation(async id => actionContext(id, id === 8 ? 'BORROWED' : 'AVAILABLE'))
  const wrapper = await dialog([7, 8])
  await wrapper.get('select[aria-label="办理动作"]').setValue('DISABLE')
  await wrapper.get('textarea').setValue('批量核实')
  const button = wrapper.findAll('button').find(button => button.text().trim() === '预览本次办理')!
  expect(button.attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('已借出，请先归还')
  await button.trigger('click'); expect(api.preview).not.toHaveBeenCalled()
})
it('restores only to DISABLED with its current source event, explicit location and zero available increment', async () => {
  api.context.mockResolvedValue(actionContext(7, 'WRITTEN_OFF')); const wrapper = await dialog(); await draft(wrapper, 'RESTORE'); await click(wrapper, '预览本次办理')
  expect(api.history).toHaveBeenCalledWith(7, 1, 20)
  for (const text of ['当前报损来源事件 31', '<img onerror=x> 原报损依据 ', '操作人 42', '2026-10-15T12:34:56Z']) expect(wrapper.text()).toContain(text)
  expect(wrapper.find('img').exists()).toBe(false)
  expect(api.preview.mock.calls[0]![0].copies).toEqual([{ copyId: 7, expectedVersion: 3, targetLocation: { kind: 'TEXT', text: ' A区 😀 ' }, restoreOfActionId: 31, restoreTo: 'DISABLED' }])
  await click(wrapper, '确认本次办理'); expect(wrapper.text()).toContain('已恢复为临时停用，仍不可借')
})
it('blocks restore preview when source history fails and allows explicit retry without losing the new location', async () => {
  api.context.mockResolvedValue(actionContext(7, 'WRITTEN_OFF')); api.history.mockRejectedValueOnce(new Error('offline'))
  const wrapper = await dialog(); await draft(wrapper, 'RESTORE')
  expect(wrapper.text()).toContain('报损来源读取失败，不能预览恢复')
  expect(wrapper.findAll('button').find(button => button.text().trim() === '预览本次办理')!.attributes('disabled')).toBeDefined()
  expect(api.preview).not.toHaveBeenCalled()
  await click(wrapper, '重试读取报损来源'); await click(wrapper, '预览本次办理')
  expect(api.history).toHaveBeenCalledTimes(2)
  expect(api.preview.mock.calls[0]![0].copies[0].targetLocation).toEqual({ kind: 'TEXT', text: ' A区 😀 ' })
})
it('rejects a source belonging to another copy instead of authorizing a restore from its event ID', async () => {
  api.context.mockResolvedValue(actionContext(7, 'WRITTEN_OFF')); api.history.mockResolvedValue({ records: [writeOffEvent(8)], total: 1, page: 1, size: 20, intakeSource: null })
  const wrapper = await dialog(); await draft(wrapper, 'RESTORE')
  expect(wrapper.text()).toContain('未查到与当前册一致的报损来源')
  expect(wrapper.findAll('button').find(button => button.text().trim() === '预览本次办理')!.attributes('disabled')).toBeDefined()
  expect(api.preview).not.toHaveBeenCalled()
})
it('allows librarian movement/disable/enable but does not offer admin-only write-off/restore', async () => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: true }); const wrapper = await dialog()
  expect(wrapper.findAll('option').some(option => option.attributes('value') === 'WRITE_OFF')).toBe(false)
  expect(wrapper.findAll('option').some(option => option.attributes('value') === 'RESTORE')).toBe(false)
  await draft(wrapper, 'DISABLE'); await click(wrapper, '预览本次办理'); expect(api.preview.mock.calls[0]![0].copies[0]).toEqual({ copyId: 7, expectedVersion: 3 })
})
it.each(['UNKNOWN', 'BORROWED'] as const)('does not infer permission for %s even if its raw status resembles a controlled state', async state => {
  api.context.mockResolvedValue(actionContext(7, state)); const wrapper = await dialog(); await draft(wrapper, 'DISABLE'); await click(wrapper, '预览本次办理')
  expect(api.preview).not.toHaveBeenCalled(); expect(wrapper.text()).toContain(state === 'UNKNOWN' ? '原状态来源待核实' : '已借出，请先归还')
})
it('rejects oversized/duplicate selection without fetching contexts and fetches nothing as reader', async () => {
  for (const ids of [[7, 7], Array.from({ length: 101 }, (_, i) => i + 1)]) await dialog(ids)
  expect(api.context).not.toHaveBeenCalled(); Object.assign(api.user, { isAdmin: false, isLibrarian: false }); await dialog()
  for (const method of [api.context, api.history, api.preview, api.confirm, api.get, api.byKey]) expect(method).not.toHaveBeenCalled()
})
it('refreshes versions after a definite stale rejection, preserves input and creates a new key only after deliberate recheck', async () => {
  api.confirm.mockRejectedValueOnce({ response: { status: 409, data: { message: '已变化', data: { reasonCode: 'PREVIEW_STALE', copyId: 7 } } } })
  const wrapper = await dialog(); await draft(wrapper); await click(wrapper, '预览本次办理'); const key = api.preview.mock.calls[0]![0].idempotencyKey
  await click(wrapper, '确认本次办理'); api.context.mockResolvedValue({ ...actionContext(), copy: { ...actionContext().copy, actionVersion: 5 } })
  await click(wrapper, '返回修改，重新核对'); await click(wrapper, '预览本次办理')
  expect(api.preview.mock.calls[1]![0].idempotencyKey).not.toBe(key); expect(api.preview.mock.calls[1]![0].copies[0].expectedVersion).toBe(5)
  expect(api.preview.mock.calls[1]![0].copies[0].targetLocation.text).toBe(' A区 😀 ')
})
it('prevents local expiry confirmation and creates no business mutation when a preview is closed', async () => {
  api.preview.mockImplementation(async data => ({ ...actionViewV2(data), expiresAt: new Date(Date.now() - 1000).toISOString() }))
  const wrapper = await dialog(); await draft(wrapper); await click(wrapper, '预览本次办理')
  expect(wrapper.findAll('button').find(button => button.text().trim() === '确认本次办理')!.attributes('disabled')).toBeDefined()
  await click(wrapper, '关闭'); expect(api.confirm).not.toHaveBeenCalled()
})
it('recovers a lost preview by original key', async () => {
  api.preview.mockRejectedValueOnce(new Error('timeout')); const session = useCopyActionSession(); await session.preview(actionRequest())
  expect(api.byKey).toHaveBeenCalledWith(actionRequest().idempotencyKey); expect(session.view.value?.requestId).toBe(21)
})
it('recovers a committed timeout response without confirming twice', async () => {
  api.confirm.mockRejectedValueOnce(new Error('timeout')); api.get.mockResolvedValue({ ...actionView(), state: 'COMMITTED', receipt: actionReceipt() })
  const session = useCopyActionSession(); await session.preview(actionRequest()); await session.confirm(); await session.confirm()
  expect(session.receipt.value?.requestId).toBe(21); expect(api.confirm).toHaveBeenCalledTimes(1)
})
it('a PREVIEWED lookup after unknown confirmation keeps close/edit/key frozen and retries exact original digest', async () => {
  api.confirm.mockRejectedValueOnce(new Error('timeout')); const wrapper = await dialog(); await draft(wrapper); await click(wrapper, '预览本次办理'); const key = api.preview.mock.calls[0]![0].idempotencyKey
  await click(wrapper, '确认本次办理'); expect(wrapper.findAll('button').find(button => button.text().trim() === '关闭')!.attributes('disabled')).toBeDefined()
  expect(wrapper.findAll('button').some(button => button.text() === '返回修改，重新核对')).toBe(false)
  await click(wrapper, '使用原请求重试确认'); expect(api.confirm.mock.calls).toEqual([[21, key, 'a'.repeat(64)], [21, key, 'a'.repeat(64)]])
})
it('failed lookup after unknown confirmation prevents reset and a pending double click makes only one request', async () => {
  api.confirm.mockRejectedValueOnce(new Error('timeout')); api.get.mockRejectedValue(new Error('offline'))
  const session = useCopyActionSession(); await session.preview(actionRequest()); await session.confirm(); expect(session.reset()).toBe(false)
  let finish!: (value: CopyActionReceipt) => void; api.confirm.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const first = session.confirm(); await session.confirm(); expect(api.confirm).toHaveBeenCalledTimes(2); finish(actionReceipt()); await first
})
it('does not replace the frozen V1 identity with a V2 lookup result while confirmation is unknown',async()=>{
  api.confirm.mockRejectedValueOnce(new Error('timeout'));api.get.mockResolvedValue(actionViewV2({...actionRequest(),schemaVersion:2}));const session=useCopyActionSession();await session.preview(actionRequest());await session.confirm()
  expect(session.view.value?.schemaVersion).toBe(1);expect(session.receipt.value).toBeNull();expect(session.reset()).toBe(false);expect(api.preview.mock.calls[0]![0]).toEqual(actionRequest())
})
