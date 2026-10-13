import type { PageResult, Publisher } from '@/types'
import { get, post, put, del } from './request'

export function getPublishers(params: { page?: number; size?: number; keyword?: string }) {
  return get<PageResult<Publisher>>('/publishers', params)
}

export function getPublisherById(id: number) {
  return get<Publisher>(`/publishers/${id}`)
}

export function createPublisher(data: Record<string, unknown>) {
  return post<Publisher>('/publishers', data)
}

export function updatePublisher(data: Record<string, unknown>) {
  return put<Publisher>('/publishers', data)
}

export function deletePublisher(id: number) {
  return del<void>(`/publishers/${id}`)
}
