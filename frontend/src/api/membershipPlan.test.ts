import { beforeEach, expect, it, vi } from 'vitest'
const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('./request', () => request)
import { createMembershipPlan, getMembershipPlan, getMembershipPlans, publishMembershipPlan, saveMembershipPlanDraft, selectMembershipPlanVersion } from './membershipPlan'
import { emptyDefinition } from '@/views/membership-plan/planDefinition'
beforeEach(() => vi.clearAllMocks())
it('uses the admin plan routes and preserves revision and publication request identity', () => {
  const draft = { ...emptyDefinition(), name: '合成套餐', changeReason: '核对来源' }
  const publication = { revision: 3, idempotencyKey: 'fixed-key', confirmationBasis: '逐项核实', changeReason: '发布' }
  getMembershipPlans({ page: 1, size: 10, keyword: '合成' }); getMembershipPlan(8); createMembershipPlan(draft)
  saveMembershipPlanDraft(8, { ...draft, revision: 2 }); publishMembershipPlan(8, publication)
  selectMembershipPlanVersion(8, { revision: 4, versionId: 9, enabled: false, changeReason: '停用' })
  expect(request.get.mock.calls).toEqual([['/membership-plans', { page: 1, size: 10, keyword: '合成' }], ['/membership-plans/8']])
  expect(request.post.mock.calls).toEqual([['/membership-plans', draft], ['/membership-plans/8/versions', publication]])
  expect(request.put.mock.calls).toEqual([['/membership-plans/8/draft', { ...draft, revision: 2 }], ['/membership-plans/8/selection', { revision: 4, versionId: 9, enabled: false, changeReason: '停用' }]])
})
