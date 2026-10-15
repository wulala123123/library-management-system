import { beforeEach, expect, it, vi } from 'vitest'
const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn() }))
vi.mock('./request', () => request)
import { createMember, getMemberById, getMemberPage, getMemberSourcePreview, getMyMembership, updateMember, type MemberFormData } from './member'
beforeEach(() => vi.clearAllMocks())
it('uses the agreed member routes, current-user route and full replacement revision', () => {
  const body: MemberFormData = { realName: '合成会员', phone: null, email: null, cardNo: '00001', planName: null, serviceStart: null, serviceEnd: null, usageType: 'UNKNOWN', usedUses: null, totalUses: null, notes: null, address: null, children: [], changeReason: '合成测试' }
  getMemberPage({ page: 1, size: 10, keyword: '00001' }); getMemberById(5); getMyMembership(); getMemberSourcePreview(5)
  createMember({ ...body, username: 'reader5', password: 'Reader123' }); updateMember(5, { ...body, revision: 3 })
  expect(request.get.mock.calls).toEqual([['/members', { page: 1, size: 10, keyword: '00001' }], ['/members/5'], ['/members/me'], ['/members/5/source-preview']])
  expect(request.post).toHaveBeenCalledWith('/members', expect.objectContaining({ cardNo: '00001' }))
  expect(request.put).toHaveBeenCalledWith('/members/5', { ...body, revision: 3 })
})
