import type { PublicationBook, PublicationPreviewInput, PublicationReceipt, PublicationRequestView } from '@/api/catalogPublication'
export function publicationBook(bookId = 7): PublicationBook { return { bookId, isbn: `000${bookId}`, catalogTitle: `登记名${bookId}`, displayTitle: `展示名${bookId}`, publishDate: null, pages: 0, description: null, revision: 2, metadataDigest: 'a'.repeat(64) } }
export function publicationInput(ids = [7]): PublicationPreviewInput {
  return { operationKey: crypto.randomUUID(), mode: 'MANUAL', reason: '合成资料补录', entries: ids.map(bookId => ({ bookId, expectedRevision: 2, expectedMetadataDigest: 'a'.repeat(64), changes: { publishDate: null, pages: null, description: { action: 'SET', value: '' } }, evidence: { kind: 'COPYRIGHT_PAGE', reference: `合成版权页 #${bookId}` } })) }
}
export function publicationRequest(input: PublicationPreviewInput): PublicationRequestView {
  const expiresAt = new Date(Date.now() + 900000).toISOString()
  return { requestId: 41, operationKey: input.operationKey, mode: 'MANUAL', state: 'PREVIEWED', previewDigest: 'b'.repeat(64), expiresAt, receipt: null, preview: { schemaVersion: 1, mode: 'MANUAL', reason: input.reason, createdAt: new Date().toISOString(), expiresAt, books: input.entries.map(entry => {
    const before = publicationBook(entry.bookId), after = { ...before }
    if (entry.changes.publishDate) after.publishDate = entry.changes.publishDate.action === 'CLEAR' ? null : entry.changes.publishDate.value
    if (entry.changes.pages) after.pages = entry.changes.pages.action === 'CLEAR' ? null : entry.changes.pages.value
    if (entry.changes.description) after.description = entry.changes.description.action === 'CLEAR' ? null : entry.changes.description.value
    const changedFields = (['publishDate', 'pages', 'description'] as const).filter(field => before[field] !== after[field]), willChange = changedFields.length > 0
    if (willChange) { after.revision++; after.metadataDigest = 'c'.repeat(64) }
    return { bookId: entry.bookId, before, after, changes: entry.changes, evidence: entry.evidence, changedFields, willChange }
  }) } }
}
export function publicationReceipt(input: PublicationPreviewInput): PublicationReceipt {
  const request = publicationRequest(input)
  return { schemaVersion: 1, requestId: request.requestId, operationKey: input.operationKey, mode: 'MANUAL', actorId: 2, confirmedAt: new Date().toISOString(), books: request.preview.books.map(book => ({ bookId: book.bookId, changed: book.willChange, beforeRevision: book.before.revision, afterRevision: book.after.revision, eventId: book.willChange ? 100 + book.bookId : null, publication: book.after, applied: book.changes, evidence: book.evidence })) }
}
