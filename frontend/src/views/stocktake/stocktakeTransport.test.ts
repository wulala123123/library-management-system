import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ lookup: vi.fn(), write: vi.fn() }))
vi.mock('@/api/stocktake', () => ({ getStocktakeOperation: api.lookup, writeStocktakeOperation: api.write }))
import { observedPlacement, stocktakeTransport } from './stocktakeTransport'
import { useStocktakeOperation, type FrozenStocktakeOperation } from './stocktakeOperation'
import { fixtureReceipt, fixtureResolution } from './stocktakeFixtures'
import { addressRow } from '@/components/locations/locationFixtures'
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear() })
const operation = (): FrozenStocktakeOperation => ({ storageVersion: 1, ownerId: 2, operationKey: 'original-key', label: '关联成功业务', path: '/stocktake-resolutions/60/link', body: { operationKey: 'original-key', expectedVersion: 1, reason: '已成功，仅关联' } })
it('preserves raw placement and nullable versions while allowing an unavailable observed address', () => {
  const row = addressRow(); row.address.selectable = false; row.address.locationVersion = null
  expect(observedPlacement({ kind: 'REGISTERED', text: '', address: row })).toEqual({ kind: 'REGISTERED', locationId: row.address.locationId, expectedAddressVersion: { location: null, storey: row.address.storeyVersion, bookshelf: row.address.bookshelfVersion } })
  expect(observedPlacement({ kind: 'TEXT', text: '  ', address: null })).toEqual({ kind: 'TEXT', text: '  ' })
  expect(observedPlacement({ kind: 'UNASSIGNED', text: 'old', address: row })).toEqual({ kind: 'UNASSIGNED' })
  expect(() => observedPlacement({ kind: 'REGISTERED', text: '', address: null })).toThrow()
})
it('rejects wrong schema, operation, session and key instead of accepting a mismatched success', () => {
  const op = operation(), good = fixtureReceipt(op.operationKey, 'LINK'); good.result.resolution = fixtureResolution()
  expect(() => stocktakeTransport.validate(op, good)).not.toThrow()
  for (const bad of [{ ...good, schemaVersion: 2 }, { ...good, operationKey: 'other' }, { ...good, operation: 'OBSERVE' }, { ...good, sessionId: 8 }, { ...good, result: { ...good.result, resolution: { ...fixtureResolution(), resolutionId: 99 } } }]) expect(() => stocktakeTransport.validate(op, bad as typeof good)).toThrow()
})
it('keeps a failed association awaiting its original key through missing lookup and never calls original business', async () => {
  const op = operation(), linked = fixtureReceipt(op.operationKey, 'LINK'); linked.result.resolution = { ...fixtureResolution(), state: 'LINKED', version: 2 }
  api.write.mockRejectedValueOnce({ response: { status: 500 } }).mockResolvedValueOnce(linked)
  api.lookup.mockRejectedValue({ response: { status: 404, data: { data: { reasonCode: 'OPERATION_NOT_FOUND' } } } })
  const session = useStocktakeOperation(2, stocktakeTransport)
  await session.start(op); expect(session.state.value).toBe('UNCERTAIN'); expect(session.acknowledge()).toBe(false)
  await session.retry(); expect(session.result.value?.result.resolution?.state).toBe('LINKED')
  expect(api.write.mock.calls).toEqual([[op.path, op.body], [op.path, op.body]])
  expect(api.lookup).toHaveBeenCalledWith(op.operationKey)
})
it('keeps BUSY and duplicate identity unresolved but permits deliberate review after stale rejection', async () => {
  const op = operation(); api.lookup.mockRejectedValue(new Error('offline'))
  for (const code of ['STOCKTAKE_BUSY', 'IDEMPOTENCY_KEY_REUSED']) { sessionStorage.clear(); api.write.mockRejectedValueOnce({ response: { status: 409, data: { data: { reasonCode: code } } } }); const session = useStocktakeOperation(2, stocktakeTransport); await session.start(op); expect(session.state.value).toBe('UNCERTAIN') }
  sessionStorage.clear(); api.write.mockRejectedValueOnce({ response: { status: 409, data: { data: { reasonCode: 'RECHECK_STALE' } } } }); const session = useStocktakeOperation(2, stocktakeTransport); await session.start(op); expect(session.state.value).toBe('REJECTED'); expect(session.pending.value?.body).toEqual(op.body)
})
