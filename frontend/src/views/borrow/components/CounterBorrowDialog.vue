<template>
  <el-dialog :model-value="visible && allowed" title="柜台多本借阅" width="min(960px, 96vw)" :close-on-click-modal="false" @close="close">
    <p>为一名读者明确选择实体册；本次最多 10 册。预览后核对全部条码和应还日期，再确认借出。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <fieldset :disabled="busy || !canStart">
      <el-form label-width="100px" @submit.prevent>
        <el-form-item label="选择读者">
          <el-select v-model="userId" aria-label="多本借阅读者" filterable remote placeholder="搜索姓名/手机号/卡号/登录账号" :remote-method="searchReaders" :loading="userLoading" style="width:100%">
            <el-option v-for="reader in readers" :key="reader.id" :value="reader.id" :label="readerLabel(reader)" />
          </el-select>
        </el-form-item>
        <el-form-item label="加入时天数"><el-input-number v-model="defaultDays" aria-label="加入时借阅天数" :min="1" :max="90" :precision="0" /><span class="hint">默认 30 天；每册可在清单中调整。</span></el-form-item>
        <el-form-item label="扫描馆内码">
          <el-input v-model="barcode" aria-label="借阅馆内条码" placeholder="完整条码，保留前导零和首尾空格" @keyup.enter="scan" />
          <el-button :loading="scanning" :disabled="scanning || !barcode.length" @click="scan">按精确条码加入</el-button>
        </el-form-item>
        <el-form-item label="按书目选册">
          <el-select v-model="bookId" aria-label="多本借阅书目" filterable remote placeholder="搜索图书名称/ISBN" :remote-method="searchBooks" :loading="bookLoading" @change="loadCopies" style="width:100%">
            <el-option v-for="book in books" :key="book.id" :value="book.id" :label="`${book.displayTitle} · ${book.isbn ?? '未设置ISBN'}`" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="bookId" v-loading="copyLoading" class="copy-options">
        <p>明确选择下列实体册；登记书名与预览中的借出名称分别显示。</p>
        <div v-for="copy in copies" :key="copy.id" class="copy-option">
          <div><code>{{ quoted(copy.barcode) }}</code> · {{ stateText(copy) }}<CopyPlacement :placement="copy.placement" :raw-text="copy.location" :anomaly="copy.locationReferenceAnomaly" :show-raw="false" /></div>
          <el-button :disabled="copy.stateClassification !== 'AVAILABLE' || selected.some(row => row.copy.id === copy.id)" @click="addCopy(copy)">加入此册</el-button>
        </div>
        <el-pagination v-model:current-page="copyPage" :page-size="20" :total="copyTotal" layout="prev, pager, next" @change="loadCopies(bookId, false)" />
      </div>
      <h3>本次明确选择 {{ selected.length }} / 10 册</h3>
      <table class="basket"><thead><tr><th>实体册</th><th>借阅天数</th><th>操作</th></tr></thead><tbody>
        <tr v-for="row in selected" :key="row.copy.id"><td>登记书名：{{ row.copy.book.title }}<br /><code>{{ quoted(row.copy.barcode) }}</code><CopyPlacement :placement="row.copy.placement" :raw-text="row.copy.location" :anomaly="row.copy.locationReferenceAnomaly" :show-raw="false" /></td><td><el-input-number v-model="row.borrowDays" :aria-label="`实体册${row.copy.id}借阅天数`" :min="1" :max="90" :precision="0" /></td><td><el-button @click="remove(row.copy.id)">移除此册</el-button></td></tr>
      </tbody></table>
      <el-form label-width="100px"><el-form-item label="本次备注"><el-input v-model="remark" aria-label="多本借阅备注" type="textarea" :rows="2" placeholder="选填，最多 500 字，不含控制字符" /></el-form-item></el-form>
    </fieldset>
    <template #footer><el-button @click="close">关闭窗口</el-button><el-button type="primary" :disabled="!canPreview" @click="preview">预览本次全部借出</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { searchUsers, type BorrowerSummary } from '@/api/user'
import { getCatalogBooks, type CatalogBook } from '@/api/catalogAttribute'
import { getBookCopies as getBookCopyPage, type CopyRow } from '@/api/bookCopyQuery'
import CopyPlacement from '@/components/locations/CopyPlacement.vue'
import { copyStateText } from '@/views/book-copy/components/copyActionPresentation'
import { assertCirculationInput } from '../circulationProtocol'

