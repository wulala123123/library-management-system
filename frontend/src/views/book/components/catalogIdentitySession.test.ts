import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ preview: vi.fn(), confirm: vi.fn(), lookup: vi.fn() }))
vi.mock('@/api/catalogIdentity', async original => ({ ...await original<object>(), previewCatalogIdentity: api.preview, confirmCatalogIdentity: api.confirm, getCatalogIdentityRequestByKey: api.lookup }))
import { useCatalogIdentitySession } from './catalogIdentitySession'
import { identityInput, identityReceipt, identityRequest } from './catalogIdentityFixtures'
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear() })

it('freezes original choices and confirms with original identity digest', async () => {
  const value = identityInput(); api.preview.mockResolvedValue(identityRequest(value)); api.confirm.mockResolvedValue(identityReceipt(value))
  const session = useCatalogIdentitySession(2, () => true); await session.preview(value); value.reason = '改写输入'; expect(session.input.value?.reason).toBe('馆藏目录核实'); await session.confirm()
  expect(api.confirm).toHaveBeenCalledWith(41, value.operationKey, 'b'.repeat(64)); expect(session.receipt.value?.book.bookId).toBe(7)
})
it('normalizes reverse theme IDs before saving/sending without modifying the caller or its key', async () => {
  const value = identityInput(); value.changes.themes = { action: 'REPLACE', categoryIds: [18, 11] }
  const sorted = JSON.parse(JSON.stringify(value)); sorted.changes.themes.categoryIds = [11, 18]
  api.preview.mockResolvedValue(identityRequest(sorted)); api.confirm.mockResolvedValue(identityReceipt(sorted))
  const session = useCatalogIdentitySession(2, () => true); await session.preview(value)
  expect(api.preview).toHaveBeenCalledWith(sorted); expect(session.input.value?.changes.themes?.categoryIds).toEqual([11, 18])
  expect(JSON.parse(sessionStorage.getItem('catalog-identities.pending.v1.2')!).input).toEqual(sorted)
  expect(value.changes.themes.categoryIds).toEqual([18, 11]); expect(Object.isFrozen(value.changes.themes.categoryIds)).toBe(false)
  await session.confirm(); expect(session.receipt.value?.operationKey).toBe(value.operationKey); expect(session.uncertain.value).toBe(false)
})
it('preserves duplicate theme IDs for backend rejection instead of silently deduplicating', async () => {
  const value = identityInput(); value.changes.themes = { action: 'REPLACE', categoryIds: [18, 11, 11] }
  api.preview.mockRejectedValue({ response: { status: 400, data: { message: 'INVALID_INPUT' } } })
  const session = useCatalogIdentitySession(2, () => true); await session.preview(value)
  expect(api.preview.mock.calls[0]![0].changes.themes.categoryIds).toEqual([11, 11, 18]); expect(value.changes.themes.categoryIds).toEqual([18, 11, 11]); expect(session.rejected.value).toBe(true)
})
it('resolves a lost preview response to committed receipt without another write', async () => {
  const value = identityInput(); api.preview.mockRejectedValue(new Error('timeout')); api.lookup.mockResolvedValue({ ...identityRequest(value), state: 'COMMITTED', receipt: identityReceipt(value) })
  const session = useCatalogIdentitySession(2, () => true); await session.preview(value); await session.retry(); expect(api.preview).toHaveBeenCalledOnce(); expect(api.confirm).not.toHaveBeenCalled(); expect(session.receipt.value?.operationKey).toBe(value.operationKey)
})
it('preserves original key across reload after missing/in-flight preview', async () => {
  const value = identityInput(); api.preview.mockRejectedValueOnce(new Error('timeout')).mockResolvedValue(identityRequest(value)); api.lookup.mockRejectedValue({ response: { status: 404 } })
  const initial = useCatalogIdentitySession(2, () => true); await initial.preview(value); expect(initial.reset()).toBe(false)
  const resumed = useCatalogIdentitySession(2, () => true); resumed.restore(); expect(resumed.input.value).toEqual(value); await resumed.retry(); expect(api.preview.mock.calls[1]![0]).toEqual(api.preview.mock.calls[0]![0]); expect(resumed.uncertain.value).toBe(false)
})
it('keeps unknown confirmation locked across reload and retries only original confirm', async () => {
  const value = identityInput(); api.preview.mockResolvedValue(identityRequest(value)); api.confirm.mockRejectedValueOnce({ response: { status: 500 } }).mockResolvedValue(identityReceipt(value)); api.lookup.mockResolvedValue(identityRequest(value))
  const session = useCatalogIdentitySession(2, () => true); await session.preview(value); await session.confirm(); expect(session.uncertain.value).toBe(true); expect(session.reset()).toBe(false)
  const resumed = useCatalogIdentitySession(2, () => true); resumed.restore(); await resumed.recover(); expect(resumed.uncertain.value).toBe(true); await resumed.retry(); expect(api.confirm.mock.calls[1]).toEqual(api.confirm.mock.calls[0]); expect(api.preview).toHaveBeenCalledOnce()
})
it.each([
  { response: { status: 409, data: { message: 'CATALOG_IDENTITY_BUSY', code: 409 } } },
  { response: { status: 409, data: { code: 'CATALOG_IDENTITY_BUSY', message: '锁忙' } } },
  { status: 409, code: 'CATALOG_IDENTITY_BUSY', message: '锁忙' },
])('treats backend identity BUSY as uncertain and retains the original key', async cause => {
  const value = identityInput(); api.preview.mockRejectedValue(cause); api.lookup.mockRejectedValue(new Error('offline')); const session = useCatalogIdentitySession(2, () => true); await session.preview(value)
  expect(session.uncertain.value).toBe(true); expect(session.rejected.value).toBe(false); expect(session.reset()).toBe(false); expect(session.input.value?.operationKey).toBe(value.operationKey)
})
it('retains explicit rejected input for correction without claiming success', async () => {
  api.preview.mockRejectedValue({ response: { status: 409, data: { message: 'CATALOG_IDENTITY_CHANGED', code: 409 } } }); const session = useCatalogIdentitySession(2, () => true); await session.preview(identityInput()); expect(session.rejected.value).toBe(true); expect(session.receipt.value).toBeNull(); expect(session.reset()).toBe(true)
})
it('does not restore/lookup/retry after losing ADMIN, or restore another owner', async () => {
  const value = identityInput(); api.preview.mockRejectedValue(new Error('timeout')); api.lookup.mockRejectedValue(new Error('offline')); const session = useCatalogIdentitySession(2, () => true); await session.preview(value); api.lookup.mockClear()
  const denied = useCatalogIdentitySession(2, () => false); denied.restore(); await denied.recover(); await denied.retry(); expect(api.lookup).not.toHaveBeenCalled(); expect(denied.input.value).toBeNull()
  const other = useCatalogIdentitySession(3, () => true); other.restore(); expect(other.input.value).toBeNull()
})
it('discards a late response after losing permission and makes no follow-up lookup', async () => {
  const value = identityInput(); let finish!: (result: unknown) => void; api.preview.mockReturnValue(new Promise(resolve => { finish = resolve })); let allowed = true; const session = useCatalogIdentitySession(2, () => allowed); const pending = session.preview(value); allowed = false; finish(identityRequest(value)); await pending
  expect(session.view.value).toBeNull(); expect(session.uncertain.value).toBe(true); expect(api.lookup).not.toHaveBeenCalled()
})
it('does not send preview when session storage cannot preserve the original key', async () => {
  const storage = { getItem: vi.fn(), setItem: vi.fn(() => { throw new Error('quota') }), removeItem: vi.fn() } as unknown as Storage
  const session = useCatalogIdentitySession(2, () => true, storage); await session.preview(identityInput()); expect(api.preview).not.toHaveBeenCalled(); expect(session.input.value).toBeNull(); expect(session.error.value).toContain('尚未发送')
})
it('does not send confirmation if its recovery state cannot be saved', async () => {
  const value = identityInput(); api.preview.mockResolvedValue(identityRequest(value)); let fail = false
  const storage = { getItem: vi.fn(), setItem: vi.fn(() => { if (fail) throw new Error('quota') }), removeItem: vi.fn() } as unknown as Storage
  const session = useCatalogIdentitySession(2, () => true, storage); await session.preview(value); fail = true; await session.confirm(); expect(api.confirm).not.toHaveBeenCalled(); expect(session.uncertain.value).toBe(false); expect(session.view.value).not.toBeNull()
})
it('does not accept a response for different preview changes or book identity', async () => {
  const value = identityInput(), view = identityRequest(value); view.preview.book.after.bookId = 8; api.preview.mockResolvedValue(view); api.lookup.mockRejectedValue(new Error('offline')); const session = useCatalogIdentitySession(2, () => true); await session.preview(value); expect(session.view.value).toBeNull(); expect(session.uncertain.value).toBe(true)
})
it('keeps a malformed saved request blocked instead of creating a replacement key', async () => {
  sessionStorage.setItem('catalog-identities.pending.v1.2', '{broken'); const session = useCatalogIdentitySession(2, () => true); session.restore(); await session.preview(identityInput()); expect(session.uncertain.value).toBe(true); expect(api.preview).not.toHaveBeenCalled(); expect(session.reset()).toBe(false)
})
it.each(['owner', 'book', 'key', 'applied', 'identityBook'])('rejects a mismatched %s receipt', async mismatch => {
  const value = identityInput(), receipt = identityReceipt(value); if (mismatch === 'owner') receipt.actorId = 99; if (mismatch === 'book') receipt.book.bookId = 8; if (mismatch === 'key') receipt.operationKey = crypto.randomUUID(); if (mismatch === 'applied') receipt.book.applied = { publisher: null, themes: null, aliases: [], categoryDimensions: [] }; if (mismatch === 'identityBook') receipt.book.identity.bookId = 8
  api.preview.mockResolvedValue(identityRequest(value)); api.confirm.mockResolvedValue(receipt); api.lookup.mockRejectedValue(new Error('offline')); const session = useCatalogIdentitySession(2, () => true); await session.preview(value); await session.confirm(); expect(session.receipt.value).toBeNull(); expect(session.uncertain.value).toBe(true)
})
