import { ref, shallowRef } from 'vue'
import { confirmCatalogAttributes, getCatalogAttributeRequestByKey, previewCatalogAttributes, type AttributeMode, type AttributePreviewInput, type AttributeReceipt, type AttributeRequestView } from '@/api/catalogAttribute'
function issue(cause: unknown) { const error = cause as { message?: string; response?: { status?: number; data?: { message?: string; data?: { reasonCode?: string } } } }; return { status: error?.response?.status, code: error?.response?.data?.data?.reasonCode, message: error?.response?.data?.message || error?.message || '请求失败' } }
function freeze<T>(value: T): T { if (value && typeof value === 'object') { Object.values(value).forEach(freeze); Object.freeze(value) } return value }
export function useCatalogAttributeSession(ownerId: number, mode: AttributeMode, allowed: () => boolean, storage: Storage = sessionStorage) {
  const input = shallowRef<AttributePreviewInput | null>(null), view = shallowRef<AttributeRequestView | null>(null), receipt = shallowRef<AttributeReceipt | null>(null)
  const busy = ref(false), uncertain = ref(false), rejected = ref(false), error = ref('')
  const storageKey = `catalog-attributes.pending.v1.${ownerId}.${mode}`
  let confirming = false, restored = false
  function save() { storage.setItem(storageKey, JSON.stringify({ storageVersion: 1, ownerId, mode, input: input.value, confirming })) }
  function authorize() { if (allowed()) return true; error.value = mode === 'SOURCE' ? '仅当前管理员可读取或恢复来源采用请求。' : '当前账号无属性维护权限。'; return false }
  function restore() {
    if (restored || !authorize()) return
    restored = true
    try { const text = storage.getItem(storageKey); if (!text) return; const saved = JSON.parse(text); if (saved.storageVersion !== 1 || saved.ownerId !== ownerId || saved.mode !== mode || saved.input?.mode !== mode || !saved.input.operationKey || !Number.isSafeInteger(saved.input.bookId)) throw new Error('invalid pending'); input.value = freeze(saved.input); confirming = !!saved.confirming; uncertain.value = true; error.value = '发现未核实的原属性请求，请查询原 key 结果。' }
    catch { uncertain.value = true; error.value = '原请求恢复资料无法读取，请先核实原操作，不能换请求重做。' }
  }
  function acceptReceipt(value: AttributeReceipt) {
    if (!input.value || value.schemaVersion !== 1 || value.operationKey !== input.value.operationKey || value.mode !== mode || value.actorId !== ownerId || value.book.bookId !== input.value.bookId || value.book.profile.bookId !== input.value.bookId || (view.value && value.requestId !== view.value.requestId)) throw new Error('原成功回执身份不一致，请继续查原请求。')
    receipt.value = freeze(value); confirming = false; uncertain.value = false; rejected.value = false; error.value = ''
  }
  function accept(value: AttributeRequestView) {
    if (!input.value || value.operationKey !== input.value.operationKey || value.mode !== mode || value.preview.schemaVersion !== 1 || value.preview.mode !== mode || value.preview.book.bookId !== input.value.bookId || value.preview.book.before.bookId !== input.value.bookId || value.preview.book.after.bookId !== input.value.bookId || value.preview.book.expectedRevision !== input.value.expectedRevision || value.preview.book.catalogDigest !== input.value.expectedCatalogDigest || value.preview.book.sourceDigest !== input.value.sourceDigest || (view.value && (value.requestId !== view.value.requestId || value.previewDigest !== view.value.previewDigest))) throw new Error('预览与原请求身份不一致，请继续核查。')
    view.value = freeze(value)
    if (value.state === 'COMMITTED') { if (!value.receipt) throw new Error('成功回执不完整'); acceptReceipt(value.receipt) }
    else { uncertain.value = confirming; error.value = confirming ? '原请求仍显示待确认，原确认可能尚在执行，请查询或重试原确认。' : ''; rejected.value = false }
  }
  async function lookup() { if (!input.value || !authorize()) return; try { accept(await getCatalogAttributeRequestByKey(input.value.operationKey)); save() } catch { uncertain.value = true; error.value = '原请求暂未查清；未找到也不能证明没有在途操作，请继续查询或重试原请求。' } }
  async function failed(cause: unknown) {
    const value = issue(cause); error.value = `${value.message}${value.code ? `（${value.code}）` : ''}`
    const definite = value.status && [400,403,404,409,422].includes(value.status) && !['CATALOG_BUSY','IDEMPOTENCY_KEY_REUSED'].includes(value.code || '')
    if (definite) { confirming = false; uncertain.value = false; rejected.value = true; save() }
    else { uncertain.value = true; await lookup() }
  }
  async function preview(value: AttributePreviewInput) {
    if (!authorize() || busy.value || input.value || uncertain.value) return
    input.value = freeze(JSON.parse(JSON.stringify(value))); confirming = false
    try { save() } catch { input.value = null; error.value = '无法保存原属性请求，尚未发送。请先恢复浏览器会话存储。'; return }
    busy.value = true; error.value = ''; rejected.value = false
    try { accept(await previewCatalogAttributes(input.value!)) } catch (cause) { await failed(cause) } finally { busy.value = false }
  }
  async function confirm() {
    if (!authorize() || busy.value || !input.value || !view.value || receipt.value || rejected.value) return
    confirming = true; uncertain.value = true
    try { save() } catch { error.value = '无法保存原确认状态，尚未发送。'; return }
    busy.value = true; error.value = ''
    try { acceptReceipt(await confirmCatalogAttributes(view.value.requestId, input.value.operationKey, view.value.previewDigest)); save() } catch (cause) { await failed(cause) } finally { busy.value = false }
  }
  async function retry() {
    if (!authorize() || busy.value || !input.value || receipt.value || rejected.value) return
    if (confirming && view.value) return confirm()
    busy.value = true; error.value = ''
    try { accept(await previewCatalogAttributes(input.value)) } catch (cause) { await failed(cause) } finally { busy.value = false }
  }
  async function recover() { if (!authorize() || busy.value || !input.value) return; busy.value = true; try { await lookup() } finally { busy.value = false } }
  function reset() { if (busy.value || uncertain.value) return false; try { storage.removeItem(storageKey) } catch { error.value = '无法清除已核实的原请求恢复记录。'; return false } input.value = null; view.value = null; receipt.value = null; confirming = false; rejected.value = false; error.value = ''; return true }
  return { input, view, receipt, busy, uncertain, rejected, error, restore, preview, confirm, retry, recover, reset }
}
