import request, { get, post } from './request'
import type { PageResult } from '@/types'
import type { AddressRow, Placement, PlacementSnapshot } from './locationAddress'

export type StocktakeScope = { kind: 'ADDRESS_SET'; locationIds: number[] } | { kind: 'COPY_SET'; copyIds: number[] }
export type CompletionKind = 'PARTIAL' | 'COMPLETE_SCOPE'
export type Workflow = 'INTAKE' | 'MOVE' | 'WRITE_OFF' | 'RESTORE' | 'ENABLE'
export interface StocktakeSession { sessionId: number; ownerId: number; campaignKey: string | null; scope: StocktakeScope; state: 'DRAFT' | 'FINALIZED' | 'CANCELLED'; revision: number; baselineSnapshotId: number; finalSnapshotId: number | null; createdAt: string; confirmedAt: string | null; receipt: FinalReceipt | null }
export interface Coverage { scopeItemId: number; status: 'NOT_ATTEMPTED' | 'PARTIAL' | 'COMPLETE'; actorId: number | null; acceptedAt: string | null; reason: string | null }
export interface Change { entityKey: string; changeToken: string; expectedSignatureDigest: string; previousSignatureDigest: string | null; category: 'COPY' | 'LOAN' | 'ADDRESS' | 'BOOK' | 'SCOPE' | 'BARCODE'; copyId: number | null; requiresRecheck: true }
export interface CopySignature { copyId: number; bookId: number; barcode: string; rawStatus: string; stateClassification: string; actionVersion: number; lastStateActionId: number | null; registeredLocationId: number | null; rawLocation: string | null; lastLocationActionId: number | null; placement: Placement; locationReferenceAnomaly: boolean }
export interface LoanSignature { recordId: number; bookId: number; copyId: number | null; status: string; deleted: number; borrowDate: string | null; dueDate: string | null; dueDateKind: string | null; returnDate: string | null; renewCount: number | null }
export interface BookBackground { bookId: number; isbn: string | null; title: string | null; stockTotal: number; stockAvailable: number; status: number; deleted: number; reconciliationV2: object }
export interface Graph { scope: StocktakeScope; baselineMembership: number[]; currentMembership: number[]; entered: number[]; left: number[]; copies: CopySignature[]; loans: LoanSignature[]; books: BookBackground[]; addresses: AddressRow[]; cursors: { actionEvent: number; intakeCopy: number; structureEvent: number }; relatedActionEventIds: number[]; relatedIntakeCopyIds: number[]; relatedStructureEventIds: number[]; barcodeMappings: { rawBarcodeBase64: string; copyId: number | null; unsupported: boolean }[]; entities: Record<string, string>; fingerprint: string; bookIds: number[]; recordIds: number[]; copyIds: number[]; addressIds: number[] }
export interface Review { snapshotId: number; snapshotKind: 'BASELINE' | 'REVIEW'; purpose: 'BASELINE' | 'CHECK' | 'PREPARE'; sourceRevision: number; schemaVersion: 1; digest: string; createdAt: string; expiresAt: string | null; completionKind: CompletionKind | null; graph: Graph; observationDigest: string; coverage: Coverage[]; changes: Change[]; differenceIds: number[] }
export interface FinalReceipt { schemaVersion: 1; sessionId: number; reviewSnapshotId: number; completionKind: CompletionKind; scopeDigest: string; baselineMembershipDigest: string; currentMembershipDigest: string; observationDigest: string; finalDifferenceIds: number[]; coverage: Coverage[]; actorId: number; confirmedAt: string; graphFingerprint: string }
export type ObservedPlacement = { kind: 'REGISTERED'; locationId: number; expectedAddressVersion: { location: number | null; storey: number | null; bookshelf: number | null } } | { kind: 'TEXT'; text: string } | { kind: 'UNASSIGNED' }
export interface ObservationEvent { clientEventKey: string; rawBarcode: string; observedPlacement: ObservedPlacement; clientObservedAt: string | null }
export interface Observation { observationId: number; kind: 'SCAN' | 'CORRECTION' | 'VOID' | 'COVERAGE' | 'RECHECK'; clientEventKey: string | null; rawBarcode: string | null; rawBarcodeBase64: string | null; observedPlacement: PlacementSnapshot | null; resolvedCopyIdAtScan: number | null; signature: object; referencedObservationId: number | null; actorId: number; acceptedAt: string; clientObservedAt: string | null; reason: string; active: boolean }
export interface Difference { differenceId: number; sessionId: number; reviewSnapshotId: number; differenceKey: string; type: string; copyId: number | null; rawBarcode: string | null; rawBarcodeBase64: string | null; evidence: { registered: CopySignature | null; observedPlacements: PlacementSnapshot[]; observationIds: number[]; coverageStatus: string | null; explanation: string }; createdAt: string }
export interface Candidate { workflow: Workflow; candidateId: number; requestId: number; copyId: number; bookId: number; barcode: string; stockFlowId: number | null; committedAt: string }
export interface Resolution { resolutionId: number; sessionId: number; differenceIds: number[]; decision: 'OPEN' | 'NO_ACTION_WITH_REASON' | 'BUSINESS_REQUIRED'; workflow: Workflow | null; candidateId: number | null; state: 'OPEN' | 'NO_ACTION' | 'AWAITING_LINK' | 'LINKED' | 'REVOKED'; version: number; actorId: number; createdAt: string; reason: string; candidate: Candidate | null; events: { eventId: number; kind: 'DECIDE' | 'INTENT' | 'LINK' | 'REVOKE'; actorId: number; occurredAt: string; reason: string; evidence: object }[] }
export interface OperationReceipt { schemaVersion: 1; operationKey: string; operation: string; sessionId: number; revision: number; result: { session: StocktakeSession | null; observationIds: number[]; review: Review | null; receipt: FinalReceipt | null; resolution: Resolution | null } }
export interface PageQuery { page: number; size: number }
export interface WriteBase { operationKey: string; expectedRevision: number; reason: string }
export interface RecheckItem { entityKey: string; changeToken: string; expectedSignatureDigest: string; decision: 'OBSERVED_AGAIN' | 'CHECKED_NOT_SEEN' | 'LEGITIMATE_LOAN' | 'CONFIRM_EXPLANATION'; evidence: string }

