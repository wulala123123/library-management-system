<template>
  <section>
    <div v-if="editable" class="entry-panel">
      <h3>追加扫描事实</h3>
      <StocktakePlacementInput v-model="place" :disabled="locked" />
      <el-form @submit.prevent><el-form-item label="馆内条码原文"><el-input v-model="rawBarcode" :disabled="locked" placeholder="扫描后按 Enter 加入待保存批次，保留原文" @keydown.enter.prevent="addScan" /></el-form-item></el-form>
      <p>原文 <code>{{ barcodeEvidence(rawBarcode).literal }}</code> · {{ barcodeEvidence(rawBarcode).codepoints }} 字符 / {{ barcodeEvidence(rawBarcode).bytes }} UTF-8 字节</p>
      <el-button :disabled="locked" @click="addScan">加入待保存扫描</el-button>
      <ul><li v-for="(event, index) in queued" :key="event.clientEventKey">{{ index + 1 }} · <code>{{ JSON.stringify(event.rawBarcode) }}</code> · {{ event.observedPlacement.kind }} <el-button link :disabled="locked" @click="queued.splice(index, 1)">移除未保存输入</el-button></li></ul>
      <el-input v-model="scanReason" :disabled="locked" placeholder="本批观察依据（1–500 字）" maxlength="500" />
      <el-button type="primary" :disabled="locked || !queued.length" @click="saveScans">保存 {{ queued.length }} 条观察</el-button>
      <p>重复扫描保留每次事件，同一身份只计一册；未知条码留待核实。保存事实不办理借还、移位或新增库存。</p>
    </div>
    <h3>观察与追加更正记录</h3>
    <el-form inline @submit.prevent="search"><el-form-item label="事件类型"><select v-model="kind"><option value="">全部</option><option v-for="value in ['SCAN','CORRECTION','VOID','COVERAGE','RECHECK']" :key="value" :value="value">{{ kindLabels[value] }}</option></select></el-form-item><el-form-item label="册 ID"><el-input v-model="copyFilter" placeholder="稳定册 ID" /></el-form-item><el-button @click="search">查询记录</el-button></el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table :data="rows" border v-loading="loading"><el-table-column label="事件 / 身份" min-width="190"><template #default="{ row }">#{{ row.observationId }} · {{ kindLabels[row.kind] }}<br />册 {{ row.resolvedCopyIdAtScan ?? '当时未知' }} · {{ row.active ? '有效事实' : '已被后续记录更正' }}<br /><code>{{ JSON.stringify(row.rawBarcode) }}</code></template></el-table-column><el-table-column label="实际观察处" min-width="260"><template #default="{ row }"><CopyPlacement v-if="row.observedPlacement" :placement="row.observedPlacement.placement" :raw-text="row.observedPlacement.rawText" /><span v-else>非扫描事件</span></template></el-table-column><el-table-column label="依据 / 时间" min-width="200"><template #default="{ row }">{{ row.reason }}<br />{{ row.acceptedAt }} · 操作者 #{{ row.actorId }}<br /><span v-if="row.referencedObservationId">引用原事件 #{{ row.referencedObservationId }}</span><details><summary>不可变证据</summary><pre>{{ JSON.stringify(row.signature, null, 2) }}</pre></details></template></el-table-column><el-table-column v-if="editable" label="更正" width="130"><template #default="{ row }"><el-button v-if="row.active && ['SCAN','CORRECTION'].includes(row.kind)" link :disabled="locked" @click="startCorrection(row)">追加误扫更正</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <section v-if="correction" class="entry-panel">
      <h3>追加更正原观察 #{{ correction.observationId }}</h3><p>原记录保留；只能追加撤销或完整替代观察。</p>
      <el-radio-group v-model="correctionKind" :disabled="locked"><el-radio-button value="VOID">标记误扫撤销</el-radio-button><el-radio-button value="CORRECTION">追加替代观察</el-radio-button></el-radio-group>
      <template v-if="correctionKind === 'CORRECTION'"><el-input v-model="replacementBarcode" :disabled="locked" placeholder="替代条码原文" /><StocktakePlacementInput v-model="replacementPlace" :disabled="locked" /></template>
      <el-input v-model="correctionReason" :disabled="locked" maxlength="500" placeholder="更正依据" /><el-button type="primary" :disabled="locked" @click="saveCorrection">保存追加更正</el-button><el-button :disabled="locked" @click="correction = null">放弃未保存更正</el-button>
    </section>
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { getStocktakeObservations, type Observation, type ObservationEvent } from '@/api/stocktake'
import CopyPlacement from '@/components/locations/CopyPlacement.vue'
import StocktakePlacementInput, { type ObservationPlaceDraft } from './StocktakePlacementInput.vue'
import { barcodeEvidence, newStocktakeKey, parseExplicitIds, validateObservedBarcode } from './stocktakeInput'
import { observedPlacement } from './stocktakeTransport'
const props = defineProps<{ sessionId: number; editable: boolean; locked: boolean; refresh: number }>()
const emit = defineEmits<{ write: [label: string, suffix: string, body: Record<string, unknown>, onSuccess?: () => void] }>()
const emptyPlace = (): ObservationPlaceDraft => ({ kind: 'UNASSIGNED', text: '', address: null })
const place = ref(emptyPlace()), rawBarcode = ref(''), scanReason = ref(''), queued = ref<ObservationEvent[]>([])
const rows = ref<Observation[]>([]), page = ref(1), total = ref(0), kind = ref(''), copyFilter = ref(''), loading = ref(false), error = ref('')
const correction = ref<Observation | null>(null), correctionKind = ref<'VOID' | 'CORRECTION'>('VOID'), replacementBarcode = ref(''), replacementPlace = ref(emptyPlace()), correctionReason = ref('')
const kindLabels: Record<string, string> = { SCAN: '扫描', CORRECTION: '替代观察', VOID: '误扫撤销', COVERAGE: '覆盖声明', RECHECK: '变化重核对' }
function addScan() { if (props.locked) return; try { const issue = validateObservedBarcode(rawBarcode.value); if (issue) throw new Error(issue); if (queued.value.length >= 100) throw new Error('每批最多 100 条，请先保存当前批次。'); queued.value.push({ clientEventKey: newStocktakeKey(), rawBarcode: rawBarcode.value, observedPlacement: observedPlacement(place.value), clientObservedAt: null }); rawBarcode.value = ''; error.value = '' } catch (cause) { error.value = (cause as Error).message } }
function reason(value: string) { if (!value.trim() || [...value].length > 500) throw new Error('请填写 1–500 字依据。'); return value }
function saveScans() { try { const events = JSON.parse(JSON.stringify(queued.value)) as ObservationEvent[], saved = new Set(events.map(event => event.clientEventKey)); emit('write', '保存扫描事实', 'observations', { reason: reason(scanReason.value), events }, () => { queued.value = queued.value.filter(event => !saved.has(event.clientEventKey)) }); error.value = '' } catch (cause) { error.value = (cause as Error).message } }
function startCorrection(row: Observation) { correction.value = row; correctionKind.value = 'VOID'; replacementBarcode.value = row.rawBarcode ?? ''; replacementPlace.value = emptyPlace(); correctionReason.value = '' }
function saveCorrection() { if (!correction.value) return; try { if (correctionKind.value === 'CORRECTION') { const issue = validateObservedBarcode(replacementBarcode.value); if (issue) throw new Error(issue) } const replacement = correctionKind.value === 'VOID' ? null : { rawBarcode: replacementBarcode.value, observedPlacement: observedPlacement(replacementPlace.value), clientObservedAt: null }; emit('write', '追加误扫更正', 'corrections', { reason: reason(correctionReason.value), observationId: correction.value.observationId, kind: correctionKind.value, replacement }, () => { correction.value = null }); error.value = '' } catch (cause) { error.value = (cause as Error).message } }
let request = 0
async function load() { const current = ++request; loading.value = true; error.value = ''; try { const copyId = copyFilter.value ? parseExplicitIds(copyFilter.value, 1)[0] : undefined; const result = await getStocktakeObservations(props.sessionId, { page: page.value, size: 20, copyId, kind: kind.value || undefined }); if (current === request) { rows.value = result.records; total.value = result.total } } catch (cause) { if (current === request) { rows.value = []; total.value = 0; error.value = cause instanceof Error ? cause.message : '观察记录读取失败。' } } finally { if (current === request) loading.value = false } }
function search() { page.value = 1; load() }
watch(() => [props.sessionId, props.refresh], load, { immediate: true })
</script>
<style scoped>
.entry-panel { display: grid; gap: 12px; background: #f6f9fd; padding: 18px; border-radius: 8px; margin-bottom: 22px; }.entry-panel h3,.entry-panel p { margin: 0; }p { font-size: 13px; color: #65758b; line-height: 1.7; }code,pre { white-space: pre-wrap; overflow-wrap: anywhere; }select { min-height: 32px; border: 1px solid #dcdfe6; border-radius: 4px; padding: 4px 10px; }
</style>
