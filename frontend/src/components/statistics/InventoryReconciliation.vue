<template>
  <section class="reconciliation" v-loading="loading">
    <h3>实体册登记与库存核对</h3>
    <template v-if="result">
      <p class="summary">{{ result.asOfDate }}（Asia/Shanghai）· 原始登记口径：差异书目 {{ result.mismatchedTitles }} 种。</p>
      <p class="summary">汇总库存：总数 {{ result.totalCopies }} 册 / 可借 {{ result.availableCopies }} 册 / 在借 {{ result.borrowedCopies }} 册；
        已登记实体册：总数 {{ result.registeredCopies }} 册 / 可借 {{ result.registeredAvailableCopies }} 册 / 在借 {{ result.registeredBorrowedCopies }} 册。</p>
      <CopyReconciliationV2 :value="result.reconciliationV2" />
      <p v-if="result.registeredOtherCopies != null && !result.reconciliationV2" class="summary">已登记其他状态实体册：{{ result.registeredOtherCopies }} 册。</p>
      <p v-if="result.reconciliationV2" class="summary">全部历史登记数含已报损保留行；原始登记口径的差异书目与差额保留旧定义，已知停用或报损也可能计入。当前是否待核实见上方及各书目的在库核对结果。</p>
      <details v-if="!compact" class="scope"><summary>查看核对范围与差额定义</summary>
        <p>{{ displayValue(result.statisticalScope.reconciliation, '核对范围待核实') }}</p>
      </details>
      <p v-if="compact" class="summary"><router-link to="/inventory">查看差异明细与核对范围</router-link></p>
      <el-table v-else-if="result.items.length" :data="result.items" border stripe size="small">
        <el-table-column prop="bookId" label="书目 ID" width="85" />
        <el-table-column prop="title" label="书名" min-width="180" show-overflow-tooltip />
        <el-table-column v-if="result.reconciliationV2" label="在库核对与已知状态" min-width="390"><template #default="{ row }"><CopyReconciliationV2 :value="row.reconciliationV2" /></template></el-table-column>
        <el-table-column label="总数（库存 / 登记）" min-width="145"><template #default="{ row }">{{ row.totalCopies }} / {{ row.registeredCopies }} 册</template></el-table-column>
        <el-table-column label="可借（库存 / 登记）" min-width="145"><template #default="{ row }">{{ row.availableCopies }} / {{ row.registeredAvailableCopies }} 册</template></el-table-column>
        <el-table-column label="在借（记录 / 登记）" min-width="145"><template #default="{ row }">{{ row.borrowedCopies }} / {{ row.registeredBorrowedCopies }} 册</template></el-table-column>
        <el-table-column v-if="result.registeredOtherCopies != null" prop="registeredOtherCopies" label="登记其他状态（册）" min-width="140" />
        <el-table-column prop="totalDifference" label="总数差额（原始登记口径，册）" min-width="210" />
        <el-table-column prop="availableDifference" label="可借差额（原始登记口径，册）" min-width="210" />
        <el-table-column prop="borrowedDifference" label="在借差额（原始登记口径，册）" min-width="210" />
        <el-table-column prop="stockBalanceDifference" label="库存平衡差额（原始登记口径，册）" min-width="235" />
      </el-table>
      <p v-else-if="!compact" class="summary">本次核对未发现差异。</p>
    </template>
    <p v-else class="summary">{{ loading ? '正在读取核对结果…' : '核对结果暂不可用' }}</p>
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { getInventoryReconciliation, type InventoryReconciliation } from '@/api/dashboard'
import { displayValue } from '@/utils/display'
import CopyReconciliationV2 from '@/views/book-copy/components/CopyReconciliationV2.vue'

defineProps<{ compact?: boolean }>()
const result = ref<InventoryReconciliation | null>(null)
const loading = ref(false)
async function refresh() {
  loading.value = true
  try { result.value = await getInventoryReconciliation() }
  catch { result.value = null }
  finally { loading.value = false }
}
onMounted(refresh)
defineExpose({ refresh })
</script>

<style scoped>
.reconciliation { margin: 20px 0; padding: 20px; background: var(--bg-white, #fff); border: 1px solid var(--border-light, #e0e6ed); border-radius: 12px; }
h3 { font-size: 16px; margin-bottom: 10px; } .summary, .scope { margin: 8px 0; font-size: 13px; color: var(--text-secondary, #606266); line-height: 1.7; }
summary { cursor: pointer; } .scope p { margin: 4px 0; }
</style>
