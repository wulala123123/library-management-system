import { get, post } from './request'

export type SetStatus = 'ACTIVE' | 'RETIRED'
export type SetOperation = 'CREATE' | 'UPDATE_METADATA' | 'ADD_MEMBERS' | 'RETIRE_MEMBERS' | 'RESTORE_MEMBERS' | 'RETIRE_SET' | 'RESTORE_SET'
export type CancelReason = 'EDIT' | 'ABANDON'
export type EvidenceKind = 'COPYRIGHT_PAGE' | 'PUBLISHER_MATERIAL' | 'LEGACY_RECORD' | 'MANUAL_REVIEW'
export interface SetEvidence { kind: EvidenceKind; reference: string }
export type SetChange<T> = { action: 'KEEP' } | { action: 'SET'; value: T } | { action: 'CLEAR' }
export interface SetExpected { revision: number; digest: string }
export interface BookExpected extends SetExpected { attributeRevision: number; sourceDigest: string }
export interface NewSetMember { bookId: number; expectedBook: BookExpected; evidence: SetEvidence }
export interface ExistingSetMember extends NewSetMember { membershipId: number; expectedMember: SetExpected }
interface Header { schemaVersion: 1; operationKey: string; mode: 'MANUAL'; reason: string }
export type SetPreviewInput = Header & (
  { operation: 'CREATE'; clientRef: string; name: string; note: string | null; evidence: SetEvidence; members: NewSetMember[] } |
  { operation: 'UPDATE_METADATA'; setId: number; expectedSet: SetExpected; changes: { name: SetChange<string>; note: SetChange<string> }; evidence: SetEvidence } |
  { operation: 'ADD_MEMBERS'; setId: number; expectedSet: SetExpected; members: NewSetMember[] } |
  { operation: 'RETIRE_MEMBERS' | 'RESTORE_MEMBERS'; setId: number; expectedSet: SetExpected; members: ExistingSetMember[] } |
  { operation: 'RETIRE_SET' | 'RESTORE_SET'; setId: number; expectedSet: SetExpected; evidence: SetEvidence }
)
export interface SetSnapshot { setId: number | null; clientRef: string | null; name: string; note: string | null; status: SetStatus; revision: number; activeMemberCount: number; retiredMemberCount: number; memberCompleteness: 'UNVERIFIED'; physicalAssembly: 'UNVERIFIED' }
export interface SetSummary extends Omit<SetSnapshot, 'setId' | 'note' | 'clientRef'> { setId: number; effectiveMemberCount: number }
export interface SetBookIdentity { bookId: number; isbn: string; catalogTitle: string; displayTitle: string; revision: number; attributeRevision: number; deleted: boolean }
export const markerStates = ['NO_SOURCE', 'CONFLICT', 'MISSING', 'NULL', 'EMPTY', 'WHITESPACE', 'UNSET', 'WRONG_TYPE', 'MEMBER_MARKER', 'UNMAPPED'] as const
export type SetMarkerState = typeof markerStates[number]
export const reviewReasons = ['MEMBER_MARKER_NO_PARENT', 'SOURCE_CONFLICT', 'ASSOCIATION_INVALID', 'UNMAPPED_MARKER', 'NO_SOURCE', 'INCOMPLETE_SOURCE_VALUE', 'SOURCE_LIMIT_EXCEEDED'] as const
export type SetReviewReason = typeof reviewReasons[number]
export interface SetSourceSummary { parserVersion: 'huiben-book-set-v1'; sourceCount: number; associationState: 'NO_SOURCE' | 'VALID' | 'ASSOCIATION_INVALID'; markerState: SetMarkerState; reasons: SetReviewReason[]; digest: string; readAt: string }
export interface SetBookContext { book: SetBookIdentity; bookDigest: string; source: SetSourceSummary }
export interface SetMemberSnapshot { membershipId: number | null; setId: number | null; bookId: number; status: SetStatus; revision: number; evidence: SetEvidence }
export interface SafeSetMember { membershipId: number; setId: number; bookId: number; status: SetStatus; revision: number; effective: boolean; book: Omit<SetBookIdentity, 'revision' | 'attributeRevision'> }
export interface SafeSetMembership extends SafeSetMember { set: { setId: number; name: string; status: SetStatus; revision: number } }
export interface MaintenanceMember { member: SetMemberSnapshot; memberDigest: string; book: SetBookContext }
export interface SetPage<T> { schemaVersion: 1; records: T[]; total: number; page: number; size: number; pages: number }
export interface SetSide { set: SetSnapshot | null; members: { bookId: number; member: SetMemberSnapshot | null }[] }
export interface SetImpact { newSets: number; addedMembers: number; retiredMembers: number; restoredMembers: number; changedMembers: number; unchangedActiveMembers: number; unchangedRetiredMembers: number; copies: 0; stockTotal: 0; stockAvailable: 0; loans: 0; charges: 0; uses: 0 }
export interface SetPreview { schemaVersion: 1; operation: SetOperation; mode: 'MANUAL'; reason: string; createdAt: string; expiresAt: string; before: SetSide; after: { set: SetSnapshot; members: { bookId: number; member: SetMemberSnapshot }[] }; books: SetBookContext[]; applied: SetPreviewInput; changedFields: string[]; willChange: boolean; impact: SetImpact; warnings: { code: 'SIMILAR_NAME' | 'COMPLETENESS_UNVERIFIED' | 'PHYSICAL_ASSEMBLY_UNVERIFIED'; setIds: number[]; total: number; truncated: boolean }[] }
export interface SetReceipt { schemaVersion: 1; requestId: number; operationKey: string; mode: 'MANUAL'; operation: SetOperation; actorId: number; confirmedAt: string; changed: boolean; eventIds: number[]; before: SetSide; impact: SetImpact; result: { set: SetSnapshot; members: SetMemberSnapshot[]; createdSet: { clientRef: string; setId: number } | null }; previewDigest: string }
export interface SetRequestView { schemaVersion: 1; requestId: number; operationKey: string; mode: 'MANUAL'; operation: SetOperation; state: 'PREVIEWED' | 'COMMITTED' | 'CANCELLED'; createdAt: string; expiresAt: string; previewDigest: string; preview: SetPreview; receipt: SetReceipt | null; cancelledAt: string | null; cancelReason: CancelReason | null }
export interface SetEvent { schemaVersion: 1; eventId: number; requestId: number; setId: number; operation: SetOperation; actorId: number; occurredAt: string; reason: string; beforeRevision: number; afterRevision: number; changedFields: string[]; before: SetSide; after: SetSide; evidence: { set: SetEvidence | null; members: { bookId: number; evidence: SetEvidence }[] }; books: SetBookContext[] }
export interface SetReviewCase { caseKey: string; caseKind: 'BOOK_SOURCE' | 'ORPHAN_SOURCE'; book: SetBookIdentity | null; source: SetSourceSummary; relationshipState: 'NONE' | 'ACTIVE' | 'HISTORICAL'; activeSetCount: number; historicalMembershipCount: number; catalogMembershipState: 'NONE' | 'HAS_CONFIRMED_RELATIONS'; sourceResolution: 'UNRESOLVED'; physicalAssembly: 'UNVERIFIED' }
export interface SetSourceRecord { recordId: number; sourceKey: string; sourceBookId: number | null; sourceBookDeleted: boolean | null; copyMatches: { copyId: number; bookId: number; deleted: boolean }[]; rootKind: string; cell: { kind: 'MISSING' | 'NULL' | 'STRING' | 'NON_STRING'; rawValue: string | null; valueSha256: string }; associationState: 'VALID' | 'ASSOCIATION_INVALID' }
export interface SetSources extends SetPage<SetSourceRecord> { caseKey: string; evidenceDigest: string }
export interface SetReviewQuery { page: number; size: number; scope?: 'ALL' | 'MEMBER_MARKER'; reason?: SetReviewReason[]; markerState?: SetMarkerState[]; associationState?: string; relationshipState?: string; keyword?: string; bookDeleted?: 'EXCLUDE' | 'ONLY' | 'ALL' }

