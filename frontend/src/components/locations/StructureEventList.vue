<template>
  <section v-if="canRead && visible">
    <h3>地址维护事件（只读）</h3><p v-if="error">{{ error }} <el-button @click="load">重试读取事件</el-button></p>
    <article v-for="event in events" :key="event.id"><strong>事件 {{ event.id }} · {{ event.operation }} · {{ event.entityType }} #{{ event.entityId }}</strong><p>操作人 {{ event.operatorId }} · {{ event.occurredAt }} · 版本 {{ event.beforeVersion ?? 'NULL' }} → {{ event.afterVersion }}</p><p>依据：<code>{{ JSON.stringify(event.reason) }}</code></p><details><summary>查看当时原值与新值快照</summary><p>快照中的 createTime / updateTime 为原数据库本地时间。</p><pre>{{ JSON.stringify({ before: event.before, after: event.after }, null, 2) }}</pre></details></article>
    <p v-if="!loading && !error && !events.length">暂无维护事件，原历史资料保留。</p>
    <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" @current-change="load" />
  </section>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getStructureEvents, type StructureEntityType, type StructureEvent } from '@/api/locationAddress'
const props = defineProps<{ entityType: StructureEntityType; entityId: number; visible: boolean; revision?: number }>()
const user = useUserStore(), canRead = computed(() => user.isAdmin || user.isLibrarian)
const page = ref(1), total = ref(0), events = ref<StructureEvent[]>([]), loading = ref(false), error = ref('')
let generation = 0
onUnmounted(() => { ++generation })
async function load() { if (!canRead.value || !props.visible) return; const current = ++generation; loading.value = true; error.value = ''; try { const result = await getStructureEvents(props.entityType, props.entityId, page.value, 20); if (current === generation) { events.value = result.records; total.value = result.total } } catch { if (current === generation) error.value = '事件读取失败。' } finally { if (current === generation) loading.value = false } }
watch(() => [props.visible, props.entityType, props.entityId, props.revision], () => { ++generation; events.value = []; page.value = 1; load() }, { immediate: true })
</script>
<style scoped>article { padding: 10px; border: 1px solid var(--border-color); margin: 10px 0; }pre, code { white-space: pre-wrap; overflow-wrap: anywhere; }p { font-size: 13px; }</style>
