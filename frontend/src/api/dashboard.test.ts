import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ get: vi.fn(), requestGet: vi.fn() }))
vi.mock('./request', () => ({ get: api.get, default: { get: api.requestGet } }))
import { exportStatistics, getDashboardStats, getInventoryReconciliation, getInventoryStats, getStatistics } from './dashboard'

beforeEach(() => vi.clearAllMocks())
it('uses the shared statistics endpoints and requests CSV as a blob', async () => {
  await getDashboardStats(); await getInventoryStats(); await getStatistics(); await getInventoryReconciliation(); await exportStatistics()
  expect(api.get.mock.calls).toEqual([['/dashboard/stats'], ['/inventory/stats'], ['/statistics'], ['/inventory/reconciliation']])
  expect(api.requestGet).toHaveBeenCalledWith('/statistics/export', { responseType: 'blob' })
})
