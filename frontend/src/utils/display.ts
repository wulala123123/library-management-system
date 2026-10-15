/** Display missing source data without replacing known zero values. */
export function displayValue(value: unknown, missing = '未设置'): string {
  if (value == null || (typeof value === 'string' && value.trim() === '')) return missing
  return String(value)
}

export function displayTotalUses(value: number | null | undefined): string {
  if (value === null) return '不限'
  return displayValue(value, '待核实')
}

export function displayPrice(value: number | null | undefined): string {
  return value == null ? '未设置' : `¥${value.toFixed(2)}`
}
