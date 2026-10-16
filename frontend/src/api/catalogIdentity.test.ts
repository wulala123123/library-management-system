import { beforeEach, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('./request', () => ({ get: api.get, post: api.post }))
import {
  confirmCatalogIdentity,
  getCatalogIdentityBook,
  getCatalogIdentityCategories,
  getCatalogIdentityPublishers,
  getCatalogIdentityRequestByKey,
  previewCatalogIdentity,
} from './catalogIdentity'

beforeEach(() => vi.clearAllMocks())

it('keeps identity reads separate from legacy publisher/category endpoints', () => {
  getCatalogIdentityPublishers({ keyword: '出版社', page: 2, size: 50 })
  getCatalogIdentityCategories({ keyword: '文学', page: 1, size: 50 })
  getCatalogIdentityBook(7)
  expect(api.get).toHaveBeenNthCalledWith(1, '/catalog-identities/publishers', { keyword: '出版社', page: 2, size: 50 })
  expect(api.get).toHaveBeenNthCalledWith(2, '/catalog-identities/categories', { keyword: '文学', page: 1, size: 50 })
  expect(api.get).toHaveBeenNthCalledWith(3, '/catalog-identities/books/7')
})

it('sends explicit stable IDs and leaves source adoption out of identity changes', () => {
  const input = {
    operationKey: '00000000-0000-4000-8000-000000000007', mode: 'MANUAL' as const, reason: '来源核对单 7', bookId: 7,
    expectedRevision: 2, expectedCatalogDigest: 'a'.repeat(64), sourceDigest: null,
    changes: { publisher: { action: 'LINK' as const, publisherId: 3 }, themes: { action: 'REPLACE' as const, categoryIds: [2, 11] }, aliases: [{ kind: 'PUBLISHER' as const, ownerId: 3, action: 'ADD' as const, name: '出版社旧名' }], categoryDimensions: [] },
  }
  previewCatalogIdentity(input)
  confirmCatalogIdentity(41, input.operationKey, 'b'.repeat(64))
  getCatalogIdentityRequestByKey(input.operationKey)
  expect(api.post).toHaveBeenNthCalledWith(1, '/catalog-identities/previews', input)
  expect(api.post).toHaveBeenNthCalledWith(2, '/catalog-identities/41/confirm', { operationKey: input.operationKey, previewDigest: 'b'.repeat(64) })
  expect(api.get).toHaveBeenCalledWith('/catalog-identities/requests/by-key', { key: input.operationKey })
  expect(JSON.stringify(input)).not.toContain('sourcePublisher')
})
