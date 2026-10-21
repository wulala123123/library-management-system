<template>
  <section v-if="user.isAdmin" class="borrow-operations" aria-label="统一借阅经营报告">
    <h3>借阅经营报告（非资金）</h3>
    <form class="report-filters" @submit.prevent="apply">
      <label>关键词 <input v-model="draft.keyword" placeholder="读者/书名/ISBN/完整馆内条码" /></label>
      <label>状态 <select v-model="draft.status" aria-label="报告借阅状态"><option value="">全部状态</option><option v-for="(name, value) in borrowStatusNames" :key="value" :value="value">{{ name }}</option></select></label>
      <label>来源 <select v-model="draft.origin" aria-label="报告借阅来源"><option v-for="(name, value) in borrowOriginNames" :key="value" :value="value">{{ name }}</option></select></label>
      <label>借出开始日 <input v-model="draft.startDate" aria-label="借出开始日" type="date" /></label><label>借出结束日 <input v-model="draft.endDate" aria-label="借出结束日" type="date" /></label>
      <el-button @click="apply">查询报告</el-button><el-button @click="reset">重置报告筛选</el-button>
    </form>
    <p>已应用筛选：关键词「{{ applied.keyword || '全部' }}」 · {{ applied.status ? borrowStatusNames[applied.status] : '全部状态' }} · {{ borrowOriginNames[applied.origin] }} · 借出日期 {{ applied.startDate ?? '不限开始日' }} 至 {{ applied.endDate ?? '不限结束日' }}（日闭区间）</p>
    <p v-if="unapplied" class="warning">输入尚未应用；报告与 CSV 均使用上方已应用筛选。</p>
    <p>本报告覆盖全部符合筛选的借阅明细；与上方按启用书目统计的库存基础指标范围分别说明。原系统累计、借阅明细和明确订单数不混加。业务日期只到天。</p>
    <p v-if="loading" role="status">正在读取借阅报告…</p>
    <p v-else-if="error" role="alert">{{ error }}</p>
    <template v-else-if="report">
      <p>业务日期 {{ report.asOfDate }}（Asia/Shanghai） · 技术读取时点 {{ report.asOf }}</p>
      <p>{{ report.scope }}</p>
      <dl class="report-totals"><div v-for="metric in metrics" :key="metric.key"><dt>{{ metric.label }}</dt><dd>{{ report.totals[metric.key] }} <small>{{ metric.unit }}</small></dd></div></dl>
      <p>来源明细：合计 {{ report.origins.total }} 条 · 迁入 {{ report.origins.imported }} 条 · 新柜台 {{ report.origins.counterOrder }} 条 · 本系统 {{ report.origins.system }} 条 · 待核实 {{ report.origins.unverified }} 条</p>
      <p>{{ report.origins.scope }}</p>
      <div class="report-tables">
        <section><h4>借出日期趋势（真实数量）</h4><p v-if="!report.trend.length">当前筛选暂无按日趋势。</p><table v-else><thead><tr><th>日期</th><th>借阅明细（条）</th><th>明确柜台订单（单）</th></tr></thead><tbody><tr v-for="row in report.trend" :key="row.date"><td>{{ row.date }} <small>{{ row.label }}</small></td><td>{{ row.borrowDetails }}</td><td>{{ row.knownCounterOrders }}</td></tr></tbody></table></section>
        <section><h4>热门书目（同一筛选范围）</h4><p v-if="!report.hotBooks.length">当前筛选暂无书目排行。</p><table v-else><thead><tr><th>序号 / 书目编号 / 登记书名</th><th>借阅明细（条）</th><th>明确柜台订单（单）</th></tr></thead><tbody><tr v-for="(book, index) in report.hotBooks" :key="book.bookId"><td>{{ index + 1 }} · 书目编号 {{ book.bookId }} · {{ book.title }}</td><td>{{ book.borrowDetails }}</td><td>{{ book.knownCounterOrders }}</td></tr></tbody></table></section>
      </div>
    </template>
    <el-button :disabled="!canDownload" :loading="busy" @click="start">导出当前筛选报告 CSV</el-button>
    <p v-if="downloadError" role="alert">{{ downloadError }}</p><p v-if="success" role="status">报告已导出 · 覆盖 {{ success.total }} 条借阅明细（不是文件行数） · 导出时点 {{ success.asOf }}</p>
  </section>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { borrowOriginNames, borrowStatusNames, type BorrowOriginFilter, type BorrowStatusFilter } from '@/api/borrowInquiry'
