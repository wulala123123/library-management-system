import type { BorrowCode, BorrowRecord, PageResult } from '@/types'
import { get, post, put } from './request'

export function generateBorrowCode(bookId: number) {
  return post<BorrowCode>('/borrow-codes/generate', {}, { params: { bookId } })
}

export function verifyBorrowCode(code: string) {
  return post<{ borrowCode: BorrowCode; borrowRecord: BorrowRecord }>('/borrow-codes/verify', {}, { params: { code } })
}

export function getMyBorrowCodes(params: { page: number; size: number }) {
  return get<PageResult<BorrowCode>>('/borrow-codes/my', params)
}

export function getBorrowCodePage(params: { page: number; size: number; status?: string }) {
  return get<PageResult<BorrowCode>>('/borrow-codes', params)
}

export function invalidateBorrowCode(id: number) {
  return put<void>(`/borrow-codes/${id}/invalidate`, {})
}
