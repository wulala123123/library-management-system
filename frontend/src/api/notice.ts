import type { Notice, PageResult } from '@/types'
import { get, post, put, del } from './request'

export function getNotices(params: { page?: number; size?: number; type?: string; status?: string }) {
  return get<PageResult<Notice>>('/notices', params)
}

export function createNotice(data: Record<string, unknown>) {
  return post<Notice>('/notices', data)
}

export function updateNotice(data: Record<string, unknown>) {
  return put<Notice>('/notices', data)
}

export function deleteNotice(id: number) {
  return del<void>(`/notices/${id}`)
}

export function publishNotice(id: number) {
  return put<void>(`/notices/${id}/publish`, {})
}
