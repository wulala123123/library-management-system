import { get } from './request'
import type { PageResult } from '@/types'
import type { CopyReconciliationV2 } from './bookCopyAction'
import type { Placement } from './locationAddress'
export interface CopyBook {
  id: number; title: string; isbn: string | null; author: string | null; publisher: string | null
  publishDate: string | null; pages: number | null; coverImage: string | null; location: string | null; status: number
}
export interface CopyRow {
  id: number; bookId: number; barcode: string; status: string; location: string | null; unassignedLocation: boolean; book: CopyBook
  actionVersion: number; lastStateActionId: number | null; stateClassification: 'AVAILABLE' | 'BORROWED' | 'DISABLED' | 'WRITTEN_OFF' | 'UNKNOWN'
  placement: Placement; lastLocationActionId: number | null; locationReferenceAnomaly: boolean
}
export interface CopyQuery { page: number; size: number; keyword?: string; bookId?: number; status?: string; location?: string; unassignedLocation?: boolean; barcode?: string; locationKind?: Placement['kind']; registeredLocationId?: number }
export interface CopyDetail {
  copy: CopyRow
  currentLoans: { borrowId: number; status: string; borrowDate: string | null; dueDate: string | null; dueDateKind: 'DATED' | 'UNLIMITED' | 'UNKNOWN'; memberName: string | null; cardNo: string | null; bookMatches: boolean }[]
  needsVerification: boolean
  reconciliationV2?: CopyReconciliationV2
}
export interface CatalogCopySummary {
  book: CopyBook; totalCopies: number; availableCopies: number; registeredCopies: number
  registeredAvailableCopies: number; registeredBorrowedCopies: number; registeredOtherCopies: number; registeredByStatus: Record<string, number>
  borrowedCopies: number; totalDifference: number; availableDifference: number; borrowedDifference: number
  stockBalanceDifference: number; needsVerification: boolean; countReconciliationOnly: true
  reconciliationV2?: CopyReconciliationV2
}
export const getBookCopies = (params: CopyQuery) => get<PageResult<CopyRow>>('/book-copies', params)
export const getBookCopy = (id: number) => get<CopyDetail>(`/book-copies/${id}`)
export const getCatalogCopySummary = (params: Pick<CopyQuery, 'page' | 'size' | 'keyword' | 'bookId'>) => get<PageResult<CatalogCopySummary>>('/book-copies/catalog-summary', params)
