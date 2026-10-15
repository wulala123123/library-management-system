import type { Book, PageResult } from '@/types'
import { get, post, put, del } from './request'

export interface BookPageParams {
  page: number
  size: number
  keyword?: string
  categoryId?: number
  status?: number | null
}

export interface BookFormData {
  id?: number
  isbn: string
  title: string
  author?: string
  publisher?: string
  publishDate?: string
  categoryId: number
  price?: number
  pages?: number
  coverImage?: string
  description?: string
  location?: string
  stockTotal: number
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

export function createBook(data: BookFormData) {
  return post<Book>('/books', data)
}

export function updateBook(data: BookFormData) {
  return put<Book>('/books', data)
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
