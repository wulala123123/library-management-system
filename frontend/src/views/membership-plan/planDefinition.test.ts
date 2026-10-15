import { expect, it } from 'vitest'
import { createPublishAttempts, definitionRows, draftIssues, emptyDefinition, normalizedDefinition, publicationIssues } from './planDefinition'

it('allows unknown draft rules while keeping them unpublishable', () => {
  const value = emptyDefinition()
  expect(draftIssues(value.rules)).toEqual([])
  expect(publicationIssues(value.rules)).toEqual(expect.arrayContaining(['单次借书上限待核实', '累计在借上限待核实', '总次数类型待核实', '归还期限待核实', '续借权限待核实', '语种范围待核实', '取书方式待核实']))
  expect(definitionRows(value).find(row => row.key === 'languages')?.value).toBe('待核实')
})
it('keeps finite zero distinct from unlimited and validates each independent limit', () => {
  const value = emptyDefinition(), rules = value.rules
  Object.assign(rules, { serviceDurationDays: 30, perOrderLimit: { mode: 'LIMITED', value: 0 }, activeLoanLimit: { mode: 'UNLIMITED', value: null }, usageType: 'LIMITED', totalUses: 0, dueDateType: 'UNLIMITED', renewalPermission: 'FORBIDDEN', languages: { mode: 'ANY', values: [] }, pickupMethods: { mode: 'ALLOW_LIST', values: ['到馆自取'] } })
  expect(publicationIssues(rules)).toEqual([])
  expect(definitionRows(value)).toEqual(expect.arrayContaining([
    { key: 'perOrderLimit', label: '单次借书上限', value: '0 册' }, { key: 'activeLoanLimit', label: '累计在借上限', value: '不限' },
    { key: 'totalUses', label: '总次数', value: '0 次' }, { key: 'dueDate', label: '归还期限', value: '明确不限归还日期' },
  ]))
  expect(definitionRows(value).find(row => row.key === 'dayCountConvention')?.value).toContain('开始日+天数−1')
})
it('never interprets empty selected scopes as unlimited and requires complete allowed renewal rules', () => {
  const rules = emptyDefinition().rules
  rules.languages = { mode: 'ALLOW_LIST', values: [] }; rules.pickupMethods = { mode: 'ALLOW_LIST', values: [] }; rules.renewalPermission = 'ALLOWED'
  expect(draftIssues(rules)).toEqual(expect.arrayContaining([expect.stringContaining('语种范围'), expect.stringContaining('取书方式')]))
  expect(publicationIssues(rules)).toEqual(expect.arrayContaining(['语种范围须填写允许项', '取书方式须填写允许项', '续借次数待核实', '续借延长天数须为正整数']))
})
it('clears inapplicable fields explicitly without mutating a published definition', () => {
  const value = emptyDefinition()
  Object.assign(value.rules, { usageType: 'UNLIMITED', totalUses: 0, dueDateType: 'UNLIMITED', borrowDays: 20, renewalPermission: 'FORBIDDEN', renewalLimit: { mode: 'LIMITED', value: 2 }, renewalDays: 10 })
  const clean = normalizedDefinition(value)
  expect(clean.rules).toEqual(expect.objectContaining({ totalUses: null, borrowDays: null, renewalLimit: null, renewalDays: null }))
  expect(value.rules.totalUses).toBe(0); expect(value.rules.renewalDays).toBe(10)
})
it('reuses one exact publish request after failure and replaces the key when content or revision changes', () => {
  const request = createPublishAttempts(), body = { revision: 3, confirmationBasis: '已核实', changeReason: '发布' }
  const first = request(1, body)
  expect(request(1, body)).toEqual(first)
  expect(request(1, { ...body, confirmationBasis: '补充核实依据' }).idempotencyKey).not.toBe(first.idempotencyKey)
  const next = request(1, { ...body, revision: 4 })
  expect(next.idempotencyKey).not.toBe(first.idempotencyKey)
  expect(request(1, { ...body, revision: 4 })).toEqual(next)
})
