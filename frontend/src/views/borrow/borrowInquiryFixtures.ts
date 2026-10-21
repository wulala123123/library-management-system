import type { BorrowInquiryExport, BorrowInquiryFilters, BorrowInquiryPage, BorrowInquiryRecord } from '@/api/borrowInquiry'

export const inquiryAsOf = '2026-10-21T14:00:00.123Z'
export const inquiryFilters = (): BorrowInquiryFilters => ({ keyword: '', status: null, origin: 'ALL' })
export const inquiryRecord = (id = 501): BorrowInquiryRecord => ({ id, userId: 101, bookId: 201, copyId: 301, counterOrderId: null, userName: '合成会员', userRealName: '合成读者', bookTitle: '合成绘本', bookIsbn: '000ISBN', bookCoverImage: null, copyBarcode: ' 000Aa ', borrowDate: '2026-10-01', dueDate: null, dueDateKind: 'UNKNOWN', returnDate: null, status: 'BORROWING', remark: null, renewCount: 0, fineAmount: 0, origin: 'SYSTEM' })
export function inquiryPage(records: BorrowInquiryRecord[] = [inquiryRecord()], filters = inquiryFilters(), page = 1, total = records.length, size = 10): BorrowInquiryPage {
  const summary = { total, imported: 0, counterOrder: 0, system: 0, unverified: 0, unit: '条' as const, scope: '当前筛选的借阅明细，不与原系统累计相加，不代表订单次数' }
  for (const row of records) summary[({ IMPORTED: 'imported', COUNTER_ORDER: 'counterOrder', SYSTEM: 'system', UNVERIFIED: 'unverified' } as const)[row.origin]]++
  if (total > records.length) summary[filters.origin === 'ALL' ? 'system' : ({ IMPORTED: 'imported', COUNTER_ORDER: 'counterOrder', SYSTEM: 'system', UNVERIFIED: 'unverified' } as const)[filters.origin]] += total - records.length
  return { records, total, page, size, pages: Math.ceil(total / size), asOf: inquiryAsOf, filters, summary }
}
export function inquiryFile(total = 21): BorrowInquiryExport { return { blob: new Blob(['\ufeff导出时点,2026-10-21T14:00:00.123Z\r\n总量,21\r\n\r\n借阅明细编号,条码\r\n501,000Aa\r\n'], { type: 'text/csv;charset=utf-8' }), filename: 'borrow-inquiry-20261021T140000Z.csv', total, asOf: inquiryAsOf } }
export function deferred<T>() { let resolve!: (value: T) => void, reject!: (cause: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
