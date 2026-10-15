import type { BasicStats, DashboardStats } from '@/api/dashboard'
import { displayValue } from './display'

export const inventoryMetrics = [
  { key: 'catalogTitles', label: '图书种数', unit: '种' },
  { key: 'totalCopies', label: '实体册总数', unit: '册' },
  { key: 'availableCopies', label: '可借册数', unit: '册' },
  { key: 'borrowedCopies', label: '在借册数', unit: '册' },
  { key: 'availableTitles', label: '可借种数', unit: '种' },
  { key: 'lowStockTitles', label: '低库存种数', unit: '种' },
] as const

export const memberMetrics = [
  { key: 'memberCount', label: '会员人数', unit: '人' },
  { key: 'accountCount', label: '账号总数', unit: '个' },
  { key: 'validMemberCount', label: '有效会员人数', unit: '人' },
  { key: 'unknownValidityMemberCount', label: '有效期待核实会员人数', unit: '人' },
] as const

export const basicMetrics = [...inventoryMetrics, ...memberMetrics]

export function getMetricRows(stats: BasicStats | null | undefined, metrics = basicMetrics) {
  return metrics.map(metric => ({
    ...metric,
    value: displayValue(stats?.[metric.key], '待核实'),
    description: displayValue(stats?.statisticalScope?.[metric.key], '口径待核实'),
  }))
}

export function getSummaryRows(stats: DashboardStats | null | undefined) {
  return [
    ...getMetricRows(stats),
    ...[
      { key: 'todayBorrows', label: '今日借出册数', unit: '册' },
      { key: 'overdueCount', label: '逾期未还册数', unit: '册' },
    ].map(metric => ({
      ...metric,
      value: displayValue(stats?.[metric.key as 'todayBorrows' | 'overdueCount'], '待核实'),
      description: displayValue(stats?.statisticalScope?.[metric.key], '口径待核实'),
    })),
  ]
}

export function downloadCsv(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}
