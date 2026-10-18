<template>
  <el-dialog :model-value="visible" :title="batch ? '批量出版资料补录' : '出版资料补录'" width="min(1080px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!locked" :before-close="beforeClose">
    <template v-if="canManage">
      <p v-if="intakeCompleted">入库已完成。此处只独立补录出版资料，失败后查询或重试原出版资料请求。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <p>只填写有依据的完整日期、页数和简介。没有可确认资料请保持未知；仅年/月信息写入引用说明。</p>
      <el-alert v-if="!canOperate" title="原请求包含批量书目；需要原操作者恢复管理员权限后查询，请保留原 key。" type="warning" :closable="false" />
      <template v-if="canOperate">
        <p v-if="operation.input.value">正在处理原请求书目 #{{ operation.input.value.entries.map(entry => entry.bookId).join('、#') }}。当前入口不替换原请求。</p>
        <fieldset v-if="!operation.input.value && !operation.uncertain.value" :disabled="locked || loading" class="publication-form">
          <legend>{{ batch ? '明确选择 1–100 种书目（2 种以上仅管理员）' : '单书出版资料' }}</legend>
          <section v-if="batch && store.isAdmin" class="selection">
            <p>逐种点击“选择书目”，跨页保留选择。本页列表不等于本批范围。</p>
            <div class="search-row"><input v-model="keyword" aria-label="出版补录搜索" placeholder="书名/作者/ISBN" @keyup.enter="search(1)" /><el-button :disabled="searching" @click="search(1)">搜索待补录书目</el-button></div>
            <p v-if="searchError" role="alert">{{ searchError }}</p><p v-if="searching">正在搜索…</p>
            <table><thead><tr><th>稳定书目 / 登记书名</th><th>ISBN</th><th>明确选择</th></tr></thead><tbody><tr v-for="book in results" :key="book.id"><td>#{{ book.id }} {{ book.displayTitle }}<br />登记：{{ book.catalogTitle }}</td><td>{{ publicationValue(book.isbn) }}</td><td><el-button :disabled="rows.some(row => row.book.bookId === book.id) || pendingIds.includes(book.id) || rows.length + pendingIds.length >= 100" @click="add(book.id)">{{ rows.some(row => row.book.bookId === book.id) ? '已选择' : '选择书目' }}</el-button></td></tr></tbody></table>
            <div class="search-row"><el-button :disabled="searching || page <= 1" @click="search(page - 1)">选书上一页</el-button><span>第 {{ page }} 页 · 共 {{ total }} 种</span><el-button :disabled="searching || page * 20 >= total" @click="search(page + 1)">选书下一页</el-button></div>
          </section>
          <p>已明确选择 {{ rows.length }} 种书目{{ pendingIds.length ? '，正在读取选中资料…' : '' }}</p>
          <section v-for="row in rows" :key="row.book.bookId" class="publication-row" :data-book-id="row.book.bookId">
            <h3>#{{ row.book.bookId }} {{ row.book.displayTitle }}</h3><p>登记书名：{{ row.book.catalogTitle }} · ISBN：{{ publicationValue(row.book.isbn) }} · 修订 {{ row.book.revision }}</p>
            <el-button v-if="batch" @click="rows = rows.filter(item => item.book.bookId !== row.book.bookId)">移除此书目</el-button>
            <div v-for="field in publicationFields" :key="field" class="field-row">
              <label>{{ publicationLabels[field] }} <select v-model="row.actions[field]" :aria-label="`${row.book.bookId} ${publicationLabels[field]}操作`"><option value="KEEP">保持原值</option><option value="SET">明确设置</option><option value="CLEAR">清除为 NULL</option></select></label>
              <p>当前值：<code>{{ publicationValue(row.book[field]) }}</code></p>
              <textarea v-if="field === 'description' && row.actions[field] === 'SET'" v-model="row.description" :aria-label="`${row.book.bookId} 简介值`" rows="4" placeholder="可明确设置为空串，允许换行和制表" />
              <input v-else-if="row.actions[field] === 'SET'" v-model="row[field]" :aria-label="`${row.book.bookId} ${publicationLabels[field]}值`" :placeholder="field === 'publishDate' ? 'YYYY-MM-DD（完整日期）' : '0–99999 整数，已知 0 请保留'" />
              <p v-if="row.actions[field] === 'CLEAR'">确认后此字段为 NULL（未知 / 未设置）。</p>
            </div>
            <label>馆员/管理员填写的依据 <select v-model="row.evidenceKind" :aria-label="`${row.book.bookId} 依据类型`"><option v-for="(label, kind) in evidenceLabels" :key="kind" :value="kind">{{ label }}</option></select></label>
            <input v-model="row.reference" class="single-line-text" :aria-label="`${row.book.bookId} 依据引用说明`" placeholder="单行 1–1000 码点；资料出处及核对说明，系统不自动核验外部来源" @paste="singleLinePaste($event, `书目 #${row.book.bookId} 依据引用说明`)" @keydown.enter.prevent="singleLineError(`书目 #${row.book.bookId} 依据引用说明`)" />
          </section>
          <label>本次办理原因<input v-model="reason" class="single-line-text" aria-label="出版补录原因" placeholder="单行 1–500 码点" @paste="singleLinePaste($event, '办理原因')" @keydown.enter.prevent="singleLineError('办理原因')" /></label>
          <p v-if="loading">正在读取出版资料…</p>
          <el-button v-if="!batch && !rows.length" @click="loadSingle">重新读取出版资料</el-button>
          <el-button type="primary" :disabled="!rows.length || loading || !!pendingIds.length" @click="preview">预览出版资料变更</el-button>
        </fieldset>
        <PublicationRequestPanel :session="operation" @reset="resetRequest" />
      </template>
    </template>
    <el-empty v-else description="当前账号无出版资料管理权限。" />
    <template #footer><el-button :disabled="canOperate && (operation.busy.value || operation.uncertain.value)" @click="close">关闭</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, shallowRef, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getCatalogBooks, type CatalogBook } from '@/api/catalogAttribute'
