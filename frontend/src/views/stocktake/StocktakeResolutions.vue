<template>
  <section>
    <section v-if="admin && selectedDifference && !hasAwaitingIntent" class="resolution-form">
      <h3>管理员核实差异 #{{ selectedDifference.differenceId }} · {{ differenceLabel(selectedDifference.type) }}</h3>
      <p>册 {{ selectedDifference.copyId ?? '当时未知' }} · 原文 <code>{{ JSON.stringify(selectedDifference.rawBarcode) }}</code>。封存回执保持原样，后续决策和关联另记历史。</p>
      <select v-model="decision" :disabled="locked"><option value="OPEN">保留待核实</option><option value="NO_ACTION_WITH_REASON">有依据，无需业务动作</option><option value="BUSINESS_REQUIRED">关联已成功的原业务</option></select>
      <template v-if="decision === 'BUSINESS_REQUIRED'">
        <select v-model="workflow" :disabled="locked" @change="resetCandidates"><option v-for="(label, value) in workflowLabels" :key="value" :value="value">{{ label }}</option></select>
        <p><a v-if="workflow === 'INTAKE'" href="/books" target="_blank" rel="noopener">打开原图书入库入口</a><a v-else :href="`/book-copies${selectedDifference.evidence.registered?.bookId ? `?bookId=${selectedDifference.evidence.registered.bookId}` : ''}`" target="_blank" rel="noopener">打开原逐册业务入口</a>。成功后回此页刷新候选。各业务仍按原权限、预览与确认办理。</p>
        <el-button :disabled="locked" @click="searchCandidates">刷新已成功的安全候选</el-button>
        <el-table :data="candidates" border v-loading="candidateLoading"><el-table-column label="选择" width="90"><template #default="{ row }"><input :id="`candidate-${row.candidateId}`" v-model="candidateId" type="radio" :value="row.candidateId" :disabled="locked" /><label :for="`candidate-${row.candidateId}`">选择</label></template></el-table-column><el-table-column label="成功业务" min-width="230"><template #default="{ row }">{{ workflowLabels[row.workflow] }} · 册 #{{ row.copyId }} · 书目 #{{ row.bookId }}<br /><code>{{ JSON.stringify(row.barcode) }}</code><br />{{ row.committedAt }}</template></el-table-column><el-table-column label="来源" min-width="170"><template #default="{ row }">来源 #{{ row.candidateId }} · 请求 #{{ row.requestId }}<br />库存流水 {{ row.stockFlowId ?? '不产生库存流水' }}</template></el-table-column></el-table>
        <el-pagination v-model:current-page="candidatePage" :page-size="10" :total="candidateTotal" layout="total, prev, pager, next" @current-change="loadCandidates" />
        <p>只显示服务端验证匹配的成功来源，不需要复制请求密钥。关联仅保存证据，不能保证所有差异标签已修正。</p>
      </template>
      <el-input v-model="reason" type="textarea" maxlength="500" :disabled="locked" placeholder="管理员核实依据；历史成功来源也须写明为何关联" />
      <el-button type="primary" :disabled="locked || (decision === 'BUSINESS_REQUIRED' && candidateId === null)" @click="saveDecision">{{ decision === 'BUSINESS_REQUIRED' ? '保存待关联意图' : '保存核实决定' }}</el-button>
    </section>
    <h3>差异核实与关联历史</h3>
    <el-table :data="rows" border v-loading="loading"><el-table-column label="决定 / 状态" min-width="220"><template #default="{ row }">#{{ row.resolutionId }} · {{ stateLabels[row.state] }}<br />差异 {{ row.differenceIds.join(', ') }} · {{ row.workflow ? workflowLabels[row.workflow] : '无业务关联' }}<br />{{ row.reason }}</template></el-table-column><el-table-column label="来源与记录" min-width="220"><template #default="{ row }"><template v-if="row.candidate">册 #{{ row.candidate.copyId }} · <code>{{ JSON.stringify(row.candidate.barcode) }}</code><br />{{ row.candidate.committedAt }}</template><details><summary>追加事件</summary><ul><li v-for="event in row.events" :key="event.eventId">{{ event.kind }} · {{ event.occurredAt }} · {{ event.reason }}</li></ul></details></template></el-table-column><el-table-column v-if="admin" label="查看" width="160"><template #default="{ row }"><el-button link :disabled="locked" @click="inspect(row.resolutionId)">查看 / 恢复关联</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="load" />
    <section v-if="active && admin" class="resolution-form"><h3>原关联 #{{ active.resolutionId }} · {{ stateLabels[active.state] }}</h3><p v-if="active.candidate">已成功原业务：{{ workflowLabels[active.candidate.workflow] }} · 册 #{{ active.candidate.copyId }} · <code>{{ JSON.stringify(active.candidate.barcode) }}</code> · {{ active.candidate.committedAt }}</p><el-alert v-if="active.state === 'AWAITING_LINK'" title="原业务已成功，当前仅待关联。恢复和重试只处理本关联，不重做原业务。" type="warning" :closable="false" /><el-input v-model="linkReason" maxlength="500" :disabled="locked" placeholder="关联或撤销关联依据" /><div><el-button v-if="active.state === 'AWAITING_LINK'" type="primary" :disabled="locked" @click="writeLink('link')">确认关联此成功来源</el-button><el-button v-if="active.state === 'LINKED'" type="danger" plain :disabled="locked" @click="writeLink('revoke')">追加撤销关联</el-button><el-button :disabled="locked" @click="inspect(active.resolutionId)">重读原关联状态</el-button></div><p>撤销保留原关联及来源占用历史，不撤销库存业务，也不改封存报告。</p></section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getStocktakeCandidates, getStocktakeResolutions, recoverStocktakeResolution, type Candidate, type Difference, type Resolution, type Workflow } from '@/api/stocktake'
