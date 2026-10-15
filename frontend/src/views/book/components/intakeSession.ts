import { ref, shallowRef } from 'vue'
import { confirmIntake, getIntake, getIntakeByKey, previewIntake, type IntakePreviewInput, type IntakeReceipt, type IntakeView } from '@/api/inventoryIntake'

export function intakeError(error: unknown): { status: number | undefined; message: string; reasonCode: string | undefined } {
  const value = error as { response?: { status?: number; data?: { message?: string; data?: { reasonCode?: string } } }; message?: string }
  return { status: value?.response?.status, message: value?.response?.data?.message || value?.message || '请求失败，请重试。', reasonCode: value?.response?.data?.data?.reasonCode }
}

export function useIntakeSession() {
  const request = shallowRef<IntakePreviewInput | null>(null), view = shallowRef<IntakeView | null>(null), receipt = shallowRef<IntakeReceipt | null>(null)
  const busy = ref(false), uncertain = ref(false), error = ref(''), reasonCode = ref('')
  let confirmationInDoubt = false
  function accept(value: IntakeView) {
    view.value = value
    if (value.state === 'COMMITTED') {
      if (!value.receipt) throw new Error('回执尚未完整返回，请查询原请求。')
      receipt.value = value.receipt
      confirmationInDoubt = false
    }
    uncertain.value = confirmationInDoubt
  }
  async function lookup() {
    if (!request.value) return
    try {
      accept(view.value ? await getIntake(view.value.intakeId) : await getIntakeByKey(request.value.idempotencyKey))
      error.value = receipt.value ? '' : confirmationInDoubt ? '原请求仍显示待确认，确认响应尚未查清，请使用原请求重试确认。' : '原请求尚待确认，未新增库存。'
    } catch (cause) {
      const failure = intakeError(cause)
      // A missing response or lookup is never evidence that confirmation did not commit.
      error.value = failure.status === 404 ? '未查到原请求。请保留本次输入，使用原请求重试。' : '结果仍未确认，请继续查询原请求或使用原请求重试。'
    }
  }
  async function recover() {
    if (busy.value || !request.value) return
    busy.value = true
    try { await lookup() } finally { busy.value = false }
  }
  async function failed(cause: unknown) {
    const failure = intakeError(cause)
    error.value = failure.message; reasonCode.value = failure.reasonCode || ''
    uncertain.value = confirmationInDoubt || failure.status === undefined || failure.status >= 500
    if (uncertain.value) await lookup()
  }
  async function preview(input: IntakePreviewInput) {
    if (busy.value || receipt.value) return
    if (!request.value) request.value = JSON.parse(JSON.stringify(input)) as IntakePreviewInput
    busy.value = true; error.value = ''; reasonCode.value = ''
    try { accept(await previewIntake(request.value)) }
    catch (cause) { await failed(cause) }
    finally { busy.value = false }
  }
  async function confirm() {
    if (busy.value || !request.value || !view.value || receipt.value) return
    busy.value = true; error.value = ''; reasonCode.value = ''
    confirmationInDoubt = true
    try { receipt.value = await confirmIntake(view.value.intakeId, request.value.idempotencyKey, view.value.previewDigest); confirmationInDoubt = false; uncertain.value = false }
    catch (cause) {
      if ([400, 409].includes(intakeError(cause).status || 0)) confirmationInDoubt = false
      await failed(cause)
    }
    finally { busy.value = false }
  }
  function reset() {
    if (busy.value || uncertain.value) return false
    request.value = null; view.value = null; receipt.value = null; error.value = ''; reasonCode.value = ''
    confirmationInDoubt = false
    return true
  }
  return { request, view, receipt, busy, uncertain, error, reasonCode, preview, confirm, recover, reset }
}
