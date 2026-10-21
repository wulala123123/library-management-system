<template>
  <div v-if="canRead" class="member-management">
    <div class="search-form">
      <el-form :model="query" inline @submit.prevent="search">
        <el-form-item label="关键词"><el-input v-model="query.keyword" placeholder="姓名/手机号/卡号/登录账号" clearable style="width: 250px" @keyup.enter="search" /></el-form-item>
        <el-form-item label="登录状态"><el-select v-model="query.accountStatus" clearable placeholder="全部登录状态" style="width: 150px"><el-option label="启用" :value="1" /><el-option label="禁用" :value="0" /></el-select></el-form-item>
        <el-form-item label="服务状态"><el-select v-model="query.serviceStatus" clearable placeholder="全部服务状态" style="width: 150px"><el-option v-for="state in serviceStates" :key="state" :label="serviceStateText(state)" :value="state" /></el-select></el-form-item>
        <el-form-item><el-button type="primary" @click="search">搜索</el-button><el-button @click="reset">重置</el-button></el-form-item>
      </el-form>
    </div>
    <div class="card-container">
      <div class="table-toolbar">
        <div class="toolbar-left"><el-button v-if="userStore.isAdmin" type="primary" @click="addMember">新增会员</el-button><el-button @click="fetchMembers">刷新</el-button><el-button v-if="userStore.isAdmin" @click="router.push('/users')">账号管理</el-button></div>
        <span v-if="listReady">共 {{ total }} 位会员</span>
      </div>
      <p class="note">{{ userStore.isAdmin ? '迁入与新建会员共用同一档案入口。' : '仅显示办理借还所需的会员资料，馆员不能编辑。' }}共享手机号的会员分别保留身份。</p>
      <AdminListExport v-if="userStore.isAdmin" kind="members" :filters="appliedFilters" :invalidation-key="query" :ready="listReady && !loading && !loadError" :description="filterDescription" :unapplied="unapplied" />
      <el-alert v-if="loadError" title="会员列表读取失败，请刷新重试。" type="error" :closable="false" />
      <el-table v-loading="loading" :data="members" stripe border>
        <el-table-column type="index" label="序号" width="65" :index="(index: number) => (query.page - 1) * query.size + index + 1" />
        <el-table-column prop="realName" label="姓名" min-width="110" />
        <el-table-column prop="username" label="登录账号" min-width="140" />
        <el-table-column label="卡号" min-width="130"><template #default="{ row }">{{ displayValue(row.cardNo) }}</template></el-table-column>
        <el-table-column label="手机号" width="140"><template #default="{ row }">{{ displayValue(row.phone) }}</template></el-table-column>
        <el-table-column label="登录状态" width="100"><template #default="{ row }">{{ row.accountStatus === 1 ? '启用' : row.accountStatus === 0 ? '禁用' : '待核实' }}</template></el-table-column>
        <el-table-column label="服务状态" width="100"><template #default="{ row }"><el-tag :type="serviceStateTag(row.serviceStatus)">{{ serviceStateText(row.serviceStatus) }}</el-tag></template></el-table-column>
        <el-table-column label="服务期限" min-width="220"><template #default="{ row }">{{ dateText(row.serviceStart) }} 至 {{ dateText(row.serviceEnd) }}</template></el-table-column>
        <el-table-column label="已用 / 总次数" min-width="140"><template #default="{ row }">{{ displayValue(row.usedUses, '待核实') }} / {{ totalUsesText(row.usageType, row.totalUses) }}</template></el-table-column>
        <el-table-column label="操作" width="100" fixed="right"><template #default="{ row }"><el-button type="primary" link @click="showDetail(row.id)">查看档案</el-button></template></el-table-column>
      </el-table>
      <div v-if="listReady && !loading && !loadError" class="pagination-wrapper"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" @change="fetchMembers" /></div>
    </div>
    <MemberDetailDialog v-model:visible="detailVisible" :member-id="selectedId" @edit="editMember" />
    <MemberFormDialog v-if="userStore.isAdmin" v-model:visible="formVisible" :member="editingMember" @saved="saved" />
  </div>
  <el-empty v-else description="无权访问会员管理，请在个人中心查看本人的会员卡。" />
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { type MemberDetail, type MemberSummary, type MemberServiceStatus } from '@/api/member'
import { createManagementReportsApi, ManagementReadError } from '@/api/managementReports'
import AdminListExport from '@/components/statistics/AdminListExport.vue'
import { useUserStore } from '@/store/modules/user'
import { displayValue } from '@/utils/display'
import { dateText, serviceStateTag, serviceStateText, totalUsesText } from './memberDisplay'
import MemberDetailDialog from './components/MemberDetailDialog.vue'
import MemberFormDialog from './components/MemberFormDialog.vue'
const userStore = useUserStore(), route = useRoute(), router = useRouter()
const accessDenied = ref(false), canRead = computed(() => !accessDenied.value && (userStore.isAdmin || userStore.isLibrarian))
const members = ref<MemberSummary[]>([]), total = ref(0), loading = ref(false), loadError = ref(false)
const query = reactive({ page: 1, size: 10, keyword: '', accountStatus: undefined as number | undefined, serviceStatus: undefined as MemberServiceStatus | undefined })
const filtersSnapshot = () => ({ keyword: query.keyword.trim(), accountStatus: query.accountStatus === 0 || query.accountStatus === 1 ? query.accountStatus : undefined, serviceStatus: query.serviceStatus || undefined })
const appliedFilters = ref(filtersSnapshot()), listReady = ref(false)
const unapplied = computed(() => JSON.stringify(filtersSnapshot()) !== JSON.stringify(appliedFilters.value))
const filterDescription = computed(() => `关键词「${appliedFilters.value.keyword || '全部'}」 · 登录状态 ${appliedFilters.value.accountStatus === 0 ? '禁用' : appliedFilters.value.accountStatus === 1 ? '启用' : '全部'} · 服务状态 ${appliedFilters.value.serviceStatus ? serviceStateText(appliedFilters.value.serviceStatus) : '全部'}`)
const serviceStates: MemberServiceStatus[] = ['WAITING', 'ACTIVE', 'EXPIRED', 'UNKNOWN']
const detailVisible = ref(false), formVisible = ref(false), selectedId = ref<number | null>(null), editingMember = ref<MemberDetail | null>(null)
let listRequest = 0
let listController: AbortController | undefined
function clearList() { ++listRequest; listController?.abort(); members.value = []; total.value = 0; listReady.value = false; loading.value = false }
async function fetchMembers() {
  if (!canRead.value) return
  clearList(); const request = listRequest, owner = { id: userStore.userInfo?.id, role: userStore.userInfo?.role, token: userStore.token }
  const current = () => request === listRequest && canRead.value && owner.id === userStore.userInfo?.id && owner.role === userStore.userInfo?.role && owner.token === userStore.token
  listController = new AbortController(); loading.value = true; loadError.value = false
  try {
    const result = await createManagementReportsApi(owner.token).listPage<MemberSummary>('members', { page: query.page, size: query.size, ...appliedFilters.value }, listController.signal)
    if (!current()) return
    members.value = result.records; total.value = result.total; listReady.value = true
  } catch (cause) { if (current()) { loadError.value = true; if (cause instanceof ManagementReadError && (cause.status === 401 || cause.status === 403)) { clearList(); accessDenied.value = true } } }
  finally { if (current()) loading.value = false }
}
function search() { appliedFilters.value = filtersSnapshot(); query.page = 1; fetchMembers() }
function reset() { Object.assign(query, { page: 1, keyword: '', accountStatus: undefined, serviceStatus: undefined }); search() }
function showDetail(id: number) { selectedId.value = id; detailVisible.value = true }
function addMember() { if (!userStore.isAdmin) return; editingMember.value = null; formVisible.value = true }
function editMember(member: MemberDetail) { if (!userStore.isAdmin) return; detailVisible.value = false; editingMember.value = member; formVisible.value = true }
function saved(member: MemberDetail) { fetchMembers(); showDetail(member.id) }
function openFromQuery() { const id = Number(route.query.userId); if (canRead.value && Number.isSafeInteger(id) && id > 0) showDetail(id) }
watch(() => route.query.userId, openFromQuery)
watch(() => [userStore.userInfo?.id, userStore.userInfo?.role, userStore.token, userStore.isAdmin, userStore.isLibrarian], () => { clearList(); accessDenied.value = false; detailVisible.value = false; if (canRead.value) fetchMembers() }, { flush: 'sync' })
function invalidateAccess() { clearList(); accessDenied.value = true; detailVisible.value = false }
window.addEventListener('auth-access-invalidated', invalidateAccess)
onBeforeUnmount(() => { clearList(); window.removeEventListener('auth-access-invalidated', invalidateAccess) })
onMounted(() => { fetchMembers(); openFromQuery() })
</script>

<style scoped>
.toolbar-left { display: flex; gap: 8px; flex-wrap: wrap; } .note { color: var(--text-secondary); font-size: 13px; line-height: 1.7; margin: 12px 0; }
</style>