import { createManagementReportsApi, ManagementReadError, type BorrowOperationsReport, type BorrowReportFilters } from '@/api/managementReports'
import { useAdminDownload } from './useAdminDownload'
const user = useUserStore(), denied = ref(false), report = ref<BorrowOperationsReport | null>(null), loading = ref(false), error = ref('')
const draft = reactive({ keyword: '', status: '' as BorrowStatusFilter | '', origin: 'ALL' as BorrowOriginFilter, startDate: '', endDate: '' })
const snapshot = (): BorrowReportFilters => ({ keyword: draft.keyword, status: draft.status || null, origin: draft.origin, startDate: draft.startDate || null, endDate: draft.endDate || null })
const applied = ref(snapshot()), unapplied = computed(() => JSON.stringify(snapshot()) !== JSON.stringify(applied.value))
const metrics: { key: keyof BorrowOperationsReport['totals']; label: string; unit: string }[] = [{ key: 'borrowDetails', label: '借阅明细', unit: '条' }, { key: 'borrowedCopyEvents', label: '借出册次（按明细）', unit: '册次' }, { key: 'activeDetails', label: '未还明细', unit: '条' }, { key: 'returnedDetails', label: '已还明细', unit: '条' }, { key: 'overdueDetails', label: '逾期未还明细', unit: '条' }, { key: 'knownCounterOrders', label: '明确柜台订单', unit: '单' }]
let alive = true, sequence = 0, controller: AbortController | undefined
function clear() { ++sequence; controller?.abort(); report.value = null; loading.value = false; error.value = '' }
const { start, cancel, busy, error: downloadError, success, canDownload } = useAdminDownload({ key: () => [draft, applied.value], ready: () => !!report.value && !loading.value && !denied.value, read: (token, signal) => createManagementReportsApi(token).reportCsv({ ...applied.value }, signal), onDenied: () => { clear(); denied.value = true; error.value = '管理员读取权限已失效，内容已清除。' }, onSuccess: file => ElMessage.success(`报告导出成功：覆盖 ${file.total} 条借阅明细，非文件行数`) })
async function load() {
  clear(); cancel()
  if (!alive || !user.isAdmin || !user.token || denied.value) return
  const owner = { id: user.userInfo?.id, role: user.userInfo?.role, token: user.token, sequence }, filters = { ...applied.value }
  const current = () => alive && user.isAdmin && owner.sequence === sequence && owner.id === user.userInfo?.id && owner.role === user.userInfo?.role && owner.token === user.token
  controller = new AbortController(); loading.value = true
  try { const value = await createManagementReportsApi(owner.token).report(filters, controller.signal); if (current()) report.value = value }
  catch (cause) { if (!current()) return; if (cause instanceof ManagementReadError && (cause.status === 401 || cause.status === 403)) denied.value = true; error.value = cause instanceof Error ? cause.message : '报告读取失败，请重试；失败不表示没有借阅。' }
  finally { if (current()) loading.value = false }
}
function apply() { applied.value = snapshot(); load() }
function reset() { Object.assign(draft, { keyword: '', status: '', origin: 'ALL', startDate: '', endDate: '' }); apply() }
watch(() => [user.userInfo?.id, user.userInfo?.role, user.token, user.isAdmin], () => { denied.value = false; Object.assign(draft, { keyword: '', status: '', origin: 'ALL', startDate: '', endDate: '' }); applied.value = snapshot(); load() }, { immediate: true, flush: 'sync' })
function invalidated() { clear(); denied.value = true; error.value = '管理员读取权限已失效，内容已清除。' }
window.addEventListener('auth-access-invalidated', invalidated)
onBeforeUnmount(() => { alive = false; clear(); window.removeEventListener('auth-access-invalidated', invalidated) })
</script>
<style scoped>
.borrow-operations { margin: 20px 0; padding: 20px; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }h3 { font-size: 18px; margin: 0 0 14px; }h4 { margin: 8px 0; }.report-filters { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }input, select { padding: 6px; font: inherit; max-width: 100%; }label { display: flex; gap: 6px; align-items: center; }.warning { color: var(--el-color-warning-dark-2); }.report-totals { display: grid; grid-template-columns: repeat(auto-fit, minmax(145px, 1fr)); gap: 12px; }.report-totals div { padding: 12px; background: var(--el-fill-color-light); border-radius: 6px; }dd { margin: 4px 0 0; font-size: 24px; }small { font-size: 12px; color: var(--text-secondary); }.report-tables { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr)); gap: 20px; margin-bottom: 16px; }table { width: 100%; border-collapse: collapse; }th, td { padding: 7px; border-bottom: 1px solid var(--el-border-color); text-align: left; }
</style>
