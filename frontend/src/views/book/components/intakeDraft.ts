import type { BookMetadata } from '@/api/book'
import type { IntakeCopyInput, IntakeLineInput } from '@/api/inventoryIntake'

export const MAX_INTAKE_COPIES = 1000
export interface CopyDraft { input: 'SCANNED' | 'GENERATE'; barcode: string; location: string }
export interface IntakeDraftLine { lineKey: string; label: string; pending: number | null; target: IntakeLineInput['target']; copies: CopyDraft[] }
export const newCopy = (): CopyDraft => ({ input: 'SCANNED', barcode: '', location: '' })
export const barcodeBytes = (value: string) => new TextEncoder().encode(value).length
export const visibleBarcode = (value: string) => JSON.stringify(value)
export function copiesInput(copies: CopyDraft[]): IntakeCopyInput[] {
  return copies.map(copy => ({ input: copy.input, barcode: copy.input === 'SCANNED' ? copy.barcode : null, location: copy.location === '' ? null : copy.location }))
}
export function validateDraft(lines: IntakeDraftLine[], reason: string): string | null {
  const selected = lines.filter(line => line.copies.length)
  const count = selected.reduce((total, line) => total + line.copies.length, 0)
  if (!count || count > MAX_INTAKE_COPIES) return '本次请选择 1 至 1000 册，大单请分批登记。'
  if (!reason.trim() || reason.length > 500) return '请填写本次来源依据（1 至 500 字）。'
  const barcodes = new Set<string>()
  for (const line of selected) {
    if (line.pending === null || line.copies.length > line.pending) return '本次册数超过待收数量，或原数量需要核实。'
    for (const copy of line.copies) {
      if (copy.location.length > 200) return '册位置不能超过 200 字。'
      if (copy.input === 'GENERATE') continue
      const value = copy.barcode
      if (!value.trim() || [...value].length > 64 || barcodeBytes(value) > 256 || /[\p{Cc}\p{Cs}]/u.test(value)) return '请填写有效的已有条码：最多 64 字，保留原文，不含控制字符。'
      if (value.startsWith('LMS-C-')) return 'LMS-C- 为新生成码前缀，请选择“生成新码”。'
      if (barcodes.has(value)) return '本次存在完全相同的已有条码，请核对。'
      barcodes.add(value)
    }
  }
  return null
}
export const metadataKeys = ['isbn', 'title', 'author', 'publisher', 'publishDate', 'categoryId', 'price', 'pages', 'coverImage', 'description', 'location', 'status'] as const
export function metadataChanges(before: Partial<BookMetadata>, after: Partial<BookMetadata>): Partial<BookMetadata> {
  return Object.fromEntries(metadataKeys.filter(key => !Object.is(before[key], after[key])).map(key => [key, after[key] === undefined ? null : after[key]]))
}
