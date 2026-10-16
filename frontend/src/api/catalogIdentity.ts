import { get, post } from './request'

export type IdentityKind = 'PUBLISHER' | 'CATEGORY'
export type CategoryDimension = 'TOPIC' | 'LEGACY_AGE' | 'UNVERIFIED'
export type IdentityMode = 'MANUAL'
export interface IdentityAlias { id: number | null; name: string; normalizedName: string; origin: string; status: string; revision: number }
export interface CatalogCategoryChainNode { id: number; name: string; status: number; dimension: CategoryDimension; parentId: number | null; revision: number }
export interface CatalogIdentityRef {
  id: number; name: string; status: number; revision: number; normalizedName: string; aliases: IdentityAlias[]
  parentId?: number | null; level?: number; path?: string | null; dimension?: CategoryDimension
  selectable?: boolean; parentChainActive?: boolean; parentChain?: CatalogCategoryChainNode[]
}
export interface CatalogIdentityTheme extends Omit<CatalogIdentityRef, 'id'> {
  id?: number; categoryId?: number
  sourceName?: string | null; relationKind?: string | null; linkRevision?: number
}
/** id is a review record ID; only entityId identifies a publisher/category. */
export interface CatalogIdentityCandidate {
  id: number | null; entityId: number | null; kind: IdentityKind; name: string | null
  rawName: string | null; normalizedValue: string | null; matchKind: 'EXACT' | 'NORMALIZED' | 'APPROXIMATE' | 'UNMATCHED'
  evidence: unknown; status: string; entityKind?: IdentityKind; rawValue?: string | null
}
export interface CatalogIdentityBook {
  schemaVersion: 1; bookId: number; identityRevision: number; catalogDigest: string
  publisherRaw: string | null; publisherId: number | null; publisher: CatalogIdentityRef | null
  categoryId: number | null; categoryFieldKind?: 'LEGACY'; themes: CatalogIdentityTheme[]; candidates?: CatalogIdentityCandidate[]
}
export interface CatalogIdentityListQuery { keyword?: string; page: number; size: number }
export interface CatalogIdentityListResult { records: CatalogIdentityRef[]; total: number; page: number; size: number; pages: number }
export interface IdentityAliasChange { kind: IdentityKind; ownerId: number; action: 'ADD' | 'CLEAR'; name: string }
export interface CatalogIdentityChanges {
  publisher: { action: 'LINK' | 'CLEAR'; publisherId: number | null } | null
  themes: { action: 'REPLACE'; categoryIds: number[] } | null
  aliases: IdentityAliasChange[]
  categoryDimensions: { categoryId: number; dimension: CategoryDimension }[]
}
export interface CatalogIdentityPreviewInput {
  operationKey: string; mode: IdentityMode; reason: string; bookId: number; expectedRevision: number
  expectedCatalogDigest: string; sourceDigest: null; changes: CatalogIdentityChanges
}
export interface IdentityAliasPreview extends IdentityAliasChange {
  ownerName: string; beforeAliases: IdentityAlias[]; afterAliases: IdentityAlias[]
}
export interface CatalogIdentityPreviewBook {
  bookId: number; expectedRevision: number; catalogDigest: string; sourceDigest: null
  sourceStatus?: 'MANUAL_LEGACY_EVIDENCE' | 'NOT_APPLICABLE' | 'NOT_READ'; before: CatalogIdentityBook; after: CatalogIdentityBook
  changedFields: string[]; changes: CatalogIdentityChanges; candidates: CatalogIdentityCandidate[]
  aliasChanges: IdentityAliasPreview[]; targetSnapshot?: unknown; afterTargetSnapshot?: unknown
}
export interface CatalogIdentityPreview { schemaVersion: 1; mode: IdentityMode; reason: string; createdAt: string; expiresAt: string; book: CatalogIdentityPreviewBook }
export interface CatalogIdentityReceipt {
  schemaVersion: 1; requestId: number; operationKey: string; mode: IdentityMode; actorId: number; confirmedAt: string
  book: { bookId: number; changed: boolean; beforeRevision: number; afterRevision: number; eventId: number | null; identity: CatalogIdentityBook; applied: CatalogIdentityChanges }
}
export interface CatalogIdentityRequestView {
  requestId: number; operationKey: string; mode: IdentityMode; state: 'PREVIEWED' | 'COMMITTED'; previewDigest: string
  expiresAt: string; preview: CatalogIdentityPreview; receipt: CatalogIdentityReceipt | null
}
export const emptyIdentityChanges = (): CatalogIdentityChanges => ({ publisher: null, themes: null, aliases: [], categoryDimensions: [] })
export const getCatalogIdentityPublishers = (params: CatalogIdentityListQuery) => get<CatalogIdentityListResult>('/catalog-identities/publishers', params)
export const getCatalogIdentityCategories = (params: CatalogIdentityListQuery) => get<CatalogIdentityListResult>('/catalog-identities/categories', params)
export const getCatalogIdentityBook = (bookId: number) => get<CatalogIdentityBook>(`/catalog-identities/books/${bookId}`)
export const previewCatalogIdentity = (input: CatalogIdentityPreviewInput) => post<CatalogIdentityRequestView>('/catalog-identities/previews', input)
export const confirmCatalogIdentity = (requestId: number, operationKey: string, previewDigest: string) => post<CatalogIdentityReceipt>(`/catalog-identities/${requestId}/confirm`, { operationKey, previewDigest })
export const getCatalogIdentityRequestByKey = (key: string) => get<CatalogIdentityRequestView>('/catalog-identities/requests/by-key', { key })
