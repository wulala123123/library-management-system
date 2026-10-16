import { ref, shallowRef } from 'vue'

export interface FrozenStocktakeOperation {
  storageVersion: 1
  ownerId: number
  operationKey: string
  label: string
  path: string
  body: Record<string, unknown>
}
export type OperationRead<T> = { state: 'COMMITTED'; result: T } | { state: 'PENDING' | 'ABSENT' } | { state: 'REJECTED'; message: string }
export interface OperationTransport<T> {
  send(operation: FrozenStocktakeOperation): Promise<T>
  read(operation: FrozenStocktakeOperation): Promise<OperationRead<T>>
  validate(operation: FrozenStocktakeOperation, result: T): void
}
function immutable<T>(value: T): T {
  if (value && typeof value === 'object') { Object.freeze(value); Object.values(value).forEach(immutable) }
  return value
}
function errorInfo(cause: unknown) {
  const error = cause as { message?: string; response?: { status?: number; data?: { message?: string; data?: { reasonCode?: string } } } }
  return { status: error?.response?.status, code: error?.response?.data?.data?.reasonCode, message: error?.response?.data?.message || error?.message || '请求失败' }
}

/** One frozen operation per account/tab. ABSENT is never evidence that a timed-out write stopped. */
export function useStocktakeOperation<T>(ownerId: number, transport: OperationTransport<T>, storage: Storage = sessionStorage) {
  const storageKey = `stocktake.pending.v1.${ownerId}`
  const pending = shallowRef<FrozenStocktakeOperation | null>(null)
  const result = shallowRef<T | null>(null)
  const busy = ref(false), state = ref<'IDLE' | 'UNCERTAIN' | 'REJECTED' | 'COMMITTED'>('IDLE'), error = ref('')
  try {
    const stored = storage.getItem(storageKey)
    if (stored) {
      const value = JSON.parse(stored) as FrozenStocktakeOperation
      if (value.storageVersion !== 1 || value.ownerId !== ownerId || !value.operationKey || !/^\/stocktake(?:s|-resolutions)(?:\/|$)/u.test(value.path) || !value.body || typeof value.body !== 'object') throw new Error('保存的盘点请求身份不完整。')
      pending.value = immutable(value); state.value = 'UNCERTAIN'
      error.value = '发现尚未核实的原请求，请先查询结果或重试原请求。'
    }
  } catch { state.value = 'UNCERTAIN'; error.value = '原请求恢复资料无法读取。请保留当前页面并联系管理员核实，不能换请求重做。' }

  function accept(operation: FrozenStocktakeOperation, value: T) {
    transport.validate(operation, value)
    result.value = immutable(value); state.value = 'COMMITTED'; error.value = ''
  }
  async function lookup() {
    if (!pending.value) return
    try {
      const found = await transport.read(pending.value)
      if (found.state === 'COMMITTED') accept(pending.value, found.result)
      else if (found.state === 'REJECTED') { state.value = 'REJECTED'; error.value = found.message }
      else {
        state.value = 'UNCERTAIN'
        error.value = found.state === 'ABSENT' ? '原请求暂未查到，仍可能正在执行。请继续查询，或使用完全相同的原请求重试。' : '原请求尚未完成，请继续查询或使用原请求重试。'
      }
    } catch { state.value = 'UNCERTAIN'; error.value = '原请求结果尚未查清，请继续查询或使用原请求重试。' }
  }
  async function sendFrozen() {
    const operation = pending.value
    if (!operation) return
    state.value = 'UNCERTAIN'; error.value = ''
    try { accept(operation, await transport.send(operation)) }
    catch (cause) {
      const issue = errorInfo(cause)
      error.value = `${issue.message}${issue.code ? `（${issue.code}）` : ''}`
      if (issue.status && [400, 403, 404, 409, 422].includes(issue.status) && !['IDEMPOTENCY_KEY_REUSED', 'STOCKTAKE_BUSY'].includes(issue.code || '')) state.value = 'REJECTED'
      else await lookup()
    }
  }
  async function start(operation: Omit<FrozenStocktakeOperation, 'storageVersion' | 'ownerId'>) {
    if (busy.value || pending.value || state.value === 'UNCERTAIN') return false
    const frozen = immutable(JSON.parse(JSON.stringify({ ...operation, storageVersion: 1, ownerId })) as FrozenStocktakeOperation)
    try { storage.setItem(storageKey, JSON.stringify(frozen)) }
    catch { error.value = '浏览器无法保存原请求，尚未发送；请先恢复会话存储。'; return false }
    pending.value = frozen; result.value = null; busy.value = true
    try { await sendFrozen(); return state.value === 'COMMITTED' } finally { busy.value = false }
  }
  async function recover() { if (busy.value || !pending.value) return; busy.value = true; try { await lookup() } finally { busy.value = false } }
  async function retry() { if (busy.value || !pending.value || state.value === 'COMMITTED' || state.value === 'REJECTED') return; busy.value = true; try { await sendFrozen() } finally { busy.value = false } }
  function acknowledge() {
    if (busy.value || !['COMMITTED', 'REJECTED', 'IDLE'].includes(state.value)) return false
    try { storage.removeItem(storageKey) } catch { error.value = '无法清除已核实的恢复资料，请恢复会话存储后继续。'; return false }
    pending.value = null; result.value = null; state.value = 'IDLE'; error.value = ''; return true
  }
  return { pending, result, busy, state, error, start, recover, retry, acknowledge }
}
