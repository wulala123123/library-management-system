import { expect, it } from 'vitest'
import { canPrefillSource } from './sourceReview'
const source = (field: string, sourceValue: unknown, reviewOnly = false) => ({ field, sourceValue, sourceField: '合成字段', currentValue: null, conflict: false, reviewOnly })
it('keeps ambiguous raw total uses and unsupported family source fields review-only', () => {
  for (const value of [null, '', 0, '0', '不限', 12]) expect(canPrefillSource(source('totalUses', value))).toBe(false)
  expect(canPrefillSource(source('children', []))).toBe(false)
  expect(canPrefillSource(source('phone', '13800138000', true))).toBe(false)
})
it('accepts only known integer counts and complete valid source dates', () => {
  for (const value of [0, '0', 12, '12', null]) expect(canPrefillSource(source('usedUses', value))).toBe(true)
  for (const value of ['', '  ', '未知', '不限', -1, 0.5]) expect(canPrefillSource(source('usedUses', value))).toBe(false)
  for (const value of ['2024-02-29', null, '']) expect(canPrefillSource(source('serviceStart', value))).toBe(true)
  for (const value of ['2025-02-29', '2026-02', '2026', '三岁', '2026-10-15 00:00:00']) expect(canPrefillSource(source('serviceStart', value))).toBe(false)
})
