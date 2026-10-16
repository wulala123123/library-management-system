<template>
  <div class="inventory-management">
    <el-row :gutter="20" class="stat-row">
      <el-col v-for="metric in statRows" :key="metric.key" :xs="12" :sm="8" :lg="4"><div class="mini-stat total"><div class="value">{{ metric.value }} <small>{{ metric.unit }}</small></div><div class="label">{{ metric.label }}</div></div></el-col>
    </el-row>
    <StatisticsScope :stats="stats" inventory-only />
    <InventoryReconciliation ref="reconciliationRef" />

    <div class="card-container">
      <div class="table-toolbar">
        <div class="toolbar-left"><el-button @click="refreshAll">刷新数据</el-button></div>
        <div class="toolbar-right"><span>共 {{ total }} 种图书</span></div>
      </div>

      <el-table v-loading="loading" :data="inventoryList" stripe border size="default">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="title" label="登记书名" min-width="180" show-overflow-tooltip />
        <el-table-column prop="isbn" label="ISBN" width="140" show-overflow-tooltip />
        <el-table-column label="库位" min-width="140" show-overflow-tooltip><template #default="{ row }">{{ displayValue(row.location) }}</template></el-table-column>
        <el-table-column prop="stockTotal" label="实体册总数（册）" width="140" align="center" />
        <el-table-column prop="stockAvailable" label="可借册数（册）" width="130" align="center" />
        <el-table-column label="库存情况" width="180" align="center">
          <template #default="{ row }">
            <el-progress :percentage="getStockPercent(row)" :stroke-width="8" :color="getStockColor(row)" />
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">{{ row.status === 1 ? '在馆' : '下架' }}</el-tag>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrapper">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="fetchInventory"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getInventoryStats, type InventoryStats } from '@/api/dashboard'
import { getBookPage } from '@/api/book'
import type { Book } from '@/types'
import { displayValue } from '@/utils/display'
import { getMetricRows, inventoryMetrics } from '@/utils/statistics'
import StatisticsScope from '@/components/statistics/StatisticsScope.vue'
import InventoryReconciliation from '@/components/statistics/InventoryReconciliation.vue'

const loading = ref(false)
const inventoryList = ref<Book[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(10)

const stats = ref<InventoryStats | null>(null)
const statRows = computed(() => getMetricRows(stats.value, [...inventoryMetrics]))
const reconciliationRef = ref<InstanceType<typeof InventoryReconciliation>>()

onMounted(() => {
  fetchStats()
  fetchInventory()
})

async function fetchStats() {
  try {
    stats.value = await getInventoryStats()
  } catch { stats.value = null }
}

function refreshAll() { fetchStats(); fetchInventory(); reconciliationRef.value?.refresh() }

async function fetchInventory() {
  loading.value = true
  try {
    const res = await getBookPage({ page: page.value, size: size.value })
    inventoryList.value = res?.records || []
    total.value = res?.total || 0
  } catch {
    inventoryList.value = []
    total.value = 0
  } finally { loading.value = false }
}

function getStockPercent(row: Book): number {
  if (!row.stockTotal) return 0
  return Math.round((row.stockAvailable / row.stockTotal) * 100)
}

function getStockColor(row: Book): string {
  const rate = getStockPercent(row)
  if (rate <= 10) return '#ff4d4f'
  if (rate <= 30) return '#faad14'
  return '#52c41a'
}
</script>

<style lang="scss" scoped>
.inventory-management {
  .stat-row { margin-bottom: 20px;
    .mini-stat {
      background: var(--bg-white); border-radius: var(--border-radius-base);
      padding: 20px; text-align: center; box-shadow: var(--shadow-sm);
      .value { font-size: 28px; font-weight: 700; line-height: 1.2; }
      .label { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
      &.total .value { color: #1890ff; } &.available .value { color: #52c41a; }
      &.borrowed .value { color: #faad14; } &.low-stock .value { color: #ff4d4f; }
    }
  }
  .toolbar-left { display: flex; gap: 8px; }
}
</style>
