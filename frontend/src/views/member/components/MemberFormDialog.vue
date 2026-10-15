<template>
  <el-dialog v-if="userStore.isAdmin" :model-value="visible" :title="member ? '编辑会员' : '新增会员'" width="min(900px, 96vw)" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" @close="close">
    <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" show-icon class="form-alert" />
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" :disabled="saving">
      <h3>基础资料</h3>
      <p class="note">{{ member ? '账号角色、密码和启停在账号管理中维护。' : '创建独立读者账号，初始密码由管理员设置。' }}</p>
      <el-row :gutter="18">
        <el-col :sm="12" :xs="24"><el-form-item label="登录账号" prop="username"><el-input v-model="form.username" placeholder="会员登录账号" :disabled="!!member" maxlength="20" /></el-form-item></el-col>
        <el-col v-if="!member" :sm="12" :xs="24"><el-form-item label="初始密码" prop="password"><el-input v-model="form.password" placeholder="初始密码（至少6位）" type="password" show-password maxlength="50" autocomplete="new-password" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="姓名" prop="realName"><el-input v-model="form.realName" placeholder="会员姓名" maxlength="20" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="手机号" prop="phone"><el-input v-model="form.phone" placeholder="手机号（选填，可共享）" maxlength="20" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="邮箱" prop="email"><el-input v-model="form.email" placeholder="邮箱（选填）" maxlength="100" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="会员卡号" prop="cardNo"><el-input v-model="form.cardNo" placeholder="未设置（选填，保留前导零）" maxlength="100" /></el-form-item></el-col>
      </el-row>
      <h3>现有权益</h3>
      <el-row :gutter="18">
        <el-col :sm="12" :xs="24"><el-form-item label="原套餐名称"><el-input v-model="form.planName" placeholder="未设置（按已知事实填写）" maxlength="200" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="次数类型" prop="usageType"><el-select v-model="form.usageType" style="width: 100%" aria-label="次数类型"><el-option value="UNKNOWN" label="待核实" /><el-option value="LIMITED" label="有限次数" /><el-option value="UNLIMITED" label="明确不限" /></el-select></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="服务开始日"><el-date-picker v-model="form.serviceStart" type="date" value-format="YYYY-MM-DD" placeholder="开始日待核实" style="width: 100%" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="服务结束日" prop="serviceEnd"><el-date-picker v-model="form.serviceEnd" type="date" value-format="YYYY-MM-DD" placeholder="结束日待核实" style="width: 100%" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="已用次数" prop="usedUses"><el-input-number v-model="form.usedUses" :min="0" :precision="0" placeholder="已用次数待核实" style="width: 100%" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="总次数" prop="totalUses"><el-input-number v-if="form.usageType === 'LIMITED'" v-model="form.totalUses" :min="0" :precision="0" placeholder="有限总次数" style="width: 100%" /><span v-else>{{ form.usageType === 'UNLIMITED' ? '不限' : '待核实' }}</span></el-form-item></el-col>
      </el-row>
      <p v-if="form.usageType === 'UNKNOWN' && member?.totalUses != null" class="note">来源总次数原值（含义待核实）：{{ member.totalUses }}，普通资料编辑继续保留原值。</p>
      <h3>家庭及宝宝资料</h3>
      <el-form-item label="地址"><el-input v-model="form.address" placeholder="地址（选填）" maxlength="500" /></el-form-item>
      <el-form-item label="会员备注"><el-input v-model="form.notes" type="textarea" :rows="2" placeholder="会员备注（选填）" maxlength="2000" /></el-form-item>
      <div v-for="(child, index) in form.children" :key="child.id ?? `new-${index}`" class="child-form">
        <div class="section-toolbar"><h4>宝宝 {{ index + 1 }}</h4><el-button type="danger" link @click="form.children.splice(index, 1)">移除宝宝 {{ index + 1 }}</el-button></div>
        <el-row :gutter="18">
          <el-col :sm="12" :xs="24"><el-form-item label="姓名"><el-input v-model="child.name" :placeholder="`宝宝${index + 1}姓名（选填）`" maxlength="100" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="性别"><el-input v-model="child.gender" placeholder="未设置（按已知资料填写）" maxlength="50" /></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item label="生日精度"><el-select v-model="child.birthDatePrecision" aria-label="宝宝生日精度" style="width: 100%"><el-option value="UNKNOWN" label="待核实" /><el-option value="YEAR" label="仅年份" /><el-option value="MONTH" label="精确到月" /><el-option value="DAY" label="精确到日" /></el-select></el-form-item></el-col>
          <el-col :sm="12" :xs="24"><el-form-item v-if="child.birthDatePrecision === 'DAY'" label="生日"><el-date-picker v-model="child.birthDate" type="date" value-format="YYYY-MM-DD" placeholder="已知完整生日" style="width: 100%" /></el-form-item><el-form-item v-else label="来源日期文本"><el-input v-model="child.sourceBirthText" placeholder="保留原日期或年龄文本，不推算生日" maxlength="300" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="宝宝备注"><el-input v-model="child.notes" placeholder="宝宝备注（选填）" maxlength="1000" /></el-form-item>
      </div>
      <el-button :disabled="form.children.length >= 30" @click="addChild">添加宝宝</el-button>
      <p class="note">缺失资料可留空；仅按已知精度保存生日，不由年龄推算。</p>
      <section v-if="member" class="source-review">
        <h3>核对原资料</h3>
        <el-button :loading="sourceLoading" @click="loadSource">读取来源预填预览</el-button>
        <template v-if="source">
          <p class="note">来源 {{ displayValue(source.sourceKey) }}；原档案 {{ displayValue(source.archiveId) }}。勾选并审查后才会预填，保存后才生效。</p>
          <p v-if="!source.sourceAvailable" class="note">没有可用的已确认来源。</p>
          <p v-if="source.asOfDate" class="note">核对日期：{{ source.asOfDate }}（Asia/Shanghai）</p>
          <div v-for="field in source.fields" :key="field.field" class="source-field">
            <el-checkbox :model-value="selectedSource.includes(field.field)" :disabled="!canPrefillSource(field)" @update:model-value="(value: string | number | boolean) => selectSource(field.field, !!value)">{{ fieldLabels[field.field] ?? field.field }}（{{ field.sourceField }}）</el-checkbox>
            <el-tag v-if="field.conflict" type="warning" size="small">与当前资料冲突</el-tag>
            <div class="source-values"><span>当前已保存：{{ sourceText(field.currentValue) }}</span><span>来源：{{ sourceText(field.sourceValue) }}</span></div>
            <p v-if="!canPrefillSource(field)" class="note">{{ field.field === 'totalUses' ? '原始次数须核实，请在表单中手工选择次数类型；不根据原导出零值或空值推断不限。' : sourceReasonText(field.reasonCode) }}</p>
          </div>
          <section v-if="source.childCandidates?.length" class="source-children">
            <h4>原资料宝宝候选</h4>
            <p class="note">逐槽审阅后选择；性别、年龄单独出现不能确认宝宝存在，年龄不用于推算生日。已有手工资料保留，按来源键更新的宝宝保留业务 ID。</p>
            <div v-for="candidate in source.childCandidates" :key="candidate.sourceKey + ':' + candidate.sourceSlot" class="source-child">
              <el-checkbox :model-value="selectedChildren.includes(candidate.sourceKey)" :disabled="!!candidateIssue(candidate)" @update:model-value="(value: string | number | boolean) => selectChild(candidate.sourceKey, !!value)">来源宝宝槽位 {{ candidate.sourceSlot }}</el-checkbox>
              <el-tag v-if="candidate.conflict" type="warning" size="small">与已保存宝宝资料冲突</el-tag>
              <p class="note">来源字段：{{ candidate.sourceFields.join('、') }}</p>
              <p class="note">来源标识：{{ candidate.sourceKey }}</p>
              <div class="source-values">
                <div><strong>当前已保存</strong><p class="source-raw">{{ childText(candidate.currentValue) }}</p></div>
                <div><strong>来源原值</strong><p class="source-raw">姓名：{{ sourceText(candidate.sourceValue.name) }}；性别：{{ sourceText(candidate.sourceValue.gender) }}；生日：{{ sourceText(candidate.sourceValue.sourceBirthText ?? candidate.sourceValue.birthDate) }}</p><p>拟填生日精度：{{ candidate.sourceValue.birthDatePrecision === 'DAY' ? '日' : '待核实' }}</p><p class="source-raw">年龄（只读）：{{ sourceText(candidate.reviewOnlyAge) }}</p></div>
              </div>
              <p class="note">{{ candidateIssue(candidate) ?? sourceReasonText(candidate.reasonCode) }}</p>
            </div>
          </section>
          <p v-if="source.unmappedFields.length" class="note">未自动映射：{{ source.unmappedFields.join('、') }}</p>
          <el-button :disabled="!selectedSource.length && !selectedChildren.length" @click="applySource">将已选来源值预填到表单</el-button>
          <p v-if="prefilled" class="note">已预填到表单，请复核字段并填写变更原因后保存。保存原因会附上本次来源审阅记录：{{ sourceAudit.join('；') }}</p>
        </template>
      </section>
      <el-form-item label="变更原因" prop="changeReason" class="change-reason"><el-input v-model="form.changeReason" type="textarea" :rows="2" placeholder="说明创建或修订依据" maxlength="500" /></el-form-item>
    </el-form>
    <template #footer><el-button :disabled="saving" @click="close">取消</el-button><el-button type="primary" :loading="saving" @click="submit">保存会员</el-button></template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { createMember, updateMember, getMemberSourcePreview, type MemberDetail, type MemberChild, type MemberFormData, type MemberSourceChildCandidate, type MemberSourcePreview, type MemberUsageType } from '@/api/member'
