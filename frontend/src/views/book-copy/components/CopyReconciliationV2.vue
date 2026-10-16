<template>
  <section v-if="value" class="copy-reconciliation-v2">
    <p>在库登记 {{ value.registeredInStockCopies }} 册 · 临时停用 {{ value.registeredDisabledCopies }} 册 · 已报损保留 {{ value.registeredWrittenOffCopies }} 册 · 原状态待核实 {{ value.registeredUnknownCopies }} 册</p>
    <p>在库 / 可借 / 在借差额：{{ value.inStockDifference }} / {{ value.availableDifference }} / {{ value.borrowedDifference }} 册；未解释平衡差额 {{ value.unexplainedStockBalanceDifference }} 册</p>
    <p v-if="value.hasHistoricalDifference || value.hasUnknownStatus || value.hasLoanLinkAnomaly" class="warning">{{ value.hasHistoricalDifference ? '历史差额保留待核实。' : '' }}{{ value.hasUnknownStatus ? '原状态含义待核实，不自动转换。' : '' }}{{ value.hasLoanLinkAnomaly ? '借阅关联异常待核实。' : '' }}</p>
    <p v-else>本口径数量一致{{ value.hasKnownUnavailable ? '；已知停用和报损单独保留，不作为未知状态。' : '。' }}</p>
  </section>
</template>
<script setup lang="ts">
import type { CopyReconciliationV2 } from '@/api/bookCopyAction'
defineProps<{ value?: CopyReconciliationV2 | null }>()
</script>
<style scoped>
.copy-reconciliation-v2 { font-size: 13px; line-height: 1.6; }p { margin: 6px 0; }.warning { color: var(--el-color-warning-dark-2); }
</style>
