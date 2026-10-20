import { assertCirculationInput, assertOrderDetail, assertOrderPage, assertReceiptShape, assertRequestShape, validKey } from '@/views/borrow/circulationProtocol'

export type CirculationOperation = 'BORROW' | 'RETURN'
export type LoanStatus = 'BORROWING' | 'OVERDUE' | 'RETURNED'
export type DueDateKind = 'DATED' | 'UNLIMITED' | 'UNKNOWN'
export type CirculationInput = { operation: 'BORROW'; userId: number; items: { bookId: number; copyId: number; borrowDays: number }[]; remark: string } | { operation: 'RETURN'; recordIds: number[]; remark: string }
export interface CirculationItem {
  ordinal: number; recordId: number | null; userId: number; userName: string | null; bookId: number; bookTitle: string
  copyId: number | null; barcode: string | null; beforeRecordStatus: LoanStatus | null; afterRecordStatus: LoanStatus
  beforeCopyStatus: string | null; afterCopyStatus: string | null; copyVersionBefore: number | null; copyVersionAfter: number | null
  borrowDate: string | null; dueDate: string | null; dueDateKind: DueDateKind; returnDate: string | null; renewCount: number
}
export interface CirculationBook { bookId: number; stockTotal: number; stockAvailable: number; deltaAvailable: number; afterAvailable: number }
export interface CirculationPreview {
  schemaVersion: 1; operation: CirculationOperation; businessDate: string
  borrower: { userId: number; userName: string | null; cardNo: string | null; status: number } | null
  items: CirculationItem[]; books: CirculationBook[]; activeCountBefore: number | null; activeCountAfter: number | null; warnings: string[]
}
export interface CirculationReceipt {
  schemaVersion: 1; operationKey: string; operation: CirculationOperation; actorId: number; confirmedAt: string; businessDate: string
  orderId: number | null; previewDigest: string; items: (CirculationItem & { stockFlowId: number })[]; books: CirculationBook[]
}
export interface CirculationRequest {
  schemaVersion: 1; operationKey: string; state: 'PREVIEWED' | 'COMMITTED' | 'CANCELLED'; operation: CirculationOperation | null; actorId: number
  createdAt: string; expiresAt: string | null; businessDate: string | null; input: CirculationInput | null; previewDigest: string | null
  preview: CirculationPreview | null; receipt: CirculationReceipt | null; cancelledAt: string | null
}
export type OrderState = 'BORROWING' | 'PARTIALLY_RETURNED' | 'RETURNED'
export interface BorrowOrder {
  orderId: number; userId: number; userName: string | null; cardNo: string | null; actorId: number; createdAt: string; businessDate: string
  itemCount: number; activeCount: number; returnedCount: number; state: OrderState; remark: string
}
export interface BorrowOrderItem { ordinal: number; recordId: number; bookId: number; bookTitle: string; copyId: number; barcode: string; status: LoanStatus; borrowDate: string | null; dueDate: string | null; dueDateKind: DueDateKind; returnDate: string | null; renewCount: number }
export interface BorrowOrderDetail extends BorrowOrder { schemaVersion: 1; items: BorrowOrderItem[] }
export interface BorrowOrderPage { schemaVersion: 1; records: BorrowOrder[]; total: number; page: number; size: number; pages: number }
export interface ReturnSelection { id: number; bookTitle: string; copyBarcode?: string | null; userName: string | null; dueDate: string | null; dueDateKind?: DueDateKind }
export class CirculationError extends Error {
  constructor(public status: number | null, public code: string, message = '办理结果尚需核对，请保留原操作编号。') { super(message); this.name = 'CirculationError' }
}
/** Capture the login before sending: delayed writes must never use a later account. */
export function createCirculationApi(token: string) {
  async function call<T>(path: string, check: (value: unknown) => void, body?: object): Promise<T> {
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 15000)
    try {
      let response: Response
      try { response = await fetch(`/api/circulation${path}`, { method: body === undefined ? 'GET' : 'POST', credentials: 'same-origin', cache: 'no-store', signal: controller.signal, headers: { Authorization: `Bearer ${token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }) }) }
      catch { throw new CirculationError(null, 'UNKNOWN_RESULT') }
      // An old captured login may finish after an account switch. Only invalidate its own context.
      if ([401, 403].includes(response.status)) {
        let current = false
        try { current = localStorage.getItem('token') === token } catch { /* No current login can be established. */ }
        if (current) window.dispatchEvent(new Event('auth-access-invalidated'))
      }
      let envelope: { code: number; message: string; data: unknown }
      try {
        const raw = await response.arrayBuffer(); if (raw.byteLength > 1024 * 1024) throw Error()
        envelope = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(raw))
        if (!envelope || Object.keys(envelope).sort().join(',') !== 'code,data,message' || envelope.code !== response.status || typeof envelope.message !== 'string') throw Error()
      } catch { throw new CirculationError(response.status, 'INVALID_RESPONSE') }
      if (response.status !== 200) {
        const data = envelope.data as { schemaVersion?: number; reasonCode?: string }
        if (!data || Object.keys(data).sort().join(',') !== 'reasonCode,schemaVersion' || data.schemaVersion !== 1 || typeof data.reasonCode !== 'string') throw new CirculationError(response.status, 'INVALID_RESPONSE')
        throw new CirculationError(response.status, data.reasonCode, envelope.message)
      }
      try { check(envelope.data) } catch { throw new CirculationError(response.status, 'INVALID_RESPONSE') }
      return envelope.data as T
    } finally { clearTimeout(timer) }
  }
  const keyPath = (key: string) => { if (!validKey(key)) throw new CirculationError(null, 'INVALID_INPUT'); return `/requests/${key}` }
  return {
    preview(key: string, input: CirculationInput) { assertCirculationInput(input); return call<CirculationRequest>(`${keyPath(key)}/preview`, assertRequestShape, input) },
    request: (key: string) => call<CirculationRequest>(keyPath(key), assertRequestShape),
    confirm(key: string, previewDigest: string) { if (previewDigest.length !== 64 || !/^[a-f0-9]{64}$/.test(previewDigest)) throw new CirculationError(null, 'INVALID_INPUT'); return call<CirculationReceipt>(`${keyPath(key)}/confirm`, assertReceiptShape, { previewDigest }) },
    cancel: (key: string) => call<CirculationRequest>(`${keyPath(key)}/cancel`, assertRequestShape, {}),
    orders(params: { page: number; size: number; userId?: number; state?: OrderState }) {
      const query = new URLSearchParams(); Object.entries(params).forEach(([key, value]) => { if (value !== undefined) query.set(key, String(value)) })
      if (Object.keys(params).some(key => !['page','size','userId','state'].includes(key)) || !Number.isInteger(params.page) || params.page < 1 || params.page > 2147483647 || !Number.isInteger(params.size) || params.size < 1 || params.size > 100 || params.userId !== undefined && (!Number.isSafeInteger(params.userId) || params.userId < 1) || params.state !== undefined && !['BORROWING','PARTIALLY_RETURNED','RETURNED'].includes(params.state)) throw new CirculationError(null, 'INVALID_INPUT')
      return call<BorrowOrderPage>(`/orders?${query}`, assertOrderPage)
    },
    order(id: number) { if (!Number.isSafeInteger(id) || id < 1) throw new CirculationError(null, 'INVALID_INPUT'); return call<BorrowOrderDetail>(`/orders/${id}`, assertOrderDetail) },
  }
}
export type CirculationApi = ReturnType<typeof createCirculationApi>
