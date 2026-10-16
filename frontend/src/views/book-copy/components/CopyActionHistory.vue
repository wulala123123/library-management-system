<template>
  <section v-if="canRead">
    <h3>成功动作历史与入库来源</h3>
    <p v-if="error">{{ error }} <el-button link @click="load">重试读取</el-button></p>
    <p v-if="loading">正在读取历史…</p>
    <template v-else-if="history">
      <p v-if="history.intakeSource">入库回执 {{ history.intakeSource.intakeId }} · {{ history.intakeSource.sourceType === 'PURCHASE' ? '采购' : '手工' }} · {{ history.intakeSource.confirmedAt }} · 流水 {{ history.intakeSource.stockFlowId }}<span v-if="history.intakeSource.purchaseOrderId"> · 采购单 {{ history.intakeSource.purchaseOrderId }} / 项 {{ history.intakeSource.purchaseItemId }}</span></p>
      <p v-else>暂无统一入库来源；保留原历史记录。</p>
      <article v-for="event in history.records" :key="event.eventId" class="event">
        <strong>事件 {{ event.eventId }} · {{ actionNames[event.action] }}</strong>
        <p>请求 {{ event.requestId }} · 操作人 {{ event.operatorId }} · {{ event.occurredAt }} · 流水 {{ event.stockFlowId ?? '无（位置移动）' }}</p>
        <p>依据：{{ event.reason }}</p><p>条码 <code>{{ rawText(event.barcode) }}</code></p>
        <p>状态 <code>{{ rawText(event.beforeStatus) }}</code> → <code>{{ rawText(event.afterStatus) }}</code> · 版本 {{ event.beforeVersion }} → {{ event.afterVersion }}</p>
        <template v-if="event.schemaVersion === 2 && event.locationChange"><p>位置快照 V2 · {{ event.locationChange.locationSourceBehavior === 'KEEP' ? '位置来源保持' : '本次事件登记位置' }}</p><strong>原位置快照</strong><CopyPlacement :placement="event.locationChange.beforePlacement.placement" :raw-text="event.locationChange.beforePlacement.rawText" /><strong>新位置快照</strong><CopyPlacement :placement="event.locationChange.afterPlacement.placement" :raw-text="event.locationChange.afterPlacement.rawText" /><p>位置来源 {{ event.locationChange.beforeLocationActionId ?? 'NULL' }} → {{ event.locationChange.afterLocationActionId ?? 'NULL' }}</p></template>
        <p v-else-if="event.schemaVersion === 1">位置（V1 原记录）<code>{{ rawText(event.beforeLocation) }}</code> → <code>{{ rawText(event.afterLocation) }}</code></p>
        <p v-else>位置快照暂不可用，保留原事件等待核对。</p>
        <p>总库存增量 {{ event.deltaTotal }} · 可借增量 {{ event.deltaAvailable }}</p>
        <p v-if="event.restoreOfActionId">恢复来源事件 {{ event.restoreOfActionId }}</p><p v-if="event.restoredByActionId">已由事件 {{ event.restoredByActionId }} 恢复</p>
      </article>
      <p v-if="!history.records.length">暂无成功管理动作。</p>
      <el-pagination v-model:current-page="page" :page-size="20" :total="history.total" layout="total, prev, pager, next" @current-change="load" />
    </template>
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getCopyActionHistory, type CopyActionHistory } from '@/api/bookCopyAction'
import { actionNames, rawText } from './copyActionPresentation'
import CopyPlacement from '@/components/locations/CopyPlacement.vue'
const props = defineProps<{ copyId: number; visible: boolean; revision?: number }>()
const user = useUserStore(), canRead = computed(() => user.isAdmin || user.isLibrarian)
const history = ref<CopyActionHistory | null>(null), page = ref(1), loading = ref(false), error = ref('')
let request = 0
onUnmounted(() => { ++request })
async function load() {
  if (!canRead.value || !props.visible) return
  const current = ++request; loading.value = true; error.value = ''; history.value = null
  try { const value = await getCopyActionHistory(props.copyId, page.value, 20); if (current === request && props.visible) history.value = value }
  catch { if (current === request) error.value = '历史读取失败，请重试。' }
  finally { if (current === request) loading.value = false }
}
watch(() => [props.visible, props.copyId, props.revision], () => { ++request; if (props.visible) { page.value = 1; load() } }, { immediate: true })
</script>
<style scoped>
.event { padding: 10px; margin: 8px 0; border: 1px solid var(--border-color); }p { font-size: 13px; line-height: 1.6; }code { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