const props = defineProps<{ visible: boolean; canStart: boolean; busy?: boolean }>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; preview: [value: { operation: 'BORROW'; userId: number; items: { bookId: number; copyId: number; borrowDays: number }[]; remark: string }] }>()
const user = useUserStore(), allowed = computed(() => user.isAdmin || user.isLibrarian)
const userId = ref<number>(), readers = ref<BorrowerSummary[]>([]), userLoading = ref(false)
const books = ref<CatalogBook[]>([]), bookId = ref<number>(), bookLoading = ref(false)
const copies = ref<CopyRow[]>([]), copyPage = ref(1), copyTotal = ref(0), copyLoading = ref(false)
const selected = reactive<{ copy: CopyRow; borrowDays: number | undefined }[]>([])
const defaultDays = ref<number | undefined>(30), barcode = ref(''), remark = ref(''), error = ref(''), scanning = ref(false)
let readerSequence = 0, bookSequence = 0, copySequence = 0, scanSequence = 0
const quoted = (text: string) => JSON.stringify(text)
const readerLabel = (reader: BorrowerSummary) => `${reader.realName || '未设置姓名'} · ${reader.username} · 卡号 ${reader.cardNo ?? '未分配'} · ${reader.phone ?? '未设置手机号'}`
const stateText = (copy: CopyRow) => copyStateText(copy.status, copy.stateClassification === 'DISABLED' || copy.stateClassification === 'WRITTEN_OFF')
const daysValid = (days: number | undefined): days is number => Number.isInteger(days) && days! >= 1 && days! <= 90
const canPreview = computed(() => props.canStart && !props.busy && allowed.value && !scanning.value && !copyLoading.value && !!userId.value && selected.length > 0 && selected.length <= 10 && selected.every(row => daysValid(row.borrowDays)))
function close() { emit('update:visible', false) }
watch(() => [user.userInfo?.id, user.userInfo?.role, user.token], () => { ++readerSequence; ++bookSequence; ++copySequence; ++scanSequence; readers.value = []; books.value = []; copies.value = []; selected.splice(0); userId.value = undefined; bookId.value = undefined; barcode.value = ''; remark.value = ''; error.value = ''; scanning.value = false; userLoading.value = false; bookLoading.value = false; copyLoading.value = false })
watch(() => props.visible, visible => { if (!visible) { ++readerSequence; ++bookSequence; ++copySequence; ++scanSequence; scanning.value = false; userLoading.value = false; bookLoading.value = false; copyLoading.value = false } })
async function searchReaders(keyword: string) {
  const sequence = ++readerSequence; if (!allowed.value || !props.visible || !keyword.trim()) { readers.value = []; userLoading.value = false; return }
  userLoading.value = true
  try { const result = await searchUsers(keyword.trim()); if (sequence === readerSequence && allowed.value && props.visible) readers.value = result.records }
  catch { if (sequence === readerSequence) error.value = '读者查询失败，请重试。' } finally { if (sequence === readerSequence) userLoading.value = false }
}
async function searchBooks(keyword: string) {
  const sequence = ++bookSequence; if (!allowed.value || !props.visible || !keyword.length) { books.value = []; bookLoading.value = false; return }
  bookLoading.value = true
  try { const result = await getCatalogBooks({ page: 1, size: 20, keyword }); if (sequence === bookSequence && allowed.value && props.visible) books.value = result.records }
  catch { if (sequence === bookSequence) error.value = '书目查询失败，请重试。' } finally { if (sequence === bookSequence) bookLoading.value = false }
}
async function loadCopies(id: number | undefined, reset = true) {
  const sequence = ++copySequence; copies.value = []; copyTotal.value = 0; if (!id || !allowed.value || !props.visible) { copyLoading.value = false; return }
  if (reset) copyPage.value = 1
  copyLoading.value = true
  try { const result = await getBookCopyPage({ page: copyPage.value, size: 20, bookId: id }); if (sequence === copySequence && bookId.value === id && allowed.value && props.visible) { copies.value = result.records; copyTotal.value = result.total } }
  catch { if (sequence === copySequence) error.value = '实体册读取失败，不能自动分配；请重试或扫描明确条码。' } finally { if (sequence === copySequence) copyLoading.value = false }
}
function addCopy(copy: CopyRow) {
  error.value = ''
  if (!props.canStart || props.busy || !allowed.value || !props.visible) return
  if (selected.some(row => row.copy.id === copy.id)) { error.value = '此实体册已在本次清单中。'; return }
  if (selected.some(row => row.copy.bookId === copy.bookId)) { error.value = '本次已选择同一书目的实体册，不能重复借阅同一书目。'; return }
  if (selected.length >= 10) { error.value = '本次最多明确选择 10 册。'; return }
  if (copy.stateClassification !== 'AVAILABLE' || copy.status !== 'AVAILABLE') { error.value = '此册不是已核实的可借实体册。'; return }
  if (!daysValid(defaultDays.value)) { error.value = '请填写 1 至 90 的整数借阅天数。'; return }
  selected.push({ copy, borrowDays: defaultDays.value })
}
async function scan() {
  if (scanning.value || !barcode.value.length || !allowed.value || !props.visible || !props.canStart || props.busy) return
  const sequence = ++scanSequence, raw = barcode.value; scanning.value = true; error.value = ''
  try {
    const result = await getBookCopyPage({ page: 1, size: 2, barcode: raw })
    if (sequence !== scanSequence || !allowed.value || !props.visible) return
    if (result.total !== 1 || result.records.length !== 1 || result.records[0]!.barcode !== raw) { error.value = '未找到唯一匹配的完整馆内条码，请核对原文。'; return }
    addCopy(result.records[0]!)
    if (!error.value && barcode.value === raw) barcode.value = ''
  } catch { if (sequence === scanSequence) error.value = '条码查询失败，未加入清单。' } finally { if (sequence === scanSequence) scanning.value = false }
}
function remove(id: number) { if (props.canStart && !props.busy) { const index = selected.findIndex(row => row.copy.id === id); if (index >= 0) selected.splice(index, 1) } }
function preview() {
  if (!canPreview.value || !userId.value) return
  const input = { operation: 'BORROW' as const, userId: userId.value, items: selected.map(row => ({ bookId: row.copy.bookId, copyId: row.copy.id, borrowDays: row.borrowDays! })), remark: remark.value }
  try { assertCirculationInput(input); emit('preview',input) } catch (cause) { error.value = (cause as Error).message }
}
</script>

<style scoped>
fieldset{border:0;margin:0;padding:0}.hint{margin-left:10px;color:#666}.copy-options{padding:12px;background:#f6f8fa}.copy-option{display:flex;align-items:center;justify-content:space-between;border-bottom:1px solid #ddd;padding:8px}.basket{width:100%;border-collapse:collapse;margin:12px 0}.basket th,.basket td{border:1px solid #ddd;padding:10px;text-align:left;vertical-align:top}code{white-space:pre-wrap;overflow-wrap:anywhere}
</style>
