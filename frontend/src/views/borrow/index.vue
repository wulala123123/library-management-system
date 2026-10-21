<template>
  <div v-if="canRead" class="borrow-management">
    <div class="page-header">
      <h2>借阅管理</h2>
      <p>办理柜台多本借阅、查看订单，按明确明细归还；原单册入口与历史记录继续保留。</p>
    </div>

    <div class="card-container circulation-orders">
      <h3>柜台借阅订单</h3>
      <div class="table-toolbar">
        <div class="toolbar-left">
          <el-button type="primary" :disabled="!session?.canStart.value" @click="counterVisible = true">柜台多本借阅</el-button>
          <el-button :disabled="!session?.canStart.value" @click="openReturn([])">扫码 / 批量归还</el-button>
        </div>
      </div>
      <el-form inline @submit.prevent="searchOrders">
        <el-form-item label="读者"><el-select v-model="orderUserId" aria-label="订单读者筛选" filterable remote clearable placeholder="搜索姓名/手机号/卡号/登录账号" :remote-method="searchOrderReaders" :loading="orderReadersLoading" style="width:320px"><el-option v-for="reader in orderReaders" :key="reader.id" :value="reader.id" :label="`${reader.realName || '未设置姓名'} · ${reader.username} · 卡号 ${reader.cardNo ?? '未设置卡号'}`" /></el-select></el-form-item>
        <el-form-item label="订单状态"><el-select v-model="orderState" aria-label="订单状态筛选" clearable style="width:150px"><el-option label="在借" value="BORROWING" /><el-option label="部分归还" value="PARTIALLY_RETURNED" /><el-option label="全部归还" value="RETURNED" /></el-select></el-form-item>
        <el-form-item><el-button @click="searchOrders">查询订单</el-button><el-button @click="resetOrders">重置订单筛选</el-button></el-form-item>
      </el-form>
      <el-alert v-if="orderError" :title="orderError" type="error" :closable="false" />
      <el-table :data="orders" v-loading="ordersLoading" border>
        <el-table-column prop="orderId" label="订单编号" width="110" />
        <el-table-column label="读者" min-width="180"><template #default="{ row }">{{ row.userName ?? '原姓名未提供' }}<br />卡号 {{ row.cardNo ?? '未设置卡号' }}</template></el-table-column>
        <el-table-column prop="businessDate" label="借出业务日期" width="140" />
        <el-table-column label="册数" min-width="200"><template #default="{ row }">全部 {{ row.itemCount }} / 未还 {{ row.activeCount }} / 已还 {{ row.returnedCount }}</template></el-table-column>
        <el-table-column label="状态" width="120"><template #default="{ row }">{{ orderStateText(row.state) }}</template></el-table-column>
        <el-table-column label="操作" width="160"><template #default="{ row }"><el-button link type="primary" @click="showOrder(row.orderId)">查看订单 / 归还</el-button></template></el-table-column>
      </el-table>
      <div class="pagination-wrapper"><el-pagination v-model:current-page="orderPage" :page-size="20" :total="orderTotal" layout="total, prev, pager, next" @change="loadOrders" /></div>
      <p>这里只展示新柜台订单；既有借阅不按会员或日期自动拼成订单。</p>
    </div>
    <CirculationRequestPanel v-if="session" :key="session.ownerId" :session="session" @success="circulationSuccess" @acknowledged="draftEpoch++" />

    <div class="search-card">
      <h3>全部借阅明细</h3>
      <el-form :model="queryParams" inline size="default" class="search-form">
        <el-form-item label="关键词">
          <el-input v-model="queryParams.keyword" placeholder="搜索读者/图书名称/ISBN" clearable style="width: 240px" :prefix-icon="Search" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="借阅中" value="BORROWING" />
            <el-option label="已逾期" value="OVERDUE" />
            <el-option label="已归还" value="RETURNED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="card-container">
      <div class="table-toolbar">
        <div class="toolbar-left">
          <el-button type="primary" @click="handleBorrow"><el-icon><Plus /></el-icon> 办理借阅</el-button>
          <el-button :disabled="!session?.canStart.value || !returnSelection.length" @click="openReturn(returnSelection)">预览归还所选 {{ returnSelection.length }} 册</el-button>
        </div>
        <div class="toolbar-right">
          <el-tag type="info" effect="plain">共 {{ total }} 条记录</el-tag>
        </div>
      </div>

      <el-table v-loading="loading" :data="borrowList" stripe border size="default" row-key="id" row-class-name="borrow-row" style="width: 100%" @selection-change="selectReturns">
        <el-table-column type="selection" width="45" :selectable="(row: BorrowRecord) => row.status === 'BORROWING' || row.status === 'OVERDUE'" />
        <el-table-column prop="id" label="ID" width="65" align="center" />
        <el-table-column label="读者信息" min-width="140">
          <template #default="{ row }">
            <div class="user-cell">
              <strong>{{ row.userName }}</strong>
              <span class="sub">{{ row.userRealName || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="图书信息" min-width="200">
          <template #default="{ row }">
            <div class="book-cell">
              <div class="book-cover-mini" v-if="row.bookCoverImage">
                <img :src="getCoverUrl(row.bookCoverImage)" alt="" @error="(e: Event) => (e.target as HTMLImageElement).style.display = 'none'" />
              </div>
              <div class="book-text">
                <strong>{{ row.bookTitle }}</strong>
                <span class="isbn">{{ row.bookIsbn || '-' }}</span>
                <span v-if="row.copyBarcode" class="isbn">馆内条码：{{ row.copyBarcode }}</span>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="borrowDate" label="借阅日期" width="110">
          <template #default="{ row }">{{ formatDate(row.borrowDate) }}</template>
        </el-table-column>
        <el-table-column prop="dueDate" label="应还日期" width="120">
          <template #default="{ row }">
            <span :class="{ 'text-danger': isOverdue(row), 'text-warning': isDueSoon(row) }">
              {{ dueDateText(row) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="returnDate" label="归还日期" width="110">
          <template #default="{ row }">
            <span>{{ formatDate(row.returnDate) || '-' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="renewCount" label="续借" width="60" align="center">
          <template #default="{ row }">{{ row.renewCount }}/2</template>
        </el-table-column>
        <el-table-column prop="fineAmount" label="罚款" width="80" align="right">
          <template #default="{ row }">
            <span :class="{ 'text-danger': row.fineAmount > 0 }">¥{{ (row.fineAmount || 0).toFixed(2) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="95" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small" effect="dark">{{ getStatusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="250" align="center" fixed="right">
          <template #default="{ row }">
            <el-button v-if="user.isAdmin" type="primary" link size="small" @click="openHistory(row.id)">历史资料</el-button>
            <template v-if="row.status === 'BORROWING' || row.status === 'OVERDUE'">
              <el-button type="danger" link size="small" @click="handleReturn(row)">归还</el-button>
              <el-button
                type="primary"
                link
                size="small"
                :disabled="row.renewCount >= 2 || !row.dueDate"
                @click="handleRenew(row)"
              >续借</el-button>
            </template>
            <span v-else class="no-action">-</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.page"
          v-model:page-size="queryParams.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @change="fetchBorrowList"
        />
      </div>
    </div>

    <BorrowDialog v-model:visible="borrowDialogVisible" @success="fetchBorrowList" />
    <RenewDialog v-model:visible="renewDialogVisible" :record="renewRecord" show-reader @success="fetchBorrowList" />
    <CounterBorrowDialog :key="draftEpoch" v-model:visible="counterVisible" :can-start="session?.canStart.value ?? false" :busy="session?.busy.value" @preview="previewCirculation" />
    <ReturnDialog v-model:visible="returnVisible" :can-start="session?.canStart.value ?? false" :busy="session?.busy.value" :initial-records="initialReturns" @preview="previewCirculation" />
    <BorrowOrderDrawer v-model:visible="orderVisible" :order-id="selectedOrderId" :can-start="session?.canStart.value ?? false" :refresh="orderRefresh" @return-records="openReturn" />
    <HistoryContextDrawer v-if="user.isAdmin" v-model:visible="historyVisible" entity-type="borrows" :entity-id="historyBorrowId" />
  </div>
  <el-empty v-else description="仅当前有效的管理员和馆员可办理借还，请重新登录核对权限。" />
</template>

<script setup lang="ts">
import { ref, reactive, computed, shallowRef, watch, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus } from '@element-plus/icons-vue'
import { getBorrowPage, returnBook } from '@/api/borrow'
import type { BorrowRecord } from '@/types'
import BorrowDialog from './components/BorrowDialog.vue'
import RenewDialog from './components/RenewDialog.vue'
import { dueDateText } from '@/utils/borrowDates'
import { useUserStore } from '@/store/modules/user'
import { searchUsers, type BorrowerSummary } from '@/api/user'
import { createCirculationApi, type BorrowOrder, type CirculationInput, type OrderState, type ReturnSelection } from '@/api/circulation'
import { useCirculationSession, type CirculationSession } from './circulationSession'
import { circulationProblem } from './circulationProtocol'
import CounterBorrowDialog from './components/CounterBorrowDialog.vue'
import ReturnDialog from './components/ReturnDialog.vue'
import BorrowOrderDrawer from './components/BorrowOrderDrawer.vue'
import CirculationRequestPanel from './components/CirculationRequestPanel.vue'
import HistoryContextDrawer from '@/components/HistoryContextDrawer.vue'

const user = useUserStore(), accessDenied = ref(false), canRead = computed(() => !accessDenied.value && (user.isAdmin || user.isLibrarian))
const session = shallowRef<CirculationSession | null>(null)
const counterVisible = ref(false), returnVisible = ref(false), initialReturns = ref<ReturnSelection[]>([]), returnSelection = ref<ReturnSelection[]>([])
const draftEpoch = ref(0)
const orders = ref<BorrowOrder[]>([]), ordersLoading = ref(false), orderError = ref(''), orderPage = ref(1), orderTotal = ref(0)
const orderUserId = ref<number>(), orderState = ref<OrderState | ''>(''), orderReaders = ref<BorrowerSummary[]>([]), orderReadersLoading = ref(false)
const orderVisible = ref(false), selectedOrderId = ref<number | null>(null), orderRefresh = ref(0)
let orderSequence = 0, orderReaderSequence = 0, borrowSequence = 0

const loading = ref(false)
const borrowList = ref<BorrowRecord[]>([])
const total = ref(0)
const borrowDialogVisible = ref(false)
const renewDialogVisible = ref(false)
const renewRecord = ref<BorrowRecord | null>(null)
const historyVisible = ref(false), historyBorrowId = ref<number | null>(null)
function openHistory(id: number) { if (user.isAdmin && user.token && canRead.value) { historyBorrowId.value = id; historyVisible.value = true } }

const queryParams = reactive({ page: 1, size: 10, keyword: '', status: undefined as string | undefined })

watch(() => [user.userInfo?.id,user.userInfo?.role,user.token], () => {
  session.value?.dispose(); session.value = null; accessDenied.value = false
  ++orderSequence; ++borrowSequence; ++orderReaderSequence; orders.value = []; borrowList.value = []; total.value = 0; orderTotal.value = 0
  orderReaders.value = []; orderUserId.value = undefined; returnSelection.value = []; initialReturns.value = []; selectedOrderId.value = null
  counterVisible.value = false; returnVisible.value = false; orderVisible.value = false; borrowDialogVisible.value = false; renewDialogVisible.value = false
  historyVisible.value = false; historyBorrowId.value = null
  const actor = user.userInfo?.id, token = user.token
  if (canRead.value && actor && Number.isSafeInteger(actor)) {
    session.value = useCirculationSession(actor,() => canRead.value && user.userInfo?.id === actor && user.token === token,createCirculationApi(token))
    fetchBorrowList(); loadOrders()
  }
},{immediate:true})
function accessInvalidated() { accessDenied.value = true; ++orderSequence; ++borrowSequence; ++orderReaderSequence; orders.value = []; borrowList.value = []; orderReaders.value = []; returnSelection.value = []; initialReturns.value = []; counterVisible.value = false; returnVisible.value = false; orderVisible.value = false; historyVisible.value = false; historyBorrowId.value = null }
window.addEventListener('auth-access-invalidated',accessInvalidated)
onUnmounted(() => { session.value?.dispose(); window.removeEventListener('auth-access-invalidated',accessInvalidated) })

async function loadOrders() {
  if (!canRead.value) return
  const sequence = ++orderSequence, token = user.token; ordersLoading.value = true; orderError.value = ''
  try { const result = await createCirculationApi(token).orders({page:orderPage.value,size:20,userId:orderUserId.value,state:orderState.value || undefined}); if (sequence === orderSequence && canRead.value && token === user.token) { orders.value = result.records; orderTotal.value = result.total } }
  catch (cause) { if (sequence === orderSequence) { orders.value = []; orderTotal.value = 0; orderError.value = circulationProblem(cause).message } } finally { if (sequence === orderSequence) ordersLoading.value = false }
}
function searchOrders() { orderPage.value = 1; loadOrders() }
function resetOrders() { orderUserId.value = undefined; orderState.value = ''; orderReaders.value = []; searchOrders() }
async function searchOrderReaders(keyword:string) {
  const sequence = ++orderReaderSequence; if (!canRead.value || !keyword.trim()) { orderReaders.value = []; orderReadersLoading.value = false; return }
  orderReadersLoading.value = true
  try { const result = await searchUsers(keyword.trim()); if (sequence === orderReaderSequence && canRead.value) orderReaders.value = result.records }
  catch { if (sequence === orderReaderSequence) orderError.value = '读者查询失败，请重试。' } finally { if (sequence === orderReaderSequence) orderReadersLoading.value = false }
}
const orderStateText = (state:string) => state === 'RETURNED' ? '全部归还' : state === 'PARTIALLY_RETURNED' ? '部分归还' : '在借'
function showOrder(id:number) { selectedOrderId.value = id; orderVisible.value = true }
function selectReturns(rows:BorrowRecord[]) { returnSelection.value = rows.filter(row => row.status === 'BORROWING' || row.status === 'OVERDUE').map(row => ({id:row.id,bookTitle:row.bookTitle,copyBarcode:row.copyBarcode,userName:row.userName,dueDate:row.dueDate,dueDateKind:row.dueDateKind})) }
function openReturn(records:ReturnSelection[]) { if (!session.value?.canStart.value || !canRead.value) return; orderVisible.value = false; initialReturns.value = records.map(row => ({...row})); returnVisible.value = true }
async function previewCirculation(input:CirculationInput) { if (!session.value?.canStart.value) return; counterVisible.value = false; returnVisible.value = false; await session.value.start(input) }
function circulationSuccess() { returnSelection.value = []; initialReturns.value = []; ++orderRefresh.value; fetchBorrowList(); loadOrders() }

async function fetchBorrowList() {
  if (!canRead.value) return
  const sequence = ++borrowSequence
  returnSelection.value = []
  loading.value = true
  try {
    const res = await getBorrowPage(queryParams)
    if (sequence === borrowSequence && canRead.value) { borrowList.value = res?.records || []; total.value = res?.total || 0 }
  } catch {
    if (sequence === borrowSequence) { borrowList.value = []; total.value = 0 }
  } finally { if (sequence === borrowSequence) loading.value = false }
}

function handleSearch() { queryParams.page = 1; fetchBorrowList() }
function resetQuery() { Object.assign(queryParams, { page: 1, keyword: '', status: undefined }); fetchBorrowList() }

function handleBorrow() { borrowDialogVisible.value = true }

async function handleReturn(record: BorrowRecord) {
  try {
    await ElMessageBox.confirm(
      `确认归还《${record.bookTitle}》？\n读者：${record.userName}\n${isOverdue(record) ? '⚠️ 该书已逾期' : ''}`,
      '办理归还',
      { confirmButtonText: '确认归还', cancelButtonText: '取消', type: 'info', dangerouslyUseHTMLString: false },
    )
    await returnBook(record.id)
    ElMessage.success(`《${record.bookTitle}》归还成功`)
    fetchBorrowList()
  } catch {}
}

function handleRenew(record: BorrowRecord) {
  if (record.renewCount >= 2) { ElMessage.warning('续借次数已达上限'); return }
  renewRecord.value = record
  renewDialogVisible.value = true
}

function formatDate(dateStr?: string): string { if (!dateStr) return '-'; return dateStr.split('T')[0] }

function isOverdue(record: BorrowRecord): boolean {
  if (!record.dueDate) return false
  return record.status === 'OVERDUE' || (record.status === 'BORROWING' && new Date(record.dueDate) < new Date())
}

function isDueSoon(record: BorrowRecord): boolean {
  if (record.status !== 'BORROWING' || !record.dueDate) return false
  const diff = new Date(record.dueDate).getTime() - new Date().getTime()
  return diff > 0 && diff <= 3 * 24 * 60 * 60 * 1000
}

function getStatusType(status: string): string {
  const map: Record<string, string> = { BORROWING: '', RETURNED: 'success', OVERDUE: 'danger' }
  return map[status] || ''
}

function getStatusText(status: string): string {
  const map: Record<string, string> = { BORROWING: '借阅中', RETURNED: '已归还', OVERDUE: '逾期' }
  return map[status] || status
}

function getCoverUrl(url: string) {
  if (!url) return ''
  if (url.startsWith('http')) return url
  if (url.startsWith('/')) return url
  return '/' + url
}
</script>

<style lang="scss" scoped>
.borrow-management {
  .circulation-orders { margin-bottom: 20px; h3 { margin: 0 0 16px; } }
  .page-header {
    margin-bottom: 20px;
    h2 { font-size: 20px; font-weight: 700; color: #1c1917; margin-bottom: 4px; }
    p { font-size: 13px; color: #78716c; }
  }

  .search-card {
    background: #fff;
    border-radius: 14px;
    padding: 18px 22px;
    margin-bottom: 16px;
    box-shadow: var(--shadow-sm);
    border: 1px solid var(--border-light);
  }

  .search-form {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .card-container {
    background: #fff;
    border-radius: 14px;
    padding: 20px;
    box-shadow: var(--shadow-sm);
    border: 1px solid var(--border-light);
  }

  .table-toolbar {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 16px;

    .toolbar-left { display: flex; gap: 8px; }
  }

  .user-cell {
    display: flex;
    flex-direction: column;
    strong { font-size: 13px; color: #1c1917; }
    .sub { font-size: 11px; color: #a8a29e; }
  }

  .book-cell {
    display: flex;
    align-items: center;
    gap: 8px;

    .book-cover-mini {
      width: 32px;
      height: 42px;
      border-radius: 3px;
      overflow: hidden;
      background: #f5f5f4;
      flex-shrink: 0;
      img { width: 100%; height: 100%; object-fit: cover; }
    }

    .book-text {
      display: flex;
      flex-direction: column;
      strong { font-size: 13px; color: #1c1917; line-height: 1.3; }
      .isbn { font-size: 11px; color: #a8a29e; }
    }
  }

  .text-danger { color: var(--danger-color); font-weight: 600; }
  .text-warning { color: var(--warning-color); font-weight: 500; }
  .no-action { color: #d6d3d1; }

  .pagination-wrapper {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
  }
}
</style>
