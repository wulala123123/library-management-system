import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import type { IntakePreviewInput, IntakeReceipt, IntakeView } from '@/api/inventoryIntake'
const api = vi.hoisted(() => ({ preview: vi.fn(), confirm: vi.fn(), get: vi.fn(), byKey: vi.fn(), purchase: vi.fn(), user: { isAdmin: true, isLibrarian: false } }))
vi.mock('@/api/inventoryIntake', () => ({ previewIntake: api.preview, confirmIntake: api.confirm, getIntake: api.get, getIntakeByKey: api.byKey }))
vi.mock('@/api/purchase', () => ({ getPurchaseOrderById: api.purchase }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import IntakeDialog from './IntakeDialog.vue'
import { useIntakeSession } from './intakeSession'
import { copiesInput, metadataChanges, newCopy, validateDraft, type IntakeDraftLine } from './intakeDraft'

const key = '00000000-0000-4000-8000-000000000001'
const generated = 'LMS-C-00000000000040008000000000000001'
const input = (): IntakePreviewInput => ({ idempotencyKey: key, source: { type: 'MANUAL', purchaseOrderId: null }, reason: '合成实收', lines: [{ lineKey: key, target: { bookId: 7, newBook: null, purchaseItemId: null }, copies: [{ input: 'SCANNED', barcode: ' 0001 ', location: null }] }] })
function makeView(request = input()): IntakeView {
  return { intakeId: 21, state: 'PREVIEWED', previewDigest: 'a'.repeat(64), expiresAt: new Date(Date.now() + 900000).toISOString(), source: request.source,
    lines: request.lines.map(line => ({ lineKey: line.lineKey, bookId: line.target.bookId, newBook: line.target.newBook, purchaseItemId: line.target.purchaseItemId,
      copies: line.copies.map((copy, index) => ({ ordinal: index + 1, input: copy.input === 'GENERATE' ? 'GENERATED' : 'SCANNED', barcode: copy.barcode || generated, location: copy.location })) })),
    books: [{ bookId: 7, isbn: '0001', title: '合成书目', metadataDigest: 'b'.repeat(64), stockTotal: 0, stockAvailable: 0, registeredCopies: 1, registeredAvailableCopies: 0, totalDifference: -1, availableDifference: 0, newBook: false }], purchaseProgress: [], warnings: ['HISTORICAL_STOCK_DIFFERENCE'], receipt: null }
}
function makeReceipt(view = makeView()): IntakeReceipt {
  const copies = view.lines.flatMap(line => line.copies.map(copy => ({ ...copy, copyId: 100 + copy.ordinal, bookId: line.bookId || 7, lineKey: line.lineKey, status: 'AVAILABLE' as const, stockFlowId: 55, purchaseItemId: line.purchaseItemId })))
  return { intakeId: view.intakeId, source: view.source, operatorId: 1, confirmedAt: new Date().toISOString(), copies,
    books: [{ bookId: 7, stockTotalBefore: 0, stockAvailableBefore: 0, stockTotalAfter: copies.length, stockAvailableAfter: copies.length, addedQuantity: copies.length }], flows: [], purchaseProgress: [] }
}
let latest = makeView()
const wrappers: ReturnType<typeof mount>[] = []
const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup(props, { slots, emit }) { return () => h('select', { value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, slots.default?.()) } })
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
async function dialog(props: Record<string, unknown> = {}) {
  const wrapper = mount(IntakeDialog, { props: { visible: true, bookId: 7, bookTitle: '合成书目', ...props }, attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, ElSelect: select, ElOption: option } } })
  wrappers.push(wrapper); await flushPromises(); return wrapper
}
async function click(wrapper: ReturnType<typeof mount>, label: string) {
  await wrapper.findAll('button').find(button => button.text().trim() === label)!.trigger('click'); await flushPromises()
}
async function fill(wrapper: ReturnType<typeof mount>, barcode = ' 0001 ') {
  await wrapper.get('textarea[placeholder="本次入库来源或实收依据（必填）"]').setValue('合成实收')
  await wrapper.get('input[placeholder="扫描或输入已有条码，保留原文"]').setValue(barcode)
}
beforeEach(() => {
  vi.clearAllMocks(); Object.assign(api.user, { isAdmin: true, isLibrarian: false }); latest = makeView()
  api.preview.mockImplementation(async data => { latest = makeView(data); return latest })
  api.confirm.mockImplementation(async () => makeReceipt(latest))
  api.get.mockImplementation(async () => latest); api.byKey.mockImplementation(async () => latest)
  api.purchase.mockResolvedValue({ id: 15, status: 'APPROVED', items: [{ id: 31, bookTitle: '部分实收项目', quantity: 5, instockQuantity: 2 }, { id: 32, bookTitle: '历史已收齐项目', quantity: 8, instockQuantity: 8 }] })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = ''; vi.useRealTimers() })

