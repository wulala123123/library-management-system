import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('./request', () => ({ get: api.get, post: api.post }))
import { confirmPublication, getPublicationBook, getPublicationEvents, getPublicationRequest, getPublicationRequestByKey, previewPublication } from './catalogPublication'
import { publicationInput } from '@/views/book/components/publicationFixtures'
beforeEach(() => vi.clearAllMocks())
it('uses the frozen publication endpoints and sends the complete per-book changes/evidence untouched', () => {
  const input = publicationInput([8, 7]); input.entries[0]!.changes.pages = { action: 'SET', value: 0 }; input.entries[1]!.changes.description = { action: 'CLEAR' }
  previewPublication(input); confirmPublication(41, input.operationKey, 'digest')
  expect(api.post).toHaveBeenNthCalledWith(1, '/catalog-publications/previews', input)
  expect(api.post).toHaveBeenNthCalledWith(2, '/catalog-publications/41/confirm', { operationKey: input.operationKey, previewDigest: 'digest' })
  expect(input.entries[0]!.changes).toEqual({ publishDate: null, pages: { action: 'SET', value: 0 }, description: { action: 'SET', value: '' } })
})
it('keeps stable book IDs and original-key lookup separate from old metadata and intake', () => {
  getPublicationBook(7); getPublicationRequest(41); getPublicationRequestByKey('original-key'); getPublicationEvents(7, 2)
  expect(api.get.mock.calls).toEqual([['/catalog-publications/books/7'], ['/catalog-publications/41'], ['/catalog-publications/requests/by-key', { key: 'original-key' }], ['/catalog-publications/books/7/events', { page: 2, size: 20 }]])
})
