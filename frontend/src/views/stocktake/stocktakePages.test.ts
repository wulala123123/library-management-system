import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ list: vi.fn(), session: vi.fn(), snapshot: vi.fn(), lookup: vi.fn(), write: vi.fn(), observations: vi.fn(), changes: vi.fn(), differences: vi.fn(), histories: vi.fn(), candidates: vi.fn(), recover: vi.fn(), export: vi.fn(), replace: vi.fn(), user: { isAdmin: true, isLibrarian: false, userInfo: { id: 2 } }, route: { query: {} as Record<string, string> } }))
vi.mock('@/api/stocktake', () => ({ getStocktakes: api.list, getStocktake: api.session, getStocktakeSnapshot: api.snapshot, getStocktakeOperation: api.lookup, writeStocktakeOperation: api.write, getStocktakeObservations: api.observations, getStocktakeChanges: api.changes, getStocktakeDifferences: api.differences, getStocktakeResolutions: api.histories, getStocktakeCandidates: api.candidates, recoverStocktakeResolution: api.recover, exportStocktake: api.export }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
vi.mock('vue-router', () => ({ useRoute: () => api.route, useRouter: () => ({ replace: api.replace }) }))
import Stocktakes from './index.vue'
import Observations from './StocktakeObservations.vue'
import ReviewPanel from './StocktakeReview.vue'
import Differences from './StocktakeDifferences.vue'
import Resolutions from './StocktakeResolutions.vue'
import { fixtureCandidate, fixtureDifference, fixtureReceipt, fixtureResolution, fixtureReview, fixtureSession } from './stocktakeFixtures'
const slot = defineComponent({ setup(_, { slots }) { return () => h('div', slots.default?.()) } })
const input = defineComponent({ props: ['modelValue', 'placeholder', 'disabled', 'type'], emits: ['update:modelValue'], setup(props, { emit }) { return () => h(props.type === 'textarea' ? 'textarea' : 'input', { value: props.modelValue, placeholder: props.placeholder, disabled: props.disabled, onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value) }) } })
const button = defineComponent({ props: ['disabled'], setup(props, { slots, attrs }) { return () => h('button', { ...attrs, disabled: props.disabled }, slots.default?.()) } })
const rowProvider = defineComponent({ props: ['row'], setup(props, { slots }) { provide('stocktake-row', computed(() => props.row)); return () => h('div', slots.default?.()) } })
const table = defineComponent({ props: ['data'], setup(props, { slots }) { return () => h('div', (props.data || []).map((row: object) => h(rowProvider, { row }, slots.default))) } })
const column = defineComponent({ props: ['prop'], setup(props, { slots }) { const row = inject<ComputedRef<Record<string, unknown>>>('stocktake-row')!; return () => h('div', slots.default?.({ row: row.value }) || String(row.value?.[props.prop] ?? '')) } })
const alert = defineComponent({ props: ['title'], setup(props) { return () => h('p', props.title) } })
const stubs = { ElForm: slot, ElFormItem: slot, ElInput: input, ElButton: button, ElTable: table, ElTableColumn: column, ElPagination: true, ElAlert: alert, ElEmpty: true, ElTabs: slot, ElTabPane: true, ElRadioGroup: true, ElRadioButton: true, StocktakePlacementInput: true, CopyPlacement: true, LocationAddressPicker: true }
const wrappers: ReturnType<typeof mount>[] = []
function mountPage(component: any, props: object = {}, extra: object = {}) { const wrapper = mount(component, { props, global: { stubs: { ...stubs, ...extra }, directives: { loading: () => {} } } }); wrappers.push(wrapper); return wrapper }
async function click(wrapper: ReturnType<typeof mount>, label: string) { await wrapper.findAll('button').find(value => value.text() === label)!.trigger('click'); await flushPromises() }
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear(); api.route.query = {}; Object.assign(api.user, { isAdmin: true, isLibrarian: false }); api.user.userInfo.id = 2; api.list.mockResolvedValue({ records: [], total: 0 }); api.session.mockResolvedValue(fixtureSession()); api.snapshot.mockResolvedValue(fixtureReview()); for (const fn of [api.observations, api.changes, api.differences, api.histories, api.candidates]) fn.mockResolvedValue({ records: [], total: 0 }); api.recover.mockResolvedValue(fixtureResolution()) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it('makes zero management requests for READER and exposes management only to staff', async () => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: false }); mountPage(Stocktakes); await flushPromises()
  for (const fn of [api.list, api.session, api.snapshot, api.lookup, api.observations, api.changes, api.differences, api.histories, api.candidates]) expect(fn).not.toHaveBeenCalled()
})
it('allows own librarian draft entry but no admin seal, and denies other-owner draft editing', async () => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: true }); api.route.query = { sessionId: '7' }
  const wrapper = mountPage(Stocktakes, {}, { StocktakeReview: true, StocktakeObservations: true, StocktakeDifferences: true }); await flushPromises()
  expect(wrapper.findComponent({ name: 'StocktakeReview' }).props('editable')).toBe(true); expect(wrapper.findComponent({ name: 'StocktakeReview' }).props('admin')).toBe(false)
  wrapper.unmount(); api.session.mockResolvedValue({ ...fixtureSession(), ownerId: 99 }); const other = mountPage(Stocktakes, {}, { StocktakeReview: true, StocktakeObservations: true, StocktakeDifferences: true }); await flushPromises()
  expect(other.findComponent({ name: 'StocktakeObservations' }).props('editable')).toBe(false)
})
it('creates only the explicitly selected finite IDs with campaignKey null and a frozen original key', async () => {
  api.write.mockImplementation((_path, body) => Promise.resolve(fixtureReceipt(body.operationKey)))
  const wrapper = mountPage(Stocktakes, {}, { StocktakeScopePicker: true, StocktakeReview: true, StocktakeObservations: true, StocktakeDifferences: true }); await flushPromises(); await click(wrapper, '新建有限范围盘点')
  wrapper.findComponent({ name: 'StocktakeScopePicker' }).vm.$emit('update:modelValue', { kind: 'COPY_SET', copyIds: [11, 12] }); await wrapper.get('input[placeholder="范围选择和盘点依据"]').setValue('明确部分抽查'); await click(wrapper, '固定范围并建立基线')
  expect(api.write).toHaveBeenCalledOnce(); const [path, body] = api.write.mock.calls[0]!
  expect(path).toBe('/stocktakes'); expect(Object.keys(body).sort()).toEqual(['campaignKey','operationKey','reason','scope']); expect(body.campaignKey).toBeNull(); expect(body.scope).toEqual({ kind: 'COPY_SET', copyIds: [11, 12] }); expect(body.operationKey).toMatch(/^[0-9a-f-]{36}$/u)
  expect(wrapper.text()).toContain('已取得原成功回执')
})
it('retains raw duplicate/unknown input as independent scan events and nullable client time', async () => {
  const wrapper = mountPage(Observations, { sessionId: 7, editable: true, locked: false, refresh: 0 }); await flushPromises()
  const field = wrapper.get('input[placeholder="扫描后按 Enter 加入待保存批次，保留原文"]')
  for (const raw of [' 00012 ', ' 00012 ', '=未知']) { await field.setValue(raw); await click(wrapper, '加入待保存扫描') }
  await wrapper.get('input[placeholder="本批观察依据（1–500 字）"]').setValue('现场扫描原样保存'); await click(wrapper, '保存 3 条观察')
  const event = wrapper.emitted('write')![0]!, body = event[2] as { events: { rawBarcode: string; clientEventKey: string; clientObservedAt: null; observedPlacement: object }[] }
  expect(body.events.map(value => value.rawBarcode)).toEqual([' 00012 ', ' 00012 ', '=未知']); expect(new Set(body.events.map(value => value.clientEventKey)).size).toBe(3)
  for (const item of body.events) expect(item).toMatchObject({ clientObservedAt: null, observedPlacement: { kind: 'UNASSIGNED' } })
  expect(wrapper.text()).toContain('同一身份只计一册')
})
it('appends VOID rather than editing/deleting an existing observation and uses server pagination', async () => {
  api.observations.mockResolvedValue({ records: [{ observationId: 40, kind: 'SCAN', active: true, rawBarcode: '00012', resolvedCopyIdAtScan: 11, observedPlacement: null, signature: {}, reason: '扫描', actorId: 2, acceptedAt: '2026-10-16T04:00:00Z', referencedObservationId: null }], total: 42 })
  const wrapper = mountPage(Observations, { sessionId: 7, editable: true, locked: false, refresh: 0 }); await flushPromises(); await click(wrapper, '追加误扫更正'); await wrapper.get('input[placeholder="更正依据"]').setValue('误扫了相邻标签'); await click(wrapper, '保存追加更正')
  expect(wrapper.emitted('write')![0]!.slice(0, 3)).toEqual(['追加误扫更正', 'corrections', { reason: '误扫了相邻标签', observationId: 40, kind: 'VOID', replacement: null }])
  const pagination = wrapper.findComponent({ name: 'ElPagination' }); pagination.vm.$emit('update:currentPage', 2); pagination.vm.$emit('current-change', 2); await flushPromises(); expect(api.observations).toHaveBeenLastCalledWith(7, expect.objectContaining({ page: 2, size: 20 }))
})
it('does not turn refresh into verification, requires one selected token with human evidence and preserves baseline', async () => {
  const review = fixtureReview(), change = { entityKey: 'LOAN:9', category: 'LOAN', copyId: 11, changeToken: 'opaque-token', expectedSignatureDigest: 'current-signature', previousSignatureDigest: 'original-signature', requiresRecheck: true }; api.changes.mockResolvedValue({ records: [change], total: 1 })
  const wrapper = mountPage(ReviewPanel, { session: fixtureSession(), review, editable: true, admin: false, locked: false, refresh: 0 }); await flushPromises(); await wrapper.setProps({ refresh: 1 }); await flushPromises(); expect(wrapper.emitted('write')).toBeUndefined()
  await click(wrapper, '逐项重新核对'); await wrapper.get('textarea[placeholder="本项实际核对依据；物理册说明须写明检查过程"]').setValue('已核对本册在借记录与未归还依据'); await wrapper.get('input[placeholder="本次覆盖、变化检测、准备或取消依据（1–500 字）"]').setValue('逐项检查'); await click(wrapper, '保存本项重核对')
  const body = wrapper.emitted('write')![0]![2] as any; expect(body.items).toEqual([{ entityKey: change.entityKey, changeToken: 'opaque-token', expectedSignatureDigest: 'current-signature', decision: 'CONFIRM_EXPLANATION', evidence: '已核对本册在借记录与未归还依据' }]); expect(review.snapshotId).toBe(20)
})
it('only saves explicit coverage and clearly distinguishes partial versus complete finite scope', async () => {
  const wrapper = mountPage(ReviewPanel, { session: fixtureSession(), review: fixtureReview(), editable: true, admin: true, locked: false, refresh: 0 }); await flushPromises(); expect(wrapper.emitted('write')).toBeUndefined()
  await wrapper.get('input[placeholder="本次覆盖、变化检测、准备或取消依据（1–500 字）"]').setValue('本册仅部分检查'); await wrapper.findAll('select')[0]!.setValue('PARTIAL'); await click(wrapper, '保存本项声明')
  expect(wrapper.emitted('write')![0]![2]).toEqual({ reason: '本册仅部分检查', items: [{ scopeItemId: 11, status: 'PARTIAL' }] })
  const select = wrapper.findAll('select').find(value => value.text().includes('所选有限范围检查完成'))!; await select.setValue('COMPLETE_SCOPE'); await click(wrapper, '准备封存预览'); expect(wrapper.emitted('write')![1]![2]).toMatchObject({ completionKind: 'COMPLETE_SCOPE' })
})
it('keeps administrator seal disabled when pending changes or coverage are incomplete, with no librarian button', async () => {
  const review = fixtureReview(); Object.assign(review, { purpose: 'PREPARE', snapshotKind: 'REVIEW', completionKind: 'COMPLETE_SCOPE', expiresAt: new Date(Date.now() + 60000).toISOString() })
  const wrapper = mountPage(ReviewPanel, { session: fixtureSession(), review, editable: true, admin: true, locked: false, refresh: 0 }); await flushPromises(); expect(wrapper.findAll('button').find(value => value.text() === '管理员最终确认并封存')!.attributes('disabled')).toBeDefined()
  await wrapper.setProps({ admin: false }); expect(wrapper.text()).not.toContain('管理员最终确认并封存'); expect(wrapper.text()).toContain('需管理员最终确认')
})
it('selects only a successful server candidate, creates AWAITING_LINK intent and restores only the link', async () => {
  api.candidates.mockResolvedValue({ records: [fixtureCandidate()], total: 1 })
  const wrapper = mountPage(Resolutions, { sessionId: 7, admin: true, locked: false, refresh: 0, selectedDifference: fixtureDifference() }); await flushPromises(); await wrapper.findAll('select')[0]!.setValue('BUSINESS_REQUIRED'); await wrapper.findAll('select')[1]!.setValue('INTAKE'); await click(wrapper, '刷新已成功的安全候选'); await wrapper.get('input[type="radio"]').setValue(); await wrapper.get('textarea[placeholder="管理员核实依据；历史成功来源也须写明为何关联"]').setValue('原入库已成功，与未知原码精确相符'); await click(wrapper, '保存待关联意图')
  expect(wrapper.emitted('write')![0]!.slice(0, 3)).toEqual(['管理员核实差异', '/stocktake-resolutions', { sessionId: 7, differenceIds: [31], decision: 'BUSINESS_REQUIRED', workflow: 'INTAKE', candidateId: 51, reason: '原入库已成功，与未知原码精确相符' }])
  await wrapper.setProps({ latestResolution: fixtureResolution() }); expect(wrapper.text()).toContain('原业务已成功，当前仅待关联'); expect(wrapper.find('a[href="/books"]').exists()).toBe(false)
  await wrapper.get('input[placeholder="关联或撤销关联依据"]').setValue('只恢复原关联'); await click(wrapper, '确认关联此成功来源'); expect(wrapper.emitted('write')![1]!.slice(0, 3)).toEqual(['关联原业务成功来源', '/stocktake-resolutions/60/link', { expectedVersion: 1, reason: '只恢复原关联' }])
  expect(api.write).not.toHaveBeenCalled()
})
it('shows safe sealed history to another librarian without any ADMIN candidate/recover calls', async () => {
  api.histories.mockResolvedValue({ records: [fixtureResolution()], total: 1 }); const wrapper = mountPage(Resolutions, { sessionId: 7, admin: false, locked: false, refresh: 0, selectedDifference: null }); await flushPromises(); expect(wrapper.text()).toContain('待关联'); expect(api.candidates).not.toHaveBeenCalled(); expect(api.recover).not.toHaveBeenCalled(); expect(wrapper.text()).not.toContain('查看 / 恢复关联')
})
it('exports server-provided bytes from the selected immutable snapshot rather than the displayed page', async () => {
  const blob = new Blob(['full-server-csv']); api.export.mockResolvedValue(blob); const create = vi.fn().mockReturnValue('blob:stocktake'), revoke = vi.fn(); vi.stubGlobal('URL', { createObjectURL: create, revokeObjectURL: revoke }); vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  const wrapper = mountPage(Differences, { sessionId: 7, review: fixtureReview(), admin: false, finalized: false, locked: false, refresh: 0 }); await flushPromises(); await click(wrapper, '导出此快照 CSV'); expect(api.export).toHaveBeenCalledWith(7, 20, 'csv'); expect(create).toHaveBeenCalledWith(blob); expect(revoke).toHaveBeenCalledWith('blob:stocktake'); vi.unstubAllGlobals()
})