export const getStocktakes = (params: PageQuery & { state?: string; campaignKey?: string }) => get<PageResult<StocktakeSession>>('/stocktakes', params)
export const getStocktake = (id: number) => get<StocktakeSession>(`/stocktakes/${id}`)
export const getStocktakeSnapshot = (id: number, snapshotId: number) => get<Review>(`/stocktakes/${id}/snapshots/${snapshotId}`)
export const getStocktakeOperation = (key: string) => get<OperationReceipt>('/stocktakes/operations/by-key', { key })
export const writeStocktakeOperation = (path: string, body: object) => post<OperationReceipt>(path, body)
export const getStocktakeObservations = (id: number, params: PageQuery & { copyId?: number; kind?: string }) => get<PageResult<Observation>>(`/stocktakes/${id}/observations`, params)
export const getStocktakeDifferences = (id: number, params: PageQuery & { reviewSnapshotId?: number; type?: string; copyId?: number }) => get<PageResult<Difference>>(`/stocktakes/${id}/differences`, params)
export const getStocktakeChanges = (id: number, params: PageQuery & { snapshotId?: number; copyId?: number; category?: string }) => get<PageResult<Change>>(`/stocktakes/${id}/changes`, params)
export const getStocktakeResolutions = (id: number, params: PageQuery) => get<PageResult<Resolution>>(`/stocktakes/${id}/resolutions`, params)
export const getStocktakeResolution = (id: number) => get<Resolution>(`/stocktake-resolutions/${id}`)
export const recoverStocktakeResolution = (id: number) => get<Resolution>(`/stocktake-resolutions/${id}/recover`)
export const getStocktakeCandidates = (params: PageQuery & { differenceId: number; workflow: Workflow }) => get<PageResult<Candidate>>('/stocktake-resolution-candidates', params)
export const exportStocktake = (id: number, reviewSnapshotId: number, format: 'json' | 'csv') => request.get<unknown, Blob>(`/stocktakes/${id}/export`, { params: { reviewSnapshotId, format }, responseType: 'blob' })
