import { ref, shallowRef } from 'vue'
import type { StructureEvent, StructureSnapshot } from '@/api/locationAddress'
export interface MaintenanceReadback { current: StructureSnapshot | null; events: StructureEvent[] }
export function useLocationMaintenanceSession() {
  const busy = ref(false), needsReview = ref(false), error = ref(''), success = ref(false)
  const readback = shallowRef<MaintenanceReadback | null>(null)
  let readCurrent: (() => Promise<MaintenanceReadback>) | null = null
  async function lookup() {
    if (!readCurrent) return
    try { readback.value = await readCurrent() }
    catch { error.value += ' 当前原值或最新事件读取失败，请重试查询。' }
  }
  async function submit(write: () => Promise<unknown>, read: () => Promise<MaintenanceReadback>) {
    if (busy.value || needsReview.value) return false
    busy.value = true; error.value = ''; success.value = false; readCurrent = read; readback.value = null
    try { await write(); success.value = true; return true }
    catch (cause) {
      const issue = cause as { response?: { status?: number; data?: { message?: string; data?: { reasonCode?: string } } } }
      const status = issue.response?.status, code = issue.response?.data?.data?.reasonCode
      error.value = `${issue.response?.data?.message || '维护请求失败'}${code ? `（${code}）` : ''}`
      if (status === undefined || status >= 500 || status === 409) { needsReview.value = true; await lookup() }
      return false
    } finally { busy.value = false }
  }
  async function recover() { if (busy.value) return; busy.value = true; try { await lookup() } finally { busy.value = false } }
  function reviewed() {
    if (busy.value || !readback.value?.current || readback.value.current.deleted !== 0) return false
    needsReview.value = false; error.value = ''; return true
  }
  function reset() { if (busy.value || needsReview.value) return false; readback.value = null; error.value = ''; success.value = false; readCurrent = null; return true }
  return { busy, needsReview, error, success, readback, submit, recover, reviewed, reset }
}