import { getPublicationBook, type PublicationEntry } from '@/api/catalogPublication'
import { evidenceLabels, publicationDraft, publicationEntry, publicationFields, publicationLabels, publicationValue, validPublicationText, type PublicationDraft } from './publicationDraft'
import { usePublicationSession } from './publicationSession'
import PublicationRequestPanel from './PublicationRequestPanel.vue'

const props = withDefaults(defineProps<{ visible: boolean; bookId?: number | null; batch?: boolean; intakeCompleted?: boolean }>(), { bookId: null, batch: false, intakeCompleted: false })
const emit = defineEmits<{ 'update:visible': [value: boolean]; success: [] }>()
const store = useUserStore()
const canManage = computed(() => !!store.userInfo?.id && (store.isAdmin || store.isLibrarian) && (!props.batch || store.isAdmin))
let generation = 0, searchRequest = 0, notified = ''
const operation = shallowRef(usePublicationSession(0, () => false))
const canOperate = computed(() => canManage.value && (store.isAdmin || (operation.value.input.value?.entries.length ?? 1) === 1))
const locked = computed(() => operation.value.busy.value || operation.value.uncertain.value || !!operation.value.input.value)
const rows = ref<PublicationDraft[]>([]), pendingIds = ref<number[]>([]), loading = ref(false), error = ref(''), reason = ref('')
const keyword = ref(''), searching = ref(false), searchError = ref(''), page = ref(1), total = ref(0), results = ref<CatalogBook[]>([])
function current(g: number) { return generation === g && props.visible && canManage.value }
async function search(target = 1) {
  if (!props.batch || !store.isAdmin || !canOperate.value || locked.value) return
  const g = generation, request = ++searchRequest; searching.value = true; searchError.value = ''
  try { const value = await getCatalogBooks({ keyword: keyword.value || undefined, page: target, size: 20 }); if (!current(g) || request !== searchRequest || locked.value) return; results.value = value.records; total.value = value.total; page.value = target }
  catch { if (current(g) && request === searchRequest) searchError.value = '选书搜索失败，已选择书目和输入保留。' }
  finally { if (g === generation && request === searchRequest) searching.value = false }
}
async function add(id: number, entry?: PublicationEntry) {
  if (!canOperate.value || locked.value || rows.value.some(row => row.book.bookId === id) || pendingIds.value.includes(id) || rows.value.length + pendingIds.value.length >= 100 || (!store.isAdmin && rows.value.length > 0)) return
  const g = generation; pendingIds.value.push(id)
  try {
    const value = await getPublicationBook(id)
    if (!current(g) || locked.value) return
    if (value.schemaVersion !== 1 || value.book.bookId !== id) throw new Error('书目身份不一致')
    const row = publicationDraft(value.book)
    if (entry) { row.reference = entry.evidence.reference; row.evidenceKind = entry.evidence.kind; for (const field of publicationFields) { const change = entry.changes[field]; row.actions[field] = change?.action ?? 'KEEP'; if (change?.action === 'SET') row[field] = String(change.value) } }
    rows.value.push(row)
  } catch { if (current(g)) error.value = `书目 #${id} 出版资料读取失败，未加入本批。请重试。` }
  finally { if (g === generation) pendingIds.value = pendingIds.value.filter(value => value !== id) }
}
async function loadSingle() { if (!props.bookId || !canOperate.value || locked.value) return; const g = generation; loading.value = true; try { await add(props.bookId) } finally { if (g === generation) loading.value = false } }
function preview() {
  if (!canOperate.value || locked.value || loading.value || pendingIds.value.length || !rows.value.length) return
  try {
    if (!validPublicationText(reason.value, 500, true)) throw new Error('请填写 1–500 码点的单行办理原因，不能含换行、制表或其他控制符。')
    const entries = rows.value.map(publicationEntry)
    if (!store.isAdmin && entries.length !== 1) throw new Error('馆员仅可办理单书出版资料。')
    error.value = ''; operation.value.preview({ operationKey: crypto.randomUUID(), mode: 'MANUAL', reason: reason.value, entries })
  } catch (cause) { error.value = (cause as Error).message }
}
function singleLineError(label: string) { error.value = `${label}须为单行，不能含换行、制表或其他控制符。` }
function singleLinePaste(event: ClipboardEvent, label: string) {
  const text = event.clipboardData?.getData('text/plain')
  if (text !== undefined && !validPublicationText(text, Number.MAX_SAFE_INTEGER)) { event.preventDefault(); singleLineError(label) }
}
async function resetRequest() {
  const previous = operation.value.input.value, saved = !!operation.value.receipt.value
  if (!operation.value.reset()) return
  rows.value = []; error.value = ''
  if (!saved && previous) { reason.value = previous.reason; for (const entry of previous.entries) await add(entry.bookId, entry) }
  else { reason.value = ''; if (props.batch) search(1); else loadSingle() }
}
watch(() => [props.visible, props.bookId, props.batch, store.userInfo?.id, store.isAdmin, store.isLibrarian], () => {
  const g = ++generation, owner = store.userInfo?.id ?? 0
  ++searchRequest; rows.value = []; pendingIds.value = []; results.value = []; total.value = 0; page.value = 1; error.value = reason.value = keyword.value = searchError.value = ''; loading.value = searching.value = false
  operation.value = usePublicationSession(owner, count => current(g) && store.userInfo?.id === owner && (store.isAdmin || (store.isLibrarian && count === 1)))
  if (!props.visible || !canManage.value) return
  operation.value.restore()
  if (operation.value.input.value) { if (canOperate.value) operation.value.recover(); return }
  if (operation.value.uncertain.value) return
  if (props.batch) search(1); else loadSingle()
}, { immediate: true })
watch(() => operation.value.receipt.value, value => { if (value && canOperate.value && value.operationKey !== notified) { notified = value.operationKey; emit('success') } })
function close() { if (canOperate.value && (operation.value.busy.value || operation.value.uncertain.value)) return; ++generation; emit('update:visible', false) }
function beforeClose(done: () => void) { if (canOperate.value && (operation.value.busy.value || operation.value.uncertain.value)) return; close(); done() }
</script>

<style scoped>
.publication-form { border: 0; padding: 0; min-width: 0; }
.publication-row { border: 1px solid #dcdfe6; padding: 16px; margin: 16px 0; }
.field-row { border-bottom: 1px solid #ebeef5; padding: 12px 0; }
.search-row { display: flex; align-items: center; gap: 10px; margin: 12px 0; }
input, select, textarea { font: inherit; color: inherit; padding: 8px; border: 1px solid #c0c4cc; border-radius: 4px; max-width: 100%; }
textarea { display: block; width: 100%; box-sizing: border-box; margin: 8px 0; }
.single-line-text { display: block; width: 100%; box-sizing: border-box; margin: 8px 0; }
code { white-space: pre-wrap; overflow-wrap: anywhere; }
table { width: 100%; border-collapse: collapse; }
th, td { text-align: left; border: 1px solid #dcdfe6; padding: 8px; }
</style>
