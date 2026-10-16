import { get, post } from './request'
import type { CopyRow } from './bookCopyQuery'
import type { PlacementSnapshot } from './locationAddress'

export type CopyAction = 'MOVE' | 'DISABLE' | 'ENABLE' | 'WRITE_OFF' | 'RESTORE'
export interface CopyReconciliationV2 {
  schema: 'copy-lifecycle-v1'
  registeredCopies: number; registeredAvailableCopies: number; registeredBorrowedCopies: number
  registeredInStockCopies: number; registeredDisabledCopies: number; registeredWrittenOffCopies: number; registeredUnknownCopies: number
  inStockDifference: number; availableDifference: number; borrowedDifference: number; unexplainedStockBalanceDifference: number
  hasHistoricalDifference: boolean; hasUnknownStatus: boolean; hasLoanLinkAnomaly: boolean; hasKnownUnavailable: boolean
}
export type CopyTargetLocation = { kind: 'UNASSIGNED' } | { kind: 'TEXT'; text: string }
export interface CopyActionInput {
  copyId: number; expectedVersion: number; targetLocation?: CopyTargetLocation
  restoreOfActionId?: number; restoreTo?: 'DISABLED'
}
export interface CopyActionPreviewInput { idempotencyKey: string; action: CopyAction; reason: string; copies: CopyActionInput[] }
export interface CopyChange {
  ordinal: number; copyId: number; bookId: number; barcode: string; beforeStatus: string; afterStatus: string
  beforeLocation: string | null; afterLocation: string | null; beforeVersion: number; afterVersion: number
  beforeStateActionId: number | null; restoreOfActionId: number | null; deltaTotal: number; deltaAvailable: number
}
export interface CopyBookChange {
  bookId: number; isbn: string; title: string; stockTotalBefore: number; stockAvailableBefore: number
  stockTotalAfter: number; stockAvailableAfter: number; deltaTotal: number; deltaAvailable: number
  reconciliationV2Before: CopyReconciliationV2; reconciliationV2After: CopyReconciliationV2
}
export interface CopyActionReceiptCopy extends CopyChange { eventId: number; stockFlowId: number | null }
export interface CopyActionReceipt {
  requestId: number; schemaVersion: 1; idempotencyKey: string; action: CopyAction; operatorId: number; committedAt: string
  copies: CopyActionReceiptCopy[]; books: CopyBookChange[]; warnings: string[]
}
export interface CopyActionView {
  requestId: number; state: 'PREVIEWED' | 'COMMITTED'; schemaVersion: 1; action: CopyAction; previewDigest: string; expiresAt: string
  copies: CopyChange[]; books: CopyBookChange[]; warnings: string[]; receipt: CopyActionReceipt | null
}
export type RegisteredTargetLocation = { kind: 'REGISTERED'; locationId: number; expectedAddressVersion: { location: number; storey: number; bookshelf: number } }
export interface CopyActionInputV2 extends Omit<CopyActionInput, 'targetLocation'> { targetLocation?: CopyTargetLocation | RegisteredTargetLocation }
export interface CopyActionPreviewInputV2 { schemaVersion: 2; idempotencyKey: string; action: CopyAction; reason: string; copies: CopyActionInputV2[] }
export interface CopyChangeV2 extends CopyChange {
  beforePlacement: PlacementSnapshot; afterPlacement: PlacementSnapshot; beforeLocationActionId: number | null; locationSourceBehavior: 'KEEP' | 'THIS_EVENT'
}
export interface CopyActionReceiptCopyV2 extends CopyChangeV2 { eventId: number; stockFlowId: number | null; afterLocationActionId: number | null }
export interface CopyActionReceiptV2 extends Omit<CopyActionReceipt, 'schemaVersion' | 'copies'> { schemaVersion: 2; copies: CopyActionReceiptCopyV2[] }
export interface CopyActionViewV2 extends Omit<CopyActionView, 'schemaVersion' | 'copies' | 'receipt'> { schemaVersion: 2; copies: CopyChangeV2[]; receipt: CopyActionReceiptV2 | null }
export type AnyCopyActionInput = CopyActionPreviewInput | CopyActionPreviewInputV2
export type AnyCopyActionView = CopyActionView | CopyActionViewV2
export type AnyCopyActionReceipt = CopyActionReceipt | CopyActionReceiptV2
export interface LocationChange {
  snapshotVersion: 1; beforePlacement: PlacementSnapshot; afterPlacement: PlacementSnapshot; beforeLocationActionId: number | null
  locationSourceBehavior: 'KEEP' | 'THIS_EVENT'; afterLocationActionId: number | null
}
export interface CopyActionPermission { action: CopyAction; allowed: boolean; reasonCode: string | null; restoreOfActionId: number | null }
export interface CopyActionContext { copy: CopyRow; reconciliationV2: CopyReconciliationV2; actions: CopyActionPermission[]; warnings: string[] }
export interface CopyActionEvent extends CopyActionReceiptCopy { requestId: number; action: CopyAction; reason: string; operatorId: number; occurredAt: string; restoredByActionId: number | null; schemaVersion: 1 | 2; locationChange: LocationChange | null }
export interface CopyActionHistory {
  records: CopyActionEvent[]; total: number; page: number; size: number
  intakeSource: { intakeId: number; sourceType: 'MANUAL' | 'PURCHASE'; purchaseOrderId: number | null; purchaseItemId: number | null; stockFlowId: number; operatorId: number; confirmedAt: string } | null
}
export const previewCopyAction = (data: CopyActionPreviewInput) => post<CopyActionView>('/book-copy-actions/previews', data)
export const previewCopyActionV2 = (data: CopyActionPreviewInputV2) => post<CopyActionViewV2>('/book-copy-actions/v2/previews', data)
export const confirmCopyAction = (id: number, idempotencyKey: string, previewDigest: string) => post<AnyCopyActionReceipt>(`/book-copy-actions/${id}/confirm`, { idempotencyKey, previewDigest })
export const getCopyAction = (id: number) => get<AnyCopyActionView>(`/book-copy-actions/${id}`)
export const getCopyActionByKey = (key: string) => get<AnyCopyActionView>('/book-copy-actions/by-key', { key })
export const getCopyActionContext = (id: number) => get<CopyActionContext>(`/book-copies/${id}/action-context`)
export const getCopyActionHistory = (id: number, page = 1, size = 20) => get<CopyActionHistory>(`/book-copies/${id}/actions`, { page, size })
