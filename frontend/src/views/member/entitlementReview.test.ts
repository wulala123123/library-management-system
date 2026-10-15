import { reactive } from 'vue'
import { expect, it } from 'vitest'
import type { MemberSummary } from '@/api/member'
import type { EntitlementView } from '@/api/memberEntitlement'
import { emptyDefinition } from '@/views/membership-plan/planDefinition'
import { createEntitlementAttempts, entitlementInput, normalizedEntitlement } from './entitlementReview'

export function syntheticView(): EntitlementView {
  const member: MemberSummary = { id: 5, username: 'synthetic', realName: '合成会员', phone: null, email: null, accountStatus: 1, profilePresent: true, revision: 3, cardNo: '00005', planName: '原名称', serviceStart: '2020-01-02', serviceEnd: '2020-03-04', serviceStatus: 'EXPIRED', asOfDate: '2026-10-15', usageType: 'UNKNOWN', usedUses: 0, totalUses: 12, remainingUses: null, overused: false }
  return { member, effectiveRules: emptyDefinition().rules, snapshot: null }
}
it('copies reactive current facts exactly without deriving dates, counts or version association', () => {
  const current = reactive(syntheticView()), input = entitlementInput(current)
  expect(input.actual).toEqual({ planName: '原名称', serviceStart: '2020-01-02', serviceEnd: '2020-03-04', usageType: 'UNKNOWN', usedUses: 0, totalUses: 12 })
  expect(input.planVersionId).toBeNull()
  input.rules.serviceDurationDays = 365
  const normalized = normalizedEntitlement(input)
  expect(normalized.actual).toEqual(input.actual)
  expect(normalized.rules.totalUses).toBeNull()
  expect(current.effectiveRules.serviceDurationDays).toBeNull()
})
it('keeps finite zero and explicit unlimited separate while retaining unknown raw facts', () => {
  const input = entitlementInput(syntheticView())
  input.actual.usageType = 'LIMITED'; input.actual.totalUses = 0
  expect(normalizedEntitlement(input).rules.totalUses).toBe(0)
  input.actual.usageType = 'UNLIMITED'
  expect(normalizedEntitlement(input).actual.totalUses).toBeNull()
})
it('reuses confirmation key only for the exact input and preview after an uncertain response', () => {
  const attempt = createEntitlementAttempts(), input = entitlementInput(syntheticView())
  input.reason = '按合成来源核对'
  const first = attempt(input, 'a'.repeat(64))
  expect(attempt(input, 'a'.repeat(64))).toEqual(first)
  input.reason = '新的依据'
  expect(attempt(input, 'a'.repeat(64)).idempotencyKey).not.toBe(first.idempotencyKey)
  expect(attempt(input, 'b'.repeat(64)).idempotencyKey).not.toBe(first.idempotencyKey)
})
