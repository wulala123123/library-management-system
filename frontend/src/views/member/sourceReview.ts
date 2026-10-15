import type { MemberSourceField } from '@/api/member'

const textFields = ['realName', 'phone', 'cardNo', 'planName']
export function canPrefillSource(field: MemberSourceField): boolean {
  if (field.reviewOnly || field.field === 'totalUses') return false
  const value = field.sourceValue
  if (textFields.includes(field.field)) return value == null || typeof value === 'string'
  if (field.field === 'usedUses') return value === null || (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) || (typeof value === 'string' && /^\d+$/.test(value.trim()) && Number.isSafeInteger(Number(value)))
  if (field.field !== 'serviceStart' && field.field !== 'serviceEnd') return false
  if (value === null || value === '') return true
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}
