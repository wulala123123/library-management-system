import { ref, shallowRef } from 'vue'
import { confirmPublication, getPublicationRequestByKey, previewPublication, type PublicationBook, type PublicationPreviewInput, type PublicationReceipt, type PublicationRequestView } from '@/api/catalogPublication'
import { publicationFields } from './publicationDraft'

export function publicationCanonical(value: unknown): string {
  if (Array.isArray(value)) return `[${value.map(publicationCanonical).join(',')}]`
  if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${publicationCanonical((value as Record<string, unknown>)[key])}`).join(',')}}`
  return JSON.stringify(value)
}
function freeze<T>(value: T): T { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value) } return value }
function bookIdentity(value: PublicationBook) { const { displayTitle: _displayTitle, ...identity } = value; return identity }
function validInput(value: PublicationPreviewInput) {
  return value?.mode === 'MANUAL' && typeof value.operationKey === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.operationKey) && typeof value.reason === 'string' && Array.isArray(value.entries) && value.entries.length >= 1 && value.entries.length <= 100 && value.entries.every(entry => Number.isSafeInteger(entry.bookId) && entry.bookId > 0 && Number.isSafeInteger(entry.expectedRevision) && entry.expectedRevision >= 0 && typeof entry.expectedMetadataDigest === 'string' && entry.expectedMetadataDigest.length > 0 && entry.changes && publicationCanonical(Object.keys(entry.changes).sort()) === publicationCanonical([...publicationFields].sort()) && entry.evidence && typeof entry.evidence.reference === 'string')
}
function problem(cause: unknown) {
  const error = cause as { status?: number; message?: string; response?: { status?: number; data?: { message?: string; data?: { reasonCode?: string } } } }
  return { status: error?.response?.status ?? error?.status, code: error?.response?.data?.data?.reasonCode, message: error?.response?.data?.message || error?.message || '出版资料请求失败' }
}

