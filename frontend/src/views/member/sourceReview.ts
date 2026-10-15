import type { MemberChild, MemberSourceChildCandidate, MemberSourceField } from '@/api/member'

const textFields: Record<string, number> = { realName: 20, phone: 20, cardNo: 100, planName: 200, address: 500, notes: 2000 }
export function canPrefillSource(field: MemberSourceField): boolean {
  if (field.reviewOnly || field.selectable === false || field.field === 'totalUses') return false
  const value = field.sourceValue
  if (value == null || (typeof value === 'string' && !value.trim())) return false
  if (Object.prototype.hasOwnProperty.call(textFields, field.field)) return typeof value === 'string' && value.length <= textFields[field.field]!
  if (field.field === 'usedUses') return (typeof value === 'number' && Number.isSafeInteger(value) && value >= 0) || (typeof value === 'string' && /^\d+$/.test(value.trim()) && Number.isSafeInteger(Number(value)))
  if (field.field !== 'serviceStart' && field.field !== 'serviceEnd') return false
  return validDay(value)
}

function validDay(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}
const hasText = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
export function sourceReasonText(code: string | undefined) {
  return ({ READY: '可审阅后选择', SOURCE_EMPTY: '来源未提供，不能清空现有资料', INSUFFICIENT_CHILD_IDENTITY: '只有性别或年龄，不能确认宝宝身份', INVALID_SOURCE_TYPE: '来源类型待核实', SOURCE_TOO_LONG: '来源内容超出字段长度', INVALID_SOURCE_DATE: '来源生日不完整或无效，不能预填', FUTURE_SOURCE_DATE: '来源生日晚于核对日期，不能预填', DUPLICATE_CURRENT_SOURCE_KEY: '现有宝宝来源键重复，请先人工核对', REVIEW_ONLY_TOTAL_USES: '总次数仅供核对' } as Record<string, string>)[code ?? ''] ?? '此来源值仅供核对'
}
export function childCollectionIssue(children: MemberChild[]): string | null {
  if (children.length > 30) return '宝宝资料最多保存 30 名，请先人工核对。'
  const keys = children.map(child => child.sourceKey).filter(hasText)
  return new Set(keys).size === keys.length ? null : '宝宝资料存在重复来源键，请先人工核对后保存。'
}
export function childCandidateIssue(candidate: MemberSourceChildCandidate, children: MemberChild[], candidates: MemberSourceChildCandidate[], asOfDate?: string): string | null {
  if (!candidate.selectable) return sourceReasonText(candidate.reasonCode)
  const value = candidate.sourceValue
  if (!hasText(candidate.sourceKey) || candidate.sourceKey.length > 128 || value.sourceKey !== candidate.sourceKey || ![1, 2, 3].includes(candidate.sourceSlot)) return '宝宝候选的来源标识不完整，请人工核对。'
  if (candidates.filter(item => item.sourceKey === candidate.sourceKey).length !== 1 || children.filter(child => child.sourceKey === candidate.sourceKey).length > 1) return '宝宝资料存在重复来源键，请先人工核对。'
  if ([value.name, value.gender, value.sourceBirthText].some(item => item != null && typeof item !== 'string')) return '宝宝来源类型待核实。'
  if ((value.name?.length ?? 0) > 100 || (value.gender?.length ?? 0) > 50 || (value.sourceBirthText?.length ?? 0) > 300) return '宝宝来源内容超出字段长度。'
  if (value.birthDatePrecision === 'DAY') {
    if (!validDay(value.birthDate)) return '来源生日无效，不能预填。'
    const today = asOfDate && validDay(asOfDate) ? asOfDate : new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
    if (value.birthDate > today) return '来源生日晚于核对日期，不能预填。'
  } else if (value.birthDatePrecision !== 'UNKNOWN' || value.birthDate != null || hasText(value.sourceBirthText)) return '来源生日精度待核实，不能预填。'
  if (!hasText(value.name) && value.birthDatePrecision !== 'DAY') return '只有性别或年龄，不能确认宝宝身份。'
  if (hasText(value.name) && children.some(child => !hasText(child.sourceKey) && child.name?.trim() === value.name!.trim())) return '现有宝宝同名且无来源键，请先人工核对；不会自动合并或覆盖。'
  return null
}

/** Apply the selected identities atomically to a copy of the complete editable collection. */
export function prefillSourceChildren(children: MemberChild[], selectedKeys: string[], candidates: MemberSourceChildCandidate[], asOfDate?: string): { children: MemberChild[]; error: string | null } {
  const collectionError = childCollectionIssue(children)
  if (collectionError) return { children, error: collectionError }
  const next = children.map(child => ({ ...child }))
  for (const key of [...new Set(selectedKeys)]) {
    const candidate = candidates.find(item => item.sourceKey === key)
    if (!candidate) return { children, error: '所选宝宝候选已变化，请重新读取来源预览。' }
    const issue = childCandidateIssue(candidate, children, candidates, asOfDate)
    if (issue) return { children, error: issue }
    const index = next.findIndex(child => child.sourceKey === key), value = candidate.sourceValue
    const current = index >= 0 ? next[index]! : null
    const replacement: MemberChild = {
      ...(current?.id == null ? {} : { id: current.id }),
      name: hasText(value.name) ? value.name : current?.name ?? null,
      gender: hasText(value.gender) ? value.gender : current?.gender ?? null,
      birthDate: value.birthDatePrecision === 'DAY' ? value.birthDate : current?.birthDate ?? null,
      birthDatePrecision: value.birthDatePrecision === 'DAY' ? 'DAY' : current?.birthDatePrecision ?? 'UNKNOWN',
      sourceBirthText: value.birthDatePrecision === 'DAY' ? value.sourceBirthText ?? value.birthDate : current?.sourceBirthText ?? null,
      sourceKey: candidate.sourceKey,
      notes: current?.notes ?? null,
    }
    if (index >= 0) next[index] = replacement
    else next.push(replacement)
  }
  const resultError = childCollectionIssue(next)
  return resultError ? { children, error: resultError } : { children: next, error: null }
}