import { useUserStore } from '@/store/modules/user'
import { displayValue } from '@/utils/display'
import { canPrefillSource, childCandidateIssue, childCollectionIssue, prefillSourceChildren, sourceReasonText } from '../sourceReview'
const props = defineProps<{ visible: boolean; member: MemberDetail | null }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'saved', value: MemberDetail): void }>()
const userStore = useUserStore()
const formRef = ref<FormInstance>()
const saving = ref(false), sourceLoading = ref(false), saveError = ref(''), prefilled = ref(false)
const source = ref<MemberSourcePreview | null>(null), selectedSource = ref<string[]>([])
const selectedChildren = ref<string[]>([]), sourceAudit = ref<string[]>([])
const emptyForm = () => ({ username: '', password: '', realName: '', phone: '', email: '', cardNo: '', planName: '', serviceStart: null as string | null, serviceEnd: null as string | null, usageType: 'UNKNOWN' as MemberUsageType, usedUses: undefined as number | undefined, totalUses: undefined as number | undefined, notes: '', address: '', children: [] as MemberChild[], changeReason: '' })
const form = reactive(emptyForm())
let sourceRequest = 0
const rules: FormRules = {
  username: [{ required: true, message: '请输入登录账号', trigger: 'blur' }, { min: 3, max: 20, message: '登录账号为3至20字符', trigger: 'blur' }, { pattern: /^[a-zA-Z0-9_\-\u4e00-\u9fa5]+$/, message: '登录账号包含非法字符', trigger: 'blur' }],
  password: [{ validator: (_rule, value, callback) => { if (!props.member && (!value || value.length < 6)) callback(new Error('请设置至少6位的初始密码')); else callback() }, trigger: 'blur' }],
  realName: [{ required: true, message: '请输入会员姓名', trigger: 'blur' }, { pattern: /^[\p{L}\s]+$/u, message: '姓名仅支持文字及空格', trigger: 'blur' }],
  phone: [{ pattern: /^(?:|1[3-9]\d{9})$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }],
  totalUses: [{ validator: (_rule, value, callback) => { if (form.usageType === 'LIMITED' && (value == null || !Number.isInteger(value) || value < 0)) callback(new Error('有限次数请填写非负整数总次数')); else callback() }, trigger: 'change' }],
  serviceEnd: [{ validator: (_rule, value, callback) => { if (form.serviceStart && value && value < form.serviceStart) callback(new Error('服务结束日不能早于开始日')); else callback() }, trigger: 'change' }],
  changeReason: [{ required: true, whitespace: true, message: '请填写变更原因', trigger: 'blur' }],
}
watch(() => [props.visible, props.member] as const, () => {
  sourceRequest++; sourceLoading.value = false; source.value = null; selectedSource.value = []; selectedChildren.value = []; sourceAudit.value = []; prefilled.value = false; saveError.value = ''
  if (!props.visible) return
  const member = props.member
  Object.assign(form, emptyForm(), member ? {
    username: member.username, realName: member.realName, phone: member.phone ?? '', email: member.email ?? '', cardNo: member.cardNo ?? '', planName: member.planName ?? '', serviceStart: member.serviceStart, serviceEnd: member.serviceEnd,
    usageType: member.usageType, usedUses: member.usedUses ?? undefined, totalUses: member.totalUses ?? undefined, notes: member.notes ?? '', address: member.address ?? '', children: (member.children ?? []).map(child => ({ ...child })),
  } : {})
  formRef.value?.clearValidate()
}, { immediate: true })
function addChild() { if (form.children.length < 30) form.children.push({ name: '', gender: '', birthDate: null, birthDatePrecision: 'UNKNOWN', sourceBirthText: '', notes: '' }) }
function close() { if (!saving.value) emit('update:visible', false) }
const textOrNull = (value: string | null | undefined) => value == null || value.trim() === '' ? null : value
async function submit() {
  if (!userStore.isAdmin || !formRef.value || saving.value) return
  try { await formRef.value.validate() } catch { return }
  const collectionIssue = childCollectionIssue(form.children)
  if (collectionIssue) { saveError.value = collectionIssue; return }
  const changeReason = sourceAudit.value.length ? `${form.changeReason}\n来源审阅：${sourceAudit.value.join('；')}` : form.changeReason
  if (changeReason.length > 500) { saveError.value = '变更原因与来源审阅记录合计不能超过 500 字符，请精简原因后保存。'; return }
  if (form.children.some(child => child.birthDatePrecision === 'DAY' && !child.birthDate)) { saveError.value = '生日精度为日时请填写完整生日，或改为待核实。'; return }
  saving.value = true; saveError.value = ''
  const data: MemberFormData = {
    realName: form.realName, phone: textOrNull(form.phone), email: textOrNull(form.email), cardNo: textOrNull(form.cardNo), planName: textOrNull(form.planName), serviceStart: form.serviceStart || null, serviceEnd: form.serviceEnd || null,
    usageType: form.usageType, usedUses: form.usedUses ?? null, totalUses: form.usageType === 'UNLIMITED' ? null : form.usageType === 'UNKNOWN' ? props.member?.totalUses ?? null : form.totalUses ?? null, notes: textOrNull(form.notes), address: textOrNull(form.address),
    children: form.children.map(child => ({ ...child, name: textOrNull(child.name), gender: textOrNull(child.gender), birthDate: child.birthDatePrecision === 'DAY' ? child.birthDate : null, sourceBirthText: textOrNull(child.sourceBirthText), notes: textOrNull(child.notes) })), changeReason,
  }
  try {
    const saved = props.member ? await updateMember(props.member.id, { ...data, revision: props.member.revision }) : await createMember({ ...data, username: form.username, password: form.password })
    ElMessage.success('会员资料已保存'); emit('saved', saved); emit('update:visible', false)
  } catch (error: any) {
    saveError.value = error?.response?.status === 409 ? '保存冲突：卡号已分配或资料已被修改。输入已保留，请关闭表单后刷新核对，再重新编辑。' : '保存失败，输入已保留，请核对提示后重试。'
  } finally { saving.value = false }
}
const fieldLabels: Record<string, string> = { realName: '姓名', phone: '手机号', cardNo: '卡号', planName: '原套餐名称', serviceStart: '服务开始日', serviceEnd: '服务结束日', usedUses: '已用次数', totalUses: '总次数', address: '地址', notes: '会员备注' }
const sourceText = (value: unknown) => displayValue(value, '未提供')
const childText = (child: MemberChild | null) => child ? `姓名：${sourceText(child.name)}；性别：${sourceText(child.gender)}；生日：${sourceText(child.birthDatePrecision === 'DAY' ? child.birthDate : child.sourceBirthText)}；精度：${({ DAY: '日', MONTH: '月', YEAR: '年', UNKNOWN: '待核实' })[child.birthDatePrecision]}` : '未关联已保存宝宝资料'
const candidateIssue = (candidate: MemberSourceChildCandidate) => childCandidateIssue(candidate, form.children, source.value?.childCandidates ?? [], source.value?.asOfDate)
function selectSource(field: string, selected: boolean) { selectedSource.value = selected ? [...new Set([...selectedSource.value, field])] : selectedSource.value.filter(item => item !== field) }
function selectChild(key: string, selected: boolean) { selectedChildren.value = selected ? [...new Set([...selectedChildren.value, key])] : selectedChildren.value.filter(item => item !== key) }
async function loadSource() {
  if (!props.member || !userStore.isAdmin || sourceLoading.value) return
  const request = ++sourceRequest
  sourceLoading.value = true; selectedSource.value = []; selectedChildren.value = []; source.value = null
  try { const preview = await getMemberSourcePreview(props.member.id); if (request === sourceRequest) source.value = preview }
  catch { /* The request layer displays the failure. */ }
  finally { if (request === sourceRequest) sourceLoading.value = false }
}
function applySource() {
  if (!userStore.isAdmin || !source.value?.sourceAvailable) return
  const childrenResult = prefillSourceChildren(form.children, selectedChildren.value, source.value.childCandidates ?? [], source.value.asOfDate)
  if (childrenResult.error) { saveError.value = childrenResult.error; return }
  const appliedFields: string[] = []
  for (const field of source.value.fields) {
    if (!selectedSource.value.includes(field.field) || !canPrefillSource(field)) continue
    appliedFields.push(fieldLabels[field.field] ?? field.field)
    if (field.field === 'usedUses') form.usedUses = field.sourceValue == null ? undefined : Number(field.sourceValue)
    else if (field.field === 'serviceStart' || field.field === 'serviceEnd') form[field.field] = field.sourceValue == null ? null : String(field.sourceValue)
    else if (['realName', 'phone', 'cardNo', 'planName', 'address', 'notes'].includes(field.field)) form[field.field as 'realName' | 'phone' | 'cardNo' | 'planName' | 'address' | 'notes'] = String(field.sourceValue)
  }
  if (!appliedFields.length && !selectedChildren.value.length) return
  form.children = childrenResult.children
  const slots = (source.value.childCandidates ?? []).filter(candidate => selectedChildren.value.includes(candidate.sourceKey)).map(candidate => candidate.sourceSlot)
  const audit = `原档案${source.value.archiveId ?? '待核实'}${appliedFields.length ? `，字段${appliedFields.join('/')}` : ''}${slots.length ? `，宝宝槽位${slots.join('/')}` : ''}`
  if (!sourceAudit.value.includes(audit)) sourceAudit.value.push(audit)
  saveError.value = ''; prefilled.value = true; selectedSource.value = []; selectedChildren.value = []
}
</script>

<style scoped>
h3 { margin: 20px 0 14px; font-size: 16px; } .note { color: var(--text-secondary); font-size: 13px; line-height: 1.7; margin: 8px 0 14px; }
.form-alert { margin-bottom: 16px; } .child-form { margin: 12px 0; padding: 14px; border: 1px solid var(--border-light); border-radius: 8px; }
.section-toolbar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; } h4 { margin: 0; }
.source-review { border-top: 1px solid var(--border-light); margin-top: 20px; } .source-field { margin: 12px 0; padding-bottom: 10px; border-bottom: 1px solid var(--border-light); }
.source-values { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; } .change-reason { margin-top: 24px; }
.source-child { padding: 12px; margin: 12px 0; border: 1px solid var(--border-light); border-radius: 8px; } .source-raw { white-space: pre-wrap; }
@media (max-width: 600px) { .source-values { grid-template-columns: 1fr; } }
</style>
