import { computed, ref, shallowRef } from 'vue'
import { CirculationError, type CirculationApi, type CirculationInput, type CirculationReceipt, type CirculationRequest } from '@/api/circulation'
import { assertCirculationInput, assertReceiptBinding, assertRequestShape, circulationCanonical, circulationProblem, frozenCirculationCopy, safeCirculationId, validKey } from './circulationProtocol'

export type CirculationPhase = 'previewing' | 'ready' | 'confirming' | 'cancelling' | 'committed' | 'cancelled'
interface RecoveryMarker { storageVersion: 1; ownerId: number; operationKey: string; phase: CirculationPhase; previewDigest: string | null; confirmInFlight: number; confirmUncertain: boolean }
const phases: CirculationPhase[] = ['previewing','ready','confirming','cancelling','committed','cancelled']
const queues = new Map<string, Promise<unknown>>()
async function transition<T>(key: string, storage: Storage, work: () => T): Promise<T> {
  if (typeof navigator !== 'undefined' && navigator.locks) return navigator.locks.request(key, work)
  // Production coordinates every tab. The fallback is for isolated test storage only.
  if (typeof localStorage !== 'undefined' && storage === localStorage) throw Error('当前浏览器不支持安全协调办理窗口，请保留原操作编号并更换支持 Web Locks 的浏览器。')
  const next = (queues.get(key) || Promise.resolve()).catch(() => {}).then(work); queues.set(key,next); return next
}
/** The durable marker contains no member identity, selected IDs, input or response. */
export function useCirculationSession(ownerId: number, allowed: () => boolean, api: CirculationApi, storage: Storage = localStorage) {
  const storageKey = `circulation.pending.v1.${ownerId}`
  const marker = shallowRef<RecoveryMarker | null>(null), input = shallowRef<CirculationInput | null>(null), view = shallowRef<CirculationRequest | null>(null), receipt = shallowRef<CirculationReceipt | null>(null)
  const busy = ref(false), error = ref(''), denied = ref(false), corrupt = ref(false)
  const phase = computed(() => marker.value?.phase ?? null), operationKey = computed(() => marker.value?.operationKey ?? '')
  const canStart = computed(() => !marker.value && !corrupt.value && !busy.value && !denied.value && allowed())
  function authorize() { if (!allowed() || denied.value) { hide(); throw Error('当前账号或角色已变化，借还办理已停用；原恢复编号保留。') } }
  function hide() { input.value = null; view.value = null; receipt.value = null }
  function read(): RecoveryMarker | null {
    const raw = storage.getItem(storageKey); if (!raw) return null
    const value = JSON.parse(raw) as RecoveryMarker
    if (!value || Object.keys(value).sort().join(',') !== 'confirmInFlight,confirmUncertain,operationKey,ownerId,phase,previewDigest,storageVersion' || value.storageVersion !== 1 || value.ownerId !== ownerId || !safeCirculationId(ownerId) || !validKey(value.operationKey) || !phases.includes(value.phase) || !(value.previewDigest === null || typeof value.previewDigest === 'string' && /^[a-f0-9]{64}$/.test(value.previewDigest)) || !Number.isSafeInteger(value.confirmInFlight) || value.confirmInFlight < 0 || typeof value.confirmUncertain !== 'boolean') throw Error('原办理恢复资料异常，请保留浏览器资料和操作编号，不可重新办理。')
    return value
  }
  function load(value: RecoveryMarker | null) { if (value?.operationKey !== marker.value?.operationKey) hide(); marker.value = value ? frozenCirculationCopy(value) : null }
  function restore() { try { load(read()) } catch (cause) { corrupt.value = true; error.value = circulationProblem(cause).message } }
  function save(value: RecoveryMarker) { storage.setItem(storageKey, JSON.stringify(value)); load(value); if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('circulation-session', { detail: storageKey })) }
  function own(): RecoveryMarker { const saved = read(); if (!saved || saved.operationKey !== operationKey.value) throw Error('另一个办理窗口已切换原请求，请重新打开恢复。'); load(saved); return saved }
  function sync(event: Event) { if (event.type === 'storage' ? (event as StorageEvent).key === storageKey : (event as CustomEvent).detail === storageKey) restore() }
  function revoked() { denied.value = true; hide(); error.value = '当前登录权限已失效，会员资料已清除，原恢复编号保留。' }
  if (typeof window !== 'undefined') { window.addEventListener('storage',sync); window.addEventListener('circulation-session',sync); window.addEventListener('auth-access-invalidated',revoked) }
  function dispose() { hide(); if (typeof window !== 'undefined') { window.removeEventListener('storage',sync); window.removeEventListener('circulation-session',sync); window.removeEventListener('auth-access-invalidated',revoked) } }
  restore()
  async function run(work: () => Promise<void>) {
    if (busy.value) return
    busy.value = true; error.value = ''
    try { authorize(); await work() }
    catch (cause) { const problem = circulationProblem(cause); if ([401,403].includes(problem.status || 0)) revoked(); else error.value = problem.message }
    finally { busy.value = false }
  }
  async function accept(result: CirculationRequest) {
    authorize(); assertRequestShape(result)
    await transition(storageKey,storage,() => {
      authorize(); const saved = own()
      if (result.operationKey !== saved.operationKey || result.actorId !== ownerId || saved.previewDigest && result.previewDigest && saved.previewDigest !== result.previewDigest) throw Error('原请求编号、操作者或摘要不一致，不能接受返回结果。')
      if (result.input && input.value && circulationCanonical(result.input) !== circulationCanonical(input.value)) throw Error('原预览与本次明确输入不一致。')
      if (view.value?.preview && result.preview && circulationCanonical(view.value.preview) !== circulationCanonical(result.preview)) throw Error('原预览发生变化，不能接受返回结果。')
      if (saved.phase === 'committed' && result.state !== 'COMMITTED' || saved.phase === 'cancelled' && result.state !== 'CANCELLED') throw Error('晚到查询未包含原终态，请再次查询原请求。')
      if (result.state === 'COMMITTED') { assertReceiptBinding(result.receipt!,result,ownerId); if (receipt.value && circulationCanonical(receipt.value) !== circulationCanonical(result.receipt)) throw Error('原成功回执发生变化。'); saved.phase = 'committed'; saved.confirmInFlight = 0; saved.confirmUncertain = false }
      else if (result.state === 'CANCELLED') { saved.phase = 'cancelled'; saved.confirmInFlight = 0; saved.confirmUncertain = false }
      else if (!['confirming','cancelling'].includes(saved.phase)) saved.phase = 'ready'
      saved.previewDigest = result.previewDigest ?? saved.previewDigest
      save(saved)
      input.value = result.input && frozenCirculationCopy(result.input); view.value = frozenCirculationCopy(result); receipt.value = result.receipt && frozenCirculationCopy(result.receipt)
    })
  }
  async function start(value: CirculationInput) { await run(async () => {
    assertCirculationInput(value); const original = frozenCirculationCopy(value); let key = ''
    await transition(storageKey,storage,() => { authorize(); if (read() || corrupt.value) { restore(); throw Error('还有原办理需要查询、取消或查看回执，请先处理。') } key = crypto.randomUUID(); save({ storageVersion:1,ownerId,operationKey:key,phase:'previewing',previewDigest:null,confirmInFlight:0,confirmUncertain:false }); input.value = original })
    await accept(await api.preview(key,original))
  }) }
  async function retryPreview() { await run(async () => { const saved = own(); if (saved.phase !== 'previewing' || !input.value) throw Error('本窗口未保留原输入，请查询或安全取消原编号。'); await accept(await api.preview(saved.operationKey,input.value)) }) }
  async function recover() { await run(async () => { const saved = own(); await accept(await api.request(saved.operationKey)) }) }
  async function confirm() { await run(async () => {
    let original!: CirculationRequest
    await transition(storageKey,storage,() => {
      authorize(); const saved = own()
      if (!view.value?.preview || !view.value.previewDigest || view.value.state !== 'PREVIEWED' || !['ready','confirming'].includes(saved.phase)) throw Error('请先查询原预览；取消中的办理不能确认。')
      if (saved.phase === 'ready' && Date.parse(view.value.expiresAt || '') <= Date.now()) throw Error('原预览已到期，请安全取消后重新核对。')
      original = view.value; saved.phase = 'confirming'; saved.confirmInFlight++; save(saved)
    })
    try {
      const result = await api.confirm(original.operationKey,original.previewDigest!)
      authorize(); assertReceiptBinding(result,original,ownerId)
      await accept({ ...original,state:'COMMITTED',receipt:result,cancelledAt:null })
    } catch (cause) {
      await transition(storageKey,storage,() => {
        const current = read(); if (!current || current.operationKey !== original.operationKey) return
        const saved = own(); if (saved.phase !== 'confirming') return
        saved.confirmInFlight = Math.max(0,saved.confirmInFlight - 1)
        const known = cause instanceof CirculationError && cause.status === 409 && ['PREVIEW_EXPIRED','PREVIEW_STALE'].includes(cause.code)
        if (!known) saved.confirmUncertain = true
        if (known && saved.confirmInFlight === 0 && !saved.confirmUncertain) saved.phase = 'ready'
        save(saved)
      })
      throw cause
    }
  }) }
  async function cancel() { await run(async () => {
    let key = ''
    // The server serializes cancel against confirm and returns the durable winner. This is also
    // the recovery path when a crashed tab left an in-flight confirmation marker behind.
    await transition(storageKey,storage,() => { authorize(); const saved = own(); if (!['previewing','ready','confirming','cancelling'].includes(saved.phase)) throw Error('原办理已有结果，请先查询并查看原回执。'); saved.phase = 'cancelling'; key = saved.operationKey; save(saved) })
    const result = await api.cancel(key)
    if (!['CANCELLED','COMMITTED'].includes(result.state)) throw Error('服务端尚未给出确定取消或成功结果，请继续查询或重试原取消。')
    await accept(result)
  }) }
  async function acknowledge() { await run(async () => {
    await transition(storageKey,storage,() => { authorize(); const saved = own(); if (!['committed','cancelled'].includes(saved.phase) || !view.value || view.value.state.toLowerCase() !== saved.phase) throw Error('请先查询并查看原成功或取消结果。'); storage.removeItem(storageKey); load(null); error.value = ''; window.dispatchEvent(new CustomEvent('circulation-session',{ detail:storageKey })) })
  }) }
  return { ownerId, input, view, receipt, phase, operationKey, busy, error, denied, canStart, start, retryPreview, recover, confirm, cancel, acknowledge, dispose }
}
export type CirculationSession = ReturnType<typeof useCirculationSession>
