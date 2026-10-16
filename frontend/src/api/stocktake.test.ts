import { expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), blob: vi.fn() }))
vi.mock('./request', () => ({ get: api.get, post: api.post, default: { get: api.blob } }))
import { exportStocktake, getStocktakeCandidates, getStocktakeChanges, getStocktakeDifferences, getStocktakeObservations, getStocktakeOperation, getStocktakes, getStocktakeSnapshot, recoverStocktakeResolution, writeStocktakeOperation } from './stocktake'
it('delegates every report collection/filter/page to the server without page slicing', () => {
  getStocktakes({ page: 3, size: 20, state: 'FINALIZED', campaignKey: '合成批次' }); expect(api.get).toHaveBeenLastCalledWith('/stocktakes', { page: 3, size: 20, state: 'FINALIZED', campaignKey: '合成批次' })
  getStocktakeObservations(7, { page: 2, size: 20, kind: 'CORRECTION', copyId: 11 }); expect(api.get).toHaveBeenLastCalledWith('/stocktakes/7/observations', { page: 2, size: 20, kind: 'CORRECTION', copyId: 11 })
  getStocktakeDifferences(7, { page: 4, size: 20, reviewSnapshotId: 20, type: 'UNKNOWN_BARCODE' }); expect(api.get).toHaveBeenLastCalledWith('/stocktakes/7/differences', { page: 4, size: 20, reviewSnapshotId: 20, type: 'UNKNOWN_BARCODE' })
  getStocktakeChanges(7, { page: 2, size: 20, snapshotId: 20, category: 'LOAN' }); expect(api.get).toHaveBeenLastCalledWith('/stocktakes/7/changes', { page: 2, size: 20, snapshotId: 20, category: 'LOAN' })
})
it('queries exact original operation and safe source candidates, with no receipt-owner workaround', () => {
  getStocktakeOperation('original-uuid'); expect(api.get).toHaveBeenLastCalledWith('/stocktakes/operations/by-key', { key: 'original-uuid' })
  getStocktakeCandidates({ differenceId: 31, workflow: 'INTAKE', page: 2, size: 10 }); expect(api.get).toHaveBeenLastCalledWith('/stocktake-resolution-candidates', { differenceId: 31, workflow: 'INTAKE', page: 2, size: 10 })
  recoverStocktakeResolution(60); expect(api.get).toHaveBeenLastCalledWith('/stocktake-resolutions/60/recover')
  getStocktakeSnapshot(7, 20); expect(api.get).toHaveBeenLastCalledWith('/stocktakes/7/snapshots/20')
})
it('downloads the immutable snapshot backend bytes for both formats', async () => {
  const blob = new Blob(['backend-complete-export']); api.blob.mockResolvedValue(blob)
  expect(await exportStocktake(7, 20, 'csv')).toBe(blob); expect(api.blob).toHaveBeenLastCalledWith('/stocktakes/7/export', { params: { reviewSnapshotId: 20, format: 'csv' }, responseType: 'blob' })
  await exportStocktake(7, 20, 'json'); expect(api.blob).toHaveBeenLastCalledWith('/stocktakes/7/export', { params: { reviewSnapshotId: 20, format: 'json' }, responseType: 'blob' })
})
it('sends frozen write input without adding old business or schema fields', () => {
  const body = { operationKey: 'original-uuid', expectedRevision: 3, reason: '原样依据', items: [{ scopeItemId: 11, status: 'PARTIAL' }] }
  writeStocktakeOperation('/stocktakes/7/coverage', body); expect(api.post).toHaveBeenCalledWith('/stocktakes/7/coverage', body)
})
