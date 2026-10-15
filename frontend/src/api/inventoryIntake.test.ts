import { beforeEach, expect, it, vi } from 'vitest'
const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }))
vi.mock('./request', () => request)
import { confirmIntake, getIntake, getIntakeByKey, previewIntake } from './inventoryIntake'
import { getBookMetadata, updateBookMetadata } from './book'
beforeEach(() => vi.clearAllMocks())
it('sends exact source/target/copy input without normalizing barcode bytes', () => {
  const data = { idempotencyKey: '00000000-0000-4000-8000-000000000001', source: { type: 'MANUAL' as const, purchaseOrderId: null }, reason: '合成实收',
    lines: [{ lineKey: 'line', target: { bookId: 7, newBook: null, purchaseItemId: null }, copies: [{ input: 'SCANNED' as const, barcode: ' 0001 ', location: null }] }] }
  previewIntake(data); expect(request.post).toHaveBeenCalledWith('/inventory-intakes/previews', data)
})
it('confirmation and both recovery lookups preserve the original identity and digest', () => {
  confirmIntake(9, 'original-key', 'digest'); getIntake(9); getIntakeByKey('original-key')
  expect(request.post).toHaveBeenCalledWith('/inventory-intakes/9/confirm', { idempotencyKey: 'original-key', previewDigest: 'digest' })
  expect(request.get).toHaveBeenCalledWith('/inventory-intakes/9')
  expect(request.get).toHaveBeenCalledWith('/inventory-intakes/by-key', { key: 'original-key' })
})
it('metadata saves use the digest and explicit changes with no stock mutation endpoint', () => {
  getBookMetadata(7); updateBookMetadata(7, 'original-digest', { title: '纯文本 <img onerror=x>', pages: 0, location: null })
  expect(request.get).toHaveBeenCalledWith('/books/7/metadata')
  expect(request.put).toHaveBeenCalledWith('/books/7/metadata', { expectedMetadataDigest: 'original-digest', changes: { title: '纯文本 <img onerror=x>', pages: 0, location: null } })
})
