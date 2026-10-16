import { ref, shallowRef } from 'vue'
import { confirmCopyAction, getCopyAction, getCopyActionByKey, previewCopyAction, type CopyActionPreviewInput, type CopyActionReceipt, type CopyActionView } from '@/api/bookCopyAction'

function failure(cause: unknown) {
  const value = cause as { response?: { status?: number; data?: { message?: string; data?: { reasonCode?: string; copyId?: number; ordinal?: number } } }; message?: string }
  const data = value?.response?.data
  return { status: value?.response?.status, code: data?.data?.reasonCode || '', message: `${data?.message || value?.message || '请求失败'}${data?.data?.copyId ? `（实体册 ${data.data.copyId}）` : ''}` }
}
export function useCopyActionSession() {
  const request = shallowRef<CopyActionPreviewInput | null>(null), view = shallowRef<CopyActionView | null>(null), receipt = shallowRef<CopyActionReceipt | null>(null)
  const busy = ref(false), uncertain = ref(false), error = ref(''), rejected = ref(false)
  let confirming = false
  function accept(result: CopyActionView) {
    if (result.state === 'COMMITTED' && !result.receipt) throw new Error('成功回执不完整，请查询原请求。')
    view.value = result
    if (result.state === 'COMMITTED') { receipt.value = result.receipt; confirming = false }
    uncertain.value = confirming
  }
  async function lookup() {
    if (!request.value) return
    try {
      accept(view.value ? await getCopyAction(view.value.requestId) : await getCopyActionByKey(request.value.idempotencyKey))
      error.value = receipt.value ? '' : confirming ? '原请求仍显示待确认，结果尚未查清，请使用原请求重试确认。' : '原请求待确认，尚未办理。'
    } catch { error.value = '原请求结果尚未查清，请继续查询或使用原请求重试。' }
  }
  async function failed(cause: unknown) {
    const issue = failure(cause)
    error.value = issue.message
    uncertain.value = confirming || issue.status === undefined || issue.status >= 500
    rejected.value = !uncertain.value
    if (uncertain.value) await lookup()
  }
  async function preview(input: CopyActionPreviewInput) {
    if (busy.value || receipt.value) return
    request.value ||= JSON.parse(JSON.stringify(input)) as CopyActionPreviewInput
    busy.value = true; error.value = ''; rejected.value = false
    try { accept(await previewCopyAction(request.value)) } catch (cause) { await failed(cause) } finally { busy.value = false }
  }
  async function confirm() {
    if (busy.value || !request.value || !view.value || receipt.value) return
    busy.value = true; error.value = ''; confirming = true
    try { receipt.value = await confirmCopyAction(view.value.requestId, request.value.idempotencyKey, view.value.previewDigest); confirming = false; uncertain.value = false }
    catch (cause) { if ([400, 409].includes(failure(cause).status || 0)) confirming = false; await failed(cause) }
    finally { busy.value = false }
  }
  async function recover() { if (busy.value || !request.value) return; busy.value = true; try { await lookup() } finally { busy.value = false } }
  function reset() {
    if (busy.value || uncertain.value) return false
    request.value = null; view.value = null; receipt.value = null; error.value = ''; rejected.value = false; confirming = false
    return true
  }
  return { request, view, receipt, busy, uncertain, error, rejected, preview, confirm, recover, reset }
}
