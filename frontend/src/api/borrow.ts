import type { BorrowRecord, PageResult } from '@/types'
import { get, post, put } from './request'

export interface BorrowPageParams {
  page: number
  size: number
  keyword?: string
  status?: string
}

export interface BorrowFormData {
  userId?: number
  bookId: number
  copyId?: number
  borrowDays?: number
  remark?: string
}

export function getBorrowPage(params: BorrowPageParams) {
  return get<PageResult<BorrowRecord>>('/borrows', params)
}

export function borrowBook(data: BorrowFormData) {
  return post<BorrowRecord>('/borrows/self', { bookId: data.bookId, borrowDays: data.borrowDays, remark: data.remark })
}

export function adminBorrowBook(data: BorrowFormData) {
  return post<BorrowRecord>('/borrows', data)
}

export function returnBook(id: number) {
  return put<BorrowRecord>(`/borrows/${id}/return`, {})
}

export function selfReturnBook(id: number) {
  return put<BorrowRecord>(`/borrows/${id}/self-return`, {})
}

export function renewBook(id: number, renewDays = 30) {
  return put<BorrowRecord>(`/borrows/${id}/renew`, { renewDays })
}

export function getMyBorrows(params: { page: number; size: number; status?: string }) {
  return get<PageResult<BorrowRecord>>('/borrows/my', params)
}
