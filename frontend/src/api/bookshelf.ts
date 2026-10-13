import type { BookLocation, Bookshelf, BookshelfStorey, PageResult } from '@/types'
import { get, post, put, del } from './request'

export function getBookshelves(params: { page?: number; size?: number; keyword?: string; deptId?: number }) {
  return get<PageResult<Bookshelf>>('/bookshelves', params)
}

export function getBookshelfById(id: number) {
  return get<Bookshelf>(`/bookshelves/${id}`)
}

export function createBookshelf(data: Record<string, unknown>) {
  return post<Bookshelf>('/bookshelves', data)
}

export function updateBookshelf(data: Record<string, unknown>) {
  return put<Bookshelf>('/bookshelves', data)
}

export function deleteBookshelf(id: number) {
  return del<void>(`/bookshelves/${id}`)
}

export function getStoreys(bookshelfId: number) {
  return get<BookshelfStorey[]>(`/bookshelves/${bookshelfId}/storeys`)
}

export function createStorey(data: Record<string, unknown>) {
  return post<BookshelfStorey>('/bookshelves/storeys', data)
}

export function updateStorey(data: Record<string, unknown>) {
  return put<BookshelfStorey>('/bookshelves/storeys', data)
}

export function deleteStorey(id: number) {
  return del<void>(`/bookshelves/storeys/${id}`)
}

export function getLocations(params: { page?: number; size?: number; storeyId?: number; status?: string }) {
  return get<PageResult<BookLocation>>('/bookshelves/locations', params)
}

export function createLocation(data: Record<string, unknown>) {
  return post<BookLocation>('/bookshelves/locations', data)
}

export function updateLocation(data: Record<string, unknown>) {
  return put<BookLocation>('/bookshelves/locations', data)
}

export function deleteLocation(id: number) {
  return del<void>(`/bookshelves/locations/${id}`)
}

export function assignBookToLocation(locationId: number, bookId: number) {
  return put<BookLocation>(`/bookshelves/locations/${locationId}/assign/${bookId}`)
}

export function clearLocation(locationId: number) {
  return put<void>(`/bookshelves/locations/${locationId}/clear`)
}
