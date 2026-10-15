import { displayValue } from '@/utils/display'

export type ServiceState = 'WAITING' | 'ACTIVE' | 'EXPIRED' | 'UNKNOWN'
export type UsesType = 'UNKNOWN' | 'LIMITED' | 'UNLIMITED'

export function serviceStateText(value: string | null | undefined): string {
  return ({ WAITING: '待生效', ACTIVE: '有效', EXPIRED: '已到期', UNKNOWN: '待核实' } as Record<string, string>)[value ?? ''] ?? '待核实'
}

export function serviceStateTag(value: string | null | undefined) {
  return ({ WAITING: 'warning', ACTIVE: 'success', EXPIRED: 'danger', UNKNOWN: 'info' } as const)[value as ServiceState] ?? 'info'
}

export function totalUsesText(type: string | null | undefined, total: number | null | undefined): string {
  if (type === 'UNLIMITED') return '不限'
  if (type !== 'LIMITED') return '待核实'
  return displayValue(total, '待核实')
}

export function remainingUsesText(type: string | null | undefined, used: number | null | undefined, total: number | null | undefined, remaining: number | null | undefined): string {
  if (type === 'UNLIMITED') return '不限'
  if (type !== 'LIMITED' || used == null || total == null) return '待核实'
  // Keep the service result, including negative remaining uses, without inventing or clamping a balance.
  return displayValue(remaining, '待核实')
}

export function dateText(value: string | null | undefined) {
  return displayValue(value?.split('T')[0], '待核实')
}
