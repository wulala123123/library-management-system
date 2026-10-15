<template>
  <div v-if="userStore.isAdmin" class="membership-plans">
    <div class="search-form"><el-form inline @submit.prevent="search"><el-form-item label="套餐名称"><el-input v-model="query.keyword" placeholder="搜索套餐名称" clearable @keyup.enter="search" /></el-form-item><el-form-item><el-button type="primary" @click="search">搜索</el-button><el-button @click="reset">重置</el-button></el-form-item></el-form></div>
    <div class="card-container">
      <div class="table-toolbar"><div class="toolbar-left"><el-button type="primary" @click="open(null)">新增套餐草稿</el-button><el-button @click="fetchPlans">刷新</el-button></div><span>共 {{ total }} 个套餐</span></div>
      <p class="note">逐套餐核实规则后发布版本，再单独选为后续可用版本。已有会员的日期、次数和权益保持原样。</p>
      <el-alert v-if="error" title="套餐列表读取失败，请刷新重试。" type="error" :closable="false" />
      <el-table v-loading="loading" :data="plans" stripe border>
        <el-table-column prop="name" label="套餐名称" min-width="180" />
        <el-table-column prop="versionCount" label="已发布版本数" width="140" align="center" />
        <el-table-column label="后续使用" width="140"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '可用' : '已停用' }}</el-tag></template></el-table-column>
        <el-table-column label="所选版本 ID" width="140"><template #default="{ row }">{{ row.selectedVersionId ?? '未选择' }}</template></el-table-column>
        <el-table-column prop="updatedAt" label="最近修改" min-width="170" />
        <el-table-column label="操作" width="170" fixed="right"><template #default="{ row }"><el-button link type="primary" @click="open(row.id)">查看草稿与版本</el-button></template></el-table-column>
      </el-table>
      <div class="pagination-wrapper"><el-pagination v-model:current-page="query.page" v-model:page-size="query.size" :total="total" :page-sizes="[10, 20, 50]" layout="total, sizes, prev, pager, next" @change="fetchPlans" /></div>
    </div>
    <PlanEditorDialog v-model:visible="editorVisible" :plan-id="selectedId" @saved="fetchPlans" />
  </div>
  <el-empty v-else description="仅管理员可管理套餐版本。" />
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getMembershipPlans, type PlanSummary } from '@/api/membershipPlan'
import PlanEditorDialog from './components/PlanEditorDialog.vue'
const userStore = useUserStore()
const query = reactive({ page: 1, size: 10, keyword: '' })
const plans = ref<PlanSummary[]>([]), total = ref(0), loading = ref(false), error = ref(false)
const editorVisible = ref(false), selectedId = ref<number | null>(null)
let requestNumber = 0
async function fetchPlans() {
  if (!userStore.isAdmin) return
  const request = ++requestNumber; loading.value = true; error.value = false
  try { const value = await getMembershipPlans({ ...query, keyword: query.keyword.trim() }); if (request === requestNumber) { plans.value = value.records; total.value = value.total } }
  catch { if (request === requestNumber) { plans.value = []; total.value = 0; error.value = true } }
  finally { if (request === requestNumber) loading.value = false }
}
function search() { query.page = 1; fetchPlans() }
function reset() { query.keyword = ''; query.page = 1; fetchPlans() }
function open(id: number | null) { if (!userStore.isAdmin) return; selectedId.value = id; editorVisible.value = true }
onMounted(fetchPlans)
</script>
<style scoped>
.toolbar-left { display: flex; gap: 8px; } .note { font-size: 13px; color: var(--text-secondary); line-height: 1.7; margin: 12px 0; }
</style>
