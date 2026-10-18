<template>
  <el-dialog :model-value="visible" title="会员档案" width="min(960px, 96vw)" @update:model-value="close">
    <div v-loading="loading">
      <template v-if="member && canRead">
        <section>
          <h3>基础资料</h3>
          <el-descriptions :column="2" border>
            <el-descriptions-item label="姓名">{{ displayValue(member.realName) }}</el-descriptions-item>
            <el-descriptions-item label="登录账号">{{ member.username }}</el-descriptions-item>
            <el-descriptions-item label="手机号">{{ displayValue(member.phone) }}</el-descriptions-item>
            <el-descriptions-item label="邮箱">{{ displayValue(member.email) }}</el-descriptions-item>
            <el-descriptions-item label="卡号">{{ displayValue(member.cardNo) }}</el-descriptions-item>
            <el-descriptions-item label="账号登录状态">{{ member.accountStatus === 1 ? '启用' : member.accountStatus === 0 ? '禁用' : '待核实' }}</el-descriptions-item>
          </el-descriptions>
          <p class="note">{{ member.profilePresent ? '' : '尚未登记业务档案。' }}账号角色、密码与启停由账号管理维护。</p>
        </section>
        <section v-if="userStore.isAdmin" class="family-section">
          <h3>家庭及宝宝资料</h3>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="地址">{{ displayValue(member.address) }}</el-descriptions-item>
            <el-descriptions-item label="会员备注">{{ displayValue(member.notes) }}</el-descriptions-item>
          </el-descriptions>
          <el-table v-if="member.children?.length" :data="member.children" border size="small">
            <el-table-column prop="name" label="宝宝姓名" min-width="100" />
            <el-table-column prop="gender" label="性别" width="80"><template #default="{ row }">{{ displayValue(row.gender) }}</template></el-table-column>
            <el-table-column label="生日 / 来源日期" min-width="160"><template #default="{ row }">{{ row.birthDatePrecision === 'DAY' ? displayValue(row.birthDate) : displayValue(row.sourceBirthText, '待核实') }}</template></el-table-column>
            <el-table-column label="日期精度" width="100"><template #default="{ row }">{{ precisionText[row.birthDatePrecision] ?? '待核实' }}</template></el-table-column>
            <el-table-column prop="notes" label="备注" min-width="140" show-overflow-tooltip />
          </el-table>
          <p v-else class="note">未设置宝宝资料</p>
        </section>
        <MemberBenefits :plan-name="member.planName" :service-state="member.serviceStatus" :service-start="member.serviceStart" :service-end="member.serviceEnd" :uses-type="member.usageType" :used-uses="member.usedUses" :total-uses="member.totalUses" :remaining-uses="member.remainingUses" :as-of-date="member.asOfDate" />
        <p v-if="member.overused" class="warning">已用次数超过总次数，请核对来源及调整记录。</p>
        <MemberEntitlementPanel v-if="userStore.isAdmin" :member-id="member.id" @saved="refresh" />
        <section v-if="userStore.isAdmin" class="finance-section">
          <h3>历史费用与押金</h3>
          <el-button type="primary" :disabled="!financeTarget" @click="openFinance">查看财务台账与来源核对</el-button>
          <p v-if="!financeTarget" class="note">会员编号需核对后再读取财务台账。</p>
          <el-descriptions :column="3" border>
            <el-descriptions-item label="历史实缴费用（元）">{{ displayValue(member.financialFacts?.paidFee, '未记录') }}</el-descriptions-item>
            <el-descriptions-item label="历史实缴押金（元）">{{ displayValue(member.financialFacts?.depositAmount, '未记录') }}</el-descriptions-item>
            <el-descriptions-item label="原押金状态">{{ displayValue(member.financialFacts?.depositStatus, '未记录') }}</el-descriptions-item>
          </el-descriptions>
          <p class="note">以上为历史事实，不代表当前应收金额或可退余额。</p>
        </section>
        <section v-if="userStore.isAdmin" class="borrow-section">
          <h3>借阅历史（最近 20 条明细）</h3>
          <el-table :data="member.borrowHistory ?? []" border size="small">
            <el-table-column prop="bookTitle" label="图书" min-width="160" show-overflow-tooltip />
            <el-table-column prop="borrowDate" label="借出日期" width="120" />
            <el-table-column label="应还日期" width="135"><template #default="{ row }">{{ dueDateText(row) }}</template></el-table-column>
            <el-table-column prop="returnDate" label="归还日期" width="120" />
            <el-table-column label="状态" width="90"><template #default="{ row }">{{ borrowStateText(row.status) }}</template></el-table-column>
          </el-table>
        </section>
        <section v-if="userStore.isAdmin" class="source-section">
          <h3>来源与修订</h3>
          <p class="note">来源：{{ displayValue(member.sourceSystem, '本系统') }} · {{ displayValue(member.sourceKey) }}；修订版本：{{ member.revision }}；最近修订：{{ displayValue(member.updatedAt, '未记录') }}</p>
        </section>
      </template>
      <p v-else-if="!loading">会员资料暂不可用。</p>
    </div>
    <template #footer>
      <el-button @click="close(false)">关闭</el-button>
      <el-button v-if="userStore.isAdmin && member" type="primary" @click="emit('edit', member)">编辑会员</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { getMemberById, type MemberDetail } from '@/api/member'
