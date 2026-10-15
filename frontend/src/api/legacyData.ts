import { get } from './request'
import type { PageResult } from '@/types'

export interface MigratedMember {
  user_id: number; username: string; real_name: string; phone?: string; card_no?: string
  plan_name?: string; service_start?: string; service_end?: string
  used_uses?: number; total_uses: number | null; deposit_amount?: number | string
  deposit_status?: string; paid_fee?: number | string
}
export interface LegacyRecord {
  id: number; source_key: string; display_name: string; summary?: string
  source_time?: string; details_json?: string
}
export interface MigrationSummary {
  batches: { batch_key: string; summary_json: string; imported_time: string }[]
  types: { record_type: string; total: number }[]
}
export const getMigrationSummary = () => get<MigrationSummary>('/legacy-data/summary')
export const getMigratedMembers = () => get<MigratedMember[]>('/legacy-data/members')
export const getLegacyRecords = (params: { type: string; keyword?: string; page: number; size: number; userId?: number }) =>
  get<PageResult<LegacyRecord>>('/legacy-data/records', params)
export const getLegacyRecord = (id: number) => get<LegacyRecord>(`/legacy-data/records/${id}`)
