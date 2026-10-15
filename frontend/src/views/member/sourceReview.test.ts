import { expect, it } from 'vitest'
import { canPrefillSource, childCandidateIssue, childCollectionIssue, prefillSourceChildren } from './sourceReview'
import type { MemberChild, MemberSourceChildCandidate } from '@/api/member'
const source = (field: string, sourceValue: unknown, reviewOnly = false) => ({ field, sourceValue, sourceField: '合成字段', currentValue: null, conflict: false, reviewOnly })
it('keeps ambiguous raw total uses and unsupported family source fields review-only', () => {
  for (const value of [null, '', 0, '0', '不限', 12]) expect(canPrefillSource(source('totalUses', value))).toBe(false)
  expect(canPrefillSource(source('children', []))).toBe(false)
  expect(canPrefillSource(source('phone', '13800138000', true))).toBe(false)
})
it('accepts only known integer counts and complete valid source dates', () => {
  for (const value of [0, '0', 12, '12']) expect(canPrefillSource(source('usedUses', value))).toBe(true)
  for (const value of [null, '', '  ', '未知', '不限', -1, 0.5]) expect(canPrefillSource(source('usedUses', value))).toBe(false)
  expect(canPrefillSource(source('serviceStart', '2024-02-29'))).toBe(true)
  for (const value of [null, '', '2025-02-29', '2026-02', '2026', '三岁', '2026-10-15 00:00:00']) expect(canPrefillSource(source('serviceStart', value))).toBe(false)
})

it('never clears a known field from an empty source and respects backend selectable and length guards', () => {
  for (const field of ['realName', 'phone', 'cardNo', 'planName', 'address', 'notes', 'usedUses', 'serviceStart', 'serviceEnd']) for (const value of [null, '', '  ']) expect(canPrefillSource(source(field, value))).toBe(false)
  expect(canPrefillSource(source('address', '原始地址\n第二行'))).toBe(true)
  expect(canPrefillSource(source('notes', '<script>作为原文保留</script>'))).toBe(true)
  expect(canPrefillSource(source('address', '字'.repeat(501)))).toBe(false)
  expect(canPrefillSource({ ...source('phone', '13800138000'), selectable: false })).toBe(false)
})

