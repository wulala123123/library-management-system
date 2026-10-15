import type { PageResult } from '@/types'
import { get, post, put } from './request'

export type MemberServiceStatus = 'WAITING' | 'ACTIVE' | 'EXPIRED' | 'UNKNOWN'
export type MemberUsageType = 'UNKNOWN' | 'LIMITED' | 'UNLIMITED'
export type BirthDatePrecision = 'DAY' | 'MONTH' | 'YEAR' | 'UNKNOWN'

export interface MemberSummary {
  id: number
  username: string
  realName: string
  phone: string | null
  email: string | null
  accountStatus: number
  profilePresent: boolean
  revision: number
  cardNo: string | null
  planName: string | null
  serviceStart: string | null
  serviceEnd: string | null
  serviceStatus: MemberServiceStatus
  asOfDate: string
  usageType: MemberUsageType
  usedUses: number | null
  totalUses: number | null
  remainingUses: number | null
  overused: boolean
}

export interface MemberChild {
  id?: number
  name: string | null
  gender: string | null
  birthDate: string | null
  birthDatePrecision: BirthDatePrecision
  sourceBirthText: string | null
  sourceKey?: string | null
  notes: string | null
}

export interface MemberDetail extends MemberSummary {
  notes?: string | null
  address?: string | null
  children?: MemberChild[]
  financialFacts?: { paidFee: number | string | null; depositAmount: number | string | null; depositStatus: string | null }
  sourceKey?: string | null
  sourceSystem?: string | null
  sourceArchiveId?: number | null
  updatedAt?: string | null
  updatedBy?: number | null
  borrowHistory?: { id: number; bookId: number; bookTitle: string | null; borrowDate: string | null; dueDate: string | null; dueDateKind: 'DATED' | 'UNLIMITED' | 'UNKNOWN'; returnDate: string | null; status: string }[]
}

export interface MemberPageParams {
  page: number
  size: number
  keyword?: string
  accountStatus?: number
  serviceStatus?: MemberServiceStatus
}

export interface MemberFormData {
  realName: string
  phone: string | null
  email: string | null
  cardNo: string | null
  planName: string | null
  serviceStart: string | null
  serviceEnd: string | null
  usageType: MemberUsageType
  usedUses: number | null
  totalUses: number | null
  notes: string | null
  address: string | null
  children: MemberChild[]
  changeReason: string
}

export interface MemberSourceField {
  field: string
  sourceField: string
  currentValue: unknown
  sourceValue: unknown
  conflict: boolean
  reviewOnly?: boolean
}
export interface MemberSourcePreview {
  sourceAvailable: boolean
  sourceKey: string | null
  archiveId: number | null
  fields: MemberSourceField[]
  unmappedFields: string[]
}

export const getMemberPage = (params: MemberPageParams) => get<PageResult<MemberSummary>>('/members', params)
export const getMemberById = (id: number) => get<MemberDetail>(`/members/${id}`)
export const getMyMembership = () => get<MemberSummary>('/members/me')
export const createMember = (data: MemberFormData & { username: string; password: string }) => post<MemberDetail>('/members', data)
export const updateMember = (id: number, data: MemberFormData & { revision: number }) => put<MemberDetail>(`/members/${id}`, data)
export const getMemberSourcePreview = (id: number) => get<MemberSourcePreview>(`/members/${id}/source-preview`)
