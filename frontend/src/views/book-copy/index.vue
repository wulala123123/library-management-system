<template>
  <div v-if="canRead">
    <div class="search-form">
      <el-form inline @submit.prevent="search">
        <el-form-item label="关键词"><el-input v-model="query.keyword" :placeholder="tab === 'catalog' ? '书名 / ISBN / 作者' : '书名 / ISBN / 作者 / 条码'" clearable @keyup.enter="search" /></el-form-item>
        <el-form-item v-if="tab === 'copies'" label="精确条码"><el-input v-model="query.barcode" placeholder="完整条码，保留前导零" clearable @keyup.enter="search" /></el-form-item>
        <el-form-item v-if="tab === 'copies'" label="册状态"><el-select v-model="query.status" clearable placeholder="全部状态" style="width: 160px"><el-option value="AVAILABLE" label="可借" /><el-option value="BORROWED" label="在借" /><el-option value="DISABLED" label="受控临时停用" /><el-option value="WRITTEN_OFF" label="受控已报损" /><el-option value="UNKNOWN" label="原状态待核实" /><el-option value="OTHER" label="全部非可借/在借状态" /></el-select></el-form-item>
        <el-form-item v-if="tab === 'copies'" label="册位置"><el-input v-model="query.location" :disabled="query.unassignedLocation" placeholder="实体册实际位置" clearable /></el-form-item>
        <el-form-item v-if="tab === 'copies'"><el-checkbox v-model="query.unassignedLocation" @change="query.location = ''">仅未分配位置</el-checkbox></el-form-item>
        <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <el-tag v-if="query.bookId" closable @close="query.bookId = undefined; search()">已限定书目 ID {{ query.bookId }}</el-tag>
    </div>
    <div class="card-container">
      <el-tabs v-model="tab" @tab-change="search"><el-tab-pane label="书目与登记差额" name="catalog" /><el-tab-pane label="实体册" name="copies" /></el-tabs>
      <p class="note">书目与每本实体册分别管理。原始登记口径含报损保留行，旧差额与其他状态计数可能包含已知停用或报损；当前是否待核实见在库口径与状态来源。历史差额保留，源位置为空继续显示未分配。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <div v-if="tab === 'copies'" class="action-toolbar"><span>本页已明确选择 {{ selectedIds.length }} 册（最多 100 册）</span><el-button type="primary" :disabled="!selectedIds.length || selectedIds.length > 100" @click="startAction(selectedIds)">办理所选实体册</el-button></div>
      <el-table v-if="tab === 'catalog'" :data="catalog" v-loading="loading" stripe border>
        <el-table-column label="书目" min-width="210"><template #default="{ row }"><strong>{{ row.book.title }}</strong><p class="note">ISBN：{{ displayValue(row.book.isbn) }}</p></template></el-table-column>
        <el-table-column label="汇总总 / 可借" width="140"><template #default="{ row }">{{ row.totalCopies }} / {{ row.availableCopies }} 册</template></el-table-column>
        <el-table-column label="原始登记总 / 可借 / 在借 / 其他" min-width="250"><template #default="{ row }">{{ row.registeredCopies }} / {{ row.registeredAvailableCopies }} / {{ row.registeredBorrowedCopies }} / {{ row.registeredOtherCopies }} 册</template></el-table-column>
        <el-table-column prop="borrowedCopies" label="在借明细（册）" width="140" />
        <el-table-column label="原始登记口径差额：总 / 可借 / 在借" min-width="260"><template #default="{ row }">{{ row.totalDifference }} / {{ row.availableDifference }} / {{ row.borrowedDifference }}</template></el-table-column>
        <el-table-column prop="stockBalanceDifference" label="原始登记口径平衡差额" width="190" />
        <el-table-column label="在库口径与状态来源" min-width="390"><template #default="{ row }"><CopyReconciliationV2 :value="row.reconciliationV2" /><span v-if="!row.reconciliationV2">在库口径暂未返回，以上为全部历史登记口径。</span></template></el-table-column>
        <el-table-column label="核对" width="100"><template #default="{ row }"><el-tag :type="row.needsVerification ? 'warning' : 'success'">{{ row.needsVerification ? '待核实' : '数量一致' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120"><template #default="{ row }"><el-button link type="primary" @click="showCopies(row.book.id)">查看实体册</el-button></template></el-table-column>
      </el-table>
      <el-table v-else :data="copies" v-loading="loading" stripe border row-key="id" @selection-change="selectCopies">
        <el-table-column type="selection" width="45" />
        <el-table-column label="馆内条码原文" min-width="180"><template #default="{ row }"><code>{{ rawText(row.barcode) }}</code></template></el-table-column>
        <el-table-column label="书目" min-width="200"><template #default="{ row }">{{ row.book.title }}</template></el-table-column>
        <el-table-column label="ISBN" min-width="150"><template #default="{ row }">{{ displayValue(row.book.isbn) }}</template></el-table-column>
        <el-table-column label="册状态" min-width="160"><template #default="{ row }">{{ stateText(row) }}</template></el-table-column>
        <el-table-column label="册位置" min-width="150"><template #default="{ row }">{{ displayValue(row.location, '未分配') }}</template></el-table-column>
        <el-table-column label="操作" width="100"><template #default="{ row }"><el-button link type="primary" @click="showDetail(row.id)">查看单册</el-button></template></el-table-column>
      </el-table>
      <div class="pagination-wrapper"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10, 20, 50, 100]" layout="total, sizes, prev, pager, next" @change="fetchRows" /></div>
    </div>
    <el-dialog v-model="detailVisible" title="实体册详情" width="min(900px, 96vw)">
      <div v-loading="detailLoading" v-if="detail">
        <el-alert v-if="detail.needsVerification" title="该册状态或借阅关联需要核实，查询不会自动修正。" type="warning" :closable="false" />
        <el-descriptions :column="2" border>
          <el-descriptions-item label="馆内条码">{{ detail.copy.barcode }}</el-descriptions-item>
          <el-descriptions-item label="书目">{{ detail.copy.book.title }}</el-descriptions-item>
          <el-descriptions-item label="册状态">{{ stateText(detail.copy) }}</el-descriptions-item>
          <el-descriptions-item label="册实际位置">{{ displayValue(detail.copy.location, '未分配') }}</el-descriptions-item>
          <el-descriptions-item label="书目位置">{{ displayValue(detail.copy.book.location, '未设置') }}</el-descriptions-item>
          <el-descriptions-item label="出版日期 / 页数">{{ displayValue(detail.copy.book.publishDate, '待补充') }} / {{ displayValue(detail.copy.book.pages, '待补充') }}</el-descriptions-item>
        </el-descriptions>
        <el-button type="primary" @click="startAction([detail.copy.id])">办理此册状态或位置</el-button>
        <CopyReconciliationV2 :value="detail.reconciliationV2" />
        <h3>当前在借关联</h3>
        <el-table :data="detail.currentLoans" border>
          <el-table-column label="会员姓名" min-width="130"><template #default="{ row }">{{ displayValue(row.memberName) }}</template></el-table-column>
          <el-table-column label="卡号" min-width="130"><template #default="{ row }">{{ displayValue(row.cardNo) }}</template></el-table-column>
          <el-table-column label="借出日" width="130"><template #default="{ row }">{{ displayValue(row.borrowDate, '原记录未提供') }}</template></el-table-column>
          <el-table-column label="应还日" width="130"><template #default="{ row }">{{ dueDateText(row) }}</template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.status === 'OVERDUE' ? '已逾期' : '借阅中' }}</template></el-table-column>
          <el-table-column label="书目关联" width="110"><template #default="{ row }">{{ row.bookMatches ? '一致' : '待核实' }}</template></el-table-column>
        </el-table>
        <CopyActionHistory :copy-id="detail.copy.id" :visible="detailVisible" :revision="historyRevision" />
      </div>
      <p v-else-if="!detailLoading">{{ detailError || '暂不可用' }}</p>
    </el-dialog>
    <CopyActionDialog v-model:visible="actionVisible" :copy-ids="actionCopyIds" @success="actionSuccess" />
  </div>
  <el-empty v-else description="仅管理员和馆员可查询实体册。" />
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { getBookCopies, getBookCopy, getCatalogCopySummary, type CopyRow, type CopyDetail, type CatalogCopySummary } from '@/api/bookCopyQuery'
import { displayValue } from '@/utils/display'
import { dueDateText } from '@/utils/borrowDates'
import CopyActionDialog from './components/CopyActionDialog.vue'
import CopyActionHistory from './components/CopyActionHistory.vue'
import CopyReconciliationV2 from './components/CopyReconciliationV2.vue'
import { copyStateText, rawText } from './components/copyActionPresentation'
const store = useUserStore(), route = useRoute()
const canRead = computed(() => store.isAdmin || store.isLibrarian)
const bookId = Number(route.query.bookId)
const query = reactive({ page: 1, size: 20, keyword: '', barcode: '', status: '', location: '', unassignedLocation: false, bookId: Number.isSafeInteger(bookId) && bookId > 0 ? bookId : undefined as number | undefined })
const tab = ref('catalog'), copies = ref<CopyRow[]>([]), catalog = ref<CatalogCopySummary[]>([]), total = ref(0), loading = ref(false), error = ref('')
const detailVisible = ref(false), detail = ref<CopyDetail | null>(null), detailLoading = ref(false), detailError = ref('')
let request = 0, detailRequest = 0
const selectedIds = ref<number[]>([]), actionCopyIds = ref<number[]>([]), actionVisible = ref(false), historyRevision = ref(0)
const stateText = (copy: CopyRow) => copyStateText(copy.status, copy.stateClassification === 'DISABLED' || copy.stateClassification === 'WRITTEN_OFF')
function selectCopies(rows: CopyRow[]) { selectedIds.value = rows.map(row => row.id) }
function startAction(ids: number[]) { if (canRead.value && ids.length && ids.length <= 100) { actionCopyIds.value = [...ids]; actionVisible.value = true } }
function actionSuccess() { ++historyRevision.value; fetchRows(); if (detailVisible.value && detail.value) showDetail(detail.value.copy.id) }
async function fetchRows() {
  if (!canRead.value) return
  selectedIds.value = []
  const number = ++request, targetTab = tab.value; loading.value = true; error.value = ''
  try {
    const common = { page: query.page, size: query.size, keyword: query.keyword.trim() || undefined, bookId: query.bookId }
    if (targetTab === 'catalog') { const result = await getCatalogCopySummary(common); if (number === request) { catalog.value = result.records; total.value = result.total } }
    else { const result = await getBookCopies({ ...common, barcode: query.barcode || undefined, status: query.status || undefined, location: query.unassignedLocation ? undefined : query.location || undefined, unassignedLocation: query.unassignedLocation || undefined }); if (number === request) { copies.value = result.records; total.value = result.total } }
  } catch { if (number === request) { catalog.value = []; copies.value = []; total.value = 0; error.value = '查询失败，请检查筛选条件后重试。' } }
  finally { if (number === request) loading.value = false }
}
function search() { query.page = 1; fetchRows() }
function reset() { Object.assign(query, { page: 1, keyword: '', barcode: '', status: '', location: '', unassignedLocation: false, bookId: undefined }); fetchRows() }
function showCopies(id: number) { query.bookId = id; tab.value = 'copies'; search() }
async function showDetail(id: number) {
  if (!canRead.value) return
  const number = ++detailRequest; detail.value = null; detailVisible.value = true; detailLoading.value = true; detailError.value = ''
  try { const value = await getBookCopy(id); if (number === detailRequest) detail.value = value }
  catch { if (number === detailRequest) detailError.value = '实体册详情读取失败，请重试。' }
  finally { if (number === detailRequest) detailLoading.value = false }
}
onMounted(fetchRows)
</script>
<style scoped>
.note { font-size: 13px; color: var(--text-secondary); line-height: 1.7; margin: 8px 0; } h3 { font-size: 16px; margin-top: 20px; }
.action-toolbar { display: flex; gap: 12px; align-items: center; margin: 12px 0; flex-wrap: wrap; }code { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
