import type { PlanDefinition, PlanLimit, PlanRules, PlanScope, PublishPlanRequest } from '@/api/membershipPlan'

export const emptyLimit = (): PlanLimit => ({ mode: 'UNKNOWN', value: null })
export const emptyScope = (): PlanScope => ({ mode: 'UNKNOWN', values: [] })
export const emptyDefinition = (): PlanDefinition => ({
  name: '', notes: null, sourceReferences: [], rules: {
    serviceDurationDays: null, dayCountConvention: 'START_INCLUDED', perOrderLimit: emptyLimit(), activeLoanLimit: emptyLimit(),
    usageType: 'UNKNOWN', totalUses: null, dueDateType: 'UNKNOWN', borrowDays: null,
    renewalPermission: 'UNKNOWN', renewalLimit: null, renewalDays: null, languages: emptyScope(), pickupMethods: emptyScope(),
  },
})
export const copyDefinition = (value: PlanDefinition): PlanDefinition => JSON.parse(JSON.stringify(value))
export function normalizedDefinition(value: PlanDefinition): PlanDefinition {
  const result = copyDefinition(value), rules = result.rules
  result.notes = result.notes?.trim() ? result.notes : null
  rules.serviceDurationDays ??= null; rules.borrowDays ??= null; rules.totalUses ??= null; rules.renewalDays ??= null
  for (const limit of [rules.perOrderLimit, rules.activeLoanLimit, rules.renewalLimit]) if (limit) limit.value = limit.mode === 'LIMITED' ? limit.value ?? null : null
  if (rules.usageType !== 'LIMITED') rules.totalUses = null
  if (rules.dueDateType !== 'DAYS') rules.borrowDays = null
  if (rules.renewalPermission !== 'ALLOWED') { rules.renewalLimit = null; rules.renewalDays = null }
  for (const scope of [rules.languages, rules.pickupMethods]) if (scope.mode !== 'ALLOW_LIST') scope.values = []
  return result
}
const nonnegative = (value: number | null) => value != null && Number.isInteger(value) && value >= 0
const positive = (value: number | null) => nonnegative(value) && value! > 0
export function draftIssues(rules: PlanRules): string[] {
  const issues: string[] = []
  if (rules.serviceDurationDays != null && !positive(rules.serviceDurationDays)) issues.push('服务时长须为正整数，未知时请留空')
  for (const [label, limit] of [['单次借书上限', rules.perOrderLimit], ['累计在借上限', rules.activeLoanLimit]] as const) if (limit.mode === 'LIMITED' && !nonnegative(limit.value)) issues.push(`${label}须填写非负整数，未知时请选择待核实`)
  if (rules.usageType === 'LIMITED' && !nonnegative(rules.totalUses)) issues.push('有限总次数须填写非负整数，未知时请选择待核实')
  if (rules.dueDateType === 'DAYS' && rules.borrowDays != null && !positive(rules.borrowDays)) issues.push('借阅天数须为正整数')
  if (rules.renewalPermission === 'ALLOWED') {
    if (rules.renewalLimit?.mode === 'LIMITED' && !nonnegative(rules.renewalLimit.value)) issues.push('有限续借次数须填写非负整数')
    if (rules.renewalDays != null && !positive(rules.renewalDays)) issues.push('续借延长天数须为正整数')
  }
  for (const [label, scope] of [['语种范围', rules.languages], ['取书方式', rules.pickupMethods]] as const) if (scope.mode === 'ALLOW_LIST' && (!scope.values.length || scope.values.some(value => !value.trim()) || new Set(scope.values).size !== scope.values.length)) issues.push(`${label}须填写不重复的允许项，未知时请选择待核实`)
  return issues
}
export function publicationIssues(rules: PlanRules): string[] {
  const issues: string[] = []
  if (!positive(rules.serviceDurationDays)) issues.push('服务时长须为正整数天')
  for (const [label, limit] of [['单次借书上限', rules.perOrderLimit], ['累计在借上限', rules.activeLoanLimit]] as const) {
    if (limit.mode === 'UNKNOWN') issues.push(`${label}待核实`)
    else if (limit.mode === 'LIMITED' && !nonnegative(limit.value)) issues.push(`${label}须填写非负整数`)
  }
  if (rules.usageType === 'UNKNOWN') issues.push('总次数类型待核实')
  else if (rules.usageType === 'LIMITED' && !nonnegative(rules.totalUses)) issues.push('有限总次数须填写非负整数')
  if (rules.dueDateType === 'UNKNOWN') issues.push('归还期限待核实')
  else if (rules.dueDateType === 'DAYS' && !positive(rules.borrowDays)) issues.push('借阅天数须为正整数')
  if (rules.renewalPermission === 'UNKNOWN') issues.push('续借权限待核实')
  else if (rules.renewalPermission === 'ALLOWED') {
    if (!rules.renewalLimit || rules.renewalLimit.mode === 'UNKNOWN') issues.push('续借次数待核实')
    else if (rules.renewalLimit.mode === 'LIMITED' && !nonnegative(rules.renewalLimit.value)) issues.push('有限续借次数须填写非负整数')
    if (!positive(rules.renewalDays)) issues.push('续借延长天数须为正整数')
  }
  for (const [label, scope] of [['语种范围', rules.languages], ['取书方式', rules.pickupMethods]] as const) {
    if (scope.mode === 'UNKNOWN') issues.push(`${label}待核实`)
    else if (scope.mode === 'ALLOW_LIST' && (!scope.values.length || scope.values.some(value => !value.trim()))) issues.push(`${label}须填写允许项`)
  }
  return issues
}
function limitText(limit: PlanLimit | null, unit: string) {
  if (!limit || limit.mode === 'UNKNOWN') return '待核实'
  return limit.mode === 'UNLIMITED' ? '不限' : limit.value == null ? '有限，数值待核实' : `${limit.value} ${unit}`
}
const scopeText = (scope: PlanScope) => scope.mode === 'UNKNOWN' ? '待核实' : scope.mode === 'ANY' ? '不限' : scope.values.length ? scope.values.join('、') : '指定范围，允许项待填写'
export function definitionRows(value: PlanDefinition) {
  const rules = value.rules
  return [
    { key: 'name', label: '套餐名称', value: value.name || '未设置' },
    { key: 'serviceDurationDays', label: '服务时长', value: rules.serviceDurationDays == null ? '待核实' : `${rules.serviceDurationDays} 天` },
    { key: 'dayCountConvention', label: '服务期算法', value: '开始日计第1天；结束日=开始日+天数−1' },
    { key: 'perOrderLimit', label: '单次借书上限', value: limitText(rules.perOrderLimit, '册') },
    { key: 'activeLoanLimit', label: '累计在借上限', value: limitText(rules.activeLoanLimit, '册') },
    { key: 'totalUses', label: '总次数', value: limitText({ mode: rules.usageType, value: rules.totalUses }, '次') },
    { key: 'dueDate', label: '归还期限', value: rules.dueDateType === 'UNKNOWN' ? '待核实' : rules.dueDateType === 'UNLIMITED' ? '明确不限归还日期' : rules.borrowDays == null ? '按天数，天数待核实' : `${rules.borrowDays} 天` },
    { key: 'renewalPermission', label: '续借权限', value: ({ UNKNOWN: '待核实', ALLOWED: '允许续借', FORBIDDEN: '不允许续借' })[rules.renewalPermission] },
    { key: 'renewalLimit', label: '续借次数', value: rules.renewalPermission === 'FORBIDDEN' ? '不适用' : limitText(rules.renewalLimit, '次') },
    { key: 'renewalDays', label: '续借延长天数', value: rules.renewalPermission === 'FORBIDDEN' ? '不适用' : rules.renewalDays == null ? '待核实' : `${rules.renewalDays} 天` },
    { key: 'languages', label: '语种范围', value: scopeText(rules.languages) },
    { key: 'pickupMethods', label: '取书方式', value: scopeText(rules.pickupMethods) },
    { key: 'sourceReferences', label: '来源引用', value: value.sourceReferences.join('；') || '未设置' },
    { key: 'notes', label: '备注', value: value.notes || '未设置' },
  ]
}

/** Keep the exact request after an uncertain result; a different body starts a fresh attempt. */
export function createPublishAttempts() {
  const attempts = new Map<number, { fingerprint: string; body: PublishPlanRequest }>()
  return (planId: number, content: Omit<PublishPlanRequest, 'idempotencyKey'>): PublishPlanRequest => {
    const fingerprint = JSON.stringify(content), previous = attempts.get(planId)
    if (previous?.fingerprint === fingerprint) return { ...previous.body }
    const body = { ...content, idempotencyKey: crypto.randomUUID() }
    attempts.set(planId, { fingerprint, body })
    return { ...body }
  }
}