/** A separate protocol: never reuses B33/B35 request keys, payloads or storage. */
export function usePublicationSession(ownerId: number, allowed: (count: number) => boolean, storage: Storage = sessionStorage) {
  const input = shallowRef<PublicationPreviewInput | null>(null), view = shallowRef<PublicationRequestView | null>(null), receipt = shallowRef<PublicationReceipt | null>(null)
  const busy = ref(false), uncertain = ref(false), rejected = ref(false), error = ref('')
  const storageKey = `catalog-publications.pending.v1.${ownerId}`
  let confirming = false, restored = false
  function authorize(count = input.value?.entries.length ?? 1) { if (allowed(count)) return true; error.value = '当前账号或角色不能办理此出版资料请求。'; return false }
  function coordinate() {
    const savedText = storage.getItem(storageKey)
    if (!savedText) return
    const saved = JSON.parse(savedText)
    if (saved.ownerId !== ownerId || saved.input?.operationKey !== input.value?.operationKey || publicationCanonical(saved.input) !== publicationCanonical(input.value)) throw new Error('浏览器中的原请求已变化，请重新打开并恢复原 key。')
    // A second mounted editor must not erase another editor's in-flight confirm.
    if (saved.confirming && !receipt.value && !rejected.value) { confirming = true; uncertain.value = true }
  }
  function save() { coordinate(); storage.setItem(storageKey, JSON.stringify({ storageVersion: 1, ownerId, input: input.value, view: view.value, confirming })) }
  function persist() { try { save() } catch { error.value = receipt.value ? '成功回执已收到；恢复资料暂不可写，请保留原 key。' : '恢复资料暂不可写；原 key 保留，只能查询或重试原请求。' } }
  function acceptReceipt(value: PublicationReceipt) {
    const original = input.value
    if (!authorize() || !original || value?.schemaVersion !== 1 || value.mode !== 'MANUAL' || value.operationKey !== original.operationKey || value.actorId !== ownerId || !Number.isSafeInteger(value.requestId) || (view.value && value.requestId !== view.value.requestId) || !Array.isArray(value.books) || value.books.length !== original.entries.length) throw new Error('成功回执与原操作者、请求或书目不一致。')
    for (let index = 0; index < original.entries.length; index++) {
      const entry = original.entries[index]!, result = value.books[index]!, planned = view.value?.preview.books[index]
      if (result.bookId !== entry.bookId || result.publication?.bookId !== entry.bookId || result.beforeRevision !== entry.expectedRevision || result.publication.revision !== result.afterRevision || result.afterRevision !== entry.expectedRevision + (result.changed ? 1 : 0) || (result.changed ? !Number.isSafeInteger(result.eventId) : result.eventId !== null) || publicationCanonical(result.applied) !== publicationCanonical(entry.changes) || publicationCanonical(result.evidence) !== publicationCanonical(entry.evidence)) throw new Error('成功回执的逐行变更或依据与原输入不一致。')
      if (planned && (result.changed !== planned.willChange || publicationCanonical(bookIdentity(result.publication)) !== publicationCanonical(bookIdentity(planned.after)))) throw new Error('成功回执与已核对的出版资料预览不一致。')
      for (const field of publicationFields) {
        const change = entry.changes[field]
        if (change && result.publication[field] !== (change.action === 'CLEAR' ? null : change.value)) throw new Error('成功回执字段值与原操作不一致。')
      }
    }
    receipt.value = freeze(value); confirming = false; uncertain.value = false; rejected.value = false; error.value = ''
  }
  function accept(value: PublicationRequestView) {
    const original = input.value
    if (!authorize() || !original || value?.mode !== 'MANUAL' || value.operationKey !== original.operationKey || !Number.isSafeInteger(value.requestId) || !value.previewDigest || !Number.isFinite(Date.parse(value.expiresAt)) || value.preview?.schemaVersion !== 1 || value.preview.mode !== 'MANUAL' || value.preview.reason !== original.reason || value.preview.expiresAt !== value.expiresAt || !Array.isArray(value.preview.books) || value.preview.books.length !== original.entries.length || !['PREVIEWED', 'COMMITTED'].includes(value.state) || (view.value && (value.requestId !== view.value.requestId || value.previewDigest !== view.value.previewDigest))) throw new Error('出版资料预览与原请求不一致。')
    for (let index = 0; index < original.entries.length; index++) {
      const entry = original.entries[index]!, row = value.preview.books[index]!
      if (row.bookId !== entry.bookId || row.before?.bookId !== entry.bookId || row.after?.bookId !== entry.bookId || row.before.revision !== entry.expectedRevision || row.before.metadataDigest !== entry.expectedMetadataDigest || publicationCanonical(row.changes) !== publicationCanonical(entry.changes) || publicationCanonical(row.evidence) !== publicationCanonical(entry.evidence)) throw new Error('逐行预览身份、原值版本或依据不一致。')
      const fields = publicationFields.filter(field => {
        const change = entry.changes[field], expected = change ? change.action === 'CLEAR' ? null : change.value : row.before[field]
        if (row.after[field] !== expected) throw new Error('预览字段值与原输入不一致。')
        return row.before[field] !== expected
      })
      if (row.before.catalogTitle !== row.after.catalogTitle || row.before.isbn !== row.after.isbn || row.willChange !== (fields.length > 0) || row.after.revision !== row.before.revision + (row.willChange ? 1 : 0) || !row.after.metadataDigest || publicationCanonical([...row.changedFields].sort()) !== publicationCanonical([...fields].sort())) throw new Error('预览变更范围或版本不一致。')
    }
    // Validate a committed receipt before retaining the candidate view.
    const previous = view.value
    view.value = freeze(value)
    try {
      if (value.state === 'COMMITTED') { if (!value.receipt) throw new Error('原请求缺少成功回执。'); acceptReceipt(value.receipt) }
      else { if (value.receipt !== null) throw new Error('待确认请求携带异常回执。'); uncertain.value = confirming; rejected.value = false; error.value = confirming ? '原确认可能仍在执行，请查询或重试原确认。' : '' }
    } catch (cause) { view.value = previous; throw cause }
  }
  function restore() {
    if (restored || !authorize()) return
    restored = true
    try {
      const savedText = storage.getItem(storageKey)
      if (!savedText) return
      const saved = JSON.parse(savedText)
      if (saved.storageVersion !== 1 || saved.ownerId !== ownerId || !validInput(saved.input)) throw new Error('invalid pending')
      input.value = freeze(saved.input); confirming = saved.confirming === true; uncertain.value = true
      if (!authorize()) return
      if (saved.view) { accept(saved.view); receipt.value = null; uncertain.value = true }
      error.value = '发现原出版资料请求，请查询原 key 核实结果。'
    } catch { uncertain.value = true; error.value = '原出版资料恢复记录无法读取；请核实原操作，不能换 key 重做。' }
  }
  async function lookup() {
    if (!input.value || !authorize()) return
    try { const result = await getPublicationRequestByKey(input.value.operationKey); if (!authorize()) { uncertain.value = true; return }; accept(result); persist() }
    catch { uncertain.value = true; error.value = '原请求结果尚未查清；404 或 BUSY 也不能证明没有在途操作，请继续查询或重试原 key。' }
  }
  async function failed(cause: unknown) {
    if (!authorize()) { uncertain.value = true; return }
    const issue = problem(cause)
    // Only explicit protocol rejections prove this attempt did not write.
    // Generic 404, BUSY and key collisions must never release the original key.
    if (issue.status && [400, 409, 404].includes(issue.status) && ['INVALID_INPUT', 'PUBLICATION_CHANGED', 'PREVIEW_EXPIRED', 'BOOK_NOT_FOUND'].includes(issue.code ?? '')) {
      confirming = false; uncertain.value = false; rejected.value = true; error.value = `${issue.message}（${issue.code}）`; persist()
    } else { uncertain.value = true; error.value = issue.message; await lookup() }
  }
  async function preview(value: PublicationPreviewInput) {
    if (!authorize(value.entries.length) || busy.value || input.value || uncertain.value || !validInput(value)) return
    try {
      // Another mounted editor may have frozen an operation since this one opened.
      if (storage.getItem(storageKey)) { restored = false; restore(); error.value = '已存在原出版资料请求，请先查询原 key。'; return }
    } catch { uncertain.value = true; error.value = '无法核对浏览器原请求记录，尚未发送。'; return }
    const prepared: PublicationPreviewInput = JSON.parse(JSON.stringify(value))
    prepared.entries.sort((a, b) => a.bookId - b.bookId) // Keep duplicates; the server rejects them.
    input.value = freeze(prepared); confirming = false
    try { save() } catch { input.value = null; error.value = '无法保存原出版资料请求，尚未发送。请恢复浏览器存储后重试。'; return }
    busy.value = true; error.value = ''; rejected.value = false
    try { const result = await previewPublication(input.value); if (authorize()) { accept(result); persist() } else uncertain.value = true } catch (cause) { await failed(cause) } finally { busy.value = false }
  }
  async function confirm() {
    if (!authorize() || busy.value || !input.value || !view.value || receipt.value || rejected.value) return
    const oldConfirming = confirming, oldUncertain = uncertain.value
    confirming = true; uncertain.value = true
    try { save() } catch { confirming = oldConfirming; uncertain.value = oldUncertain; error.value = '无法保存原确认状态，尚未发送。'; return }
    busy.value = true; error.value = ''
    try { const result = await confirmPublication(view.value.requestId, input.value.operationKey, view.value.previewDigest); if (authorize()) { acceptReceipt(result); persist() } else uncertain.value = true } catch (cause) { await failed(cause) } finally { busy.value = false }
  }
  async function retry() {
    if (!authorize() || busy.value || !input.value || receipt.value || rejected.value) return
    if (confirming) { if (view.value) return confirm(); return recover() }
    busy.value = true
    try { const result = await previewPublication(input.value); if (authorize()) { accept(result); persist() } else uncertain.value = true } catch (cause) { await failed(cause) } finally { busy.value = false }
  }
  async function recover() { if (!authorize() || busy.value || !input.value) return; busy.value = true; try { await lookup() } finally { busy.value = false } }
  function reset() {
    if (!authorize() || busy.value || uncertain.value) return false
    try { coordinate(); if (uncertain.value) { error.value = '另一个编辑窗口已开始原确认，请先查询原 key。'; return false }; storage.removeItem(storageKey) } catch { uncertain.value = true; error.value = '无法核对或清除原请求恢复记录，请重新打开核实原 key。'; return false }
    input.value = view.value = receipt.value = null; confirming = false; rejected.value = false; error.value = ''; return true
  }
  return { input, view, receipt, busy, uncertain, rejected, error, restore, preview, confirm, retry, recover, reset }
}
