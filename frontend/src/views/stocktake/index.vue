<template>
  <div v-if="canRead" class="stocktake-page">
    <header class="page-heading"><div><h2>盘点与差异核对</h2><p>按明确有限范围保存观察、核对期间变化，再由管理员封存报告。</p></div><el-button v-if="!session" type="primary" :disabled="locked" @click="toggleCreate">新建有限范围盘点</el-button></header>
    <section v-if="operation.pending.value || operation.error.value" class="operation-panel" :class="operation.state.value.toLowerCase()" role="status">
      <h3>{{ operation.pending.value?.label || '原盘点请求恢复' }} · {{ operationLabels[operation.state.value] }}</h3>
      <p v-if="operation.error.value">{{ operation.error.value }}</p>
      <p v-if="operation.state.value === 'UNCERTAIN'">原输入与请求身份已冻结。查询未找到也不能证明原操作已停止，请继续查询或重试完全相同的原请求。</p>
      <p v-if="operation.state.value === 'REJECTED'">输入已保留。确认失败后可继续编辑；版本、变化或锁冲突须重新检测并逐项核对。</p>
      <p v-if="operation.result.value">原成功回执：盘点 #{{ operation.result.value.sessionId }} · 修订 {{ operation.result.value.revision }} · {{ operation.result.value.operation }}。相同请求重放不会新增观察或业务动作。</p>
      <div class="toolbar"><el-button v-if="operation.pending.value && operation.state.value !== 'COMMITTED'" :loading="operation.busy.value" @click="operation.recover">查询原请求结果</el-button><el-button v-if="operation.state.value === 'UNCERTAIN' && operation.pending.value" :loading="operation.busy.value" @click="operation.retry">使用原请求重试</el-button><el-button v-if="['COMMITTED','REJECTED'].includes(operation.state.value)" :disabled="operation.busy.value" @click="operation.acknowledge">{{ operation.state.value === 'COMMITTED' ? '已查看回执，继续办理' : '已确认失败，保留输入继续核对' }}</el-button></div>
      <details v-if="operation.result.value"><summary>查看本次不可变成功回执</summary><pre>{{ JSON.stringify(operation.result.value, null, 2) }}</pre></details>
      <details v-else-if="operation.pending.value"><summary>查看冻结保存的原输入</summary><pre>{{ pendingInput }}</pre></details>
    </section>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <section v-if="!session" class="card-container">
      <section v-if="createFormVisible" class="create-form"><h3>创建有限盘点单元</h3><StocktakeScopePicker v-model="scope" :disabled="locked" /><el-input v-model="campaignKey" :disabled="locked" maxlength="100" placeholder="可选分组名称（不合并各单元的完成时点）" /><el-input v-model="createReason" :disabled="locked" maxlength="500" placeholder="范围选择和盘点依据" /><el-button type="primary" :disabled="locked" @click="create">固定范围并建立基线</el-button></section>
      <el-form inline @submit.prevent="search"><el-form-item label="状态"><select v-model="stateFilter"><option value="">全部可见盘点</option><option value="DRAFT">草稿</option><option value="FINALIZED">已封存</option><option value="CANCELLED">已取消</option></select></el-form-item><el-form-item label="分组"><el-input v-model="campaignFilter" /></el-form-item><el-button @click="search">查询盘点页</el-button></el-form>
      <p class="note">馆员可录入、核对本人草稿；其他馆员草稿由服务端隔离。管理员最终确认报告和核实差异。</p>
      <el-table :data="sessions" border v-loading="loading"><el-table-column label="盘点 / 范围" min-width="250"><template #default="{ row }">#{{ row.sessionId }} · {{ stateLabels[row.state] }}<br />{{ row.scope.kind === 'ADDRESS_SET' ? '明确地址' : '明确实体册' }} [{{ scopeIds(row).join(', ') }}]<br />分组：{{ row.campaignKey ?? '未分组' }}</template></el-table-column><el-table-column label="完成与操作者" min-width="240"><template #default="{ row }">{{ row.receipt ? completenessLabels[row.receipt.completionKind] : row.state === 'CANCELLED' ? '未完成证据归档' : '尚未封存' }}<br />所有者 #{{ row.ownerId }} · 修订 {{ row.revision }}<br />创建 {{ row.createdAt }}</template></el-table-column><el-table-column label="查看" width="120"><template #default="{ row }"><el-button link :disabled="locked" @click="openSession(row.sessionId)">打开盘点</el-button></template></el-table-column></el-table>
      <el-pagination v-model:current-page="page" :page-size="20" :total="total" layout="total, prev, pager, next" @current-change="loadSessions" />
    </section>
    <section v-else class="card-container">
      <div class="detail-head"><div><h3>盘点 #{{ session.sessionId }} · {{ stateLabels[session.state] }}</h3><p>{{ session.scope.kind }} [{{ scopeIds(session).join(', ') }}] · 所有者 #{{ session.ownerId }} · 当前修订 {{ session.revision }}</p></div><div><el-button :disabled="locked" @click="closeSession">返回盘点列表</el-button><el-button @click="refreshSession">刷新已保存资料</el-button></div></div>
      <el-alert v-if="session.state === 'FINALIZED' && session.receipt" :title="completenessLabels[session.receipt.completionKind] + ' · 已封存，仅保存报告；差异需另行核实。'" type="success" :closable="false" />
      <el-alert v-else-if="session.state === 'CANCELLED'" title="已取消，原事实保留为未完成证据，不表示完成盘点。" type="info" :closable="false" />
      <el-alert v-else-if="!editable" title="当前账号只能查看，不可录入或核对其他所有者的草稿。" type="info" :closable="false" />
      <div class="toolbar"><span>查看不可变快照</span><select :value="review?.snapshotId" @change="selectSnapshot"><option v-for="item in snapshotOptions" :key="item.id" :value="item.id">{{ item.label }} #{{ item.id }}</option></select><span class="note">切换或刷新资料不等于核对变化。</span></div>
      <details v-if="session.receipt"><summary>最终封存原回执</summary><pre>{{ JSON.stringify(session.receipt, null, 2) }}</pre></details>
      <el-tabs v-model="tab"><el-tab-pane label="范围、覆盖与变化" name="review" /><el-tab-pane label="扫描与更正事实" name="observations" /><el-tab-pane label="差异与后续核实" name="differences" /></el-tabs>
      <StocktakeReview v-if="review" v-show="tab === 'review'" :key="session.sessionId" :session="session" :review="review" :editable="editable" :admin="store.isAdmin" :locked="locked" :refresh="refresh" @write="writeSession" />
      <StocktakeObservations v-show="tab === 'observations'" :key="session.sessionId" :session-id="session.sessionId" :editable="editable" :locked="locked" :refresh="refresh" @write="writeSession" />
      <StocktakeDifferences v-if="review" v-show="tab === 'differences'" :key="session.sessionId" :session-id="session.sessionId" :review="review" :admin="store.isAdmin" :finalized="session.state === 'FINALIZED' && review.snapshotId === session.finalSnapshotId" :locked="locked" :refresh="refresh" :latest-resolution="latestResolution" @write="writeResolution" />
    </section>
  </div>
  <el-empty v-else description="仅管理员和馆员可访问盘点管理。" />
