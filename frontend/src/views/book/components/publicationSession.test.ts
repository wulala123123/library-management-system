import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ preview: vi.fn(), confirm: vi.fn(), lookup: vi.fn() }))
vi.mock('@/api/catalogPublication', async original => ({ ...await original<object>(), previewPublication: api.preview, confirmPublication: api.confirm, getPublicationRequestByKey: api.lookup }))
import { usePublicationSession } from './publicationSession'
import { publicationInput, publicationReceipt, publicationRequest } from './publicationFixtures'
beforeEach(() => { vi.resetAllMocks(); sessionStorage.clear(); api.lookup.mockRejectedValue(new Error('offline')) })
const busy = { response: { status: 409, data: { code: 409, message: '目录忙', data: { reasonCode: 'CATALOG_BUSY' } } } }
it('freezes all rows in ID order without swallowing duplicates or changing the caller input', async () => {
  api.preview.mockRejectedValue({ response: { status: 400, data: { message: '重复书目', data: { reasonCode: 'INVALID_INPUT' } } } })
  const input = publicationInput([8, 7, 7]), session = usePublicationSession(2, () => true); await session.preview(input)
  input.entries[0]!.evidence.reference = '后来改动'
  expect(api.preview.mock.calls[0]![0].entries.map((row: any) => row.bookId)).toEqual([7, 7, 8])
  expect(session.input.value!.entries[2]!.evidence.reference).toBe('合成版权页 #8'); expect(Object.isFrozen(session.input.value!.entries[0]!.changes)).toBe(true)
  expect(session.rejected.value).toBe(true); expect(session.reset()).toBe(true)
})
it('restores a whole batch after a lost preview and 404, preserving every row/key on retry', async () => {
  const input = publicationInput([8, 7]); api.preview.mockRejectedValueOnce(new Error('timeout')).mockImplementation(value => Promise.resolve(publicationRequest(value)))
  api.lookup.mockRejectedValue({ response: { status: 404, data: { data: { reasonCode: 'REQUEST_NOT_FOUND' } } } })
  const first = usePublicationSession(2, () => true); await first.preview(input); expect(first.reset()).toBe(false)
  const resumed = usePublicationSession(2, () => true); resumed.restore(); await resumed.recover(); expect(resumed.reset()).toBe(false); await resumed.retry()
  expect(api.preview.mock.calls[1]![0]).toEqual(api.preview.mock.calls[0]![0]); expect(resumed.view.value?.preview.books.map(row => row.bookId)).toEqual([7, 8]); expect(resumed.uncertain.value).toBe(false)
})
it('keeps confirmation BUSY locked through reload and only retries the original confirm, then refreshes once', async () => {
  const input = publicationInput([7, 8]); api.preview.mockResolvedValue(publicationRequest(input)); api.confirm.mockRejectedValueOnce(busy).mockResolvedValue(publicationReceipt(input)); api.lookup.mockResolvedValue(publicationRequest(input))
  const first = usePublicationSession(2, () => true); await first.preview(input); await first.confirm(); expect(first.uncertain.value).toBe(true)
  const resumed = usePublicationSession(2, () => true); resumed.restore(); await resumed.recover(); expect(resumed.reset()).toBe(false); await resumed.retry()
  expect(api.preview).toHaveBeenCalledOnce(); expect(api.confirm.mock.calls[1]).toEqual(api.confirm.mock.calls[0]); expect(resumed.receipt.value?.books).toHaveLength(2)
})
it('looks up a lost confirm success without repeating a write and accepts an unchanged zero row', async () => {
  const input = publicationInput([7, 8]); input.entries[1]!.changes = { publishDate: null, pages: { action: 'SET', value: 0 }, description: null }
  api.preview.mockResolvedValue(publicationRequest(input)); api.confirm.mockRejectedValue(new Error('timeout')); api.lookup.mockResolvedValue({ ...publicationRequest(input), state: 'COMMITTED', receipt: publicationReceipt(input) })
  const session = usePublicationSession(2, () => true); await session.preview(input); await session.confirm(); await session.retry()
  expect(api.confirm).toHaveBeenCalledOnce(); expect(session.receipt.value?.books[1]).toMatchObject({ changed: false, eventId: null, beforeRevision: 2, afterRevision: 2 }); expect(session.reset()).toBe(true)
})
it.each([busy, { response: { status: 404 } }, { response: { status: 409, data: { data: { reasonCode: 'IDEMPOTENCY_KEY_REUSED' } } } }])('never releases a key on BUSY, unknown 404 or key collision', async cause => {
  api.preview.mockRejectedValue(cause); const session = usePublicationSession(2, () => true); await session.preview(publicationInput()); expect(session.uncertain.value).toBe(true); expect(session.rejected.value).toBe(false); expect(session.reset()).toBe(false)
})
it('locks a malformed preview and refuses a wrong row, actor, evidence or after-value receipt', async () => {
  const input = publicationInput([7, 8]); api.preview.mockResolvedValue(publicationRequest(input))
  for (const mismatch of ['row', 'actor', 'evidence', 'after', 'digest']) {
    sessionStorage.clear(); const session = usePublicationSession(2, () => true); await session.preview(input)
    const receipt = publicationReceipt(input)
    if (mismatch === 'row') receipt.books.reverse()
    if (mismatch === 'actor') receipt.actorId = 3
    if (mismatch === 'evidence') receipt.books[1]!.evidence = { kind: 'MANUAL_REVIEW', reference: '不属于本次' }
    if (mismatch === 'after') receipt.books[1]!.publication.description = null
    if (mismatch === 'digest') receipt.books[1]!.publication.metadataDigest = 'wrong'
    api.confirm.mockResolvedValue(receipt); await session.confirm(); expect(session.receipt.value).toBeNull(); expect(session.reset()).toBe(false)
  }
  sessionStorage.clear(); const wrong = publicationRequest(input); wrong.preview.books[1]!.before.metadataDigest = 'wrong'; api.preview.mockResolvedValue(wrong)
  const session = usePublicationSession(2, () => true); await session.preview(input); expect(session.view.value).toBeNull(); expect(session.uncertain.value).toBe(true)
})
it('blocks management calls after owner/role changes, including a batch restored by a librarian', async () => {
  const input = publicationInput([7, 8]); api.preview.mockRejectedValue(new Error('timeout')); const first = usePublicationSession(2, () => true); await first.preview(input); vi.clearAllMocks()
  const librarian = usePublicationSession(2, count => count === 1); librarian.restore(); await librarian.recover(); await librarian.retry(); await librarian.confirm(); expect(api.lookup).not.toHaveBeenCalled(); expect(api.preview).not.toHaveBeenCalled()
  const reader = usePublicationSession(2, () => false); reader.restore(); await reader.recover(); expect(reader.input.value).toBeNull()
  const other = usePublicationSession(3, () => true); other.restore(); expect(other.input.value).toBeNull()
})
it('ignores a response arriving after the original account lost permission', async () => {
  let finish!: (value: unknown) => void, allowed = true; const input = publicationInput(); api.preview.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const session = usePublicationSession(2, () => allowed), pending = session.preview(input); allowed = false; finish(publicationRequest(input)); await pending
  expect(session.view.value).toBeNull(); expect(session.uncertain.value).toBe(true); expect(api.lookup).not.toHaveBeenCalled()
})
it('sends no write when storage fails before preview or confirmation and does not drop the key', async () => {
  const input = publicationInput(); let fail = true
  const storage = { getItem: vi.fn(() => null), setItem: vi.fn(() => { if (fail) throw new Error('quota') }), removeItem: vi.fn() } as unknown as Storage
  const session = usePublicationSession(2, () => true, storage); await session.preview(input); expect(api.preview).not.toHaveBeenCalled(); expect(session.input.value).toBeNull()
  fail = false; api.preview.mockResolvedValue(publicationRequest(input)); await session.preview(input); fail = true; await session.confirm(); expect(api.confirm).not.toHaveBeenCalled(); expect(session.input.value?.operationKey).toBe(input.operationKey); expect(session.uncertain.value).toBe(false)
})
it('does not overwrite another open editor original key and retains corrupt records for manual recovery', async () => {
  const firstInput = publicationInput(); api.preview.mockRejectedValue(new Error('timeout')); const first = usePublicationSession(2, () => true), second = usePublicationSession(2, () => true)
  await first.preview(firstInput); await second.preview(publicationInput([8])); expect(api.preview).toHaveBeenCalledOnce(); expect(second.input.value?.operationKey).toBe(firstInput.operationKey)
  sessionStorage.setItem('catalog-publications.pending.v1.2', '{broken'); const corrupt = usePublicationSession(2, () => true); corrupt.restore(); expect(corrupt.reset()).toBe(false); await corrupt.preview(publicationInput()); expect(api.preview).toHaveBeenCalledOnce()
})
it('a second editor cannot clear another editor in-flight confirmation', async () => {
  const input = publicationInput(); api.preview.mockResolvedValue(publicationRequest(input)); api.lookup.mockResolvedValue(publicationRequest(input))
  const first = usePublicationSession(2, () => true); await first.preview(input)
  const second = usePublicationSession(2, () => true); second.restore(); await second.recover(); expect(second.uncertain.value).toBe(false)
  let finish!: (value: unknown) => void; api.confirm.mockReturnValue(new Promise(resolve => { finish = resolve }))
  const pending = first.confirm(); expect(second.reset()).toBe(false); expect(second.uncertain.value).toBe(true)
  finish(publicationReceipt(input)); await pending; expect(first.receipt.value).not.toBeNull()
})
it('retains a verified receipt when storage fails after commit and refuses to clear an unreadable original record', async () => {
  const input = publicationInput(); let fail = false, stored = ''
  const storage = { getItem() { return stored || null }, setItem(_key: string, text: string) { if (fail) throw new Error('quota'); stored = text }, removeItem() { if (fail) throw new Error('quota'); stored = '' } } as unknown as Storage
  api.preview.mockResolvedValue(publicationRequest(input)); api.confirm.mockImplementation(async () => { fail = true; return publicationReceipt(input) })
  const session = usePublicationSession(2, () => true, storage); await session.preview(input); await session.confirm()
  expect(session.receipt.value?.operationKey).toBe(input.operationKey); expect(session.uncertain.value).toBe(false); expect(session.error.value).toContain('成功回执已收到'); expect(session.reset()).toBe(false); expect(session.input.value?.operationKey).toBe(input.operationKey)
})
