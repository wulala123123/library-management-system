<template>
  <el-card v-if="userStore.isReader" shadow="hover" class="membership-card" v-loading="loading">
    <template #header>我的会员卡</template>
    <template v-if="member">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="卡号">{{ displayValue(member.cardNo) }}</el-descriptions-item>
        <el-descriptions-item label="登录状态">{{ member.accountStatus === 1 ? '启用' : member.accountStatus === 0 ? '禁用' : '待核实' }}</el-descriptions-item>
      </el-descriptions>
      <MemberBenefits :plan-name="member.planName" :service-state="member.serviceStatus" :service-start="member.serviceStart" :service-end="member.serviceEnd" :uses-type="member.usageType" :used-uses="member.usedUses" :total-uses="member.totalUses" :remaining-uses="member.remainingUses" :as-of-date="member.asOfDate" />
      <EntitlementRules v-if="rules" :rules="rules" />
    </template>
    <p v-else-if="!loading">会员资料暂不可用，请稍后刷新。</p>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type { MemberSummary } from '@/api/member'
import { getMyEntitlement } from '@/api/memberEntitlement'
import type { PlanRules } from '@/api/membershipPlan'
import { useUserStore } from '@/store/modules/user'
import { displayValue } from '@/utils/display'
import MemberBenefits from './MemberBenefits.vue'
import EntitlementRules from './EntitlementRules.vue'
const userStore = useUserStore()
const member = ref<MemberSummary | null>(null)
const rules = ref<PlanRules | null>(null)
const loading = ref(false)
onMounted(async () => {
  if (!userStore.isReader) return
  loading.value = true
  try { const value = await getMyEntitlement(); member.value = value.member; rules.value = value.effectiveRules } catch { member.value = null; rules.value = null }
  finally { loading.value = false }
})
</script>

<style scoped>
.membership-card { margin-top: 20px; } .member-benefits { margin-top: 20px; }
</style>
