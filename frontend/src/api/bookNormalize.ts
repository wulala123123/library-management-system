import type { Book, BookNormalizeLog, NormalizeCandidate, PageResult } from '@/types'
import { get, put } from './request'

export function getPendingBooks() {
  return get<NormalizeCandidate[]>('/book-normalize/pending')
}

export function normalizeBook(id: number, data: Record<string, string>) {
  return put<Book>(`/book-normalize/${id}`, data)
}

export function batchNormalize(data: Record<string, string>[]) {
  return put<number>('/book-normalize/batch', data)
}

export function getNormalizeReport(params: { page: number; size: number }) {
  return get<PageResult<BookNormalizeLog>>('/book-normalize/report', params)
}
