<template>
  <el-dialog v-if="userStore.isAdmin" :model-value="visible" :title="detail ? '套餐草稿与版本' : '新增套餐草稿'" width="min(980px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy" @close="close">
    <div v-loading="loading">
      <p class="intro">版本仅用于后续办理，已有会员权益保持原样。办卡、续费与套餐变更后续开放。</p>
      <el-alert v-if="error" :title="error" type="error" :closable="false" class="notice" show-icon />
      <template v-if="!loadFailed">
        <el-form ref="formRef" :model="form" :rules="formRules" label-width="145px" :disabled="busy || loading">
          <h3>套餐草稿</h3>
          <el-form-item label="套餐名称" prop="name"><el-input v-model="form.name" placeholder="套餐名称" maxlength="200" /></el-form-item>
          <el-form-item label="服务时长（天）"><el-input-number v-model="form.rules.serviceDurationDays" :min="1" :precision="0" placeholder="服务时长待核实" /><span class="hint">留空表示待核实；发布时须为正整数。</span></el-form-item>
          <p class="note">开始日计第 1 天，结束日为开始日加天数减 1。</p>
          <el-form-item label="单次借书上限"><LimitEditor v-model="form.rules.perOrderLimit" label="单次借书上限" unit="册" /></el-form-item>
          <el-form-item label="累计在借上限"><LimitEditor v-model="form.rules.activeLoanLimit" label="累计在借上限" unit="册" /></el-form-item>
          <el-form-item label="总次数"><LimitEditor :model-value="{ mode: form.rules.usageType, value: form.rules.totalUses }" label="总次数" unit="次" @update:model-value="setUsage" /></el-form-item>
          <p class="note">有限数值 0 表示零额度；只有选择“明确不限”才表示不限。</p>
          <el-form-item label="归还期限"><el-select v-model="form.rules.dueDateType" aria-label="归还期限类型" style="width: 180px"><el-option value="UNKNOWN" label="待核实" /><el-option value="DAYS" label="具体借阅天数" /><el-option value="UNLIMITED" label="明确不限归还日期" /></el-select><template v-if="form.rules.dueDateType === 'DAYS'"><el-input-number v-model="form.rules.borrowDays" :min="1" :precision="0" placeholder="具体借阅天数" /><span>天</span></template></el-form-item>
          <el-form-item label="续借权限"><el-select v-model="form.rules.renewalPermission" aria-label="续借权限" style="width: 180px"><el-option value="UNKNOWN" label="待核实" /><el-option value="ALLOWED" label="允许续借" /><el-option value="FORBIDDEN" label="不允许续借" /></el-select></el-form-item>
          <template v-if="form.rules.renewalPermission === 'ALLOWED'">
            <el-form-item label="续借次数"><LimitEditor :model-value="form.rules.renewalLimit ?? emptyLimit()" label="续借次数" unit="次" @update:model-value="setRenewalLimit" /></el-form-item>
            <el-form-item label="续借延长天数"><el-input-number v-model="form.rules.renewalDays" :min="1" :precision="0" placeholder="续借延长天数" /><span>天</span></el-form-item>
          </template>
          <el-form-item label="语种范围"><ScopeEditor v-model="form.rules.languages" label="语种" /></el-form-item>
          <el-form-item label="取书方式"><ScopeEditor v-model="form.rules.pickupMethods" label="取书方式" /></el-form-item>
          <el-form-item label="来源引用"><el-input v-model="sourceLines" type="textarea" :rows="3" placeholder="逐行填写来源名称、编号或链接" /></el-form-item>
          <el-form-item label="备注"><el-input v-model="form.notes" type="textarea" :rows="2" placeholder="套餐备注（选填）" maxlength="2000" /></el-form-item>
          <el-form-item label="保存原因" prop="changeReason"><el-input v-model="form.changeReason" placeholder="说明本次保存草稿的原因" maxlength="500" /></el-form-item>
          <el-button type="primary" :loading="saving" :disabled="busy && !saving" @click="saveDraft">保存草稿</el-button>
        </el-form>

        <section v-if="detail" class="publish-section">
          <h3>核实后发布版本</h3>
          <p v-if="dirty" class="warning">有未保存的修改，请先保存草稿。</p>
          <details v-if="issues.length" class="note"><summary>还有 {{ issues.length }} 项规则需要核实，当前可继续保存草稿。</summary><ul><li v-for="issue in issues" :key="issue">{{ issue }}</li></ul></details>
          <el-input v-model="confirmationBasis" type="textarea" :rows="2" :disabled="busy" placeholder="逐项核实依据（发布必填）" maxlength="2000" />
          <el-input v-model="publishReason" :disabled="busy" placeholder="本次发布原因（必填）" maxlength="500" />
          <el-button type="primary" :loading="publishing" :disabled="busy || dirty || !!issues.length || !confirmationBasis.trim() || !publishReason.trim()" @click="publishVersion">发布不可变版本</el-button>
        </section>

        <section v-if="detail" class="selection-section">
          <h3>后续可用版本</h3>
          <p class="note">{{ detail.enabled ? `当前为版本 ${selectedVersionNumber ?? '待核实'}，已允许后续使用。` : '当前已停用，未允许后续使用。' }}发布版本后需要在此单独选择。</p>
          <el-input v-model="selectionReason" :disabled="busy" placeholder="选择或停用的原因（必填）" maxlength="500" />
          <el-table :data="detail.versions" border size="small">
            <el-table-column prop="versionNumber" label="版本" width="80" />
            <el-table-column prop="name" label="发布时名称" min-width="150" />
            <el-table-column prop="createdAt" label="发布时间" min-width="160" />
            <el-table-column label="状态" width="130"><template #default="{ row }">{{ detail.selectedVersionId === row.id ? (detail.enabled ? '后续可用' : '已选，已停用') : '已发布' }}</template></el-table-column>
            <el-table-column label="操作" min-width="180"><template #default="{ row }"><el-button link type="primary" :disabled="busy || dirty || !selectionReason.trim() || (detail.enabled && detail.selectedVersionId === row.id)" @click="selectVersion(row.id, true)">选为后续可用版本</el-button></template></el-table-column>
          </el-table>
          <el-button v-if="detail.enabled && detail.selectedVersionId != null" type="warning" :disabled="busy || dirty || !selectionReason.trim()" @click="selectVersion(detail.selectedVersionId!, false)">停止后续使用</el-button>
          <VersionCompare :versions="detail.versions" />
        </section>
      </template>
    </div>
    <template #footer><el-button :disabled="busy" @click="close">关闭</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { createMembershipPlan, getMembershipPlan, publishMembershipPlan, saveMembershipPlanDraft, selectMembershipPlanVersion, type PlanDefinition, type PlanDetail, type PlanLimit } from '@/api/membershipPlan'
