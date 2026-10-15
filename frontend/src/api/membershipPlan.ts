import type { PageResult } from '@/types'
import { get, post, put } from './request'

export type LimitMode = 'UNKNOWN' | 'LIMITED' | 'UNLIMITED'
export interface PlanLimit { mode: LimitMode; value: number | null }
export interface PlanScope { mode: 'UNKNOWN' | 'ANY' | 'ALLOW_LIST'; values: string[] }
export interface PlanRules {
  serviceDurationDays: number | null
  dayCountConvention: 'START_INCLUDED'
  perOrderLimit: PlanLimit
  activeLoanLimit: PlanLimit
  usageType: LimitMode
  totalUses: number | null
  dueDateType: 'UNKNOWN' | 'DAYS' | 'UNLIMITED'
  borrowDays: number | null
  renewalPermission: 'UNKNOWN' | 'ALLOWED' | 'FORBIDDEN'
  renewalLimit: PlanLimit | null
  renewalDays: number | null
  languages: PlanScope
  pickupMethods: PlanScope
}
export interface PlanDefinition { name: string; notes: string | null; sourceReferences: string[]; rules: PlanRules }
export interface PlanSummary {
  id: number; name: string; revision: number; enabled: boolean; selectedVersionId: number | null
  versionCount: number; updatedAt: string | null; updatedBy: number | null
}
export interface PlanVersion extends PlanDefinition {
  id: number; planId: number; versionNumber: number; confirmationBasis: string; createdAt: string; createdBy: number
}
export interface PlanDetail extends PlanSummary { draft: PlanDefinition; versions: PlanVersion[] }
export interface PublishPlanRequest { revision: number; idempotencyKey: string; confirmationBasis: string; changeReason: string }
export interface PublishedPlan { version: PlanVersion; revision: number; replayed: boolean }

export const getMembershipPlans = (params: { page: number; size: number; keyword?: string }) => get<PageResult<PlanSummary>>('/membership-plans', params)
export const getMembershipPlan = (id: number) => get<PlanDetail>(`/membership-plans/${id}`)
export const createMembershipPlan = (data: PlanDefinition & { changeReason: string }) => post<PlanDetail>('/membership-plans', data)
export const saveMembershipPlanDraft = (id: number, data: PlanDefinition & { revision: number; changeReason: string }) => put<PlanDetail>(`/membership-plans/${id}/draft`, data)
export const publishMembershipPlan = (id: number, data: PublishPlanRequest) => post<PublishedPlan>(`/membership-plans/${id}/versions`, data)
export const selectMembershipPlanVersion = (id: number, data: { revision: number; versionId: number; enabled: boolean; changeReason: string }) => put<PlanDetail>(`/membership-plans/${id}/selection`, data)
