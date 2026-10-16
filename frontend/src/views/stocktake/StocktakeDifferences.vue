<template>
  <section>
    <h3>快照 #{{ review.snapshotId }} 的差异标签</h3><p>一册可有多个标签；重复事件、未知条码和独立实体册分别核对。差异标签不改变库存与借还。</p>
    <el-form inline @submit.prevent="search"><el-form-item label="差异类型"><select v-model="type"><option value="">全部</option><option v-for="(labels, value) in differenceLabels" :key="value" :value="value">{{ labels[0] }}</option></select></el-form-item><el-form-item label="册 ID"><el-input v-model="copyFilter" placeholder="稳定册 ID" /></el-form-item><el-button @click="search">查询差异页</el-button><el-button :loading="exporting" @click="download('csv')">导出此快照 CSV</el-button><el-button :loading="exporting" @click="download('json')">导出此快照 JSON</el-button></el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" border v-loading="loading"><el-table-column label="标签 / 身份" min-width="250"><template #default="{ row }"><strong>{{ differenceLabel(row.type) }}</strong><p>{{ differenceExplanation(row.type) }}</p>差异 #{{ row.differenceId }} · 册 {{ row.copyId ?? '当时未知' }}<br /><code>{{ JSON.stringify(row.rawBarcode) }}</code></template></el-table-column><el-table-column label="登记与观察依据" min-width="320"><template #default="{ row }"><CopyPlacement v-if="row.evidence.registered" :placement="row.evidence.registered.placement" :raw-text="row.evidence.registered.rawLocation" :anomaly="row.evidence.registered.locationReferenceAnomaly" /><div v-for="(place, index) in row.evidence.observedPlacements" :key="index"><strong>观察 {{ index + 1 }}</strong><CopyPlacement :placement="place.placement" :raw-text="place.rawText" /></div><p>{{ row.evidence.explanation }}</p><p>覆盖：{{ row.evidence.coverageStatus ? coverageLabels[row.evidence.coverageStatus] : '无范围内声明' }} · 观察事件 {{ row.evidence.observationIds.join(', ') || '无' }}</p></template></el-table-column><el-table-column v-if="admin && finalized" label="核实" width="120"><template #default="{ row }"><el-button link :disabled="locked" @click="selected = row">核实此差异</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <StocktakeResolutions v-if="finalized" :session-id="sessionId" :admin="admin" :locked="locked" :refresh="refresh" :selected-difference="selected" :latest-resolution="latestResolution" @write="(label, path, body, callback) => emit('write', label, path, body, callback)" />
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { exportStocktake, getStocktakeDifferences, type Difference, type Resolution, type Review } from '@/api/stocktake'
import CopyPlacement from '@/components/locations/CopyPlacement.vue'
import StocktakeResolutions from './StocktakeResolutions.vue'
import { coverageLabels, differenceExplanation, differenceLabel, differenceLabels, parseExplicitIds } from './stocktakeInput'
const props = defineProps<{ sessionId: number; review: Review; admin: boolean; finalized: boolean; locked: boolean; refresh: number; latestResolution?: Resolution | null }>()
const emit = defineEmits<{ write: [label: string, path: string, body: Record<string, unknown>, onSuccess?: () => void] }>()
const rows = ref<Difference[]>([]), page = ref(1), total = ref(0), type = ref(''), copyFilter = ref(''), loading = ref(false), error = ref(''), exporting = ref(false), selected = ref<Difference | null>(null)
let request = 0
async function load() { const current = ++request; loading.value = true; try { const copyId = copyFilter.value ? parseExplicitIds(copyFilter.value, 1)[0] : undefined; const result = await getStocktakeDifferences(props.sessionId, { reviewSnapshotId: props.review.snapshotId, page: page.value, size: 20, type: type.value || undefined, copyId }); if (current === request) { rows.value = result.records; total.value = result.total } } catch { if (current === request) { rows.value = []; total.value = 0; error.value = '差异页读取失败，请重试。' } } finally { if (current === request) loading.value = false } }
function search() { page.value = 1; load() }
async function download(format: 'json' | 'csv') { if (exporting.value) return; exporting.value = true; try { const blob = await exportStocktake(props.sessionId, props.review.snapshotId, format); const url = URL.createObjectURL(blob), anchor = document.createElement('a'); anchor.href = url; anchor.download = `stocktake-${props.sessionId}-snapshot-${props.review.snapshotId}.${format}`; anchor.click(); URL.revokeObjectURL(url); error.value = '' } catch { error.value = '快照导出失败，未使用当前页重建报告；请重试原快照导出。' } finally { exporting.value = false } }
watch(() => [props.sessionId, props.review.snapshotId], () => { selected.value = null; page.value = 1; load() }, { immediate: true })
watch(() => props.refresh, load)
</script>
<style scoped>
p { color: #65758b; font-size: 13px; line-height: 1.7; }code { white-space: pre-wrap; overflow-wrap: anywhere; }select { min-height: 32px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 4px 10px; }
</style>
