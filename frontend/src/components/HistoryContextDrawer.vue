<template>
  <el-drawer v-if="canRead" :model-value="visible" title="历史资料（只读）" size="min(880px, 96vw)" :append-to-body="true" @update:model-value="close">
    <div class="history-context">
      <p class="note">来源档案仅供核对。原订单保持原状态与原始内容；此处查询不办理借还、收费或扣次。</p>
      <p v-if="loading" role="status">正在读取历史关联…</p>
      <section v-else-if="contextError" role="alert"><p>{{ contextError }}</p><el-button @click="loadContext">重新读取历史关联</el-button></section>
      <template v-else-if="context">
        <section aria-label="当前稳定身份">
          <h3>当前稳定身份</h3>
          <p>{{ entityName }}编号：<span class="raw">{{ context.entityId }}</span></p>
          <dl class="facts"><template v-for="field in identityFields" :key="field.key"><dt>{{ field.label }}</dt><dd class="raw">{{ raw(context.identity[field.key]) }}</dd></template></dl>
          <p class="note">业务编号与会员卡号、馆内条码分别展示，保留原文与前导零。</p>
        </section>
        <section aria-label="来源与关联依据">
          <h3>来源与关联依据</h3>
          <dl class="facts">
            <dt>当前来源</dt><dd>{{ classifications[context.provenance.classification] }}</dd>
            <dt>关联核验</dt><dd>{{ associationStates[context.provenance.associationState] }}</dd>
            <dt>来源系统原文</dt><dd class="raw">{{ raw(context.provenance.sourceSystem) }}</dd>
            <dt>来源键原文</dt><dd class="raw">{{ raw(context.provenance.sourceKey) }}</dd>
            <template v-for="link in linkedIds" :key="link.key"><dt>{{ link.label }}</dt><dd class="raw">{{ raw(context.links[link.key]) }}</dd></template>
          </dl>
          <ul v-if="context.provenance.basis.length"><li v-for="(basis, index) in context.provenance.basis" :key="index" class="raw">{{ basis }}</li></ul>
          <p v-else class="note">未提供明确关联依据，需核实。</p>
          <p v-for="(warning, index) in context.provenance.warnings" :key="index" class="warning raw">{{ warning }}</p>
          <p v-if="entityType === 'borrows'" class="warning">旧订单仅作同会员资料查看：同会员原订单，未与本条借阅建立对应。不按姓名、手机号或日期推断关联。</p>
          <p v-if="context.links.counterOrderId" class="note">新柜台订单编号 {{ context.links.counterOrderId }}：由服务端按本条借阅的明确关联提供。</p>
        </section>
        <section aria-label="相关来源档案">
          <h3>相关来源档案</h3>
          <p class="note">{{ archiveSummary }}</p>
          <label class="filter">资料类型
            <select aria-label="历史资料类型" :value="filter" @change="changeFilter(($event.target as HTMLSelectElement).value as HistoryFilter)">
              <option value="ALL">全部资料</option><option v-for="type in recordTypes" :key="type" :value="type">{{ recordName(type) }}</option>
            </select>
          </label>
          <p v-if="recordsLoading" role="status">正在读取第 {{ page }} 页…</p>
          <section v-else-if="recordsError" role="alert"><p>{{ recordsError }}</p><el-button @click="loadRecords">重试本页资料</el-button></section>
          <p v-else-if="records.length === 0" class="note">此范围暂无相关来源档案；不表示历史业务未发生。</p>
          <ul v-else class="archive-list">
            <li v-for="record in records" :key="record.id">
              <h4>{{ recordName(record.recordType) }} #{{ record.id }} · {{ raw(record.displayName) }}</h4>
              <dl class="facts"><dt>来源系统原文</dt><dd class="raw">{{ raw(record.sourceSystem) }}</dd><dt>来源键原文</dt><dd class="raw">{{ raw(record.sourceKey) }}</dd><dt>来源时间原文</dt><dd class="raw">{{ raw(record.sourceTime) }}</dd><dt>原摘要</dt><dd class="raw">{{ raw(record.summary) }}</dd><dt>关联依据</dt><dd class="raw">{{ record.association.basis }}</dd><dt>关联核验</dt><dd>{{ associationStates[record.association.state] }}</dd></dl>
              <p v-if="entityType === 'borrows' && record.recordType === 'ORDER'" class="warning">同会员原订单，未与本条借阅建立对应</p>
              <el-button :aria-label="`查看原始档案${record.id}`" @click="showRecord(record.id)">查看原始内容</el-button>
            </li>
          </ul>
          <el-pagination v-if="!recordsLoading && !recordsError" :current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="changePage" />
        </section>
        <section v-if="selectedRecordId" aria-label="原始档案全文" class="original-record">
          <h3>原始档案全文 #{{ selectedRecordId }}</h3>
          <p class="note">原状态及其他来源字段保留在下列原文中；来源未提供的字段不补写。原文不代表当前业务状态。</p>
          <p v-if="detailLoading" role="status">正在读取原始内容…</p>
          <section v-else-if="detailError" role="alert"><p>{{ detailError }}</p><el-button @click="showRecord(selectedRecordId)">重试原始内容</el-button></section>
          <template v-else-if="detail">
            <p class="raw">关联依据：{{ detail.association.basis }}</p>
            <p v-if="invalidJson" class="warning">原始内容不是有效 JSON，以下按原文保留，需核实。</p>
            <p v-if="detail.detailsJson === ''" class="note">原始内容为空字符串。</p>
            <template v-if="!invalidJson">
              <h4>原字段浏览</h4>
              <p class="note">以下为原始资料的 JSON 解析展示；数值与嵌套值为显示投影，精确原文见下方。原字段不解释为当前办理事实。</p>
              <dl class="facts source-fields"><template v-for="field in sourceFields" :key="field.key"><dt class="raw">{{ field.key }}</dt><dd><small>{{ field.type }}</small><pre class="raw source-value">{{ field.value }}</pre></dd></template></dl>
              <p v-if="sourceFields.length === 0" class="note">原始 JSON 未包含字段。</p>
            </template>
            <details :open="invalidJson"><summary>查看完整原文（含原状态）</summary><pre class="raw original-text">{{ detail.detailsJson }}</pre></details>
          </template>
        </section>
      </template>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { createHistoryContextApi, HistoryReadError, historyId, type HistoryContext, type HistoryEntity, type HistoryFilter, type HistoryIdentity, type HistoryRecord, type HistoryRecordDetail, type HistoryRecordType } from '@/api/historyContext'