function query(values: object) { const params = new URLSearchParams(); Object.entries(values).forEach(([key, value]) => { if (value !== undefined && value !== null) (Array.isArray(value) ? value : [value]).forEach(item => params.append(key, String(item))) }); return params.toString() }
export const getCatalogSets = (params: { page: number; size: number; keyword?: string; status?: SetStatus | 'ALL'; bookId?: number }) => get<SetPage<SetSummary>>(`/catalog-sets?${query(params)}`)
export const getCatalogSet = (setId: number) => get<{ schemaVersion: 1; set: SetSnapshot; setDigest: string }>(`/catalog-sets/${setId}`)
export const getSetMembers = (setId: number, page = 1, size = 20, status: SetStatus | 'ALL' = 'ACTIVE') => get<SetPage<SafeSetMember>>(`/catalog-sets/${setId}/members?${query({ page, size, status })}`)
export const getMaintenanceMembers = (setId: number, page = 1, size = 20, status: SetStatus | 'ALL' = 'ACTIVE') => get<SetPage<MaintenanceMember>>(`/catalog-sets/${setId}/maintenance-members?${query({ page, size, status })}`)
export const getBookSetMemberships = (bookId: number, page = 1, size = 20, setStatus: SetStatus | 'ALL' = 'ACTIVE', memberStatus: SetStatus | 'ALL' = 'ACTIVE') => get<SetPage<SafeSetMembership>>(`/catalog-sets/books/${bookId}/memberships?${query({ page, size, setStatus, memberStatus })}`)
export const getSetBookContexts = (bookId: number[]) => get<{ schemaVersion: 1; records: SetBookContext[] }>(`/catalog-sets/books/contexts?${query({ bookId })}`)
export const previewCatalogSet = (input: SetPreviewInput) => post<SetRequestView>('/catalog-sets/previews', input)
export const confirmCatalogSet = (id: number, operationKey: string, previewDigest: string) => post<SetReceipt>(`/catalog-sets/requests/${id}/confirm`, { schemaVersion: 1, operationKey, previewDigest })
export const cancelCatalogSet = (id: number, operationKey: string, previewDigest: string, reason: CancelReason) => post<SetRequestView>(`/catalog-sets/requests/${id}/cancel`, { schemaVersion: 1, operationKey, previewDigest, reason })
export const getSetRequestByKey = (key: string) => get<SetRequestView>(`/catalog-sets/requests/by-key?${query({ key })}`)
export const getSetEvents = (setId: number, page = 1, size = 20) => get<SetPage<SetEvent>>(`/catalog-sets/${setId}/events?${query({ page, size })}`)
export const getSetReviews = (params: SetReviewQuery) => get<SetPage<SetReviewCase>>(`/catalog-set-reviews?${query(params)}`)
export const getSetReviewSources = (caseKey: string, page = 1, size = 20) => get<SetSources>(`/catalog-set-reviews/sources?${query({ caseKey, page, size })}`)
