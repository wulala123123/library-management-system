import { get, post } from './request'
import type { PageResult } from '@/types'
export type AgeLabel = 'AGE_0_2' | 'AGE_3_4' | 'AGE_5_6' | 'AGE_7_9' | 'AGE_10_12'
export type BindingCode = 'BOARD' | 'PAPERBACK' | 'HARDCOVER'
export type Origin = 'UNKNOWN' | 'MANUAL' | 'SOURCE'
export type AttributeMode = 'MANUAL' | 'SOURCE'
export type AttributeField = 'displayTitleOverride' | 'bindingCode' | 'ageLabels' | 'adoptedSourceTitle'
export interface CatalogBook {
  id: number; isbn: string | null; catalogTitle: string; displayTitle: string; adoptedSourceTitle: string | null
  author: string | null; publisher: string | null; publishDate: string | null; categoryId: number | null; categoryName: string | null
  price: number | null; pages: number | null; coverImage: string | null; description: string | null; location: string | null
  stockTotal: number | null; stockAvailable: number | null; status: number | null; createTime: string | null; updateTime: string | null
  bindingCode: BindingCode | null; ageLabels: AgeLabel[] | null
}
export interface CatalogQuery { page: number; size: number; keyword?: string; categoryId?: number; status?: number | null; bindingCode?: BindingCode; bindingState?: 'KNOWN' | 'UNKNOWN'; ageLabels?: AgeLabel[]; ageState?: 'KNOWN' | 'UNKNOWN' }
export interface ProfileSnapshot { bookId: number; revision: number; catalogTitle: string; displayTitleOverride: string | null; displayOrigin: Origin; adoptedSourceTitle: string | null; bindingCode: BindingCode | null; bindingOrigin: Origin; ageLabels: AgeLabel[] | null; ageOrigin: Origin }
export interface AttributeView { schemaVersion: 1; profile: ProfileSnapshot; displayTitle: string; catalogDigest: string }
export type SourceState = 'NO_SOURCE' | 'MISSING' | 'NULL' | 'EMPTY' | 'WHITESPACE' | 'UNSET' | 'VALUE' | 'UNMAPPED' | 'WRONG_TYPE' | 'CONFLICT'
export type SourceKey = AttributeField | 'publisher' | 'setType'
export interface SourceCell { kind: 'MISSING' | 'NULL' | 'STRING' | 'NON_STRING'; rawValue: string | null; valueSha256: string }
export interface SourceField { state: SourceState; canAdopt: boolean; proposedValue: string | AgeLabel[] | null }
export interface BookSource { bookId: number; catalogDigest: string; revision: number; sourceDigest: string; associationState: 'VALID' | 'NO_SOURCE' | 'ASSOCIATION_INVALID'; sourceCount: number; fields: Record<SourceKey, SourceField>; records: { recordId: number; sourceKey: string; copyId: number | null; bookId: number | null; detailsSha256: string; cells: Record<SourceKey, SourceCell> }[] }
export type Choice = { action: 'SET'; value: string | AgeLabel[] } | { action: 'CLEAR' } | { action: 'ADOPT' }
export type AttributeChanges = Record<AttributeField, Choice | null>
export interface AttributePreviewInput { operationKey: string; mode: AttributeMode; reason: string; bookId: number; expectedRevision: number; expectedCatalogDigest: string; sourceDigest: string | null; changes: AttributeChanges }
export interface AttributePreview { schemaVersion: 1; mode: AttributeMode; reason: string; createdAt: string; expiresAt: string; book: { bookId: number; expectedRevision: number; catalogDigest: string; sourceDigest: string | null; before: ProfileSnapshot; after: ProfileSnapshot; changedFields: string[]; willChange: boolean; sourceEvidence: BookSource | null } }
export interface AttributeReceipt { schemaVersion: 1; requestId: number; operationKey: string; mode: AttributeMode; actorId: number; confirmedAt: string; book: { bookId: number; changed: boolean; beforeRevision: number; afterRevision: number; eventId: number | null; profile: ProfileSnapshot; displayTitle: string; catalogDigest: string } }
export interface AttributeRequestView { requestId: number; operationKey: string; mode: AttributeMode; state: 'PREVIEWED' | 'COMMITTED'; previewDigest: string; expiresAt: string; preview: AttributePreview; receipt: AttributeReceipt | null }
export const getCatalogBooks = (params: CatalogQuery) => {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null) continue
    if (Array.isArray(value)) value.forEach(item => query.append(key, item))
    else query.set(key, String(value))
  }
  return get<PageResult<CatalogBook>>(`/catalog-books?${query.toString()}`)
}
export const getCatalogBook = (id: number) => get<CatalogBook>(`/catalog-books/${id}`)
export const getCatalogAttributes = (id: number) => get<AttributeView>(`/catalog-books/${id}/attributes`)
export const getBookSource = (id: number) => get<BookSource>(`/catalog-books/${id}/source`)
export const previewCatalogAttributes = (input: AttributePreviewInput) => post<AttributeRequestView>('/catalog-attributes/previews', input)
export const confirmCatalogAttributes = (id: number, operationKey: string, previewDigest: string) => post<AttributeReceipt>(`/catalog-attributes/${id}/confirm`, { operationKey, previewDigest })
export const getCatalogAttributeRequestByKey = (key: string) => get<AttributeRequestView>('/catalog-attributes/by-key', { key })