it('starts manual intake with one scanned copy and makes no business request before preview', async () => {
  const wrapper = await dialog()
  expect(wrapper.findAll('input[placeholder="扫描或输入已有条码，保留原文"]')).toHaveLength(1)
  expect(wrapper.text()).toContain('本次合计 1 册'); expect(api.preview).not.toHaveBeenCalled(); expect(api.confirm).not.toHaveBeenCalled()
  await click(wrapper, '预览本次入库'); expect(api.preview).not.toHaveBeenCalled()
})
it('preserves leading/trailing spaces and only sends the fixed preview key/digest to confirmation', async () => {
  const wrapper = await dialog(); await fill(wrapper); await click(wrapper, '预览本次入库')
  const request = api.preview.mock.calls[0]![0] as IntakePreviewInput
  expect(request.lines[0]!.copies[0]!.barcode).toBe(' 0001 '); expect(wrapper.text()).toContain('" 0001 "')
  expect(wrapper.text()).toContain('预览尚未入库'); expect(wrapper.text()).toContain('-1 / 0')
  expect(wrapper.findAll('svg[aria-label^="Code128"]')).toHaveLength(0)
  await click(wrapper, '确认本次入库')
  expect(api.confirm).toHaveBeenCalledWith(21, request.idempotencyKey, 'a'.repeat(64))
  expect(wrapper.emitted('success')).toHaveLength(1)
})
it('procurement starts at zero and sends only explicitly received copies from pending items', async () => {
  const wrapper = await dialog({ bookId: null, purchaseOrderId: 15 })
  expect(api.purchase).toHaveBeenCalledWith(15); expect(wrapper.text()).toContain('仍待收 3 册 · 本次 0 册')
  expect(wrapper.text()).toContain('本次合计 0 册')
  await click(wrapper, '+ 增加一册'); await fill(wrapper, '00003'); await click(wrapper, '预览本次入库')
  const request = api.preview.mock.calls[0]![0] as IntakePreviewInput
  expect(request.source).toEqual({ type: 'PURCHASE', purchaseOrderId: 15 })
  expect(request.lines).toHaveLength(1); expect(request.lines[0]!.target).toEqual({ bookId: null, newBook: null, purchaseItemId: 31 })
  expect(request.lines[0]!.copies).toHaveLength(1)
})
it('bulk generation changes only explicitly blank copies and preserves an already scanned barcode', async () => {
  const wrapper = await dialog(); await fill(wrapper, '0000 '); await click(wrapper, '+ 增加一册')
  await click(wrapper, '空白条码改为生成新码'); await click(wrapper, '预览本次入库')
  expect(api.preview.mock.calls[0]![0].lines[0].copies).toEqual([{ input: 'SCANNED', barcode: '0000 ', location: null }, { input: 'GENERATE', barcode: null, location: null }])
})
it('allows 64 supplementary-plane characters without an HTML UTF-16 maxlength truncating the barcode', async () => {
  const wrapper = await dialog(); const barcode = '😀'.repeat(64)
  const field = wrapper.get('input[placeholder="扫描或输入已有条码，保留原文"]')
  expect(field.attributes('maxlength')).toBeUndefined()
  await fill(wrapper, barcode); await click(wrapper, '预览本次入库')
  expect(api.preview.mock.calls[0]![0].lines[0].copies[0].barcode).toBe(barcode)
})
it('prints Code128 only for generated codes in the successful receipt and displays metadata as text', async () => {
  const wrapper = await dialog({ bookTitle: '<img onerror=alert(1)>' }); await fill(wrapper)
  await click(wrapper, '+ 增加一册')
  await wrapper.findAll('select')[1]!.setValue('GENERATE'); await click(wrapper, '预览本次入库')
  expect(wrapper.findAll('svg[aria-label^="Code128"]')).toHaveLength(0)
  expect(wrapper.findAll('button').some(button => button.text().startsWith('打印本次'))).toBe(false)
  await click(wrapper, '确认本次入库')
  const labels = wrapper.findAll('svg[aria-label^="Code128"]')
  expect(labels).toHaveLength(1); expect(labels[0]!.attributes('aria-label')).toContain(generated)
  expect(labels[0]!.findAll('rect').length).toBeGreaterThan(30)
  expect(wrapper.find('.barcode-text').text()).toBe(generated)
  expect(wrapper.find('.label-title').text()).toBe('<img onerror=alert(1)>')
  expect(wrapper.find('img').exists()).toBe(false)
})
it('a stale preview retains the raw inputs and requires a deliberate new preview with a new key', async () => {
  api.confirm.mockRejectedValue({ response: { status: 409, data: { message: '预览已变化，请重新核对', data: { reasonCode: 'PREVIEW_STALE' } } } })
  const wrapper = await dialog(); await fill(wrapper); await click(wrapper, '预览本次入库')
  const originalKey = api.preview.mock.calls[0]![0].idempotencyKey
  await click(wrapper, '确认本次入库'); expect(wrapper.emitted('success')).toBeUndefined()
  await click(wrapper, '返回修改，重新预览')
  expect((wrapper.get('input[placeholder="扫描或输入已有条码，保留原文"]').element as HTMLInputElement).value).toBe(' 0001 ')
  await click(wrapper, '预览本次入库')
  expect(api.preview.mock.calls[1]![0].idempotencyKey).not.toBe(originalKey)
})
it('an expired preview cannot be confirmed locally', async () => {
  api.preview.mockImplementation(async data => ({ ...makeView(data), expiresAt: new Date(Date.now() - 1000).toISOString() }))
  const wrapper = await dialog(); await fill(wrapper); await click(wrapper, '预览本次入库')
  const button = wrapper.findAll('button').find(button => button.text().trim() === '确认本次入库')!
  expect(button.attributes('disabled')).toBeDefined(); await button.trigger('click'); expect(api.confirm).not.toHaveBeenCalled()
})
it('canceling a preview creates no receipt or inventory confirmation', async () => {
  const wrapper = await dialog(); await fill(wrapper); await click(wrapper, '预览本次入库'); await click(wrapper, '关闭')
  expect(api.confirm).not.toHaveBeenCalled(); expect(wrapper.emitted('update:visible')).toEqual([[false]])
})
it('a reader cannot fetch procurement or issue preview/confirmation/receipt requests', async () => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: false }); await dialog({ purchaseOrderId: 15 })
  for (const action of [api.purchase, api.preview, api.confirm, api.get, api.byKey]) expect(action).not.toHaveBeenCalled()
})

