<template>
  <section v-if="user.isAdmin" class="admin-list-export" aria-label="完整筛选导出">
    <p>已应用筛选：{{ description }}</p>
    <p v-if="unapplied" class="warning">输入尚未应用；导出使用上方已应用筛选，请点击搜索更新列表。</p>
    <el-button :disabled="!canDownload" :loading="busy" @click="start">导出全部筛选结果</el-button>
    <p v-if="busy" role="status">正在生成完整{{ names[kind] }} CSV；超过 20000 条会整笔拒绝，请缩小筛选。</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="success" role="status">已导出 {{ success.total }} {{ units[kind] }} · 导出时点 {{ success.asOf }}；文件包含全部筛选结果。</p>
  </section>
</template>
<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { createManagementReportsApi, type ManagementFilters, type ManagementList } from '@/api/managementReports'
import { useAdminDownload } from './useAdminDownload'
const props = defineProps<{ kind: ManagementList; filters: ManagementFilters; invalidationKey: unknown; ready: boolean; description: string; unapplied?: boolean }>()
const user = useUserStore()
const names = { members: '会员', books: '书目', inventory: '库存书目' }, units = { members: '位会员', books: '种书目', inventory: '种库存书目' }
const { start, busy, error, success, canDownload } = useAdminDownload({ key: () => [props.kind, props.filters, props.invalidationKey], ready: () => props.ready, read: (token, signal) => createManagementReportsApi(token).listCsv(props.kind, props.filters, signal), onSuccess: file => ElMessage.success(`已导出全部筛选结果：${file.total} ${units[props.kind]}`) })
</script>
<style scoped>
.admin-list-export { margin: 14px 0; font-size: 13px; color: var(--text-secondary); line-height: 1.7; overflow-wrap: anywhere; }p { margin: 5px 0; }.warning { color: var(--el-color-warning-dark-2); }
</style>
