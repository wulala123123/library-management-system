import type { BorrowRecord } from '@/types'

export function dueDateText(record: Pick<BorrowRecord, 'dueDate' | 'dueDateKind'>): string {
  if (record.dueDate) return record.dueDate.split('T')[0]
  return record.dueDateKind === 'UNLIMITED' ? '不限制' : '原记录未提供'
}