import { copyDefinition, createPublishAttempts, draftIssues, emptyDefinition, emptyLimit, normalizedDefinition, publicationIssues } from '../planDefinition'
import LimitEditor from './LimitEditor.vue'
import ScopeEditor from './ScopeEditor.vue'
import VersionCompare from './VersionCompare.vue'
const props = defineProps<{ visible: boolean; planId: number | null }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'saved'): void }>()
const userStore = useUserStore(), formRef = ref<FormInstance>(), detail = ref<PlanDetail | null>(null)
const loading = ref(false), loadFailed = ref(false), saving = ref(false), publishing = ref(false), selecting = ref(false), error = ref('')
const busy = computed(() => saving.value || publishing.value || selecting.value)
const form = reactive({ ...emptyDefinition(), changeReason: '' })
const sourceLines = ref(''), savedFingerprint = ref('')
const confirmationBasis = ref(''), publishReason = ref(''), selectionReason = ref('')
const publishAttempt = createPublishAttempts()
let loadRequest = 0
const formRules: FormRules = { name: [{ required: true, whitespace: true, message: '请输入套餐名称', trigger: 'blur' }], changeReason: [{ required: true, whitespace: true, message: '请填写保存原因', trigger: 'blur' }] }
function draftDefinition(): PlanDefinition { return normalizedDefinition({ name: form.name, notes: form.notes, sourceReferences: sourceLines.value.split(/\r?\n/).map(line => line.trim()).filter(Boolean), rules: form.rules }) }
const dirty = computed(() => JSON.stringify(draftDefinition()) !== savedFingerprint.value)
const issues = computed(() => publicationIssues(form.rules))
const selectedVersionNumber = computed(() => detail.value?.versions.find(version => version.id === detail.value?.selectedVersionId)?.versionNumber)
function applyDetail(value: PlanDetail) {
  detail.value = value; Object.assign(form, copyDefinition(value.draft), { changeReason: '' }); sourceLines.value = value.draft.sourceReferences.join('\n')
  savedFingerprint.value = JSON.stringify(draftDefinition()); formRef.value?.clearValidate()
}
watch(() => [props.visible, props.planId] as const, async () => {
  const request = ++loadRequest
  loading.value = false
  if (!props.visible || !userStore.isAdmin) return
  Object.assign(form, emptyDefinition(), { changeReason: '' }); sourceLines.value = ''; detail.value = null; error.value = ''; loadFailed.value = false
  confirmationBasis.value = ''; publishReason.value = ''; selectionReason.value = ''; savedFingerprint.value = JSON.stringify(draftDefinition())
  if (props.planId == null) return
  loading.value = true
  try { const value = await getMembershipPlan(props.planId); if (request === loadRequest) applyDetail(value) }
  catch { if (request === loadRequest) { error.value = '套餐读取失败，请关闭后重新打开。'; loadFailed.value = true } }
  finally { if (request === loadRequest) loading.value = false }
}, { immediate: true })
function setUsage(value: PlanLimit) { form.rules.usageType = value.mode; form.rules.totalUses = value.value }
function setRenewalLimit(value: PlanLimit) { form.rules.renewalLimit = value }
function close() { if (!busy.value) emit('update:visible', false) }
function failure(caught: any, action: string) {
  error.value = caught?.response?.status === 409 ? `${action}冲突：输入已保留，请关闭后重新打开套餐，核对最新内容。` : `${action}失败，输入已保留，可重试。`
}
async function saveDraft() {
  if (!userStore.isAdmin || !formRef.value || busy.value || loading.value) return
  try { await formRef.value.validate() } catch { return }
  const invalid = draftIssues(form.rules)
  if (invalid.length) { error.value = invalid.join('；'); return }
  saving.value = true; error.value = ''
  try {
    const data = { ...draftDefinition(), changeReason: form.changeReason }
    const value = detail.value ? await saveMembershipPlanDraft(detail.value.id, { ...data, revision: detail.value.revision }) : await createMembershipPlan(data)
    applyDetail(value); emit('saved'); ElMessage.success('草稿已保存')
  } catch (caught) { failure(caught, '保存') } finally { saving.value = false }
}
async function publishVersion() {
  if (!userStore.isAdmin || !detail.value || busy.value || dirty.value || issues.value.length || !confirmationBasis.value.trim() || !publishReason.value.trim()) return
  const current = detail.value
  const body = publishAttempt(current.id, { revision: current.revision, confirmationBasis: confirmationBasis.value, changeReason: publishReason.value })
  publishing.value = true; error.value = ''
  try {
    const response = await publishMembershipPlan(current.id, body)
    detail.value = { ...current, revision: response.revision, versions: current.versions.some(version => version.id === response.version.id) ? current.versions : [response.version, ...current.versions] }
    detail.value.versionCount = detail.value.versions.length
    confirmationBasis.value = ''; publishReason.value = ''; emit('saved'); ElMessage.success(response.replayed ? '已确认原发布结果' : '版本已发布，请单独选择后续可用版本')
  } catch (caught) { failure(caught, '发布') } finally { publishing.value = false }
}
async function selectVersion(versionId: number, enabled: boolean) {
  if (!userStore.isAdmin || !detail.value || busy.value || dirty.value || !selectionReason.value.trim()) return
  selecting.value = true; error.value = ''
  try {
    applyDetail(await selectMembershipPlanVersion(detail.value.id, { revision: detail.value.revision, versionId, enabled, changeReason: selectionReason.value }))
    selectionReason.value = ''; emit('saved'); ElMessage.success(enabled ? '已选为后续可用版本' : '已停止后续使用')
  } catch (caught) { failure(caught, '选择版本') } finally { selecting.value = false }
}
</script>

<style scoped>
h3 { font-size: 16px; margin: 22px 0 14px; } .intro, .note { color: var(--text-secondary); font-size: 13px; line-height: 1.7; margin: 10px 0 16px; } .hint { color: var(--text-secondary); font-size: 12px; margin-left: 12px; }
.notice { margin: 12px 0; } .warning { color: var(--el-color-warning); font-size: 13px; margin: 10px 0; }
.publish-section, .selection-section { border-top: 1px solid var(--border-light); margin-top: 24px; }
.publish-section > *, .selection-section > * { margin-bottom: 12px; }
:deep(.el-form-item__content) { gap: 10px; }
</style>
