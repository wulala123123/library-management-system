import { get, post } from './request'

export type PublicationEvidenceKind = 'COPYRIGHT_PAGE' | 'PUBLISHER_MATERIAL' | 'LEGACY_RECORD' | 'MANUAL_REVIEW'
export interface PublicationEvidence { kind: PublicationEvidenceKind; reference: string }
export type PublicationChange<T> = { action: 'SET'; value: T } | { action: 'CLEAR' } | null
export interface PublicationChanges {
  publishDate: PublicationChange<string>
  pages: PublicationChange<number>
  description: PublicationChange<string>
}
export interface PublicationBook {
  bookId: number; isbn: string | null; catalogTitle: string; displayTitle: string
  publishDate: string | null; pages: number | null; description: string | null
  revision: number; metadataDigest: string
}
export interface PublicationEntry {
  bookId: number; expectedRevision: number; expectedMetadataDigest: string
  changes: PublicationChanges; evidence: PublicationEvidence
}
export interface PublicationPreviewInput { operationKey: string; mode: 'MANUAL'; reason: string; entries: PublicationEntry[] }
export interface PublicationPreviewBook {
  bookId: number; before: PublicationBook; after: PublicationBook; changes: PublicationChanges
  evidence: PublicationEvidence; changedFields: string[]; willChange: boolean
}
export interface PublicationPreview {
  schemaVersion: 1; mode: 'MANUAL'; reason: string; createdAt: string; expiresAt: string; books: PublicationPreviewBook[]
}
export interface PublicationReceiptBook {
  bookId: number; changed: boolean; beforeRevision: number; afterRevision: number; eventId: number | null
  publication: PublicationBook; applied: PublicationChanges; evidence: PublicationEvidence
}
export interface PublicationReceipt {
  schemaVersion: 1; requestId: number; operationKey: string; mode: 'MANUAL'; actorId: number
  confirmedAt: string; books: PublicationReceiptBook[]
}
export interface PublicationRequestView {
  requestId: number; operationKey: string; mode: 'MANUAL'; state: 'PREVIEWED' | 'COMMITTED'
  previewDigest: string; expiresAt: string; preview: PublicationPreview; receipt: PublicationReceipt | null
}
export interface PublicationAuditSnapshot extends Omit<PublicationBook, 'displayTitle' | 'metadataDigest'> { schemaVersion: 1; deleted: number }
export interface PublicationEvent {
  eventId: number; schemaVersion: 1; bookId: number; requestId: number | null
  kind: 'MANUAL' | 'LEGACY_METADATA' | 'LEGACY_NORMALIZE' | 'LEGACY_NORMALIZE_BATCH' | 'LEGACY_DELETE'
  beforeRevision: number; afterRevision: number; changedFields: string[]; before: PublicationAuditSnapshot; after: PublicationAuditSnapshot
  evidence: PublicationEvidence | null; actorId: number; occurredAt: string; reason: string
}
export interface PublicationEvents { records: PublicationEvent[]; total: number; page: number; size: number; pages: number }
export const emptyPublicationChanges = (): PublicationChanges => ({ publishDate: null, pages: null, description: null })
export const getPublicationBook = (bookId: number) => get<{ schemaVersion: 1; book: PublicationBook }>(`/catalog-publications/books/${bookId}`)
export const previewPublication = (input: PublicationPreviewInput) => post<PublicationRequestView>('/catalog-publications/previews', input)
export const confirmPublication = (requestId: number, operationKey: string, previewDigest: string) => post<PublicationReceipt>(`/catalog-publications/${requestId}/confirm`, { operationKey, previewDigest })
export const getPublicationRequest = (requestId: number) => get<PublicationRequestView>(`/catalog-publications/${requestId}`)
export const getPublicationRequestByKey = (key: string) => get<PublicationRequestView>('/catalog-publications/requests/by-key', { key })
export const getPublicationEvents = (bookId: number, page = 1, size = 20) => get<PublicationEvents>(`/catalog-publications/books/${bookId}/events`, { page, size })
