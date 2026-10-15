<template>
  <details class="statistics-scope">
    <summary>统计日期：{{ displayValue(stats?.asOfDate, '待核实') }}（Asia/Shanghai） · 查看统计范围</summary>
    <dl>
      <template v-for="metric in rows" :key="metric.key">
        <dt>{{ metric.label }}</dt><dd>{{ metric.description }}</dd>
      </template>
    </dl>
  </details>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { BasicStats } from '@/api/dashboard'
import { displayValue } from '@/utils/display'
import { basicMetrics, getMetricRows } from '@/utils/statistics'

const props = defineProps<{ stats?: BasicStats | null; inventoryOnly?: boolean }>()
const rows = computed(() => getMetricRows(props.stats, props.inventoryOnly ? basicMetrics.slice(0, 6) : basicMetrics))
</script>

<style scoped>
.statistics-scope { margin: 0 0 20px; color: var(--text-secondary, #606266); font-size: 13px; line-height: 1.7; }
summary { cursor: pointer; }
dl { display: grid; grid-template-columns: minmax(110px, auto) 1fr; gap: 6px 16px; padding: 12px; background: var(--bg-white, #fff); }
dt { font-weight: 600; } dd { margin: 0; overflow-wrap: anywhere; }
</style>
