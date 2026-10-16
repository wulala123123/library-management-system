import type { BookLocation, Bookshelf, BookshelfStorey, PageResult } from '@/types'
import request, { get, post, put } from './request'
export interface MaintenanceIdentity { expectedVersion: number; reason: string }
export interface BookshelfFields { name: string; code: string; deptId: number | null; location: string | null; capacity: number; status: number; reason: string }
export interface StoreyFields { bookshelfId: number; name: string; levelNum: number; capacity: number; status: number; reason: string }
export interface LocationFields { storeyId: number; code: string; status: string; reason: string }

export function getBookshelves(params: { page?: number; size?: number; keyword?: string; deptId?: number }) {
  return get<PageResult<Bookshelf>>('/bookshelves', params)
}

export function getBookshelfById(id: number) {
  return get<Bookshelf>(`/bookshelves/${id}`)
}

export function createBookshelf(data: BookshelfFields) {
  return post<Bookshelf>('/bookshelves', data)
}

export function updateBookshelf(data: BookshelfFields & MaintenanceIdentity & { id: number }) {
  return put<Bookshelf>('/bookshelves', data)
}

export function deleteBookshelf(id: number, data: MaintenanceIdentity) {
  return request.delete<unknown, null>(`/bookshelves/${id}`, { data })
}

export function getStoreys(bookshelfId: number) {
  return get<BookshelfStorey[]>(`/bookshelves/${bookshelfId}/storeys`)
}

export function createStorey(data: StoreyFields) {
  return post<BookshelfStorey>('/bookshelves/storeys', data)
}

export function updateStorey(data: StoreyFields & MaintenanceIdentity & { id: number }) {
  return put<BookshelfStorey>('/bookshelves/storeys', data)
}

export function deleteStorey(id: number, data: MaintenanceIdentity) {
  return request.delete<unknown, null>(`/bookshelves/storeys/${id}`, { data })
}

export function getLocations(params: { page?: number; size?: number; storeyId?: number; status?: string }) {
  return get<PageResult<BookLocation>>('/bookshelves/locations', params)
}

export function createLocation(data: LocationFields) {
  return post<BookLocation>('/bookshelves/locations', data)
}

export function updateLocation(data: LocationFields & MaintenanceIdentity & { id: number; bookId?: number | null }) {
  return put<BookLocation>('/bookshelves/locations', data)
}

export function deleteLocation(id: number, data: MaintenanceIdentity) {
  return request.delete<unknown, null>(`/bookshelves/locations/${id}`, { data })
}

export function assignBookToLocation(locationId: number, bookId: number, data: MaintenanceIdentity) {
  return put<null>(`/bookshelves/locations/${locationId}/assign/${bookId}`, data)
}

export function clearLocation(locationId: number, data: MaintenanceIdentity) {
  return put<null>(`/bookshelves/locations/${locationId}/clear`, data)
}