it('after a lost preview response, looks up the original key before allowing any new request', async () => {
  api.preview.mockRejectedValueOnce(new Error('timeout'))
  const session = useIntakeSession(); await session.preview(input())
  expect(api.byKey).toHaveBeenCalledWith(key); expect(session.view.value?.intakeId).toBe(21)
  expect(session.request.value?.idempotencyKey).toBe(key); expect(api.preview).toHaveBeenCalledTimes(1)
})
it('after a lost committed response, recovers a receipt without a second confirmation', async () => {
  api.confirm.mockRejectedValueOnce(new Error('timeout'))
  api.get.mockImplementation(async () => ({ ...latest, state: 'COMMITTED', receipt: makeReceipt(latest) }))
  const session = useIntakeSession(); await session.preview(input()); await session.confirm(); await session.confirm()
  expect(api.get).toHaveBeenCalledWith(21); expect(session.receipt.value?.intakeId).toBe(21); expect(api.confirm).toHaveBeenCalledTimes(1)
})
it('a pending GET during an unknown confirmation cannot unlock a new key; retry keeps the exact original key/digest', async () => {
  api.confirm.mockRejectedValueOnce(new Error('timeout'))
  const session = useIntakeSession(); await session.preview(input()); await session.confirm()
  expect(session.uncertain.value).toBe(true); expect(session.reset()).toBe(false)
  await session.confirm()
  expect(api.confirm).toHaveBeenNthCalledWith(1, 21, key, 'a'.repeat(64))
  expect(api.confirm).toHaveBeenNthCalledWith(2, 21, key, 'a'.repeat(64))
  expect(session.receipt.value).not.toBeNull()
})
it('an unknown confirmation and failed lookup keep inputs frozen and close disabled', async () => {
  api.confirm.mockRejectedValue(new Error('timeout')); api.get.mockRejectedValue(new Error('offline'))
  const wrapper = await dialog(); await fill(wrapper); await click(wrapper, '预览本次入库'); await click(wrapper, '确认本次入库')
  expect((wrapper.get('fieldset').element as HTMLFieldSetElement).disabled).toBe(true)
  expect(wrapper.findAll('button').find(button => button.text().trim() === '关闭')!.attributes('disabled')).toBeDefined()
  expect(wrapper.findAll('button').some(button => button.text().trim() === '返回修改，重新预览')).toBe(false)
  expect(api.get).toHaveBeenCalledWith(21); expect(api.preview).toHaveBeenCalledTimes(1)
})
it('rapid double confirmation sends only one mutation while the first response is pending', async () => {
  let finish!: (value: IntakeReceipt) => void
  api.confirm.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const session = useIntakeSession(); await session.preview(input())
  const first = session.confirm(); await session.confirm(); expect(api.confirm).toHaveBeenCalledTimes(1)
  finish(makeReceipt()); await first
})
it('rejects oversized batches and invalid or duplicate exact barcodes without normalizing distinct values', () => {
  const line = (barcodes: string[]): IntakeDraftLine[] => [{ lineKey: key, label: '合成', pending: 1000, target: input().lines[0]!.target, copies: barcodes.map(barcode => ({ ...newCopy(), barcode })) }]
  expect(validateDraft(line(['0001', '00001', 'abc', 'ABC', 'abc ']), '合成')).toBeNull()
  expect(validateDraft(line(['😀'.repeat(64)]), '合成')).toBeNull()
  for (const barcodes of [['abc', 'abc'], ['\t'], ['LMS-C-owned'], ['a'.repeat(65)], ['😀'.repeat(65)], ['\ud800']]) expect(validateDraft(line(barcodes), '合成')).not.toBeNull()
  expect(validateDraft(line(Array.from({ length: 1001 }, (_, i) => String(i))), '合成')).not.toBeNull()
  expect(copiesInput([{ input: 'GENERATE', barcode: '不发送旧输入', location: '' }])).toEqual([{ input: 'GENERATE', barcode: null, location: null }])
})
it('metadata diffs preserve untouched NULL/zero and include only explicit clears or changes', () => {
  const before = { isbn: '0001', title: '合成', categoryId: 1, pages: 0, price: 0, publishDate: null, location: null }
  expect(metadataChanges(before, { ...before, title: '新书名' })).toEqual({ title: '新书名' })
  expect(metadataChanges({ ...before, location: 'A' }, { ...before, location: null })).toEqual({ location: null })
})