import { useUserStore } from '@/store/modules/user'
import { displayValue } from '@/utils/display'
import { dueDateText } from '@/utils/borrowDates'
import MemberBenefits from './MemberBenefits.vue'
import MemberEntitlementPanel from './MemberEntitlementPanel.vue'
const props = defineProps<{ visible: boolean; memberId: number | null }>()
const emit = defineEmits<{ (e: 'update:visible', value: boolean): void; (e: 'edit', value: MemberDetail): void }>()
const userStore = useUserStore()
const router = useRouter()
const accessInvalidated = ref(false)
const canRead = computed(() => !accessInvalidated.value && userStore.token !== '' && (userStore.isAdmin || userStore.isLibrarian))
const member = ref<MemberDetail | null>(null)
const financeTarget = computed(() => member.value && Number.isSafeInteger(member.value.id) && member.value.id > 0 ? String(member.value.id) : null)
const loading = ref(false)
let requestNumber = 0
const precisionText: Record<string, string> = { DAY: '日', MONTH: '月', YEAR: '年', UNKNOWN: '待核实' }
const borrowStateText = (state: string) => ({ BORROWING: '借阅中', OVERDUE: '已逾期', RETURNED: '已归还' }[state] ?? state)
function close(value: boolean) { emit('update:visible', value) }
function openFinance() { if (!userStore.isAdmin || !financeTarget.value || !props.visible) return; const memberId=financeTarget.value;close(false);router.push({path:`/member-finance/${memberId}`}) }
async function refresh() {
  const request = ++requestNumber
  member.value = null
  if (!props.visible || props.memberId == null || !canRead.value) { loading.value = false; return }
  loading.value = true
  try { const value = await getMemberById(props.memberId); if (request === requestNumber) member.value = value }
  catch { /* The request layer displays the failure. */ }
  finally { if (request === requestNumber) loading.value = false }
}
function invalidateAccess() { accessInvalidated.value=true; ++requestNumber; member.value=null; loading.value=false }
window.addEventListener('auth-access-invalidated',invalidateAccess)
window.addEventListener('member-finance-access-revoked',invalidateAccess)
onBeforeUnmount(()=>{++requestNumber;member.value=null;window.removeEventListener('auth-access-invalidated',invalidateAccess);window.removeEventListener('member-finance-access-revoked',invalidateAccess)})
watch(() => [userStore.userInfo?.id,userStore.userInfo?.role,userStore.token,userStore.isAdmin,userStore.isLibrarian],()=>{accessInvalidated.value=false;refresh()},{flush:'sync'})
watch(() => [props.visible, props.memberId], refresh, { immediate: true,flush:'sync' })
defineExpose({ refresh })
</script>

<style scoped>
section { margin: 0 0 24px; } h3 { margin: 0 0 12px; font-size: 16px; }
.note { margin: 10px 0; color: var(--text-secondary); font-size: 13px; line-height: 1.7; overflow-wrap: anywhere; }
.warning { color: var(--el-color-warning); margin: 10px 0; }
</style>
