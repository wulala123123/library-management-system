import type { Book, PageResult } from '@/types'
import { get, post, put, del } from './request'

export interface BookPageParams {
  page: number
  size: number
  keyword?: string
  categoryId?: number
  status?: number | null
}

export interface BookMetadata {
  isbn: string
  title: string
  author?: string | null
  publisher?: string | null
  publishDate?: string | null
  categoryId: number
  price?: number | null
  pages?: number | null
  coverImage?: string | null
  description?: string | null
  location?: string | null
  status?: number | null
}

export function getBookPage(params: BookPageParams) {
  return get<PageResult<Book>>('/books', params)
}

export function getBookById(id: number) {
  return get<Book>(`/books/${id}`)
}

export interface BookCopy { id: number; bookId: number; barcode: string; status: string; location?: string }
export function getBookCopies(id: number) { return get<BookCopy[]>(`/books/${id}/copies`) }

export interface BookMetadataView { book: Book; metadataDigest: string }
export function getBookMetadata(id: number) { return get<BookMetadataView>(`/books/${id}/metadata`) }
export function updateBookMetadata(id: number, expectedMetadataDigest: string, changes: Partial<BookMetadata>) {
  return put<BookMetadataView>(`/books/${id}/metadata`, { expectedMetadataDigest, changes })
}

export function deleteBook(id: number) {
  return del<void>(`/books/${id}`)
}

export function updateBookStatus(id: number, status: number) {
  return put<void>(`/books/${id}/status`, {}, { params: { status } })
}

export function uploadBookCover(bookId: number, file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return post<string>(`/upload/book-cover/${bookId}`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 30000,
  })
}
