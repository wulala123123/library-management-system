import request, { get } from './request'
import type { CopyReconciliationV2 } from './bookCopyAction'

export interface BasicStats {
  catalogTitles: number
  totalCopies: number
  availableCopies: number
  borrowedCopies: number
  availableTitles: number
  lowStockTitles: number
  accountCount: number
  memberCount: number
  validMemberCount: number
  unknownValidityMemberCount: number
  asOfDate: string
  statisticalScope: Record<string, string>
  reconciliationV2?: CopyReconciliationV2
}

export interface DashboardStats extends BasicStats {
  todayBorrows: number
  overdueCount: number
  totalBorrows: number
}

export type InventoryStats = BasicStats

export interface InventoryReconciliationItem {
  bookId: number
  title: string
  totalCopies: number
  availableCopies: number
  borrowedCopies: number
  registeredCopies: number
  registeredAvailableCopies: number
  registeredBorrowedCopies: number
  registeredOtherCopies?: number
  totalDifference: number
  availableDifference: number
  borrowedDifference: number
  stockBalanceDifference: number
  consistent: boolean
  reconciliationV2?: CopyReconciliationV2
}

export interface InventoryReconciliation {
  asOfDate: string
  statisticalScope: Record<string, string>
  totalCopies: number
  availableCopies: number
  borrowedCopies: number
  registeredCopies: number
  registeredAvailableCopies: number
  registeredBorrowedCopies: number
  registeredOtherCopies?: number
  mismatchedTitles: number
  items: InventoryReconciliationItem[]
  reconciliationV2?: CopyReconciliationV2
}

export function getDashboardStats() {
  return get<DashboardStats>('/dashboard/stats')
}

export function getInventoryStats() {
  return get<InventoryStats>('/inventory/stats')
}

export function getStatistics() {
  return get<DashboardStats>('/statistics')
}

export function getInventoryReconciliation() {
  return get<InventoryReconciliation>('/inventory/reconciliation')
}

export function exportStatistics() {
  return request.get<unknown, Blob>('/statistics/export', { responseType: 'blob' })
}

export function getBorrowTrend() {
  return get<Array<{ label: string; value: number }>>('/statistics/borrow-trend')
}

export function getCategoryDistribution() {
  return get<Array<{ name: string; count: number; percentage: number; color: string }>>('/statistics/category-distribution')
}

export function getHotBooks() {
  return get<Array<{ title: string; borrowCount: number }>>('/statistics/hot-books')
}
