<template>
  <section v-if="userStore.isAdmin" class="entitlement-panel" v-loading="loading">
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="current">
      <EntitlementRules :rules="current.effectiveRules" />
      <p class="note">{{ current.snapshot ? `权益快照 #${current.snapshot.id}，确认于 ${current.snapshot.confirmedAt}` : '尚未登记权益快照；现有实际日期和次数继续保留。' }}</p>
      <el-button @click="openReview">核对权益与套餐依据</el-button>
      <el-button @click="loadHistory">查看快照历史</el-button>
      <el-table v-if="history" :data="history.records" border size="small" class="history">
        <el-table-column prop="confirmedAt" label="确认时间" min-width="160" />
        <el-table-column label="版本依据" min-width="140"><template #default="{ row }">{{ row.versionSnapshot ? `${row.versionSnapshot.name} · 版本 ${row.versionSnapshot.versionNumber}` : '未关联套餐版本' }}</template></el-table-column>
        <el-table-column prop="reason" label="核对或调整原因" min-width="180" />
        <el-table-column label="当时日期 / 次数" min-width="240"><template #default="{ row }">{{ row.factsAtConfirmation.serviceStart ?? '待核实' }} 至 {{ row.factsAtConfirmation.serviceEnd ?? '待核实' }}；已用 {{ row.factsAtConfirmation.usedUses ?? '待核实' }} / {{ row.factsAtConfirmation.usageType === 'UNLIMITED' ? '不限' : row.factsAtConfirmation.totalUses ?? '待核实' }}</template></el-table-column>
      </el-table>
      <el-pagination v-if="history" v-model:current-page="historyPage" :page-size="10" :total="history.total" layout="prev, pager, next" @current-change="loadHistory" />
    </template>
    <el-dialog v-model="reviewVisible" title="核对会员实际权益" width="min(1040px, 96vw)" append-to-body :close-on-click-modal="false" :close-on-press-escape="!busy" :show-close="!busy">
      <template v-if="input">
        <el-alert v-if="reviewError" :title="reviewError" type="error" :closable="false" />
        <p class="note">保留会员实际日期和累计次数。套餐版本用于记录依据，不会自动覆盖实际权益；此处不办理收费、续费或换套餐结算。</p>
        <el-form :disabled="busy" label-width="140px">
          <el-form-item label="套餐依据"><el-select v-model="selectedPlanId" filterable remote :remote-method="searchPlans" clearable placeholder="搜索套餐名称（可不关联）" @change="selectPlan"><el-option v-for="plan in plans" :key="plan.id" :value="plan.id" :label="plan.name" /></el-select></el-form-item>
          <el-form-item label="已发布版本"><el-select v-model="input.planVersionId" clearable placeholder="未关联版本"><el-option v-for="version in versions" :key="version.id" :value="version.id" :label="`${version.name} · 版本 ${version.versionNumber}`" /></el-select><el-button :disabled="!chosenVersion" @click="copyVersionRules">预填版本规则供核对</el-button></el-form-item>
          <el-form-item label="历史版本依据"><el-input v-model="input.historyBasis" type="textarea" maxlength="2000" placeholder="关联非当前启用版本时必填" /></el-form-item>
          <el-form-item label="实际套餐名称"><el-input v-model="input.actual.planName" maxlength="200" /></el-form-item>
          <el-form-item label="实际服务期"><el-date-picker v-model="input.actual.serviceStart" value-format="YYYY-MM-DD" type="date" placeholder="实际开始日" /><el-date-picker v-model="input.actual.serviceEnd" value-format="YYYY-MM-DD" type="date" placeholder="实际结束日" /></el-form-item>
          <el-form-item label="次数类型"><el-select v-model="input.actual.usageType" aria-label="权益次数类型"><el-option value="UNKNOWN" label="待核实" /><el-option value="LIMITED" label="有限" /><el-option value="UNLIMITED" label="明确不限" /></el-select></el-form-item>
          <el-form-item label="累计已用次数"><el-input-number v-model="input.actual.usedUses" :min="0" :precision="0" placeholder="已用次数待核实" /></el-form-item>
          <el-form-item label="总次数"><el-input-number v-if="input.actual.usageType === 'LIMITED'" v-model="input.actual.totalUses" :min="0" :precision="0" /><span v-else>{{ input.actual.usageType === 'UNLIMITED' ? '不限' : `待核实，保留原值 ${input.actual.totalUses ?? '未提供'}` }}</span></el-form-item>
          <el-form-item label="套餐时长（天）"><el-input-number v-model="input.rules.serviceDurationDays" :min="1" :precision="0" /><span>仅记录依据，不重算上述日期；开始日计第 1 天。</span></el-form-item>
          <el-form-item label="单次借书上限"><LimitEditor v-model="input.rules.perOrderLimit" label="单次借书上限" unit="册" /></el-form-item>
          <el-form-item label="累计在借上限"><LimitEditor v-model="input.rules.activeLoanLimit" label="累计在借上限" unit="册" /></el-form-item>
          <el-form-item label="归还期限"><el-select v-model="input.rules.dueDateType"><el-option value="UNKNOWN" label="待核实" /><el-option value="DAYS" label="具体天数" /><el-option value="UNLIMITED" label="明确不限归还日期" /></el-select><el-input-number v-if="input.rules.dueDateType === 'DAYS'" v-model="input.rules.borrowDays" :min="1" :precision="0" /></el-form-item>
          <el-form-item label="续借权限"><el-select v-model="input.rules.renewalPermission"><el-option value="UNKNOWN" label="待核实" /><el-option value="ALLOWED" label="允许" /><el-option value="FORBIDDEN" label="不允许" /></el-select></el-form-item>
          <template v-if="input.rules.renewalPermission === 'ALLOWED'">
            <el-form-item label="续借次数"><LimitEditor :model-value="input.rules.renewalLimit ?? emptyLimit()" label="续借次数" unit="次" @update:model-value="input!.rules.renewalLimit = $event" /></el-form-item>
            <el-form-item label="续借延长天数"><el-input-number v-model="input.rules.renewalDays" :min="1" :precision="0" /></el-form-item>
          </template>
          <el-form-item label="语种范围"><ScopeEditor v-model="input.rules.languages" label="语种" /></el-form-item>
          <el-form-item label="取书方式"><ScopeEditor v-model="input.rules.pickupMethods" label="取书方式" /></el-form-item>
          <el-form-item label="来源引用"><el-input v-model="sourceLines" type="textarea" placeholder="逐行填写核对来源" /></el-form-item>
          <el-form-item label="核对或调整原因"><el-input v-model="input.reason" type="textarea" maxlength="500" placeholder="本次权益核对或调整原因（必填）" /></el-form-item>
        </el-form>
        <el-button :loading="previewing" :disabled="busy || !input.reason.trim()" @click="preview">生成只读核对预览</el-button>
        <section v-if="proposal" class="proposal">
          <p v-if="proposal.historicalVersion" class="note">本次关联历史保留版本，须有明确历史依据；后续新办理只选当前启用版本。</p>
          <el-table :data="proposal.differences" border size="small">
            <el-table-column label="字段" width="150"><template #default="{ row }">{{ entitlementFieldLabel(row.field) }}</template></el-table-column>
            <el-table-column label="当前实际 / 规则" min-width="180"><template #default="{ row }">{{ entitlementValue(row.currentValue) }}</template></el-table-column>
            <el-table-column label="拟登记值" min-width="180"><template #default="{ row }">{{ entitlementValue(row.proposedValue) }}</template></el-table-column>
            <el-table-column label="版本依据" min-width="180"><template #default="{ row }">{{ ['actual.serviceStart', 'actual.serviceEnd', 'actual.usedUses'].includes(row.field) ? '按会员实际保留' : entitlementValue(row.versionValue) }}</template></el-table-column>
          </el-table>
          <el-checkbox v-model="confirmed" :disabled="busy">已核对实际权益、差异和来源依据</el-checkbox>
          <el-button type="primary" :loading="confirming" :disabled="busy || !confirmed || previewFingerprint !== fingerprint" @click="confirm">确认登记权益快照</el-button>
        </section>
      </template>
      <template #footer><el-button :disabled="busy" @click="reviewVisible = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/modules/user'
