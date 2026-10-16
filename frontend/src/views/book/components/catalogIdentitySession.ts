import { ref, shallowRef } from 'vue'
import {
  confirmCatalogIdentity,
  getCatalogIdentityRequestByKey,
  previewCatalogIdentity,
  type CatalogIdentityPreviewInput,
  type CatalogIdentityReceipt,
  type CatalogIdentityRequestView,
} from '@/api/catalogIdentity'

type SessionError = { status?: number; code?: string; message?: string; response?: { status?: number; data?: { code?: string | number; message?: string; data?: { reasonCode?: string } } } }

function issue(cause: unknown) {
  const error = cause as SessionError
  const message = error?.response?.data?.message || error?.message || '请求失败'
  const bodyCode = error?.response?.data?.code
  const code = error?.response?.data?.data?.reasonCode || (typeof bodyCode === 'string' ? bodyCode : undefined) || (error?.code?.startsWith('CATALOG_') ? error.code : undefined) || (/^[A-Z][A-Z0-9_]+$/.test(message) ? message : undefined)
  return {
    status: error?.response?.status ?? error?.status,
    code,
    message,
  }
}
function canonical(value: unknown): string { if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`; if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical((value as Record<string, unknown>)[key])}`).join(',')}}`; return JSON.stringify(value) }

function freeze<T>(value: T): T {
  if (value && typeof value === 'object') {
    Object.values(value as Record<string, unknown>).forEach(freeze)
    Object.freeze(value)
  }
  return value
}

/**
 * Keeps an identity preview/confirm operation recoverable after a lost
 * response. Writes are ADMIN-only; the same session is also used by the
 * read-only librarian dialog so it never sends a write request.
 */
export function useCatalogIdentitySession(ownerId: number, allowed: () => boolean, storage: Storage = sessionStorage) {
  const input = shallowRef<CatalogIdentityPreviewInput | null>(null)
  const view = shallowRef<CatalogIdentityRequestView | null>(null)
  const receipt = shallowRef<CatalogIdentityReceipt | null>(null)
  const busy = ref(false)
  const uncertain = ref(false)
  const rejected = ref(false)
  const error = ref('')
  const storageKey = `catalog-identities.pending.v1.${ownerId}`
  let confirming = false
  let restored = false

  function save() {
    storage.setItem(storageKey, JSON.stringify({ storageVersion: 1, ownerId, input: input.value, confirming }))
  }

  function authorize() {
    if (allowed()) return true
    error.value = '当前账号无出版社/分类身份维护权限。'
    return false
  }

  function restore() {
    if (restored || !authorize()) return
    restored = true
    try {
      const text = storage.getItem(storageKey)
      if (!text) return
      const saved = JSON.parse(text)
      if (saved.storageVersion !== 1 || saved.ownerId !== ownerId || saved.input?.mode !== 'MANUAL' || !saved.input?.operationKey || !Number.isSafeInteger(saved.input.bookId) || !saved.input?.changes || !Object.prototype.hasOwnProperty.call(saved.input.changes, 'categoryDimensions')) throw new Error('invalid pending')
      input.value = freeze(saved.input)
      confirming = !!saved.confirming
      uncertain.value = true
      error.value = '发现未核实的出版社/分类身份请求，请查询原 key 结果。'
    } catch {
      uncertain.value = true
      error.value = '原身份请求恢复资料无法读取，请先核实原操作，不能换请求重做。'
    }
  }

  function acceptReceipt(value: CatalogIdentityReceipt) {
    if (!authorize() || !input.value || value.schemaVersion !== 1 || value.operationKey !== input.value.operationKey || value.mode !== 'MANUAL' || value.actorId !== ownerId || value.book.bookId !== input.value.bookId || value.book.identity?.bookId !== input.value.bookId || value.book.beforeRevision !== input.value.expectedRevision || value.book.identity.identityRevision !== value.book.afterRevision || canonical(value.book.applied) !== canonical(input.value.changes) || (view.value && value.requestId !== view.value.requestId)) {
      throw new Error('原成功回执身份不一致，请继续查原请求。')
    }
    receipt.value = freeze(value)
    confirming = false
    uncertain.value = false
    rejected.value = false
    error.value = ''
  }

  function accept(value: CatalogIdentityRequestView) {
    if (!authorize() || !input.value || value.operationKey !== input.value.operationKey || value.mode !== 'MANUAL' || value.preview.schemaVersion !== 1 || value.preview.mode !== 'MANUAL' || value.preview.book.bookId !== input.value.bookId || value.preview.book.before.bookId !== input.value.bookId || value.preview.book.after.bookId !== input.value.bookId || value.preview.book.expectedRevision !== input.value.expectedRevision || value.preview.book.catalogDigest !== input.value.expectedCatalogDigest || value.preview.book.sourceDigest !== null || canonical(value.preview.book.changes) !== canonical(input.value.changes) || (view.value && (value.requestId !== view.value.requestId || value.previewDigest !== view.value.previewDigest))) {
      throw new Error('预览与原身份请求不一致，请继续核查。')
    }
    view.value = freeze(value)
    if (value.state === 'COMMITTED') {
      if (!value.receipt) throw new Error('成功回执不完整')
      acceptReceipt(value.receipt)
    } else {
      uncertain.value = confirming
      error.value = confirming ? '原请求仍显示待确认，原确认可能尚在执行，请查询或重试原确认。' : ''
      rejected.value = false
    }
  }

  async function lookup() {
    if (!input.value || !authorize()) return
    try {
      const result = await getCatalogIdentityRequestByKey(input.value.operationKey)
      if (!authorize()) return
      accept(result)
      persistResult()
    } catch {
      uncertain.value = true
      error.value = '原请求暂未查清；未找到也不能证明没有在途操作，请继续查询或重试原请求。'
    }
  }

  async function failed(cause: unknown) {
    if (!authorize()) { uncertain.value = true; return }
    const value = issue(cause)
    error.value = `${value.message}${value.code ? `（${value.code}）` : ''}`
    const definite = value.status && [400, 403, 404, 409, 422].includes(value.status) && !['CATALOG_BUSY', 'CATALOG_IDENTITY_BUSY', 'IDEMPOTENCY_KEY_REUSED'].includes(value.code || '')
    if (definite) {
      confirming = false
      uncertain.value = false
      rejected.value = true
      persistResult()
    } else {
      uncertain.value = true
      await lookup()
    }
  }
  function persistResult() { try { save() } catch { error.value = receipt.value ? '成功回执已收到；会话存储暂不可写，重新打开时请查询原 key。' : '会话存储暂不可写；原 key 保持，恢复后只处理原请求。' } }

  async function preview(value: CatalogIdentityPreviewInput) {
    if (!authorize() || busy.value || input.value || uncertain.value || value.mode !== 'MANUAL') return
    const prepared: CatalogIdentityPreviewInput = JSON.parse(JSON.stringify(value))
    // The server sorts theme IDs before hashing and returning changes/applied.
    // Keep duplicates so invalid input is still rejected by the server.
    if (prepared.changes.themes) prepared.changes.themes.categoryIds.sort((left, right) => left - right)
    input.value = freeze(prepared)
    confirming = false
    try { save() } catch { input.value = null; error.value = '无法保存原身份请求，尚未发送。请先恢复浏览器会话存储。'; return }
    busy.value = true
    error.value = ''
    rejected.value = false
    try { const result = await previewCatalogIdentity(input.value!); if (authorize()) { accept(result); persistResult() } else uncertain.value = true } catch (cause) { await failed(cause) } finally { busy.value = false }
  }

  async function confirm() {
    if (!authorize() || busy.value || !input.value || !view.value || receipt.value || rejected.value) return
    const wasConfirming = confirming, wasUncertain = uncertain.value
    confirming = true
    uncertain.value = true
    try { save() } catch { confirming = wasConfirming; uncertain.value = wasUncertain; error.value = '无法保存原确认状态，尚未发送。'; return }
    busy.value = true
    error.value = ''
    try { const result = await confirmCatalogIdentity(view.value.requestId, input.value.operationKey, view.value.previewDigest); if (authorize()) { acceptReceipt(result); persistResult() } } catch (cause) { await failed(cause) } finally { busy.value = false }
  }

  async function retry() {
    if (!authorize() || busy.value || !input.value || receipt.value || rejected.value) return
    if (confirming && view.value) return confirm()
    busy.value = true
    error.value = ''
    try { const result = await previewCatalogIdentity(input.value); if (authorize()) { accept(result); persistResult() } } catch (cause) { await failed(cause) } finally { busy.value = false }
  }

  async function recover() {
    if (!authorize() || busy.value || !input.value) return
    busy.value = true
    try { await lookup() } finally { busy.value = false }
  }

  function reset() {
    if (busy.value || uncertain.value) return false
    try { storage.removeItem(storageKey) } catch { error.value = '无法清除已核实的原身份请求恢复记录。'; return false }
    input.value = null
    view.value = null
    receipt.value = null
    confirming = false
    rejected.value = false
    error.value = ''
    return true
  }

  return { input, view, receipt, busy, uncertain, rejected, error, restore, preview, confirm, retry, recover, reset }
}
