import { get, post } from './request'
import type { BookMetadata } from './book'

export interface IntakeSource { type: 'MANUAL' | 'PURCHASE'; purchaseOrderId: number | null }
export interface IntakeCopyInput { input: 'SCANNED' | 'GENERATE'; barcode: string | null; location: string | null }
export interface IntakeLineInput {
  lineKey: string
  target: { bookId: number | null; newBook: BookMetadata | null; purchaseItemId: number | null }
  copies: IntakeCopyInput[]
}
export interface IntakePreviewInput { idempotencyKey: string; source: IntakeSource; reason: string; lines: IntakeLineInput[] }
export interface IntakePreviewLine {
  lineKey: string; bookId: number | null; newBook: BookMetadata | null; purchaseItemId: number | null
  copies: { ordinal: number; input: 'SCANNED' | 'GENERATED'; barcode: string; location: string | null }[]
}
export interface IntakeBookSnapshot {
  bookId: number | null; isbn: string; title: string; metadataDigest: string
  stockTotal: number; stockAvailable: number; registeredCopies: number; registeredAvailableCopies: number
  totalDifference: number; availableDifference: number; newBook: boolean
}
export interface IntakePurchaseProgress { purchaseItemId: number; quantity: number; instockQuantity: number; pendingQuantity: number; receivingQuantity: number }
export interface IntakeReceiptCopy {
  copyId: number; bookId: number; lineKey: string; ordinal: number; barcode: string; input: 'SCANNED' | 'GENERATED'
  location: string | null; status: 'AVAILABLE'; stockFlowId: number; purchaseItemId: number | null
}
export interface IntakeReceipt {
  intakeId: number; source: IntakeSource; operatorId: number; confirmedAt: string
  books: { bookId: number; stockTotalBefore: number; stockAvailableBefore: number; stockTotalAfter: number; stockAvailableAfter: number; addedQuantity: number }[]
  copies: IntakeReceiptCopy[]
  flows: { flowId: number; bookId: number; lineKey: string; purchaseItemId: number | null; quantity: number }[]
  purchaseProgress: IntakePurchaseProgress[]
}
export interface IntakeView {
  intakeId: number; state: 'PREVIEWED' | 'COMMITTED'; previewDigest: string; expiresAt: string; source: IntakeSource
  lines: IntakePreviewLine[]; books: IntakeBookSnapshot[]; purchaseProgress: IntakePurchaseProgress[]; warnings: string[]; receipt: IntakeReceipt | null
}
export const previewIntake = (data: IntakePreviewInput) => post<IntakeView>('/inventory-intakes/previews', data)
export const confirmIntake = (id: number, idempotencyKey: string, previewDigest: string) =>
  post<IntakeReceipt>(`/inventory-intakes/${id}/confirm`, { idempotencyKey, previewDigest })
export const getIntake = (id: number) => get<IntakeView>(`/inventory-intakes/${id}`)
export const getIntakeByKey = (key: string) => get<IntakeView>('/inventory-intakes/by-key', { key })