import { getMemberEntitlement, previewMemberEntitlement, confirmMemberEntitlement, getEntitlementHistory, type EntitlementInput, type EntitlementView, type EntitlementPreview, type EntitlementSnapshot } from '@/api/memberEntitlement'
import { getMembershipPlans, getMembershipPlan, type PlanSummary, type PlanVersion } from '@/api/membershipPlan'
import type { PageResult } from '@/types'
import { draftIssues, emptyLimit } from '@/views/membership-plan/planDefinition'
import { entitlementInput, normalizedEntitlement, createEntitlementAttempts, entitlementFieldLabel, entitlementValue } from '../entitlementReview'
import EntitlementRules from './EntitlementRules.vue'
import LimitEditor from '@/views/membership-plan/components/LimitEditor.vue'
import ScopeEditor from '@/views/membership-plan/components/ScopeEditor.vue'
const props = defineProps<{ memberId: number }>()
const emit = defineEmits<{ (e: 'saved'): void }>()
const userStore = useUserStore(), current = ref<EntitlementView | null>(null)
const loading = ref(false), error = ref(''), reviewVisible = ref(false), reviewError = ref('')
const input = ref<EntitlementInput | null>(null), sourceLines = ref(''), plans = ref<PlanSummary[]>([]), versions = ref<PlanVersion[]>([]), selectedPlanId = ref<number | null>(null)
const proposal = ref<EntitlementPreview | null>(null), previewFingerprint = ref(''), confirmed = ref(false), previewing = ref(false), confirming = ref(false)
const history = ref<PageResult<EntitlementSnapshot> | null>(null), historyPage = ref(1)
const busy = computed(() => previewing.value || confirming.value)
const chosenVersion = computed(() => versions.value.find(version => version.id === input.value?.planVersionId))
const attempts = createEntitlementAttempts()
let request = 0, planRequest = 0
function body(): EntitlementInput {
  const value = normalizedEntitlement(input.value!)
  value.planVersionId ??= null
  value.actual.planName = value.actual.planName?.trim() ? value.actual.planName : null
  value.actual.serviceStart ||= null; value.actual.serviceEnd ||= null; value.actual.usedUses ??= null; value.actual.totalUses ??= null
  value.sourceReferences = sourceLines.value.split(/\r?\n/).map(line => line.trim()).filter(Boolean)
  return value
}
const fingerprint = computed(() => input.value ? JSON.stringify(body()) : '')
watch(fingerprint, () => { proposal.value = null; confirmed.value = false })
watch(() => props.memberId, () => { reviewVisible.value = false; history.value = null; refresh() }, { immediate: true })
async function refresh() {
  const number = ++request
  if (!userStore.isAdmin) return
  loading.value = true; error.value = ''; current.value = null
  try { const value = await getMemberEntitlement(props.memberId); if (number === request) current.value = value }
  catch { if (number === request) error.value = '权益读取失败，请重新打开档案。' }
  finally { if (number === request) loading.value = false }
}
async function searchPlans(keyword = '') {
  const number = ++planRequest
  try { const value = await getMembershipPlans({ page: 1, size: 100, keyword }); if (number === planRequest) plans.value = value.records } catch { reviewError.value = '套餐列表读取失败，可继续核对未关联的实际权益。' }
}
async function selectPlan(id: number | null) {
  const number = ++planRequest
  versions.value = []; if (input.value) input.value.planVersionId = null
  if (!id) return
  try { const plan = await getMembershipPlan(id); if (number === planRequest) versions.value = plan.versions }
  catch { reviewError.value = '套餐版本读取失败，请重试。' }
}
function openReview() {
  if (!current.value || !userStore.isAdmin) return
  input.value = entitlementInput(current.value); sourceLines.value = input.value.sourceReferences.join('\n')
  selectedPlanId.value = current.value.snapshot?.versionSnapshot?.planId ?? null
  versions.value = current.value.snapshot?.versionSnapshot ? [current.value.snapshot.versionSnapshot] : []
  proposal.value = null; confirmed.value = false; reviewError.value = ''; reviewVisible.value = true; searchPlans()
  const planId = selectedPlanId.value
  if (planId) getMembershipPlan(planId).then(plan => { if (reviewVisible.value && selectedPlanId.value === planId) versions.value = plan.versions }).catch(() => { reviewError.value = '其他历史版本读取失败；当前保留的版本依据仍可核对。' })
  // Existing snapshot/version selection stays until an explicit new plan is chosen.
}
function copyVersionRules() {
  if (input.value && chosenVersion.value) input.value.rules = JSON.parse(JSON.stringify(chosenVersion.value.rules))
}
function failure(caught: any, action: string) { reviewError.value = caught?.response?.status === 409 ? `${action}冲突，输入已保留。请关闭核对窗口并重新打开档案，核实最新资料。` : `${action}失败，输入已保留，可重试。` }
async function preview() {
  if (!input.value || !userStore.isAdmin || busy.value) return
  const value = body(), issues = draftIssues(value.rules)
  if (value.actual.serviceStart && value.actual.serviceEnd && value.actual.serviceStart > value.actual.serviceEnd) issues.push('实际服务结束日不能早于开始日')
  if (value.actual.usageType === 'LIMITED' && (value.actual.totalUses == null || !Number.isInteger(value.actual.totalUses) || value.actual.totalUses < 0)) issues.push('有限总次数须为非负整数')
  if (!value.reason.trim()) issues.push('请填写核对或调整原因')
  if (value.reason.length > 500) issues.push('核对或调整原因不能超过 500 字符')
  if (issues.length) { reviewError.value = issues.join('；'); return }
  const sentFingerprint = JSON.stringify(value)
  previewing.value = true; reviewError.value = ''; proposal.value = null
  try { const result = await previewMemberEntitlement(props.memberId, value); if (fingerprint.value === sentFingerprint) { proposal.value = result; previewFingerprint.value = sentFingerprint; confirmed.value = false } }
  catch (caught) { failure(caught, '预览') } finally { previewing.value = false }
}
async function confirm() {
  if (!input.value || !proposal.value || !confirmed.value || busy.value || fingerprint.value !== previewFingerprint.value || !userStore.isAdmin) return
  confirming.value = true; reviewError.value = ''
  try {
    await confirmMemberEntitlement(props.memberId, attempts(body(), proposal.value.previewHash))
    reviewVisible.value = false; proposal.value = null; history.value = null
    await refresh(); emit('saved'); ElMessage.success('权益快照已登记')
  } catch (caught) { failure(caught, '确认') } finally { confirming.value = false }
}
async function loadHistory() {
  if (!userStore.isAdmin) return
  try { history.value = await getEntitlementHistory(props.memberId, historyPage.value) }
  catch { error.value = '快照历史读取失败，请重试。' }
}
</script>
<style scoped>
.note { font-size: 13px; color: var(--text-secondary); line-height: 1.7; margin: 12px 0; } .history, .proposal { margin-top: 18px; } :deep(.el-form-item__content) { gap: 10px; }
</style>
