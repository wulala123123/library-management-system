import type { ApprovalRecord, PageResult, PurchaseOrder } from '@/types'
import { get, post, put, del } from './request'

export function getPurchaseOrders(params: { page?: number; size?: number; status?: string; deptId?: number }) {
  return get<PageResult<PurchaseOrder>>('/purchases', params)
}

export function getPurchaseOrderById(id: number) {
  return get<PurchaseOrder>(`/purchases/${id}`)
}

export function createPurchaseOrder(data: Record<string, unknown>) {
  return post<PurchaseOrder>('/purchases', data)
}

export function updatePurchaseOrder(data: Record<string, unknown>) {
  return put<PurchaseOrder>('/purchases', data)
}

export function deletePurchaseOrder(id: number) {
  return del<void>(`/purchases/${id}`)
}

export function submitPurchaseOrder(id: number) {
  return put<PurchaseOrder>(`/purchases/${id}/submit`, {})
}

export function approvePurchaseOrder(id: number, data: { action: string; comment?: string }) {
  return put<PurchaseOrder>(`/purchases/${id}/approve`, data)
}

export function getApprovalRecords(orderId: number) {
  return get<ApprovalRecord[]>(`/purchases/${orderId}/approvals`)
}
