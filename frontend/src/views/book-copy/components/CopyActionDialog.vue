<template>
  <el-dialog :model-value="visible" title="逐册状态与位置办理" width="min(1080px, 96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy && !uncertain && !loading" :before-close="beforeClose">
    <template v-if="canManage">
      <el-alert v-if="loadError || error" :title="loadError || error" type="error" :closable="false" />
      <el-alert v-if="uncertain" title="结果尚未确认。输入与原请求保持不变，请查询原请求或使用原请求重试。" type="warning" :closable="false" />
      <CopyActionReceiptPanel v-if="receipt" :receipt="receipt" />
      <template v-else>
        <p>仅办理明确选中的 {{ copyIds.length }} 册，每批最多 100 册、同一种动作。原条码不变；历史差额保留，不补造实体册。</p>
        <el-skeleton v-if="loading" :rows="3" animated />
        <fieldset v-else :disabled="!!request || busy || !!loadError" class="draft">
          <el-form label-width="100px">
            <el-form-item label="办理动作"><el-select v-model="action" aria-label="办理动作" placeholder="请选择同一种动作" :disabled="!!request || busy || !!loadError">
              <el-option v-for="kind in availableKinds" :key="kind" :value="kind" :label="actionNames[kind]" :disabled="!allAllow(kind)" />
            </el-select></el-form-item>
            <el-form-item label="办理依据"><el-input v-model="reason" type="textarea" :rows="2" placeholder="本次办理依据（必填，原文保留）" :disabled="!!request || busy || !!loadError" /></el-form-item>
          </el-form>
          <el-alert v-if="action === 'RESTORE'" title="恢复只回到临时停用：总库存 +1，可借不变。请明确现在位置，核实可借后另办启用。" type="info" :closable="false" />
          <article v-for="draft in drafts" :key="draft.context.copy.id" class="copy-draft">
            <strong>{{ draft.context.copy.book.title }}</strong>
            <p>实体册 {{ draft.context.copy.id }} · 条码 <code>{{ rawText(draft.context.copy.barcode) }}</code>（{{ utf8Bytes(draft.context.copy.barcode) }} 字节）</p>
            <p>{{ stateLabel(draft.context.copy) }} · 原版本 {{ draft.context.copy.actionVersion }} · 原位置 <code>{{ rawText(draft.context.copy.location) }}</code></p>
            <p v-if="blockedReason(draft.context)">{{ blockedReason(draft.context) }}</p>
            <details><summary>可办动作与拒绝原因</summary><p v-for="permission in draft.context.actions" :key="permission.action">{{ actionNames[permission.action] }}：{{ permission.allowed ? '可办理' : denyText(permission.reasonCode) }}</p></details>
            <section v-if="action === 'RESTORE'" class="restore-source">
              <template v-if="draft.restoreSource">
                <strong>当前报损来源事件 {{ draft.restoreSource.eventId }}</strong>
                <p>原报损依据：<code>{{ rawText(draft.restoreSource.reason) }}</code></p>
                <p>操作人 {{ draft.restoreSource.operatorId }} · {{ draft.restoreSource.occurredAt }}</p>
              </template>
              <p v-else-if="draft.sourceLoading">正在读取当前报损来源…</p>
              <template v-else>
                <p>{{ draft.sourceError || '当前报损来源尚未核实，不能预览恢复。' }}</p>
                <el-button :disabled="!!request || busy || !restorePermission(draft)?.allowed" @click="loadRestoreSource(draft)">重试读取报损来源</el-button>
              </template>
            </section>
            <CopyLocationInput v-if="action && needsLocation(action)" v-model="draft.location" :disabled="!!request || busy" />
            <CopyReconciliationV2 :value="draft.context.reconciliationV2" />
          </article>
        </fieldset>
        <template v-if="view">
          <h3>待确认预览 · {{ actionNames[view.action] }}</h3>
          <el-alert title="此预览尚未办理；确认将再次核对册版本、借阅关联和库存。" type="info" :closable="false" />
          <p>有效至 {{ new Date(view.expiresAt).toLocaleString() }}{{ expired ? ' · 已过期，请返回重新核对' : '' }}</p>
          <CopyActionChanges :copies="view.copies" :books="view.books" />
        </template>
        <details v-if="request"><summary>原请求记录</summary><p>请求键 <code>{{ request.idempotencyKey }}</code>{{ view ? `，记录 ${view.requestId}` : '' }}。结果不明时不换请求键。</p></details>
      </template>
    </template>
    <el-empty v-else description="仅管理员和馆员可办理逐册动作。" />
    <template #footer>
      <el-button :disabled="busy || uncertain || loading" @click="close">{{ receipt ? '完成' : '关闭' }}</el-button>
      <template v-if="canManage && !receipt">
        <el-button v-if="request" :disabled="busy" @click="session.recover">查询原请求</el-button>
        <el-button v-if="request && !uncertain" :disabled="busy || loading" @click="editAgain">返回修改，重新核对</el-button>
        <el-button v-if="!request" :disabled="loading || busy" @click="loadContexts">刷新原值</el-button>
        <el-button v-if="!view" type="primary" :disabled="loading || busy || !!loadError || (!request && !canPreview)" :loading="busy" @click="preview">{{ request ? '使用原请求重试预览' : '预览本次办理' }}</el-button>
        <el-button v-else type="primary" :disabled="busy || (!uncertain && (expired || rejected))" :loading="busy" @click="confirm">{{ uncertain ? '使用原请求重试确认' : '确认本次办理' }}</el-button>
      </template>
    </template>
  </el-dialog>
