<template>
  <el-card v-if="userStore.isReader" shadow="hover" class="membership-card" v-loading="loading">
    <template #header>我的会员卡</template>
    <template v-if="member">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="卡号">{{ displayValue(member.cardNo) }}</el-descriptions-item>
        <el-descriptions-item label="登录状态">{{ member.accountStatus === 1 ? '启用' : member.accountStatus === 0 ? '禁用' : '待核实' }}</el-descriptions-item>
      </el-descriptions>
      <MemberBenefits :plan-name="member.planName" :service-state="member.serviceStatus" :service-start="member.serviceStart" :service-end="member.serviceEnd" :uses-type="member.usageType" :used-uses="member.usedUses" :total-uses="member.totalUses" :remaining-uses="member.remainingUses" :as-of-date="member.asOfDate" />
    </template>
    <p v-else-if="!loading">会员资料暂不可用，请稍后刷新。</p>
  </el-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getMyMembership, type MemberSummary } from '@/api/member'
import { useUserStore } from '@/store/modules/user'
import { displayValue } from '@/utils/display'
import MemberBenefits from './MemberBenefits.vue'
const userStore = useUserStore()
const member = ref<MemberSummary | null>(null)
const loading = ref(false)
onMounted(async () => {
  if (!userStore.isReader) return
  loading.value = true
  try { member.value = await getMyMembership() } catch { member.value = null }
  finally { loading.value = false }
})
</script>

<style scoped>
.membership-card { margin-top: 20px; } .member-benefits { margin-top: 20px; }
</style>
