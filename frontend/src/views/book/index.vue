<template>
  <div v-if="canManage" class="book-management">
    <div class="search-form">
      <el-form :model="queryParams" inline size="default">
        <el-form-item label="关键词">
          <el-input
            v-model="queryParams.keyword"
            placeholder="书名/作者/ISBN"
            clearable
            style="width: 240px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="分类">
          <el-select v-model="queryParams.categoryId" placeholder="全部分类" clearable style="width: 160px">
            <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="queryParams.status" placeholder="全部状态" clearable style="width: 120px">
            <el-option label="在馆" :value="1" />
            <el-option label="下架" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
      <CatalogAttributeFilters v-model="attributeFilters" @change="handleSearch" />
    </div>

    <div class="card-container">
      <div class="table-toolbar">
        <div class="toolbar-left">
          <el-button type="primary" @click="handleAdd">新书目与首批入库</el-button>
          <el-button @click="handleExport">导出本页书目</el-button>
          <el-button v-if="user.isAdmin" @click="openPublication(null)">批量出版资料补录</el-button>
        </div>
        <div class="toolbar-right"><span v-if="listReady">共 {{ total }} 种图书</span></div>
      </div>
      <AdminListExport v-if="user.isAdmin" kind="books" :filters="appliedFilters" :invalidation-key="[queryParams, attributeFilters]" :ready="listReady && !loading && !listError" :description="filterDescription" :unapplied="unapplied" />
      <el-alert v-if="listError" title="书目列表读取失败，请重试；失败不表示没有书目。" type="error" :closable="false" />

      <el-table v-loading="loading" :data="bookList" stripe border size="default">
        <el-table-column
          type="index"
          label="序号"
          :index="(index: number) => (queryParams.page - 1) * queryParams.size + index + 1"
          width="70"
          align="center"
        />
        <el-table-column label="封面" width="70" align="center">
          <template #default="{ row }">
            <el-image v-if="row.coverImage" :src="getImageUrl(row.coverImage)" fit="cover" style="width: 40px; height: 52px;" />
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="展示名称" min-width="230"><template #default="{ row }"><strong>{{ bookDisplayTitle(row) }}</strong><details><summary>登记及已采用原名</summary><p>登记书名：{{ row.catalogTitle }}</p><p>已采用来源原书名：{{ row.adoptedSourceTitle ?? '未设置' }}</p></details></template></el-table-column>
        <el-table-column label="装帧 / 年龄标签" min-width="210"><template #default="{ row }">{{ bindingText(row.bindingCode) }}<br />{{ ageText(row.ageLabels) }}</template></el-table-column>
        <el-table-column prop="author" label="作者" width="120" show-overflow-tooltip />
        <el-table-column prop="isbn" label="ISBN" width="140" show-overflow-tooltip />
        <el-table-column prop="publisher" label="出版社" width="140" show-overflow-tooltip />
        <el-table-column label="出版日期" width="120"><template #default="{ row }">{{ displayValue(row.publishDate?.split('T')[0]) }}</template></el-table-column>
        <el-table-column label="页数" width="85" align="center"><template #default="{ row }">{{ displayValue(row.pages) }}</template></el-table-column>
        <el-table-column label="库位" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ displayValue(row.location) }}</template></el-table-column>
        <el-table-column label="简介" min-width="160" show-overflow-tooltip><template #default="{ row }">{{ displayValue(row.description, '待补充') }}</template></el-table-column>
        <el-table-column prop="stockTotal" label="实体册总数（册）" width="140" align="center" />
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{ row.status === 1 ? '在馆' : '下架' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="590" align="center" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="handleEdit(row)">书目资料</el-button>
            <el-button type="primary" link size="small" @click="openPublication(row.id)">出版资料补录</el-button>
            <el-button type="primary" link size="small" @click="openIdentity(row.id)">出版社/分类身份</el-button>
            <el-button type="primary" link size="small" @click="openAttributes(row.id)">属性</el-button>
            <el-button type="primary" link size="small" @click="membershipBookId = row.id; membershipVisible = true">套装关系</el-button>
            <el-button v-if="user.isAdmin" type="primary" link size="small" @click="openSource(row.id)">来源核对</el-button>
            <el-button v-if="user.isAdmin" type="primary" link size="small" @click="openHistory(row.id)">历史资料</el-button>
            <el-button type="primary" link size="small" @click="handleIntake(row)">登记实体册</el-button>
            <el-button type="success" link size="small" @click="handleBorrow(row)">借阅</el-button>
            <el-button type="danger" link size="small" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div v-if="listReady && !loading && !listError" class="pagination-wrapper">
        <el-pagination
          v-model:current-page="queryParams.page"
          v-model:page-size="queryParams.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          @change="fetchBookList"
        />
      </div>
    </div>

    <BookSetMemberships v-model:visible="membershipVisible" :book-id="membershipBookId" />
    <BookDialog v-model:visible="dialogVisible" :book-id="editBookId" @success="fetchBookList" />
    <IntakeDialog v-model:visible="intakeVisible" :book-id="intakeBook?.id" :book-title="intakeBook?.catalogTitle" @success="fetchBookList" />
    <BookAttributeDialog v-model:visible="attributeVisible" :book-id="attributeBookId" @success="fetchBookList" />
    <CatalogIdentityDialog v-model:visible="identityVisible" :book-id="identityBookId" @success="fetchBookList" />
    <CatalogPublicationDialog v-model:visible="publicationVisible" :book-id="publicationBookId" :batch="publicationBookId === null" @success="fetchBookList" />
    <BookSourceAdoptionDialog v-if="user.isAdmin" v-model:visible="sourceVisible" :book-id="sourceBookId" @success="fetchBookList" />
    <HistoryContextDrawer v-if="user.isAdmin" v-model:visible="historyVisible" entity-type="books" :entity-id="historyBookId" />
  </div>
  <el-empty v-else description="仅管理员和馆员可管理书目与实体册。" />
</template>

<script setup lang="ts">
import { computed, ref, reactive, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import BookDialog from './components/BookDialog.vue'
import IntakeDialog from './components/IntakeDialog.vue'
import BookAttributeDialog from './components/BookAttributeDialog.vue'
import CatalogIdentityDialog from './components/CatalogIdentityDialog.vue'
import CatalogPublicationDialog from './components/CatalogPublicationDialog.vue'
import BookSetMemberships from '@/views/catalog-sets/BookSetMemberships.vue'
import BookSourceAdoptionDialog from './components/BookSourceAdoptionDialog.vue'
import CatalogAttributeFilters from '@/components/catalog/CatalogAttributeFilters.vue'
import HistoryContextDrawer from '@/components/HistoryContextDrawer.vue'
import { useUserStore } from '@/store/modules/user'
import { deleteBook } from '@/api/book'
import { type CatalogBook } from '@/api/catalogAttribute'
import { createManagementReportsApi, ManagementReadError } from '@/api/managementReports'
import AdminListExport from '@/components/statistics/AdminListExport.vue'
import { getCategoryList } from '@/api/category'
import type { BookCategory } from '@/types'
import { ageText, bindingText, bookDisplayTitle, catalogPageCsv, emptyAttributeFilters, attributeFilterQuery } from '@/utils/catalogAttributes'
import { displayValue } from '@/utils/display'
import { downloadCsv } from '@/utils/statistics'

const router = useRouter()
const user = useUserStore(), accessDenied = ref(false), canManage = computed(() => !accessDenied.value && (user.isAdmin || user.isLibrarian))

const loading = ref(false)
const bookList = ref<CatalogBook[]>([])
const total = ref(0)
const categories = ref<BookCategory[]>([])
const dialogVisible = ref(false)
const editBookId = ref<number | null>(null)
const intakeVisible = ref(false), intakeBook = ref<CatalogBook | null>(null)
const attributeVisible = ref(false), attributeBookId = ref<number | null>(null), sourceVisible = ref(false), sourceBookId = ref<number | null>(null), attributeFilters = ref(emptyAttributeFilters())
const identityVisible = ref(false), identityBookId = ref<number | null>(null)
const publicationVisible = ref(false), publicationBookId = ref<number | null>(null)
const membershipVisible = ref(false), membershipBookId = ref<number | null>(null)
const historyVisible = ref(false), historyBookId = ref<number | null>(null)
function openHistory(id: number) { if (user.isAdmin && user.token) { historyBookId.value = id; historyVisible.value = true } }
watch(() => [user.userInfo?.id, user.userInfo?.role, user.token, user.isAdmin], () => { historyVisible.value = false; historyBookId.value = null }, { flush: 'sync' })
function openPublication(id: number | null) { if (!canManage.value || (id === null && !user.isAdmin)) return; publicationBookId.value = id; publicationVisible.value = true }
function openAttributes(id: number) { attributeBookId.value = id; attributeVisible.value = true }
function openIdentity(id: number) { identityBookId.value = id; identityVisible.value = true }
function openSource(id: number) { if (user.isAdmin) { sourceBookId.value = id; sourceVisible.value = true } }

const queryParams = reactive({ page: 1, size: 10, keyword: '', categoryId: undefined as number | undefined, status: null as number | null })
const filtersSnapshot = () => ({ keyword: queryParams.keyword, categoryId: queryParams.categoryId || undefined, status: queryParams.status === 0 || queryParams.status === 1 ? queryParams.status : undefined, ...attributeFilterQuery(attributeFilters.value) })
const appliedFilters = ref(filtersSnapshot()), listReady = ref(false), listError = ref(false)
const unapplied = computed(() => JSON.stringify(filtersSnapshot()) !== JSON.stringify(appliedFilters.value))
const filterDescription = computed(() => { const filters = appliedFilters.value; return `关键词「${filters.keyword || '全部'}」 · 分类 ${filters.categoryId ? categories.value.find(category => category.id === filters.categoryId)?.name ?? `编号 ${filters.categoryId}` : '全部'} · 状态 ${filters.status === 0 ? '下架' : filters.status === 1 ? '在馆' : '全部'} · 装帧 ${filters.bindingState === 'UNKNOWN' ? '未设置' : filters.bindingCode ? bindingText(filters.bindingCode) : filters.bindingState === 'KNOWN' ? '已设置' : '全部'} · 年龄 ${filters.ageState === 'UNKNOWN' ? '未设置' : filters.ageLabels?.length ? ageText(filters.ageLabels) : filters.ageState === 'KNOWN' ? '已设置' : '全部'}` })

onMounted(() => {
  if (!canManage.value) return
  fetchBookList()
  fetchCategories()
})

async function fetchCategories() {
  try {
    categories.value = await getCategoryList() || []
  } catch {}
}

let listRequest = 0
let listController: AbortController | undefined
function clearList() { ++listRequest; listController?.abort(); bookList.value = []; total.value = 0; listReady.value = false; loading.value = false }
async function fetchBookList() {
  if (!canManage.value) return
  clearList(); const request = listRequest, owner = { id: user.userInfo?.id, role: user.userInfo?.role, token: user.token }
  const current = () => request === listRequest && canManage.value && owner.id === user.userInfo?.id && owner.role === user.userInfo?.role && owner.token === user.token
  listController = new AbortController(); loading.value = true; listError.value = false
  try {
    const res = await createManagementReportsApi(owner.token).listPage<CatalogBook>('books', { page: queryParams.page, size: queryParams.size, ...appliedFilters.value }, listController.signal)
    if (!current()) return
    bookList.value = res.records; total.value = res.total; listReady.value = true
  } catch (cause) {
    if (!current()) return
    listError.value = true
    if (cause instanceof ManagementReadError && (cause.status === 401 || cause.status === 403)) { clearList(); accessDenied.value = true }
  } finally {
    if (current()) loading.value = false
  }
}

function handleSearch() { appliedFilters.value = filtersSnapshot(); queryParams.page = 1; fetchBookList() }
function resetQuery() { Object.assign(queryParams, { page: 1, keyword: '', categoryId: undefined, status: null }); attributeFilters.value = emptyAttributeFilters(); handleSearch() }
watch(() => [user.userInfo?.id, user.userInfo?.role, user.token, user.isAdmin, user.isLibrarian], () => { clearList(); accessDenied.value = false; if (canManage.value) fetchBookList() }, { flush: 'sync' })
function invalidateAccess() { clearList(); accessDenied.value = true }
window.addEventListener('auth-access-invalidated', invalidateAccess)
onBeforeUnmount(() => { clearList(); window.removeEventListener('auth-access-invalidated', invalidateAccess) })
function handleAdd() { editBookId.value = null; dialogVisible.value = true }
function handleEdit(row: CatalogBook) { editBookId.value = row.id; dialogVisible.value = true }
function handleIntake(row: CatalogBook) { if (canManage.value) { intakeBook.value = row; intakeVisible.value = true } }
function handleBorrow(row: CatalogBook) { router.push({ path: '/borrow-code', query: { bookId: String(row.id) } }) }
function getImageUrl(url: string) {
  if (!url) return ''
  if (url.startsWith('http')) return url
  if (url.startsWith('/')) return url
  return '/' + url
}
function handleExport() {
  if (bookList.value.length === 0) {
    ElMessage.warning('暂无数据可导出')
    return
  }
  const blob = new Blob([catalogPageCsv(bookList.value)], { type: 'text/csv;charset=utf-8;' })
  downloadCsv(blob, '本页书目.csv')
  ElMessage.success('导出成功')
}
async function handleDelete(row: CatalogBook) {
  try {
    await ElMessageBox.confirm(`确定要删除图书"${bookDisplayTitle(row)}"吗？`, '警告', { type: 'warning' })
    await deleteBook(row.id)
    ElMessage.success('删除成功')
    fetchBookList()
  } catch {}
}
</script>

<style lang="scss" scoped>
.book-management .toolbar-left { display: flex; gap: 8px; }
</style>
