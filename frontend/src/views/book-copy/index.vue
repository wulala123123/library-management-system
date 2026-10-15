<template>
  <div v-if="canRead">
    <div class="search-form">
      <el-form inline @submit.prevent="search">
        <el-form-item label="关键词"><el-input v-model="query.keyword" placeholder="书名 / ISBN / 作者 / 条码" clearable @keyup.enter="search" /></el-form-item>
        <el-form-item v-if="tab === 'copies'" label="精确条码"><el-input v-model="query.barcode" placeholder="完整条码，保留前导零" clearable @keyup.enter="search" /></el-form-item>
        <el-form-item v-if="tab === 'copies'" label="册状态"><el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px"><el-option value="AVAILABLE" label="可借" /><el-option value="BORROWED" label="在借" /><el-option value="OTHER" label="其他原状态" /></el-select></el-form-item>
        <el-form-item v-if="tab === 'copies'" label="册位置"><el-input v-model="query.location" :disabled="query.unassignedLocation" placeholder="实体册实际位置" clearable /></el-form-item>
        <el-form-item v-if="tab === 'copies'"><el-checkbox v-model="query.unassignedLocation" @change="query.location = ''">仅未分配位置</el-checkbox></el-form-item>
        <el-form-item><el-button type="primary" @click="search">查询</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
      <el-tag v-if="query.bookId" closable @close="query.bookId = undefined; search()">已限定书目 ID {{ query.bookId }}</el-tag>
    </div>
    <div class="card-container">
      <el-tabs v-model="tab" @tab-change="search"><el-tab-pane label="书目与登记差额" name="catalog" /><el-tab-pane label="实体册" name="copies" /></el-tabs>
      <p class="note">书目与每本实体册分别管理。汇总差额只作为待核实线索，不会自动造册或改库存；源位置为空继续显示未分配。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <el-table v-if="tab === 'catalog'" :data="catalog" v-loading="loading" stripe border>
        <el-table-column label="书目" min-width="210"><template #default="{ row }"><strong>{{ row.book.title }}</strong><p class="note">ISBN：{{ displayValue(row.book.isbn) }}</p></template></el-table-column>
        <el-table-column label="汇总总 / 可借" width="140"><template #default="{ row }">{{ row.totalCopies }} / {{ row.availableCopies }} 册</template></el-table-column>
        <el-table-column label="登记总 / 可借 / 在借 / 其他" min-width="230"><template #default="{ row }">{{ row.registeredCopies }} / {{ row.registeredAvailableCopies }} / {{ row.registeredBorrowedCopies }} / {{ row.registeredOtherCopies }} 册</template></el-table-column>
        <el-table-column prop="borrowedCopies" label="在借明细（册）" width="140" />
        <el-table-column label="总数 / 可借 / 在借差额" min-width="190"><template #default="{ row }">{{ row.totalDifference }} / {{ row.availableDifference }} / {{ row.borrowedDifference }}</template></el-table-column>
        <el-table-column prop="stockBalanceDifference" label="汇总平衡差额" width="140" />
        <el-table-column label="核对" width="100"><template #default="{ row }"><el-tag :type="row.needsVerification ? 'warning' : 'success'">{{ row.needsVerification ? '待核实' : '数量一致' }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="120"><template #default="{ row }"><el-button link type="primary" @click="showCopies(row.book.id)">查看实体册</el-button></template></el-table-column>
      </el-table>
      <el-table v-else :data="copies" v-loading="loading" stripe border>
        <el-table-column prop="barcode" label="馆内条码" min-width="150" />
        <el-table-column label="书目" min-width="200"><template #default="{ row }">{{ row.book.title }}</template></el-table-column>
        <el-table-column label="ISBN" min-width="150"><template #default="{ row }">{{ displayValue(row.book.isbn) }}</template></el-table-column>
        <el-table-column label="册状态" min-width="110"><template #default="{ row }">{{ stateText(row.status) }}</template></el-table-column>
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
          <el-descriptions-item label="册状态">{{ stateText(detail.copy.status) }}</el-descriptions-item>
          <el-descriptions-item label="册实际位置">{{ displayValue(detail.copy.location, '未分配') }}</el-descriptions-item>
          <el-descriptions-item label="书目位置">{{ displayValue(detail.copy.book.location, '未设置') }}</el-descriptions-item>
          <el-descriptions-item label="出版日期 / 页数">{{ displayValue(detail.copy.book.publishDate, '待补充') }} / {{ displayValue(detail.copy.book.pages, '待补充') }}</el-descriptions-item>
        </el-descriptions>
        <h3>当前在借关联</h3>
        <el-table :data="detail.currentLoans" border>
          <el-table-column label="会员姓名" min-width="130"><template #default="{ row }">{{ displayValue(row.memberName) }}</template></el-table-column>
          <el-table-column label="卡号" min-width="130"><template #default="{ row }">{{ displayValue(row.cardNo) }}</template></el-table-column>
          <el-table-column label="借出日" width="130"><template #default="{ row }">{{ displayValue(row.borrowDate, '原记录未提供') }}</template></el-table-column>
          <el-table-column label="应还日" width="130"><template #default="{ row }">{{ dueDateText(row) }}</template></el-table-column>
          <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.status === 'OVERDUE' ? '已逾期' : '借阅中' }}</template></el-table-column>
          <el-table-column label="书目关联" width="110"><template #default="{ row }">{{ row.bookMatches ? '一致' : '待核实' }}</template></el-table-column>
        </el-table>
      </div>
      <p v-else-if="!detailLoading">{{ detailError || '暂不可用' }}</p>
    </el-dialog>
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
const store = useUserStore(), route = useRoute()
const canRead = computed(() => store.isAdmin || store.isLibrarian)
const bookId = Number(route.query.bookId)
const query = reactive({ page: 1, size: 20, keyword: '', barcode: '', status: '', location: '', unassignedLocation: false, bookId: Number.isSafeInteger(bookId) && bookId > 0 ? bookId : undefined as number | undefined })
const tab = ref('catalog'), copies = ref<CopyRow[]>([]), catalog = ref<CatalogCopySummary[]>([]), total = ref(0), loading = ref(false), error = ref('')
const detailVisible = ref(false), detail = ref<CopyDetail | null>(null), detailLoading = ref(false), detailError = ref('')
let request = 0, detailRequest = 0
const states: Record<string, string> = { AVAILABLE: '可借', BORROWED: '在借' }
const stateText = (state: string) => states[state] ?? `原状态：${state}`
async function fetchRows() {
  if (!canRead.value) return
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
</style>
