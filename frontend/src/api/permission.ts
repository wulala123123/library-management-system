import type { PageResult, PermissionMatrixRow, PermissionLog } from '@/types'
import { get } from './request'

export function getPermissionMatrix() {
  return get<PermissionMatrixRow[]>('/permissions/matrix')
}

export function getPermissionLogs(params: { page: number; size: number; keyword?: string }) {
  return get<PageResult<PermissionLog>>('/permissions/logs', params)
}
