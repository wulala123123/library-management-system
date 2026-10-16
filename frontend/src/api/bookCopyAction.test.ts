import { beforeEach, expect, it, vi } from 'vitest'
const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('./request', () => request)
import { confirmCopyAction, getCopyAction, getCopyActionByKey, getCopyActionContext, getCopyActionHistory, previewCopyAction } from './bookCopyAction'
beforeEach(() => vi.clearAllMocks())
it('sends exact move/restore input with explicit versions and null location union, without stock or barcode writes', () => {
  const payload = { idempotencyKey: 'original-key', action: 'RESTORE' as const, reason: '找到原册 ', copies: [{ copyId: 7, expectedVersion: 3, targetLocation: { kind: 'UNASSIGNED' as const }, restoreOfActionId: 31, restoreTo: 'DISABLED' as const }] }
  previewCopyAction(payload)
  expect(request.post).toHaveBeenCalledWith('/book-copy-actions/previews', payload)
  previewCopyAction({ ...payload, action: 'MOVE', copies: [{ copyId: 8, expectedVersion: 0, targetLocation: { kind: 'TEXT', text: 'javascript:原文 😀 ' } }] })
  expect(request.post.mock.calls[1]![1].copies[0].targetLocation.text).toBe('javascript:原文 😀 ')
})
it('uses the fixed confirm identity and precise owner lookup/context/history endpoints', () => {
  confirmCopyAction(21, 'key', 'digest'); getCopyAction(21); getCopyActionByKey('key'); getCopyActionContext(7); getCopyActionHistory(7, 2, 20)
  expect(request.post).toHaveBeenCalledWith('/book-copy-actions/21/confirm', { idempotencyKey: 'key', previewDigest: 'digest' })
  expect(request.get.mock.calls).toEqual([['/book-copy-actions/21'], ['/book-copy-actions/by-key', { key: 'key' }], ['/book-copies/7/action-context'], ['/book-copies/7/actions', { page: 2, size: 20 }]])
})