function childCandidate(slot: 1 | 2 | 3, name = `来源宝宝${slot}`): MemberSourceChildCandidate {
  const key = `huiben:5:child:${slot}`
  return { sourceKey: key, sourceSlot: slot, sourceFields: ['名字', '生日', '性别', '年龄'], currentValue: null, sourceValue: { name, gender: '原性别', birthDate: '2020-02-29', birthDatePrecision: 'DAY', sourceBirthText: '2020-02-29', sourceKey: key, notes: null }, selectable: true, reasonCode: 'READY', conflict: false, reviewOnlyAge: '六岁' }
}
const manual: MemberChild = { id: 9, name: '手工宝宝', gender: null, birthDate: null, birthDatePrecision: 'UNKNOWN', sourceBirthText: null, notes: '手工备注' }
it('applies independent named slots without splitting names or merging same-name source identities', () => {
  const first = childCandidate(1, '来源 姓名'), second = childCandidate(2, '来源 姓名'), candidates = [first, second]
  const result = prefillSourceChildren([], candidates.map(candidate => candidate.sourceKey), candidates, '2026-10-15')
  expect(result.error).toBeNull(); expect(result.children).toHaveLength(2)
  expect(result.children.map(child => child.name)).toEqual(['来源 姓名', '来源 姓名'])
  expect(result.children.map(child => child.sourceKey)).toEqual(['huiben:5:child:1', 'huiben:5:child:2'])
  expect(result.children.every(child => !('id' in child) && child.notes === null && child.birthDate === '2020-02-29')).toBe(true)
})
it('reapplies by exact source key, keeping existing IDs and manual rows without copying preview IDs', () => {
  const candidate = childCandidate(1), current = { ...manual, id: 10, sourceKey: candidate.sourceKey, notes: '业务备注', name: '原业务名字' }
  candidate.currentValue = { ...current, id: 999 }
  const original = [manual, current], first = prefillSourceChildren(original, [candidate.sourceKey], [candidate], '2026-10-15')
  const second = prefillSourceChildren(first.children, [candidate.sourceKey], [candidate], '2026-10-15')
  expect(second.error).toBeNull(); expect(second.children).toHaveLength(2); expect(second.children[0]).toEqual(manual)
  expect(second.children[1]).toEqual(expect.objectContaining({ id: 10, name: '来源宝宝1', notes: '业务备注' }))
  expect(original[1]!.name).toBe('原业务名字')
})
it('blocks same-name manual records, duplicate keys and collection overflow without changing the collection', () => {
  const candidate = childCandidate(1, '手工宝宝')
  expect(childCandidateIssue(candidate, [manual], [candidate], '2026-10-15')).toContain('同名且无来源键')
  expect(prefillSourceChildren([manual], [candidate.sourceKey], [candidate], '2026-10-15').children).toEqual([manual])
  const duplicate = [{ ...manual, sourceKey: candidate.sourceKey }, { ...manual, id: 10, sourceKey: candidate.sourceKey }]
  expect(childCollectionIssue(duplicate)).toContain('重复来源键')
  expect(prefillSourceChildren([], [candidate.sourceKey], [candidate, { ...candidate }], '2026-10-15').error).toContain('重复来源键')
  const full = Array.from({ length: 30 }, (_, index) => ({ ...manual, id: index + 1, name: `手工${index}` }))
  const result = prefillSourceChildren(full, [candidate.sourceKey], [candidate], '2026-10-15')
  expect(result.error).toContain('30 名'); expect(result.children).toEqual(full)
})
it('rejects empty identity slots and invalid, future or uncertain birth dates even when selectable is erroneously true', () => {
  const candidate = childCandidate(1), empty = { ...candidate, sourceValue: { ...candidate.sourceValue, name: null, birthDate: null, birthDatePrecision: 'UNKNOWN' as const, sourceBirthText: null } }
  expect(childCandidateIssue(empty, [], [empty], '2026-10-15')).toContain('只有性别或年龄')
  for (const date of ['2021-02-29', '2020-02', '2020', '99']) {
    const invalid = { ...candidate, sourceValue: { ...candidate.sourceValue, birthDate: date } }
    expect(childCandidateIssue(invalid, [], [invalid], '2026-10-15')).toContain('生日无效')
  }
  const future = { ...candidate, sourceValue: { ...candidate.sourceValue, birthDate: '2026-10-16' } }
  expect(childCandidateIssue(future, [], [future], '2026-10-15')).toContain('晚于核对日期')
  const partial = { ...candidate, sourceValue: { ...candidate.sourceValue, birthDate: null, birthDatePrecision: 'UNKNOWN' as const, sourceBirthText: '2020-02' } }
  expect(childCandidateIssue(partial, [], [partial], '2026-10-15')).toContain('精度待核实')
})
it('preserves known gender and birthday when the same source identity has only a known name', () => {
  const candidate = childCandidate(1), current = { ...manual, sourceKey: candidate.sourceKey, gender: '原业务性别', birthDate: '2020-01-01', birthDatePrecision: 'DAY' as const, sourceBirthText: '2020-01-01' }
  candidate.sourceValue = { ...candidate.sourceValue, gender: null, birthDate: null, birthDatePrecision: 'UNKNOWN', sourceBirthText: null }
  const result = prefillSourceChildren([current], [candidate.sourceKey], [candidate], '2026-10-15')
  expect(result.error).toBeNull(); expect(result.children[0]).toEqual(expect.objectContaining({ gender: '原业务性别', birthDate: '2020-01-01', birthDatePrecision: 'DAY', notes: '手工备注' }))
})
