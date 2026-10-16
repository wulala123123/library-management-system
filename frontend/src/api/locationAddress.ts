import { get } from './request'
import type { PageResult } from '@/types'

export interface AddressSnapshot {
  locationId: number; locationVersion: number | null; code: string | null
  storeyId: number | null; storeyVersion: number | null; storeyName: string | null; levelNum: number | null
  bookshelfId: number | null; bookshelfVersion: number | null; bookshelfCode: string | null; bookshelfName: string | null; bookshelfLocation: string | null
  selectable: boolean; unavailableReason: string | null
}
export interface Placement { kind: 'TEXT' | 'UNASSIGNED' | 'REGISTERED'; text: string | null; registeredLocation: AddressSnapshot | null }
export interface PlacementSnapshot { placement: Placement; rawText: string | null }
export interface LegacyBinding { bookId: number | null; rawStatus: string | null; needsReview: boolean }
export interface AddressRow { address: AddressSnapshot; legacyBinding: LegacyBinding; linkedCopyCount: number }
export interface AddressQuery { page: number; size: number; keyword?: string; bookshelfId?: number; storeyId?: number; selectableOnly?: boolean }
export type StructureEntityType = 'BOOKSHELF' | 'STOREY' | 'LOCATION'
export interface StructureSnapshot {
  id: number; layoutVersion: number; deleted: number; code?: string; name?: string; deptId?: number | null; location?: string | null
  capacity?: number; status: string | number; bookshelfId?: number; storeyId?: number; levelNum?: number; bookId?: number | null
  createTime: string | null; updateTime: string | null
}
export interface StructureEvent {
  id: number; entityType: StructureEntityType; entityId: number; operation: 'CREATE' | 'UPDATE' | 'DELETE' | 'ASSIGN' | 'CLEAR'
  operatorId: number; occurredAt: string; reason: string; beforeVersion: number | null; afterVersion: number; snapshotVersion: 1
  before: StructureSnapshot | null; after: StructureSnapshot
}
export const getLocationAddresses = (params: AddressQuery) => get<PageResult<AddressRow>>('/location-addresses', params)
export const getLocationAddress = (id: number) => get<AddressRow>(`/location-addresses/${id}`)
export const getStructureEvents = (entityType: StructureEntityType, entityId: number, page = 1, size = 20) => get<PageResult<StructureEvent>>('/location-structure-events', { entityType, entityId, page, size })
export const getStructureEvent = (id: number) => get<StructureEvent>(`/location-structure-events/${id}`)
