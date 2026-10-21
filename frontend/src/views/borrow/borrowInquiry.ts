import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { createBorrowInquiryApi, BorrowInquiryError, type BorrowInquiryApi, type BorrowInquiryExport, type BorrowInquiryFilters, type BorrowInquiryPage, type BorrowOriginFilter, type BorrowStatusFilter } from '@/api/borrowInquiry'
import { downloadCsv } from '@/utils/statistics'

export interface InquiryAccess { id: number | undefined; role: string | undefined; token: string }
const emptyFilters = (): BorrowInquiryFilters => ({ keyword: '', status: null, origin: 'ALL' })
const filtersFrom = (draft: { keyword: string; status: BorrowStatusFilter | ''; origin: BorrowOriginFilter }): BorrowInquiryFilters => ({ keyword: draft.keyword, status: draft.status || null, origin: draft.origin })
/** One page owns its applied filters and pending reads; no shared data or browser persistence. */
export function useBorrowInquiry(access: () => InquiryAccess, makeApi: (token: string) => BorrowInquiryApi = createBorrowInquiryApi, download: (blob: Blob, filename: string) => void = downloadCsv) {
  const draft = reactive({ keyword: '', status: '' as BorrowStatusFilter | '', origin: 'ALL' as BorrowOriginFilter })
  const pagination = reactive({ page: 1, size: 10 })
  const applied = ref<BorrowInquiryFilters>(emptyFilters()), data = ref<BorrowInquiryPage | null>(null)
  const loading = ref(false), error = ref(''), exportBusy = ref(false), exportError = ref(''), lastExport = ref<Pick<BorrowInquiryExport, 'total' | 'asOf'> | null>(null), denied = ref(false)
  let alive = true, epoch = 0, readSequence = 0, exportSequence = 0
  let readController: AbortController | undefined, exportController: AbortController | undefined
  const permitted = computed(() => { const actor = access(); return alive && !denied.value && !!actor.token && Number.isSafeInteger(actor.id) && Number(actor.id) > 0 && (actor.role === 'ADMIN' || actor.role === 'LIBRARIAN') })
  const records = computed(() => data.value?.records ?? []), total = computed(() => data.value?.total ?? 0)
  const canExport = computed(() => permitted.value && !!data.value && !loading.value && !error.value && !exportBusy.value)
  const unapplied = computed(() => { const current = filtersFrom(draft); return current.keyword !== applied.value.keyword || current.status !== applied.value.status || current.origin !== applied.value.origin })
  function stopExport() { ++exportSequence; exportController?.abort(); exportBusy.value = false; exportError.value = ''; lastExport.value = null }
  function clear() { ++epoch; ++readSequence; readController?.abort(); stopExport(); data.value = null; loading.value = false; error.value = '' }
  function owner() { return { ...access(), epoch } }
  function current(request: ReturnType<typeof owner>) { const actor = access(); return alive && permitted.value && request.epoch === epoch && actor.id === request.id && actor.role === request.role && actor.token === request.token }
  function invalidated() { clear(); denied.value = true; error.value = '借阅查询权限已失效，已清除查询内容，请重新登录核对权限。' }
  function failure(cause: unknown) { return cause instanceof BorrowInquiryError ? cause.message : '借阅查询失败，请重试；读取失败不表示没有借阅明细。' }
  function revoked(cause: unknown) { if (cause instanceof BorrowInquiryError && (cause.status === 401 || cause.status === 403)) { invalidated(); return true } return false }
  async function reload() {
    if (!permitted.value) return
    const request = owner(), sequence = ++readSequence, query = { ...applied.value, ...pagination }
    readController?.abort(); readController = new AbortController(); stopExport(); data.value = null; error.value = ''; loading.value = true
    try {
      const result = await makeApi(request.token).query(query, readController.signal)
      if (current(request) && sequence === readSequence) data.value = result
    } catch (cause) { if (current(request) && sequence === readSequence && !revoked(cause)) error.value = failure(cause) }
    finally { if (current(request) && sequence === readSequence) loading.value = false }
  }
  function apply() { if (!permitted.value) return; applied.value = filtersFrom(draft); pagination.page = 1; reload() }
  function reset() { Object.assign(draft, { keyword: '', status: '', origin: 'ALL' }); apply() }
  function changePage(page: number) { if (!Number.isSafeInteger(page) || page < 1 || page === pagination.page) return; pagination.page = page; reload() }
  function changeSize(size: number) { if (![10, 20, 50].includes(size) || size === pagination.size) return; pagination.size = size; pagination.page = 1; reload() }
  async function exportAll() {
    if (!canExport.value) return
    const request = owner(), sequence = ++exportSequence, filters = { ...applied.value }
    exportController = new AbortController(); exportBusy.value = true; exportError.value = ''; lastExport.value = null
    try {
      const file = await makeApi(request.token).export(filters, exportController.signal)
      // Object URLs are only created inside download, after both identity and criteria checks.
      if (!current(request) || sequence !== exportSequence) return
      download(file.blob, file.filename)
      lastExport.value = { total: file.total, asOf: file.asOf }
    } catch (cause) { if (current(request) && sequence === exportSequence && !revoked(cause)) exportError.value = failure(cause) }
    finally { if (current(request) && sequence === exportSequence) exportBusy.value = false }
  }
  const stopIdentityWatch = watch(() => { const actor = access(); return [actor.id, actor.role, actor.token] }, () => {
    clear(); denied.value = false; Object.assign(draft, { keyword: '', status: '', origin: 'ALL' }); applied.value = emptyFilters(); pagination.page = 1; pagination.size = 10
    if (permitted.value) reload()
  }, { immediate: true, flush: 'sync' })
  const stopDraftWatch = watch(() => [draft.keyword, draft.status, draft.origin], stopExport, { flush: 'sync' })
  window.addEventListener('auth-access-invalidated', invalidated)
  function dispose() { if (!alive) return; alive = false; clear(); stopIdentityWatch(); stopDraftWatch(); window.removeEventListener('auth-access-invalidated', invalidated) }
  onBeforeUnmount(dispose)
  return { draft, pagination, applied, data, records, total, loading, error, exportBusy, exportError, lastExport, canExport, unapplied, permitted, reload, apply, reset, changePage, changeSize, exportAll, dispose }
}
