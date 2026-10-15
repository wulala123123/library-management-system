import { get, post } from './request'
import type { MemberSummary, MemberUsageType } from './member'
import type { PlanRules, PlanVersion } from './membershipPlan'
import type { PageResult } from '@/types'

export interface EntitlementActual {
  planName: string | null
  serviceStart: string | null
  serviceEnd: string | null
  usageType: MemberUsageType
  usedUses: number | null
  totalUses: number | null
}
export interface EntitlementSnapshot {
  id: number; memberId: number; memberRevision: number; planVersionId: number | null
  versionSnapshot: PlanVersion | null; factsAtConfirmation: EntitlementActual
  rulesAtConfirmation: PlanRules; sourceReferences: string[]; reason: string
  historyBasis: string | null; confirmedAt: string; confirmedBy: number
}
export interface EntitlementView { member: MemberSummary; effectiveRules: PlanRules; snapshot: EntitlementSnapshot | null }
export interface ReaderEntitlementView { member: MemberSummary; effectiveRules: PlanRules; snapshotPresent: boolean }
export interface EntitlementInput {
  revision: number; planVersionId: number | null; actual: EntitlementActual; rules: PlanRules
  sourceReferences: string[]; reason: string; historyBasis: string | null
}
export interface EntitlementPreview {
  previewHash: string; current: EntitlementView
  proposed: { actual: EntitlementActual; rules: PlanRules; planVersionId: number | null }
  version: PlanVersion | null
  differences: { field: string; currentValue: unknown; proposedValue: unknown; versionValue: unknown }[]
  historicalVersion: boolean
}
export interface EntitlementConfirmation extends EntitlementInput { previewHash: string; confirmed: true; idempotencyKey: string }
export const getMemberEntitlement = (id: number) => get<EntitlementView>(`/members/${id}/entitlement`)
export const getMyEntitlement = () => get<ReaderEntitlementView>('/members/me/entitlement')
export const previewMemberEntitlement = (id: number, body: EntitlementInput) => post<EntitlementPreview>(`/members/${id}/entitlement/preview`, body)
export const confirmMemberEntitlement = (id: number, body: EntitlementConfirmation) => post<{ snapshot: EntitlementSnapshot; revision: number; replayed: boolean }>(`/members/${id}/entitlement/confirm`, body)
export const getEntitlementHistory = (id: number, page = 1, size = 10) => get<PageResult<EntitlementSnapshot>>(`/members/${id}/entitlement/history`, { page, size })
