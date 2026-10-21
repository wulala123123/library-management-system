import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { ManagementReadError } from '@/api/managementReports'
import type { CsvDownload } from '@/api/csvDownload'
import { downloadCsv } from '@/utils/statistics'

/** Own one administrator download. All awaits finish before the final ownership check and object URL. */
export function useAdminDownload(options: { key: () => unknown; ready: () => boolean; read: (token: string, signal: AbortSignal) => Promise<CsvDownload>; onDenied?: () => void; onSuccess?: (file: CsvDownload) => void }) {
  const user = useUserStore(), busy = ref(false), error = ref(''), success = ref<CsvDownload | null>(null), denied = ref(false)
  let alive = true, epoch = 0, controller: AbortController | undefined
  const allowed = computed(() => !denied.value && user.isAdmin && !!user.token && Number.isSafeInteger(user.userInfo?.id))
  const canDownload = computed(() => allowed.value && options.ready() && !busy.value)
  function cancel() { ++epoch; controller?.abort(); busy.value = false; error.value = ''; success.value = null }
  function invalidate() { cancel(); denied.value = true; error.value = '管理员读取权限已失效，内容已清除。'; options.onDenied?.() }
  async function start() {
    if (!alive || !canDownload.value) return
    const owner = { epoch: ++epoch, id: user.userInfo?.id, role: user.userInfo?.role, token: user.token }
    const current = () => alive && allowed.value && options.ready() && owner.epoch === epoch && owner.id === user.userInfo?.id && owner.role === user.userInfo?.role && owner.token === user.token
    controller = new AbortController(); busy.value = true; error.value = ''; success.value = null
    try {
      const file = await options.read(owner.token, controller.signal)
      if (!current()) return
      downloadCsv(file.blob, file.filename); success.value = file; options.onSuccess?.(file)
    } catch (cause) {
      if (!current()) return
      if (cause instanceof ManagementReadError && (cause.status === 401 || cause.status === 403)) invalidate()
      else error.value = cause instanceof Error ? cause.message : '导出失败，请重试。'
    } finally { if (current()) busy.value = false }
  }
  watch(() => [user.userInfo?.id, user.userInfo?.role, user.token, user.isAdmin], () => { cancel(); denied.value = false }, { flush: 'sync' })
  watch(options.key, cancel, { deep: true, flush: 'sync' })
  watch(options.ready, ready => { if (!ready) cancel() }, { flush: 'sync' })
  window.addEventListener('auth-access-invalidated', invalidate)
  onBeforeUnmount(() => { alive = false; cancel(); window.removeEventListener('auth-access-invalidated', invalidate) })
  return { start, cancel, busy, error, success, canDownload }
}
