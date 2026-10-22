<template>
  <div class="statistics-page">
    <div class="export-toolbar"><el-button type="primary" :loading="exporting" @click="handleExport">导出基础指标 CSV</el-button></div>
    <StatisticsScope :stats="stats" />
    <p v-if="user.isAdmin">基础指标沿用原库存与会员口径：库存只含启用书目；借阅报告覆盖全部符合筛选的明细，分别计算。</p>
    <BorrowOperationsReport v-if="user.isAdmin" />
    <el-row :gutter="20">
      <el-col v-if="!user.isAdmin" :xs="24" :lg="16">
        <el-card shadow="hover" v-loading="loading">
          <template #header>近7天借出册数</template>
          <div class="chart-container" style="height: 350px;">
            <div class="mock-chart">
              <div class="chart-bars">
                <div v-for="(bar, i) in trendData" :key="i" class="chart-bar-wrapper">
                  <span class="trend-count">{{ bar.display }} 册</span>
                  <div class="chart-bar" :style="{ height: bar.value + '%' }"></div>
                  <span class="chart-label">{{ bar.label }}</span>
                </div>
              </div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="8">
        <el-card shadow="hover"><template #header>书目种数分类占比</template>
          <div style="padding: 16px;">
            <div v-for="(cat, i) in categoryData" :key="i" class="cat-item">
              <span class="cat-name">{{ cat.name }}</span><el-progress :percentage="cat.percentage" :color="cat.color" />
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px;">
      <el-col v-if="!user.isAdmin" :xs="24" :lg="12">
        <el-card shadow="hover"><template #header>热门图书 TOP10（已接入借阅明细，登记书名）</template>
          <div v-if="hotBooks.length">
            <div v-for="(book, i) in hotBooks" :key="i" class="hot-item">
              <span class="rank" :class="{ top3: i < 3 }">{{ i + 1 }}</span>
              <span class="title">{{ book.title }}</span>
              <span class="count">{{ book.borrowCount }}册</span>
            </div>
          </div>
          <el-empty v-else description="暂无借阅数据" />
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="hover"><template #header>统计数据汇总</template>
          <el-table :data="summaryData" border stripe size="default">
            <el-table-column prop="label" label="统计指标" width="170" />
            <el-table-column prop="value" label="数值" width="100" align="center" />
            <el-table-column prop="unit" label="单位" width="65" />
            <el-table-column prop="description" label="说明" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
    </el-row>
    <InventoryReconciliation compact />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getStatistics, exportStatistics, getBorrowTrend, getCategoryDistribution, getHotBooks, type DashboardStats } from '@/api/dashboard'
import { getMetricRows, getSummaryRows } from '@/utils/statistics'
import { downloadBlob } from '@/utils/download'
import { useUserStore } from '@/store/modules/user'
import StatisticsScope from '@/components/statistics/StatisticsScope.vue'
import InventoryReconciliation from '@/components/statistics/InventoryReconciliation.vue'
import BorrowOperationsReport from '@/components/statistics/BorrowOperationsReport.vue'

const user = useUserStore()

const loading = ref(false)

const trendData = ref<Array<{ label: string; value: number; display: number }>>([])

const categoryData = ref<Array<{ name: string; percentage: number; color: string }>>([])

const hotBooks = ref<{ title: string; borrowCount: number }[]>([])

const stats = ref<DashboardStats | null>(null)
const summaryData = computed(() => user.isAdmin ? getMetricRows(stats.value) : getSummaryRows(stats.value))
const exporting = ref(false)

onMounted(async () => {
  await Promise.all([fetchDashboardStats(), user.isAdmin ? Promise.resolve() : fetchBorrowTrend(), fetchCategoryDistribution(), user.isAdmin ? Promise.resolve() : fetchHotBooks()])
})

async function fetchDashboardStats() {
  loading.value = true
  try {
    stats.value = await getStatistics()
  } catch { stats.value = null } finally { loading.value = false }
}

async function handleExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    const csv = await exportStatistics()
    downloadBlob(csv, '基础统计指标.csv')
    ElMessage.success('导出成功，统计日期和范围见 CSV')
  } catch {} finally { exporting.value = false }
}

async function fetchBorrowTrend() {
  try {
    const data = await getBorrowTrend()
    if (data && data.length) {
      const maxVal = Math.max(...data.map(d => d.value), 1)
      trendData.value = data.map(d => ({ label: d.label, value: Math.round((d.value / maxVal) * 100), display: d.value }))
    }
  } catch {}
}

async function fetchCategoryDistribution() {
  try {
    const data = await getCategoryDistribution()
    if (data && data.length) {
      categoryData.value = data
    }
  } catch {}
}

async function fetchHotBooks() {
  try {
    const data = await getHotBooks()
    hotBooks.value = data || []
  } catch {}
}
</script>

<style lang="scss" scoped>
.statistics-page {
  .export-toolbar { display: flex; justify-content: flex-end; margin-bottom: 14px; }
  .mock-chart {
    height: 100%;
    .chart-bars {
      display: flex; justify-content: space-around; align-items: flex-end;
      height: 280px; gap: 6px;
      .chart-bar-wrapper {
        flex: 1; display: flex; flex-direction: column; align-items: center;
        .chart-bar {
          width: 100%; background: linear-gradient(180deg, #1890ff, #36cfc9);
          border-radius: 3px 3px 0 0; transition: all 0.3s ease;
          &:hover { opacity: 0.85; transform: scaleY(1.05); }
        }
        .chart-label { margin-top: 6px; font-size: 10px; color: var(--text-secondary); }
      }
    }
  }

  .cat-item { margin-bottom: 14px; &:last-child { margin-bottom: 0; }
    .cat-name { display: inline-block; width: 80px; font-size: 13px; color: var(--text-regular); }
  }

  .hot-item {
    display: flex; align-items: center; padding: 10px 0;
    border-bottom: 1px solid var(--border-light);
    &:last-child { border-bottom: none; }
    .rank {
      width: 24px; height: 24px; border-radius: 50%; background: var(--bg-page);
      text-align: center; line-height: 24px; font-size: 12px; font-weight: 600;
      margin-right: 10px; color: var(--text-secondary);
      &.top3 { background: var(--primary-color); color: white; }
    }
    .title { flex: 1; font-size: 14px; color: var(--text-primary); }
    .count { color: var(--text-secondary); font-size: 13px; }
  }
}
</style>