</template>
<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/store/modules/user'
import { getStocktake, getStocktakes, getStocktakeSnapshot, type OperationReceipt, type Resolution, type Review, type StocktakeScope, type StocktakeSession } from '@/api/stocktake'
import StocktakeScopePicker from './StocktakeScopePicker.vue'
import StocktakeReview from './StocktakeReview.vue'
import StocktakeObservations from './StocktakeObservations.vue'
import StocktakeDifferences from './StocktakeDifferences.vue'
import { completenessLabels, newStocktakeKey } from './stocktakeInput'
import { useStocktakeOperation } from './stocktakeOperation'
import { stocktakeTransport } from './stocktakeTransport'
const store = useUserStore(), router = useRouter(), route = useRoute()
const canRead = computed(() => store.isAdmin || store.isLibrarian)
const operation = useStocktakeOperation<OperationReceipt>(store.userInfo?.id ?? 0, stocktakeTransport)
const locked = computed(() => !!operation.pending.value || operation.busy.value || operation.state.value === 'UNCERTAIN')
const pendingInput = computed(() => JSON.stringify(operation.pending.value?.body, (key, value) => ['operationKey', 'changeToken', 'expectedSignatureDigest', 'previewDigest', 'clientEventKey'].includes(key) ? undefined : value, 2))
const sessions = ref<StocktakeSession[]>([]), session = ref<StocktakeSession | null>(null), review = ref<Review | null>(null), snapshotOptions = ref<{ id: number; label: string }[]>([])
const latestResolution = ref<Resolution | null>(null)
const editable = computed(() => !!session.value && session.value.state === 'DRAFT' && (store.isAdmin || (store.isLibrarian && session.value.ownerId === store.userInfo?.id)))
const page = ref(1), total = ref(0), stateFilter = ref(''), campaignFilter = ref(''), loading = ref(false), error = ref(''), tab = ref('review'), refresh = ref(0)
const createVisible = ref(false), scope = ref<StocktakeScope>({ kind: 'ADDRESS_SET', locationIds: [] }), campaignKey = ref(''), createReason = ref('')
const createFormVisible = computed(() => canRead.value && !session.value && createVisible.value)
watch(createFormVisible, visible => {
  if (!visible) return
  // Reset at every picker mount, including a return from session details: visible and submitted scope start empty together.
  scope.value = { kind: 'ADDRESS_SET', locationIds: [] }
  campaignKey.value = ''; createReason.value = ''; error.value = ''
}, { flush: 'sync' })
const stateLabels: Record<string, string> = { DRAFT: '草稿', FINALIZED: '已封存', CANCELLED: '已取消' }
const operationLabels: Record<string, string> = { IDLE: '待办理', UNCERTAIN: '结果待核实', REJECTED: '明确失败，输入保留', COMMITTED: '已取得原成功回执' }
const scopeIds = (value: StocktakeSession) => value.scope.kind === 'ADDRESS_SET' ? value.scope.locationIds : value.scope.copyIds
let listRequest = 0, detailRequest = 0, lastAppliedKey = '', successCallback: (() => void) | undefined
function rememberReview(value: Review) { review.value = value; if (!snapshotOptions.value.some(item => item.id === value.snapshotId)) snapshotOptions.value.push({ id: value.snapshotId, label: value.purpose === 'BASELINE' ? '起始基线' : value.purpose === 'PREPARE' ? '准备封存' : '变化检查' }) }
async function loadSessions() { if (!canRead.value) return; const current = ++listRequest; loading.value = true; error.value = ''; try { const result = await getStocktakes({ page: page.value, size: 20, state: stateFilter.value || undefined, campaignKey: campaignFilter.value || undefined }); if (current === listRequest) { sessions.value = result.records; total.value = result.total } } catch { if (current === listRequest) { sessions.value = []; total.value = 0; error.value = '盘点列表读取失败。' } } finally { if (current === listRequest) loading.value = false } }
function search() { page.value = 1; loadSessions() }
function toggleCreate() {
  if (locked.value) return
  createVisible.value = !createVisible.value
}
async function loadSession(id: number, retainSnapshot = false) { if (!canRead.value) return; const current = ++detailRequest; loading.value = true; error.value = ''; try { const value = await getStocktake(id); const snapshotId = retainSnapshot && session.value?.sessionId === id && review.value ? review.value.snapshotId : value.finalSnapshotId ?? value.baselineSnapshotId; const snapshot = await getStocktakeSnapshot(id, snapshotId); if (current === detailRequest) { if (session.value?.sessionId !== id) snapshotOptions.value = []; session.value = value; if (!snapshotOptions.value.some(item => item.id === value.baselineSnapshotId)) snapshotOptions.value.unshift({ id: value.baselineSnapshotId, label: '起始基线' }); if (value.finalSnapshotId && !snapshotOptions.value.some(item => item.id === value.finalSnapshotId)) snapshotOptions.value.push({ id: value.finalSnapshotId, label: '封存报告' }); rememberReview(snapshot); ++refresh.value } } catch { if (current === detailRequest) error.value = '盘点或快照读取失败，请核对权限后重试。' } finally { if (current === detailRequest) loading.value = false } }
function openSession(id: number) { if (locked.value) return; tab.value = 'review'; router.replace({ query: { sessionId: id } }) }
function closeSession() { if (locked.value) return; ++detailRequest; session.value = null; review.value = null; snapshotOptions.value = []; router.replace({ query: {} }); loadSessions() }
async function refreshSession() { if (session.value) await loadSession(session.value.sessionId, true) }
async function selectSnapshot(event: Event) { const id = Number((event.target as HTMLSelectElement).value); if (!session.value || !snapshotOptions.value.some(item => item.id === id)) return; try { rememberReview(await getStocktakeSnapshot(session.value.sessionId, id)) } catch { error.value = '不可变快照读取失败。' } }
function reason(value: string) { if (!value.trim() || [...value].length > 500) throw new Error('请填写 1–500 字依据。'); return value }
async function start(label: string, path: string, body: Record<string, unknown>, callback?: () => void) { if (!canRead.value || locked.value) return; successCallback = callback; const operationKey = newStocktakeKey(); await operation.start({ operationKey, label, path, body: { operationKey, ...body } }) }
async function create() { try { const ids = scope.value.kind === 'ADDRESS_SET' ? scope.value.locationIds : scope.value.copyIds; if (!ids.length || ids.length > (scope.value.kind === 'ADDRESS_SET' ? 10 : 100)) throw new Error('请明确选择有限范围。'); await start('创建有限范围盘点', '/stocktakes', { scope: scope.value, campaignKey: campaignKey.value.length ? campaignKey.value : null, reason: reason(createReason.value) }, () => { createVisible.value = false }) } catch (cause) { error.value = (cause as Error).message } }
function writeSession(label: string, suffix: string, body: Record<string, unknown>, callback?: () => void) { if (!editable.value || !session.value || (suffix === 'confirm' && !store.isAdmin)) return; start(label, `/stocktakes/${session.value.sessionId}/${suffix}`, { expectedRevision: session.value.revision, ...body }, callback) }
function writeResolution(label: string, path: string, body: Record<string, unknown>, callback?: () => void) { if (!store.isAdmin || session.value?.state !== 'FINALIZED') return; start(label, path, body, callback) }
async function applyReceipt(value: OperationReceipt) { if (lastAppliedKey === value.operationKey) return; lastAppliedKey = value.operationKey; if (value.result.resolution) latestResolution.value = value.result.resolution; const target = value.result.session ?? await getStocktake(value.sessionId); if (session.value?.sessionId !== target.sessionId) snapshotOptions.value = []; session.value = target; if (!snapshotOptions.value.some(item => item.id === target.baselineSnapshotId)) snapshotOptions.value.unshift({ id: target.baselineSnapshotId, label: '起始基线' }); if (value.result.review) rememberReview(value.result.review); else if (target.finalSnapshotId) rememberReview(await getStocktakeSnapshot(target.sessionId, target.finalSnapshotId)); else if (!review.value || !snapshotOptions.value.some(item => item.id === review.value!.snapshotId)) rememberReview(await getStocktakeSnapshot(target.sessionId, target.baselineSnapshotId)); ++refresh.value; const callback = successCallback; successCallback = undefined; callback?.(); if (route.query.sessionId !== String(target.sessionId)) router.replace({ query: { sessionId: target.sessionId } }) }
watch(operation.result, value => { if (value) applyReceipt(value).catch(() => { error.value = '成功回执已保存，页面资料刷新失败；请保留回执并重读资料，不重复业务。' }) })
watch(() => route.query.sessionId, value => { const id = Number(value); if (Number.isSafeInteger(id) && id > 0 && session.value?.sessionId !== id) loadSession(id); else if (!value && !locked.value) { session.value = null; review.value = null } })
onMounted(() => { if (!canRead.value) return; const id = Number(route.query.sessionId); if (Number.isSafeInteger(id) && id > 0) loadSession(id); else loadSessions(); if (operation.pending.value) operation.recover() })
</script>
<style scoped>
.stocktake-page { display: grid; gap: 20px; }.page-heading,.detail-head { display: flex; align-items: center; justify-content: space-between; gap: 18px; flex-wrap: wrap; }.page-heading h2,.detail-head h3 { margin: 0 0 8px; }.page-heading p,.detail-head p,.note { color: #65758b; font-size: 13px; line-height: 1.7; margin: 0; }.operation-panel { border: 1px solid #dfc793; border-radius: 8px; padding: 18px; background: #fffaf0; }.operation-panel.committed { border-color: #a2c8ae; background: #f2faf4; }.operation-panel h3 { margin: 0 0 10px; font-size: 16px; }.operation-panel p { font-size: 13px; line-height: 1.7; }.toolbar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; margin: 14px 0; }.create-form { display: grid; gap: 14px; margin-bottom: 26px; padding-bottom: 24px; border-bottom: 1px solid #dce4ef; }select { min-height: 32px; padding: 4px 10px; border: 1px solid #dcdfe6; border-radius: 4px; }pre { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 420px; overflow: auto; background: #f5f7fb; padding: 12px; }@media(max-width:700px) { .card-container { padding: 12px; } }
</style>
