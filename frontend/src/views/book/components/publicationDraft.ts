import { emptyPublicationChanges, type PublicationBook, type PublicationEntry, type PublicationEvidenceKind } from '@/api/catalogPublication'

export const publicationFields = ['publishDate', 'pages', 'description'] as const
export const publicationLabels = { publishDate: '出版日期', pages: '页数', description: '简介' }
export const evidenceLabels: Record<PublicationEvidenceKind, string> = { COPYRIGHT_PAGE: '版权页', PUBLISHER_MATERIAL: '出版社资料', LEGACY_RECORD: '历史登记', MANUAL_REVIEW: '人工核对' }
export type PublicationAction = 'KEEP' | 'SET' | 'CLEAR'
export interface PublicationDraft {
  book: PublicationBook
  actions: Record<typeof publicationFields[number], PublicationAction>
  publishDate: string; pages: string; description: string
  evidenceKind: PublicationEvidenceKind; reference: string
}
export function publicationDraft(book: PublicationBook): PublicationDraft {
  return { book, actions: { publishDate: 'KEEP', pages: 'KEEP', description: 'KEEP' }, publishDate: book.publishDate ?? '', pages: book.pages == null ? '' : String(book.pages), description: book.description ?? '', evidenceKind: 'MANUAL_REVIEW', reference: '' }
}
export function publicationValue(value: string | number | null) {
  return value === null ? 'NULL（未知 / 未设置）' : value === '' ? '""（明确空串）' : typeof value === 'string' ? JSON.stringify(value) : String(value)
}
export function validPublicationText(value: string, max: number, nonempty = false, lines = false) {
  // Same blank characters as Character.isWhitespace || Character.isSpaceChar.
  const blank = /^[\u0009-\u000d\u001c-\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/u.test(value)
  if ([...value].length > max || (nonempty && blank)) return false
  const forbidden = lines ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f\ud800-\udfff]/u : /[\u0000-\u001f\u007f-\u009f\ud800-\udfff]/u
  return !forbidden.test(value)
}
export function validPublicationDate(value: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value) || value.startsWith('0000')) return false
  const date = new Date(`${value}T00:00:00Z`)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value
}
export function publicationEntry(row: PublicationDraft): PublicationEntry {
  const changes = emptyPublicationChanges()
  for (const field of publicationFields) {
    if (row.actions[field] === 'CLEAR') changes[field] = { action: 'CLEAR' }
    if (row.actions[field] === 'SET') {
      if (field === 'publishDate') { if (!validPublicationDate(row.publishDate)) throw new Error(`书目 #${row.book.bookId} 须填写有效的完整日期 YYYY-MM-DD；仅年/月请写入引用说明。`); changes.publishDate = { action: 'SET', value: row.publishDate } }
      if (field === 'pages') { if (!/^\d{1,5}$/.test(row.pages)) throw new Error(`书目 #${row.book.bookId} 页数须为 0–99999 整数。`); changes.pages = { action: 'SET', value: Number(row.pages) } }
      if (field === 'description') { if (!validPublicationText(row.description, 2000, false, true)) throw new Error(`书目 #${row.book.bookId} 简介最多 2000 码点，不能含非法控制符。`); changes.description = { action: 'SET', value: row.description } }
    }
  }
  if (publicationFields.every(field => changes[field] === null)) throw new Error(`书目 #${row.book.bookId} 请明确选择至少一个字段操作。`)
  if (!validPublicationText(row.reference, 1000, true)) throw new Error(`书目 #${row.book.bookId} 须填写 1–1000 码点的单行依据引用说明，不能含换行、制表或其他控制符。`)
  return { bookId: row.book.bookId, expectedRevision: row.book.revision, expectedMetadataDigest: row.book.metadataDigest, changes, evidence: { kind: row.evidenceKind, reference: row.reference } }
}