import { useUserStore } from '@/store/modules/user'

const props = defineProps<{ visible: boolean; entityType: HistoryEntity; entityId: string | number | null }>()
const emit = defineEmits<{ (event: 'update:visible', value: boolean): void }>()
const user = useUserStore(), denied = ref(false)
const canRead = computed(() => !denied.value && user.isAdmin && !!user.token)
const context = ref<HistoryContext | null>(null), loading = ref(false), contextError = ref('')
const filter = ref<HistoryFilter>('ALL'), page = ref(1), total = ref(0), records = ref<HistoryRecord[]>([]), recordsLoading = ref(false), recordsError = ref('')
const selectedRecordId = ref<string | null>(null), detail = ref<HistoryRecordDetail | null>(null), detailLoading = ref(false), detailError = ref('')
let epoch = 0, contextSequence = 0, listSequence = 0, detailSequence = 0
let contextController: AbortController | undefined, listController: AbortController | undefined, detailController: AbortController | undefined
const recordTypes: HistoryRecordType[] = ['MEMBER', 'ORDER', 'BOOK', 'PLAN']
const recordNames: Record<string, string> = { MEMBER: '会员原档', ORDER: '原始订单', BOOK: '图书原档', PLAN: '原借阅计划' }
function recordName(type: string) { return Object.prototype.hasOwnProperty.call(recordNames, type) ? recordNames[type] : `其他类型（原文：${raw(type)}）` }
const classifications = { IMPORTED: '迁入资料', COUNTER_ORDER: '新柜台订单', SYSTEM: '本系统', UNVERIFIED: '待核实' }
const associationStates = { VERIFIED: '有明确关联依据', NONE: '无来源关联', UNVERIFIED: '待核实' }
const entityName = computed(() => ({ members: '会员', books: '书目', borrows: '借阅明细' }[props.entityType]))
const labels: Record<keyof HistoryIdentity, string> = { memberId: '会员编号', username: '登录账号', memberName: '会员姓名', cardNo: '会员卡号原文', bookId: '书目编号', bookTitle: '当前登记书名', isbn: 'ISBN 原文', borrowId: '借阅明细编号', copyId: '实体册编号', barcode: '馆内条码原文', status: '当前状态原文', borrowDate: '当前借出日期', dueDate: '当前应还日期', returnDate: '当前归还日期', dueDateKind: '应还日期含义原值' }
const identityFields = computed(() => {
  const keys: (keyof HistoryIdentity)[] = props.entityType === 'members' ? ['memberId', 'username', 'memberName', 'cardNo'] : props.entityType === 'books' ? ['bookId', 'bookTitle', 'isbn'] : ['borrowId', 'memberId', 'username', 'memberName', 'cardNo', 'bookId', 'bookTitle', 'isbn', 'copyId', 'barcode', 'status', 'borrowDate', 'dueDate', 'returnDate', 'dueDateKind']
  return keys.map(key => ({ key, label: labels[key] }))
})
const linkedIds: { key: keyof HistoryContext['links']; label: string }[] = [{ key: 'memberId', label: '关联会员编号' }, { key: 'bookId', label: '关联书目编号' }, { key: 'copyId', label: '关联实体册编号' }, { key: 'counterOrderId', label: '新柜台订单编号' }, { key: 'memberArchiveId', label: '明确会员原档编号' }]
const archiveSummary = computed(() => context.value?.archiveTypes.map(item => `${recordName(item.recordType)} ${item.total} 条`).join('；') || '未发现明确关联的来源档案。')
const invalidJson = computed(() => { if (!detail.value) return false; try { JSON.parse(detail.value.detailsJson); return false } catch { return true } })
const sourceFields = computed(() => {
  if (!detail.value || invalidJson.value) return []
  const parsed: unknown = JSON.parse(detail.value.detailsJson)
  const entries = parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? Object.entries(parsed) : [['根值', parsed]]
  return entries.map(([key, value]) => ({ key: String(key), type: value === null ? 'null 原值' : Array.isArray(value) ? '数组' : typeof value === 'object' ? '嵌套对象' : typeof value === 'string' ? '文本' : typeof value === 'number' ? 'JSON 数值投影' : '布尔值', value: typeof value === 'string' ? raw(value) : JSON.stringify(value, null, 2) }))
})
function raw(value: string | null) { return value === null ? '未记录' : value === '' ? '空字符串（原文）' : value }
function clearDetail() { ++detailSequence; detailController?.abort(); selectedRecordId.value = null; detail.value = null; detailError.value = ''; detailLoading.value = false }
function clear() {
  ++epoch; ++contextSequence; ++listSequence; contextController?.abort(); listController?.abort(); clearDetail()
  context.value = null; contextError.value = ''; loading.value = false; filter.value = 'ALL'; page.value = 1; total.value = 0; records.value = []; recordsError.value = ''; recordsLoading.value = false
}
function close(value = false) { if (value) return; clear(); emit('update:visible', false) }
function accessInvalidated() { denied.value = true; close() }
function target() {
  const id = historyId(props.entityId)
  if (!canRead.value || !props.visible || !id) return null
  return { id, entity: props.entityType, token: user.token, actor: user.userInfo?.id, epoch }
}
type Target = NonNullable<ReturnType<typeof target>>
function current(request: Target) { return canRead.value && props.visible && request.epoch === epoch && request.entity === props.entityType && request.id === historyId(props.entityId) && request.token === user.token && request.actor === user.userInfo?.id }
function errorText(cause: unknown) { return cause instanceof HistoryReadError ? cause.message : '历史资料读取失败，请重试；读取失败不表示没有资料。' }
function revoked(cause: unknown) { if (cause instanceof HistoryReadError && (cause.status === 401 || cause.status === 403)) { accessInvalidated(); return true } return false }
async function loadContext() {
  const request = target(); if (!request) return
  const sequence = ++contextSequence; contextController?.abort(); contextController = new AbortController()
  context.value = null; contextError.value = ''; loading.value = true
  try {
    const value = await createHistoryContextApi(request.token).context(request.entity, request.id, contextController.signal)
    if (!current(request) || sequence !== contextSequence) return
    context.value = value; await loadRecords()
  } catch (cause) { if (current(request) && sequence === contextSequence && !revoked(cause)) contextError.value = errorText(cause) }
  finally { if (current(request) && sequence === contextSequence) loading.value = false }
}
async function loadRecords() {
  const request = target(); if (!request || !context.value) return
  const sequence = ++listSequence; listController?.abort(); listController = new AbortController(); clearDetail()
  records.value = []; total.value = 0; recordsLoading.value = true; recordsError.value = ''
  try {
    const value = await createHistoryContextApi(request.token).records(request.entity, request.id, { page: page.value, size: 20, type: filter.value }, listController.signal)
    if (!current(request) || sequence !== listSequence) return
    records.value = value.records; total.value = value.total
  } catch (cause) { if (current(request) && sequence === listSequence && !revoked(cause)) recordsError.value = errorText(cause) }
  finally { if (current(request) && sequence === listSequence) recordsLoading.value = false }
}
function changeFilter(value: HistoryFilter) { if (!['ALL', ...recordTypes].includes(value)) return; filter.value = value; page.value = 1; loadRecords() }
function changePage(value: number) { if (!Number.isSafeInteger(value) || value < 1 || value === page.value) return; page.value = value; loadRecords() }
async function showRecord(id: string) {
  const request = target(); if (!request || !records.value.some(record => record.id === id)) return
  clearDetail(); selectedRecordId.value = id; detailLoading.value = true; detailController = new AbortController(); const sequence = detailSequence
  try {
    const value = await createHistoryContextApi(request.token).record(request.entity, request.id, id, detailController.signal)
    if (current(request) && sequence === detailSequence) detail.value = value
  } catch (cause) { if (current(request) && sequence === detailSequence && !revoked(cause)) detailError.value = errorText(cause) }
  finally { if (current(request) && sequence === detailSequence) detailLoading.value = false }
}
watch(() => [user.userInfo?.id, user.userInfo?.role, user.token, user.isAdmin], () => { denied.value = false; close() }, { flush: 'sync' })
watch(() => [props.visible, props.entityType, props.entityId], () => { clear(); if (props.visible && canRead.value) loadContext() }, { immediate: true, flush: 'sync' })
window.addEventListener('auth-access-invalidated', accessInvalidated)
onBeforeUnmount(() => { clear(); window.removeEventListener('auth-access-invalidated', accessInvalidated) })
</script>

<style scoped>
.history-context { line-height: 1.7; }
section { margin-bottom: 24px; }
h3 { margin: 0 0 12px; font-size: 16px; }
h4 { margin: 0 0 8px; font-size: 14px; }
.facts { display: grid; grid-template-columns: minmax(100px, 150px) minmax(0, 1fr); gap: 6px 12px; }
dt { color: var(--text-secondary); } dd { margin: 0; }
.raw { white-space: pre-wrap; overflow-wrap: anywhere; }
.note { color: var(--text-secondary); font-size: 13px; }
.warning { color: var(--el-color-warning-dark-2); }
.filter { display: flex; align-items: center; gap: 12px; margin: 12px 0; }
select { font: inherit; padding: 5px; }
.archive-list { padding: 0; list-style: none; }
.archive-list > li { border: 1px solid var(--el-border-color); padding: 12px; margin-bottom: 12px; border-radius: 6px; }
.original-record { margin-top: 24px; }.original-text { padding: 12px; background: var(--el-fill-color-light); font-size: 13px; }
.source-value { margin: 2px 0; font: inherit; }small { color: var(--text-secondary); }
</style>