import { differenceLabel } from './stocktakeInput'
const props = defineProps<{ sessionId: number; admin: boolean; locked: boolean; refresh: number; selectedDifference: Difference | null; latestResolution?: Resolution | null }>()
const emit = defineEmits<{ write: [label: string, path: string, body: Record<string, unknown>, onSuccess?: () => void] }>()
const workflowLabels: Record<string, string> = { INTAKE: '原入库', MOVE: '原移位', WRITE_OFF: '原报损', RESTORE: '原恢复停用', ENABLE: '原启用' }
const stateLabels: Record<string, string> = { OPEN: '待核实', NO_ACTION: '有依据无需动作', AWAITING_LINK: '原业务已成功，待关联', LINKED: '已关联成功来源', REVOKED: '关联已追加撤销' }
const decision = ref<Resolution['decision']>('OPEN'), workflow = ref<Workflow>('MOVE'), reason = ref(''), candidateId = ref<number | null>(null), candidates = ref<Candidate[]>([]), candidatePage = ref(1), candidateTotal = ref(0), candidateLoading = ref(false)
const rows = ref<Resolution[]>([]), page = ref(1), total = ref(0), loading = ref(false), error = ref(''), active = ref<Resolution | null>(null), linkReason = ref('')
const hasAwaitingIntent = computed(() => !!active.value && ['AWAITING_LINK', 'LINKED', 'REVOKED'].includes(active.value.state) && !!props.selectedDifference && active.value.differenceIds.includes(props.selectedDifference.differenceId))
watch(() => props.latestResolution, value => { if (value?.sessionId === props.sessionId) active.value = value }, { immediate: true })
function validReason(value: string) { if (!value.trim() || [...value].length > 500) throw new Error('请填写 1–500 字核实依据。'); return value }
function resetCandidates() { ++candidateRequest; candidates.value = []; candidateId.value = null; candidateTotal.value = 0; candidatePage.value = 1 }
watch(() => props.selectedDifference?.differenceId, () => { decision.value = 'OPEN'; reason.value = ''; resetCandidates() })
let candidateRequest = 0, historyRequest = 0
async function loadCandidates() { if (!props.admin || !props.selectedDifference) return; const current = ++candidateRequest; candidateLoading.value = true; error.value = ''; candidateId.value = null; try { const result = await getStocktakeCandidates({ differenceId: props.selectedDifference.differenceId, workflow: workflow.value, page: candidatePage.value, size: 10 }); if (current === candidateRequest) { candidates.value = result.records; candidateTotal.value = result.total } } catch { if (current === candidateRequest) { candidates.value = []; candidateTotal.value = 0; error.value = '安全业务候选读取失败；请保持原业务成功结果，不重做。' } } finally { if (current === candidateRequest) candidateLoading.value = false } }
function searchCandidates() { candidatePage.value = 1; loadCandidates() }
function saveDecision() { if (!props.admin || !props.selectedDifference) return; try { if (decision.value === 'BUSINESS_REQUIRED' && !candidates.value.some(row => row.candidateId === candidateId.value)) throw new Error('请在服务端成功候选中明确选择来源。'); emit('write', '管理员核实差异', '/stocktake-resolutions', { sessionId: props.sessionId, differenceIds: [props.selectedDifference.differenceId], decision: decision.value, workflow: decision.value === 'BUSINESS_REQUIRED' ? workflow.value : null, candidateId: decision.value === 'BUSINESS_REQUIRED' ? candidateId.value : null, reason: validReason(reason.value) }, load); error.value = '' } catch (cause) { error.value = (cause as Error).message } }
async function inspect(id: number) { if (!props.admin) return; try { active.value = await recoverStocktakeResolution(id); error.value = '' } catch { error.value = '原关联状态尚未查清，请继续读取原关联，不重做业务。' } }
function writeLink(action: 'link' | 'revoke') { if (!props.admin || !active.value) return; try { const id = active.value.resolutionId; emit('write', action === 'link' ? '关联原业务成功来源' : '追加撤销关联', `/stocktake-resolutions/${id}/${action}`, { expectedVersion: active.value.version, reason: validReason(linkReason.value) }, () => { load(); inspect(id) }); error.value = '' } catch (cause) { error.value = (cause as Error).message } }
async function load() { const current = ++historyRequest; loading.value = true; try { const result = await getStocktakeResolutions(props.sessionId, { page: page.value, size: 20 }); if (current === historyRequest) { rows.value = result.records; total.value = result.total } } catch { if (current === historyRequest) { rows.value = []; total.value = 0; error.value = '核实历史读取失败。' } } finally { if (current === historyRequest) loading.value = false } }
watch(() => [props.sessionId, props.refresh], load, { immediate: true })
</script>
<style scoped>
.resolution-form { background: #f6f9fd; border: 1px solid #dce5f0; padding: 18px; border-radius: 8px; display: grid; gap: 12px; margin: 18px 0; }.resolution-form h3,.resolution-form p { margin: 0; }p { font-size: 13px; line-height: 1.7; color: #65758b; }select { min-height: 32px; padding: 4px 10px; border: 1px solid #dcdfe6; border-radius: 4px; }code { white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
