import { ref, shallowRef } from 'vue'
import { cancelCatalogSet, confirmCatalogSet, getSetRequestByKey, previewCatalogSet, type CancelReason, type SetPreviewInput, type SetReceipt, type SetRequestView } from '@/api/catalogSet'
import { assertSetReceipt, assertSetRequest, frozenCopy, normalizeSetInput, setCanonical, setProblem, validSetInput } from './setProtocol'

type Phase = 'previewing' | 'previewRejected' | 'editing' | 'ready' | 'confirming' | 'cancelling' | 'committed' | 'cancelled'
interface Saved { storageVersion: 1; ownerId: number; input: SetPreviewInput; view: SetRequestView | null; receipt: SetReceipt | null; phase: Phase; cancelReason: CancelReason | null; previewAttempts: number; previewInFlight: number; everUncertain: boolean; rejection: { schemaVersion: 1; httpStatus: number; reasonCode: string; requestId: null } | null }
const queues = new Map<string, Promise<unknown>>()
/** Short origin-wide transition lock. The durable phase is saved before HTTP begins. */
async function transition<T>(key: string, storage: Storage, work: () => T): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks) return navigator.locks.request(key, work)
  // Tests use an isolated Storage. Real browsers must support origin-wide coordination.
  if (typeof localStorage !== 'undefined' && storage === localStorage) throw new Error('浏览器暂无法协调原请求，请保留操作编号并重新打开恢复。')
  const previous = queues.get(key) || Promise.resolve()
  const next = previous.catch(() => {}).then(work); queues.set(key, next)
  return next
}