</template>
<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { useUserStore } from '@/store/modules/user'
import { getCopyActionContext, getCopyActionHistory, type CopyAction, type CopyActionContext, type CopyActionEvent, type CopyActionInput, type CopyActionReceipt } from '@/api/bookCopyAction'
import type { CopyRow } from '@/api/bookCopyQuery'
import CopyLocationInput, { type LocationDraft } from './CopyLocationInput.vue'
import CopyActionChanges from './CopyActionChanges.vue'
import CopyActionReceiptPanel from './CopyActionReceipt.vue'
import CopyReconciliationV2 from './CopyReconciliationV2.vue'
import { actionKinds, actionNames, adminAction, copyStateText, needsLocation, rawText, utf8Bytes, validateActionBasics, validateLocation } from './copyActionPresentation'
import { useCopyActionSession } from './copyActionSession'
const props = defineProps<{ visible: boolean; copyIds: number[] }>()
const emit = defineEmits<{ 'update:visible': [value: boolean]; success: [value: CopyActionReceipt] }>()
const user = useUserStore(), canManage = computed(() => user.isAdmin || user.isLibrarian)
const availableKinds = computed(() => actionKinds.filter(kind => user.isAdmin || !adminAction(kind)))
interface CopyDraft { context: CopyActionContext; location: LocationDraft; restoreSource: CopyActionEvent | null; sourceLoading: boolean; sourceError: string }
const action = ref<CopyAction | ''>(''), reason = ref(''), drafts = ref<CopyDraft[]>([])
const loading = ref(false), loadError = ref(''), now = ref(Date.now())
const session = useCopyActionSession(), { request, view, receipt, busy, uncertain, error, rejected } = session
let generation = 0, reported: number | null = null
const timer = setInterval(() => { now.value = Date.now() }, 1000)
onUnmounted(() => { ++generation; clearInterval(timer) })
const expired = computed(() => !!view.value && Date.parse(view.value.expiresAt) <= now.value)
const stateLabel = (copy: CopyRow) => copyStateText(copy.status, copy.stateClassification === 'DISABLED' || copy.stateClassification === 'WRITTEN_OFF')
const denial: Record<string, string> = { COPY_ON_LOAN: '已借出，请先归还。', COPY_LINK_ANOMALY: '借阅关联异常，需先核实。', UNKNOWN_LEGACY_STATUS: '原状态来源待核实，不能自动转换。', ACTION_ROLE_REQUIRED: '当前角色不能办理。', INVALID_TRANSITION: '当前状态不适用此动作。', RESTORE_SOURCE_REQUIRED: '缺少本册有效报损来源。', ALREADY_RESTORED: '原报损已恢复。' }
const denyText = (code: string | null) => code ? denial[code] || `暂不可办（${code}）` : '暂不可办，请重新读取原值。'
function allAllow(kind: CopyAction) { return drafts.value.length === props.copyIds.length && drafts.value.length > 0 && (!adminAction(kind) || user.isAdmin) && drafts.value.every(draft => draft.context.actions.some(permission => permission.action === kind && permission.allowed)) }
function blockedReason(context: CopyActionContext) {
  if (action.value) { const permission = context.actions.find(value => value.action === action.value); if (!permission?.allowed) return denyText(permission?.reasonCode || null) }
  if (!context.actions.some(value => value.allowed)) return '本册没有可办理的动作，请查看拒绝原因。'
  return ''
}
const restorePermission = (draft: CopyDraft) => draft.context.actions.find(permission => permission.action === 'RESTORE')
const restoreSourcesReady = computed(() => drafts.value.every(draft => !!draft.restoreSource && draft.restoreSource.eventId === restorePermission(draft)?.restoreOfActionId && !draft.sourceLoading))
const canPreview = computed(() => !!action.value && allAllow(action.value) && (action.value !== 'RESTORE' || restoreSourcesReady.value))
async function loadRestoreSource(draft: CopyDraft) {
  if (!canManage.value || !user.isAdmin || !props.visible || request.value || busy.value || draft.sourceLoading || !restorePermission(draft)?.allowed) return
  const source = restorePermission(draft)?.restoreOfActionId, current = generation
  draft.restoreSource = null; draft.sourceError = ''
  if (!Number.isSafeInteger(source) || !source || source <= 0) { draft.sourceError = '恢复缺少本册有效报损事件，请刷新原值。'; return }
  draft.sourceLoading = true
  try {
    const history = await getCopyActionHistory(draft.context.copy.id, 1, 20)
    if (generation !== current || !props.visible) return
    const event = history.records.find(event => event.eventId === source && event.copyId === draft.context.copy.id && event.bookId === draft.context.copy.bookId && event.action === 'WRITE_OFF' && event.afterStatus === 'WRITTEN_OFF' && event.restoredByActionId === null)
    if (event) draft.restoreSource = event
    else draft.sourceError = '未查到与当前册一致的报损来源，请刷新原值后重试。'
  } catch { if (generation === current) draft.sourceError = '报损来源读取失败，不能预览恢复。请重试读取。' }
  finally { if (generation === current) draft.sourceLoading = false }
}
async function loadRestoreSources() { await Promise.all(drafts.value.filter(draft => !draft.restoreSource).map(loadRestoreSource)) }
async function loadContexts() {
  if (!canManage.value || request.value || busy.value) return
  const invalidIds = validateActionBasics('MOVE', props.copyIds, '校验所选册', true)
  if (invalidIds) { loadError.value = invalidIds; return }
  const current = ++generation; loading.value = true; loadError.value = ''
  try {
    const contexts = await Promise.all(props.copyIds.map(id => getCopyActionContext(id)))
    if (generation !== current || !props.visible) return
    const prior = drafts.value
    drafts.value = contexts.map(context => ({ context, location: prior.find(draft => draft.context.copy.id === context.copy.id)?.location || { kind: '', text: '' }, restoreSource: null, sourceLoading: false, sourceError: '' }))
    if (action.value === 'RESTORE') await loadRestoreSources()
  } catch { if (generation === current) { drafts.value = []; loadError.value = '读取所选实体册失败，请刷新原值后重试。' } }
  finally { if (generation === current) loading.value = false }
}
watch(() => props.visible, visible => {
  if (!visible) { ++generation; loading.value = false; return }
  if (!canManage.value || request.value) return
  drafts.value = []; reason.value = ''; action.value = ''; reported = null; loadContexts()
}, { immediate: true })
watch(receipt, value => { if (value && value.requestId !== reported) { reported = value.requestId; emit('success', value) } })
watch(action, value => { if (value === 'RESTORE') loadRestoreSources() })
async function preview() {
  if (!canManage.value || busy.value || loading.value) return
  if (request.value) { await session.preview(request.value); return }
  if (!action.value) { error.value = '请选择办理动作。'; return }
  const invalid = validateActionBasics(action.value, props.copyIds, reason.value, user.isAdmin)
  if (invalid) { error.value = invalid; return }
  if (!allAllow(action.value)) { error.value = '所选册不能全部办理该动作，请重新核对。'; return }
  if (action.value === 'RESTORE' && !restoreSourcesReady.value) { error.value = '请先成功读取每册当前引用的报损来源。'; return }
  const copies: CopyActionInput[] = []
  for (const draft of drafts.value) {
    const copy = draft.context.copy, value: CopyActionInput = { copyId: copy.id, expectedVersion: copy.actionVersion }
    if (!Number.isSafeInteger(copy.actionVersion) || copy.actionVersion < 0) { error.value = '册版本缺失，请刷新原值。'; return }
    if (needsLocation(action.value)) {
      const invalidLocation = validateLocation(draft.location.kind, draft.location.text)
      if (invalidLocation) { error.value = `实体册 ${copy.id}：${invalidLocation}`; return }
      value.targetLocation = draft.location.kind === 'UNASSIGNED' ? { kind: 'UNASSIGNED' } : { kind: 'TEXT', text: draft.location.text }
    }
    if (action.value === 'RESTORE') {
      const source = draft.context.actions.find(permission => permission.action === 'RESTORE')?.restoreOfActionId
      if (!Number.isSafeInteger(source) || !source || source <= 0) { error.value = '恢复缺少本册有效报损事件，请刷新原值。'; return }
      value.restoreOfActionId = source; value.restoreTo = 'DISABLED'
    }
    copies.push(value)
  }
  await session.preview({ idempotencyKey: crypto.randomUUID(), action: action.value, reason: reason.value, copies })
}
async function confirm() { if (canManage.value && view.value && (!adminAction(view.value.action) || user.isAdmin) && (uncertain.value || (!expired.value && !rejected.value))) await session.confirm() }
async function editAgain() { if (!session.reset()) return; await loadContexts() }
function close() { if (busy.value || uncertain.value || loading.value) return false; emit('update:visible', false); session.reset(); return true }
function beforeClose(done: () => void) { if (close()) done() }
</script>
<style scoped>
.draft { border: 0; padding: 0; min-width: 0; }.draft:disabled { opacity: .75; pointer-events: none; }.copy-draft { margin: 10px 0; padding: 12px; border: 1px solid var(--border-color); }code { white-space: pre-wrap; overflow-wrap: anywhere; }p { font-size: 13px; line-height: 1.6; }
</style>
