import type { EntitlementConfirmation, EntitlementInput, EntitlementView } from '@/api/memberEntitlement'
import { emptyDefinition, normalizedDefinition } from '@/views/membership-plan/planDefinition'

export function entitlementInput(view: EntitlementView): EntitlementInput {
  const m = view.member
  return {
    revision: m.revision, planVersionId: view.snapshot?.planVersionId ?? null,
    actual: { planName: m.planName, serviceStart: m.serviceStart, serviceEnd: m.serviceEnd, usageType: m.usageType, usedUses: m.usedUses, totalUses: m.totalUses },
    rules: JSON.parse(JSON.stringify(view.effectiveRules)), sourceReferences: [...(view.snapshot?.sourceReferences ?? [])],
    reason: '', historyBasis: view.snapshot?.historyBasis ?? null,
  }
}
export function normalizedEntitlement(input: EntitlementInput): EntitlementInput {
  const copy = JSON.parse(JSON.stringify(input)) as EntitlementInput
  if (copy.actual.usageType === 'UNLIMITED') copy.actual.totalUses = null
  copy.rules.usageType = copy.actual.usageType
  copy.rules.totalUses = copy.actual.usageType === 'LIMITED' ? copy.actual.totalUses : null
  copy.rules = normalizedDefinition({ ...emptyDefinition(), rules: copy.rules }).rules
  copy.historyBasis = copy.historyBasis?.trim() ? copy.historyBasis : null
  return copy
}
export function createEntitlementAttempts() {
  let last: { fingerprint: string; body: EntitlementConfirmation } | null = null
  return (input: EntitlementInput, previewHash: string): EntitlementConfirmation => {
    const body = normalizedEntitlement(input), fingerprint = JSON.stringify({ body, previewHash })
    if (last?.fingerprint === fingerprint) return JSON.parse(JSON.stringify(last.body))
    last = { fingerprint, body: { ...body, previewHash, confirmed: true, idempotencyKey: crypto.randomUUID() } }
    return JSON.parse(JSON.stringify(last.body))
  }
}
export const entitlementFieldLabel = (field: string): string => ({
  planName: '实际套餐名称', serviceStart: '实际开始日', serviceEnd: '实际结束日', usageType: '次数类型', usedUses: '累计已用次数', totalUses: '总次数',
  serviceDurationDays: '套餐时长', dayCountConvention: '服务期算法', perOrderLimit: '单次借书上限', activeLoanLimit: '累计在借上限', dueDateType: '归还期限类型', borrowDays: '借阅天数', renewalPermission: '续借权限', renewalLimit: '续借次数', renewalDays: '续借天数', languages: '语种范围', pickupMethods: '取书方式',
} as Record<string, string>)[field.split('.').pop() ?? field] ?? field
export function entitlementValue(value: unknown): string {
  if (value == null || value === '') return '待核实'
  if (typeof value === 'object') {
    const item = value as { mode?: string; value?: number | null; values?: string[] }
    if (item.mode === 'UNKNOWN') return '待核实'
    if (item.mode === 'UNLIMITED' || item.mode === 'ANY') return '不限'
    if (item.mode === 'LIMITED') return item.value == null ? '有限，数值待核实' : String(item.value)
    if (item.mode === 'ALLOW_LIST') return item.values?.join('、') || '待核实'
    return '待核实'
  }
  return ({ UNKNOWN: '待核实', LIMITED: '有限', UNLIMITED: '不限', DAYS: '具体天数', ALLOWED: '允许', FORBIDDEN: '不允许', START_INCLUDED: '开始日计第 1 天' } as Record<string, string>)[String(value)] ?? String(value)
}