export function useSetSession(ownerId: number, allowed: () => boolean, storage: Storage = localStorage) {
  const storageKey = `catalog-sets.pending.schema1.${ownerId}`
  const input = shallowRef<SetPreviewInput | null>(null), view = shallowRef<SetRequestView | null>(null), receipt = shallowRef<SetReceipt | null>(null)
  const phase = ref<Phase | null>(null), cancelReason = ref<CancelReason | null>(null), busy = ref(false), error = ref(''), denied = ref(false)
  function authorize() { if (!allowed() || denied.value) throw new Error('当前账号或角色不能办理此套装目录请求，请刷新页面重新核对权限。') }
  function read(): Saved | null {
    const text = storage.getItem(storageKey); if (!text) return null
    const saved = JSON.parse(text) as Saved
    if (saved.storageVersion !== 1 || saved.ownerId !== ownerId || !validSetInput(saved.input) || !['previewing', 'previewRejected', 'editing', 'ready', 'confirming', 'cancelling', 'committed', 'cancelled'].includes(saved.phase) || ![null, 'EDIT', 'ABANDON'].includes(saved.cancelReason)) throw new Error('原请求恢复资料异常，请保留浏览器资料和操作编号。')
    // Earlier in-session records without send provenance stay conservative and cannot be released as a first rejection.
    saved.previewAttempts ??= 2; saved.previewInFlight ??= 0; saved.everUncertain ??= true; saved.rejection ??= null
    if (!Number.isSafeInteger(saved.previewAttempts) || saved.previewAttempts < 1 || !Number.isSafeInteger(saved.previewInFlight) || saved.previewInFlight < 0 || typeof saved.everUncertain !== 'boolean') throw new Error('原预览发送证据异常。')
    if (saved.view) assertSetRequest(saved.view)
    if (saved.receipt) assertSetReceipt(saved.receipt)
    if ((saved.phase === 'cancelling') !== (saved.cancelReason !== null) && saved.phase !== 'cancelled' && saved.phase !== 'committed') throw new Error('原取消动作资料异常。')
    return saved
  }
  function load(saved: Saved) { input.value = frozenCopy(saved.input); view.value = saved.view && frozenCopy(saved.view); receipt.value = saved.receipt && frozenCopy(saved.receipt); phase.value = saved.phase; cancelReason.value = saved.cancelReason; if (saved.phase === 'committed' || saved.phase === 'cancelled') error.value = '' }
  function save(saved: Saved) {
    storage.setItem(storageKey, JSON.stringify(saved)); load(saved)
    if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('catalog-sets-session', { detail: storageKey }))
  }
  function own(): Saved {
    const saved = read(); if (!saved || !input.value || saved.input.operationKey !== input.value.operationKey || setCanonical(saved.input) !== setCanonical(input.value)) throw new Error('另一个编辑器已切换原请求，请重新打开恢复。')
    load(saved); return saved
  }
  function restore() { try { const saved = read(); if (saved) load(saved) } catch (cause) { error.value = setProblem(cause).message } }
  function sync(event?: Event) { if (!event || event.type === 'storage' && (event as StorageEvent).key === storageKey || (event as CustomEvent).detail === storageKey) restore() }
  if (typeof window !== 'undefined') { window.addEventListener('storage', sync); window.addEventListener('catalog-sets-session', sync) }
  const dispose = () => { if (typeof window !== 'undefined') { window.removeEventListener('storage', sync); window.removeEventListener('catalog-sets-session', sync) } }
  function assertPreview(value: SetRequestView, original: SetPreviewInput) {
    assertSetRequest(value)
    if (value.operationKey !== original.operationKey || value.operation !== original.operation || value.preview.operation !== original.operation || value.preview.mode !== 'MANUAL' || setCanonical(normalizeSetInput(value.preview.applied)) !== setCanonical(original) || value.preview.reason !== original.reason || value.createdAt !== value.preview.createdAt || value.expiresAt !== value.preview.expiresAt) throw new Error('返回预览与原请求不一致，保留原操作编号查询。')
    const ids = 'members' in original ? original.members.map(member => member.bookId) : []
    if (setCanonical(value.preview.books.map(row => row.book.bookId)) !== setCanonical(ids) || setCanonical(value.preview.before.members.map(row => row.bookId)) !== setCanonical(ids) || setCanonical(value.preview.after.members.map(row => row.bookId)) !== setCanonical(ids)) throw new Error('返回预览未完整覆盖本次明确选择的书目。')
    if (original.operation === 'CREATE' ? value.preview.before.set !== null || value.preview.after.set.setId !== null || value.preview.after.set.clientRef !== original.clientRef : value.preview.before.set?.setId !== original.setId || value.preview.after.set.setId !== original.setId || value.preview.before.set.revision !== original.expectedSet.revision) throw new Error('返回预览的套装目标不一致。')
    for (const row of value.preview.books) {
      const expected = 'members' in original && original.members.find(member => member.bookId === row.book.bookId)?.expectedBook
      if (!expected || row.book.deleted || row.book.revision !== expected.revision || row.book.attributeRevision !== expected.attributeRevision || row.bookDigest !== expected.digest || row.source.digest !== expected.sourceDigest) throw new Error('预览书目与所核对的版本或来源不一致。')
    }
    const before = value.preview.before.set, planned = value.preview.after.set, rows = 'members' in original ? original.members : []
    const desired = before ? { ...before } : { setId:null, clientRef: original.operation === 'CREATE' ? original.clientRef : null, name: original.operation === 'CREATE' ? original.name : '', note: original.operation === 'CREATE' ? original.note : null, status:'ACTIVE', revision:1, activeMemberCount:0, retiredMemberCount:0, memberCompleteness:'UNVERIFIED', physicalAssembly:'UNVERIFIED' }
    const fields:string[] = []
    if (original.operation === 'CREATE') fields.push('set.create')
    if (original.operation === 'UPDATE_METADATA') { if (original.changes.name.action === 'SET') desired.name = original.changes.name.value; if (original.changes.note.action === 'SET') desired.note = original.changes.note.value; else if (original.changes.note.action === 'CLEAR') desired.note = null; if (desired.name !== before!.name) fields.push('set.name'); if (desired.note !== before!.note) fields.push('set.note') }
    if (original.operation === 'RETIRE_SET' || original.operation === 'RESTORE_SET') { desired.status = original.operation === 'RETIRE_SET' ? 'RETIRED' : 'ACTIVE'; if (desired.status !== before!.status) fields.push('set.status') }
    const added = ['CREATE','ADD_MEMBERS'].includes(original.operation) ? rows.length : 0, retired = original.operation === 'RETIRE_MEMBERS' ? rows.length : 0, restored = original.operation === 'RESTORE_MEMBERS' ? rows.length : 0
    if (added) fields.push('members.add'); if (retired) fields.push('members.retire'); if (restored) fields.push('members.restore')
    const changed = fields.length > 0
    desired.activeMemberCount += added - retired + restored; desired.retiredMemberCount += retired - restored
    if (before) desired.revision += changed ? 1 : 0
    if (setCanonical(desired) !== setCanonical(planned) || value.preview.willChange !== changed || setCanonical(fields) !== setCanonical(value.preview.changedFields)) throw new Error('套装预览的前后资料、版本或变更范围不一致。')
    rows.forEach((row,index) => {
      const previous = value.preview.before.members[index]!.member, next = value.preview.after.members[index]!.member
      if ('membershipId' in row) {
        if (!previous || previous.membershipId !== row.membershipId || previous.setId !== ('setId' in original ? original.setId : null) || previous.bookId !== row.bookId || previous.revision !== (row as any).expectedMember.revision || previous.status !== (original.operation === 'RETIRE_MEMBERS' ? 'ACTIVE' : 'RETIRED')) throw new Error('原成员关系的身份或状态不一致。')
      } else if (previous !== null) throw new Error('新增成员预览包含未明确选择的原关系。')
      const expectedMember = { membershipId: previous?.membershipId ?? null, setId: desired.setId, bookId:row.bookId, status:original.operation === 'RETIRE_MEMBERS' ? 'RETIRED' : 'ACTIVE', revision:(previous?.revision ?? 0)+1, evidence:row.evidence }
      if (setCanonical(expectedMember) !== setCanonical(next)) throw new Error('预览成员身份、状态或逐条依据不一致。')
    })
    const expectedImpact = { newSets: original.operation === 'CREATE' ? 1 : 0, addedMembers:added, retiredMembers:retired, restoredMembers:restored, changedMembers:added+retired+restored, unchangedActiveMembers:(before?.activeMemberCount ?? 0)-retired, unchangedRetiredMembers:(before?.retiredMemberCount ?? 0)-restored, copies:0,stockTotal:0,stockAvailable:0,loans:0,charges:0,uses:0 }
    if (setCanonical(expectedImpact) !== setCanonical(value.preview.impact) || !value.preview.warnings.some(row=>row.code==='COMPLETENESS_UNVERIFIED') || !value.preview.warnings.some(row=>row.code==='PHYSICAL_ASSEMBLY_UNVERIFIED')) throw new Error('预览计数或未核实范围不一致。')
  }
  function assertReceipt(value: SetReceipt, saved: Saved) {
    assertSetReceipt(value)
    const preview = saved.view?.preview, original = saved.input
    if (!preview || value.actorId !== ownerId || value.requestId !== saved.view?.requestId || value.operationKey !== original.operationKey || value.previewDigest !== saved.view?.previewDigest || value.operation !== original.operation || setCanonical(value.before) !== setCanonical(preview.before) || setCanonical(value.impact) !== setCanonical(preview.impact) || value.changed !== preview.willChange || value.eventIds.length !== (value.changed ? 1 : 0)) throw new Error('成功回执与原操作者、请求或预览不一致。')
    const resultSet = value.result.set, plannedSet = preview.after.set
    if (!Number.isSafeInteger(resultSet.setId) || !resultSet.setId || resultSet.clientRef !== null) throw new Error('成功回执缺少套装身份。')
    const planned = { ...plannedSet, setId: resultSet.setId, clientRef: null }
    if (setCanonical(resultSet) !== setCanonical(planned) || (original.operation !== 'CREATE' && resultSet.setId !== original.setId) || (original.operation === 'CREATE' ? value.result.createdSet?.clientRef !== original.clientRef || value.result.createdSet?.setId !== resultSet.setId : value.result.createdSet !== null)) throw new Error('成功回执的套装身份或资料不一致。')
    if (value.result.members.length !== preview.after.members.length) throw new Error('成功回执未完整覆盖本次成员。')
    const generated = new Set<number>()
    value.result.members.forEach((member, index) => {
      const plan = preview.after.members[index]!.member
      if (!member.membershipId || !Number.isSafeInteger(member.membershipId) || generated.has(member.membershipId) || member.setId !== resultSet.setId || setCanonical(member) !== setCanonical({ ...plan, membershipId: plan.membershipId ?? member.membershipId, setId: resultSet.setId })) throw new Error('成功回执的稳定成员关系不一致。')
      generated.add(member.membershipId)
    })
  }
  async function accept(value: SetRequestView) {
    authorize()
    await transition(storageKey, storage, () => {
      const saved = own(); assertPreview(value, saved.input)
      if (saved.view && (saved.view.requestId !== value.requestId || saved.view.previewDigest !== value.previewDigest || setCanonical(saved.view.preview) !== setCanonical(value.preview))) throw new Error('原请求预览发生变化，请保留原操作编号。')
      if (saved.phase === 'committed') { if (value.state !== 'COMMITTED' || setCanonical(value.receipt) !== setCanonical(saved.receipt)) throw new Error('旧查询结果尚未包含原成功回执，请再次查询。'); return }
      if (saved.phase === 'cancelled') { if (value.state !== 'CANCELLED' || value.cancelReason !== saved.cancelReason) throw new Error('旧查询结果尚未包含原取消回执，请再次查询。'); return }
      saved.view = value
      if (value.state === 'COMMITTED') { assertReceipt(value.receipt!, saved); saved.receipt = value.receipt; saved.phase = 'committed' }
      else if (value.state === 'CANCELLED') { if (saved.cancelReason && saved.cancelReason !== value.cancelReason) throw new Error('原取消原因不一致。'); saved.phase = 'cancelled'; saved.cancelReason = value.cancelReason }
      else if (saved.phase !== 'confirming' && saved.phase !== 'cancelling') saved.phase = 'ready'
      save(saved)
    })
  }
  async function run(work: () => Promise<void>) { if (busy.value) return; busy.value = true; error.value = ''; try { authorize(); await work() } catch (cause) { if (setProblem(cause).status === 403) denied.value = true; error.value = setProblem(cause).message } finally { busy.value = false } }
  async function sendPreview(original: SetPreviewInput) {
    try { await accept(await previewCatalogSet(original)); await transition(storageKey, storage, () => { const saved = own(); saved.previewInFlight = Math.max(0, saved.previewInFlight - 1); save(saved) }) }
    catch (cause) {
      await transition(storageKey, storage, () => {
        const saved = own(), problem = setProblem(cause), detail = (cause as any)?.response?.data?.data
        const known = problem.status === 400 && problem.code === 'INVALID_INPUT' || problem.status === 404 && ['BOOK_NOT_FOUND','SET_NOT_FOUND'].includes(problem.code || '') || problem.status === 409 && ['CATALOG_SET_CHANGED','BOOK_IDENTITY_CHANGED','SOURCE_CHANGED','MEMBERSHIP_EXISTS','MEMBERSHIP_STATE_CONFLICT','SET_RETIRED','SOURCE_LIMIT_EXCEEDED','REVISION_EXHAUSTED'].includes(problem.code || '')
        if (saved.phase === 'previewing' && !saved.view && saved.previewAttempts === 1 && saved.previewInFlight === 1 && !saved.everUncertain && detail?.schemaVersion === 1 && detail?.requestId === null && known) {
          saved.rejection = { schemaVersion: 1, httpStatus: problem.status!, reasonCode: problem.code!, requestId: null }; saved.phase = 'previewRejected'
        } else saved.everUncertain = true
        saved.previewInFlight = Math.max(0, saved.previewInFlight - 1); save(saved)
      })
      throw cause
    }
  }
  async function start(value: SetPreviewInput) { await run(async () => {
    if (!validSetInput(value)) throw new Error('请填写合法的套装资料、办理原因和每条成员依据；每笔最多100种书目。')
    const original = normalizeSetInput(value)
    await transition(storageKey, storage, () => { const saved = read(); if (saved && !['committed', 'cancelled', 'editing'].includes(saved.phase)) { load(saved); throw new Error('还有原请求需要恢复或安全取消，请先处理原请求。') } save({ storageVersion: 1, ownerId, input: original, view: null, receipt: null, phase: 'previewing', cancelReason: null, previewAttempts: 1, previewInFlight: 1, everUncertain: false, rejection: null }) })
    await sendPreview(original)
  }) }
  async function recover() { await run(async () => { const saved = own(); try { await accept(await getSetRequestByKey(saved.input.operationKey)) } catch (cause) { await transition(storageKey, storage, () => { const current = own(); if (current.phase === 'previewing') { current.everUncertain = true; save(current) } }); throw cause } }) }
  async function retryPreview() { await run(async () => { let original!: SetPreviewInput; await transition(storageKey, storage, () => { const saved = own(); if (saved.phase !== 'previewing') throw new Error('此请求只能按原确认或取消动作恢复。'); saved.previewAttempts++; saved.previewInFlight++; saved.everUncertain = true; original = saved.input; save(saved) }); await sendPreview(original) }) }
  async function editRejected() { await run(async () => { await transition(storageKey, storage, () => { const saved = own(); if (saved.phase !== 'previewRejected' || saved.view || !saved.rejection || saved.previewAttempts !== 1 || saved.previewInFlight !== 0 || saved.everUncertain) throw new Error('原请求结果仍需恢复，不能重新办理。'); storage.setItem(`${storageKey}.rejected.${saved.input.operationKey}`, JSON.stringify(saved)); saved.phase = 'editing'; save(saved) }) }) }
  async function confirm() { await run(async () => {
    let original!: Saved
    await transition(storageKey, storage, () => { const saved = own(); if (!saved.view || !['ready', 'confirming'].includes(saved.phase) || saved.view.state !== 'PREVIEWED') throw new Error('原请求正在取消或已有结果，请先查询。'); if (saved.phase === 'ready' && Date.parse(saved.view.expiresAt) <= Date.now()) throw new Error('预览已到期，请安全取消后重新核对。'); saved.phase = 'confirming'; save(saved); original = saved })
    try {
      const result = await confirmCatalogSet(original.view!.requestId, original.input.operationKey, original.view!.previewDigest)
      authorize(); await transition(storageKey, storage, () => { const saved = own(); assertReceipt(result, saved); saved.receipt = result; saved.phase = 'committed'; saved.view = { ...saved.view!, state: 'COMMITTED', receipt: result, cancelledAt: null, cancelReason: null }; save(saved) })
    } catch (cause) {
      const problem = setProblem(cause)
      if (problem.code && ['PREVIEW_EXPIRED', 'PREVIEW_CANCELLED', 'CATALOG_SET_CHANGED', 'BOOK_IDENTITY_CHANGED', 'SOURCE_CHANGED', 'MEMBERSHIP_EXISTS', 'MEMBERSHIP_STATE_CONFLICT', 'SET_RETIRED'].includes(problem.code)) await transition(storageKey, storage, () => { const saved = own(); if (saved.phase === 'confirming') { saved.phase = 'ready'; save(saved) } })
      throw cause
    }
  }) }
  async function cancel(reason: CancelReason) { await run(async () => {
    let original!: Saved
    await transition(storageKey, storage, () => { const saved = own(); if (!saved.view || !['ready', 'cancelling'].includes(saved.phase) || saved.view.state !== 'PREVIEWED') throw new Error('原确认结果尚未确定，请先查询或重试原确认。'); if (saved.cancelReason && saved.cancelReason !== reason) throw new Error('必须重试原取消原因。'); saved.phase = 'cancelling'; saved.cancelReason = reason; save(saved); original = saved })
    await accept(await cancelCatalogSet(original.view!.requestId, original.input.operationKey, original.view!.previewDigest, original.cancelReason!))
  }) }
  async function recoverKey(key: string) { await run(async () => {
    const value = await getSetRequestByKey(key); assertSetRequest(value)
    if (value.operationKey !== key || !validSetInput(value.preview.applied)) throw new Error('恢复编号与返回请求不一致。')
    await transition(storageKey, storage, () => { const existing = read(); if (existing && !['committed', 'cancelled', 'editing'].includes(existing.phase) && existing.input.operationKey !== key) throw new Error('先处理浏览器保留的原请求。'); if (!existing || existing.input.operationKey !== key) save({ storageVersion: 1, ownerId, input: normalizeSetInput(value.preview.applied), view: null, receipt: null, phase: 'previewing', cancelReason: null, previewAttempts: 1, previewInFlight: 0, everUncertain: true, rejection: null }); else load(existing) })
    await accept(value)
  }) }
  restore()
  return { input, view, receipt, phase, cancelReason, busy, denied, error, restore, dispose, start, recover, retryPreview, editRejected, confirm, cancel, recoverKey }
}
