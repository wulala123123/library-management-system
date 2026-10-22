import type { BorrowOrderDetail, BorrowOrderPage, CirculationInput, CirculationPreview, CirculationReceipt, CirculationRequest } from '@/api/circulation'

type Check = (value: any) => boolean
const obj = (shape: Record<string, Check>): Check => value => !!value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === Object.keys(shape).length && Object.entries(shape).every(([key, check]) => Object.prototype.hasOwnProperty.call(value, key) && check(value[key]))
const literal = (...values: unknown[]): Check => value => values.includes(value)
const nullable = (check: Check): Check => value => value === null || check(value)
const array = (check: Check): Check => value => Array.isArray(value) && value.every(check)
export const safeCirculationId: Check = value => Number.isSafeInteger(value) && value > 0
const natural: Check = value => Number.isSafeInteger(value) && value >= 0
const integer: Check = value => Number.isSafeInteger(value)
const string: Check = value => typeof value === 'string' && !/[\ud800-\udbff](?![\udc00-\udfff])|(?<![\ud800-\udbff])[\udc00-\udfff]/u.test(value)
export const validKey: Check = value => typeof value === 'string' && value.length === 36 && /^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/.test(value)
const digest: Check = value => typeof value === 'string' && value.length === 64 && /^[a-f0-9]{64}$/.test(value)
const date: Check = value => typeof value === 'string' && value.length === 10 && /^\d{4}-\d{2}-\d{2}$/.test(value) && Number.isFinite(Date.parse(`${value}T00:00:00Z`)) && new Date(`${value}T00:00:00Z`).toISOString().slice(0,10) === value
const stamp: Check = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$(?![\s\S])/.test(value) && Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0,10) === value.slice(0,10)
const remark: Check = value => string(value) && [...value].length <= 500 && !/[\p{Cc}]/u.test(value)
const days: Check = value => Number.isInteger(value) && value >= 1 && value <= 90
const operation = literal('BORROW', 'RETURN'), loanStatus = literal('BORROWING', 'OVERDUE', 'RETURNED')
const kind = literal('DATED', 'UNLIMITED', 'UNKNOWN')
const borrowInput = obj({ operation: literal('BORROW'), userId: safeCirculationId, items: array(obj({ bookId: safeCirculationId, copyId: safeCirculationId, borrowDays: days })), remark })
const returnInput = obj({ operation: literal('RETURN'), recordIds: array(safeCirculationId), remark })
export function assertCirculationInput(value: unknown): asserts value is CirculationInput {
  const input = value as CirculationInput
  if (borrowInput(input) && input.operation === 'BORROW' && input.items.length >= 1 && input.items.length <= 10 && new Set(input.items.map(row => row.bookId)).size === input.items.length && new Set(input.items.map(row => row.copyId)).size === input.items.length) return
  if (returnInput(input) && input.operation === 'RETURN' && input.recordIds.length >= 1 && input.recordIds.length <= 100 && new Set(input.recordIds).size === input.recordIds.length) return
  throw new Error('请明确选择合法的借阅/归还目标及借阅天数，备注最多 500 字且不含控制字符。')
}
const inputCheck: Check = value => { try { assertCirculationInput(value); return true } catch { return false } }
const itemShape = { ordinal: safeCirculationId, recordId: nullable(safeCirculationId), userId: safeCirculationId, userName: nullable(string), bookId: safeCirculationId, bookTitle: string, copyId: nullable(safeCirculationId), barcode: nullable(string), beforeRecordStatus: nullable(loanStatus), afterRecordStatus: loanStatus, beforeCopyStatus: nullable(string), afterCopyStatus: nullable(string), copyVersionBefore: nullable(natural), copyVersionAfter: nullable(natural), borrowDate: nullable(date), dueDate: nullable(date), dueDateKind: kind, returnDate: nullable(date), renewCount: natural }
const book = obj({ bookId: safeCirculationId, stockTotal: natural, stockAvailable: natural, deltaAvailable: integer, afterAvailable: natural })
const preview = obj({ schemaVersion: literal(1), operation, businessDate: date, borrower: nullable(obj({ userId: safeCirculationId, userName: nullable(string), cardNo: nullable(string), status: integer })), items: array(obj(itemShape)), books: array(book), activeCountBefore: nullable(natural), activeCountAfter: nullable(natural), warnings: array(string) })
const receipt = obj({ schemaVersion: literal(1), operationKey: validKey, operation, actorId: safeCirculationId, confirmedAt: stamp, businessDate: date, orderId: nullable(safeCirculationId), previewDigest: digest, items: array(obj({ ...itemShape, stockFlowId: safeCirculationId })), books: array(book) })
export const circulationCanonical = (value: unknown): string => JSON.stringify(value, (_key, item) => item && typeof item === 'object' && !Array.isArray(item) ? Object.fromEntries(Object.entries(item).sort(([a],[b]) => a.localeCompare(b))) : item)
export const frozenCirculationCopy = <T>(value: T): T => {
  const copy = JSON.parse(JSON.stringify(value)) as T
  function freeze(item: any) { if (item && typeof item === 'object') { Object.values(item).forEach(freeze); Object.freeze(item) } }
  freeze(copy); return copy
}
function checkItems(value: CirculationPreview | CirculationReceipt) {
  if (!value.items.length || value.items.length > (value.operation === 'BORROW' ? 10 : 100) || new Set(value.items.map(row => row.ordinal)).size !== value.items.length) throw Error('明细数量异常。')
  const changes = new Map<number, number>(), ids = new Set<number>(), copies = new Set<number>()
  value.items.forEach((row, index) => {
    if (row.ordinal !== index + 1 || (row.dueDateKind === 'DATED') !== (row.dueDate !== null)) throw Error('明细顺序或期限含义异常。')
    if (row.recordId !== null) { if (ids.has(row.recordId)) throw Error('借阅重复。'); ids.add(row.recordId) }
    if (row.copyId !== null) { if (copies.has(row.copyId)) throw Error('实体册重复。'); copies.add(row.copyId) }
    if (value.operation === 'BORROW') {
      if (row.beforeRecordStatus !== null || row.afterRecordStatus !== 'BORROWING' || row.beforeCopyStatus !== 'AVAILABLE' || row.afterCopyStatus !== 'BORROWED' || row.copyId === null || row.barcode === null || row.copyVersionBefore === null || row.copyVersionAfter !== row.copyVersionBefore + 1 || row.borrowDate !== value.businessDate || row.dueDateKind !== 'DATED' || row.returnDate !== null || row.renewCount !== 0) throw Error('借出明细状态异常。')
    } else {
      if (!row.recordId || !['BORROWING','OVERDUE'].includes(row.beforeRecordStatus || '') || row.afterRecordStatus !== 'RETURNED' || row.returnDate !== value.businessDate) throw Error('归还明细状态异常。')
      if (row.copyId === null ? [row.barcode,row.beforeCopyStatus,row.afterCopyStatus,row.copyVersionBefore,row.copyVersionAfter].some(item => item !== null) : row.barcode === null || row.beforeCopyStatus !== 'BORROWED' || row.afterCopyStatus !== 'AVAILABLE' || row.copyVersionBefore === null || row.copyVersionAfter !== row.copyVersionBefore + 1) throw Error('归还实体册关联异常。')
    }
    changes.set(row.bookId, (changes.get(row.bookId) || 0) + (value.operation === 'BORROW' ? -1 : 1))
  })
  if (value.operation === 'BORROW' && changes.size !== value.items.length || value.books.length !== changes.size || new Set(value.books.map(row => row.bookId)).size !== changes.size) throw Error('书目范围异常。')
  for (const row of value.books) if (row.deltaAvailable !== changes.get(row.bookId) || row.afterAvailable !== row.stockAvailable + row.deltaAvailable || row.stockAvailable > row.stockTotal || row.afterAvailable > row.stockTotal) throw Error('库存预览异常。')
}
export function assertReceiptShape(value: unknown): asserts value is CirculationReceipt {
  if (!receipt(value)) throw Error('回执格式异常。')
  const result = value as CirculationReceipt; checkItems(result)
  if (result.operation === 'BORROW' ? !result.orderId : result.orderId !== null) throw Error('回执订单身份异常。')
  if (result.items.some(row => row.recordId === null) || new Set(result.items.map(row => row.stockFlowId)).size !== result.items.length) throw Error('回执明细身份异常。')
}
export function assertPreviewBinding(value: CirculationPreview, input: CirculationInput) {
  checkItems(value)
  if (value.operation !== input.operation) throw Error('预览动作不一致。')
  if (input.operation === 'BORROW') {
    if (value.borrower?.userId !== input.userId || value.borrower.status !== 1 || value.activeCountBefore === null || value.activeCountAfter !== value.activeCountBefore + input.items.length || value.activeCountAfter > 10 || value.items.length !== input.items.length) throw Error('借阅会员或数量不一致。')
    input.items.forEach((source,index) => { const target = value.items[index]!; if (target.recordId !== null || target.userId !== input.userId || target.bookId !== source.bookId || target.copyId !== source.copyId || target.userName !== value.borrower!.userName || Date.parse(`${target.dueDate}T00:00:00Z`) - Date.parse(`${value.businessDate}T00:00:00Z`) !== source.borrowDays * 86400000) throw Error('借阅预览与明确选择的册、会员或期限不一致。') })
  } else if (value.borrower !== null || value.activeCountBefore !== null || value.activeCountAfter !== null || circulationCanonical(value.items.map(row => row.recordId)) !== circulationCanonical(input.recordIds)) throw Error('归还预览未完整覆盖原明细顺序。')
}
export function assertReceiptBinding(value: CirculationReceipt, original: CirculationRequest, ownerId: number) {
  assertReceiptShape(value)
  if (!original.preview || value.actorId !== ownerId || value.operationKey !== original.operationKey || value.operation !== original.operation || value.previewDigest !== original.previewDigest || value.businessDate !== original.businessDate || Date.parse(value.confirmedAt) < Date.parse(original.createdAt) || circulationCanonical(value.books) !== circulationCanonical(original.preview.books) || value.items.length !== original.preview.items.length) throw Error('回执与原操作者、请求或预览不一致。')
  value.items.forEach((item,index) => { const { stockFlowId: _flow, recordId, ...rest } = item, expected = original.preview!.items[index]!, { recordId: expectedId, ...expectedRest } = expected; if (circulationCanonical(rest) !== circulationCanonical(expectedRest) || original.operation === 'RETURN' && recordId !== expectedId) throw Error('回执未精确对应原目标全集。') })
}
export function assertRequestShape(value: unknown): asserts value is CirculationRequest {
  if (!obj({ schemaVersion: literal(1), operationKey: validKey, state: literal('PREVIEWED','COMMITTED','CANCELLED'), operation: nullable(operation), actorId: safeCirculationId, createdAt: stamp, expiresAt: nullable(stamp), businessDate: nullable(date), input: nullable(inputCheck), previewDigest: nullable(digest), preview: nullable(preview), receipt: nullable(receipt), cancelledAt: nullable(stamp) })(value)) throw Error('原请求格式异常。')
  const view = value as CirculationRequest
  if (view.operation === null) { if (view.state !== 'CANCELLED' || view.expiresAt !== null || view.businessDate !== null || view.input !== null || view.previewDigest !== null || view.preview !== null || view.receipt !== null || !view.cancelledAt) throw Error('原取消凭据异常。'); return }
  if (!view.input || !view.preview || !view.previewDigest || !view.businessDate || !view.expiresAt || Date.parse(view.expiresAt) <= Date.parse(view.createdAt) || view.input.operation !== view.operation || view.preview.businessDate !== view.businessDate) throw Error('原预览身份异常。')
  assertPreviewBinding(view.preview, view.input)
  if (view.state === 'COMMITTED') { if (!view.receipt || view.cancelledAt !== null) throw Error('成功状态缺少原回执。'); assertReceiptBinding(view.receipt,view,view.actorId) }
  else if (view.receipt !== null || (view.state === 'CANCELLED' ? !view.cancelledAt : view.cancelledAt !== null)) throw Error('原请求终态异常。')
}
const orderShape = { orderId: safeCirculationId, userId: safeCirculationId, userName: nullable(string), cardNo: nullable(string), actorId: safeCirculationId, createdAt: stamp, businessDate: date, itemCount: safeCirculationId, activeCount: natural, returnedCount: natural, state: literal('BORROWING','PARTIALLY_RETURNED','RETURNED'), remark }
function checkOrder(value: { itemCount: number; activeCount: number; returnedCount: number; state: string }) { if (value.itemCount > 10 || value.activeCount + value.returnedCount !== value.itemCount || value.state !== (value.returnedCount === 0 ? 'BORROWING' : value.activeCount === 0 ? 'RETURNED' : 'PARTIALLY_RETURNED')) throw Error('订单数量与状态异常。') }
export function assertOrderPage(value: unknown): asserts value is BorrowOrderPage {
  if (!obj({ schemaVersion: literal(1), records: array(obj(orderShape)), total: natural, page: safeCirculationId, size: safeCirculationId, pages: natural })(value)) throw Error('订单列表格式异常。')
  const page = value as BorrowOrderPage
  if (page.size > 100 || page.page > 2147483647 || page.pages !== Math.ceil(page.total / page.size) || page.records.length > page.size || new Set(page.records.map(row => row.orderId)).size !== page.records.length) throw Error('订单分页异常。')
  page.records.forEach(checkOrder)
}
export function assertOrderDetail(value: unknown): asserts value is BorrowOrderDetail {
  if (!obj({ ...orderShape, schemaVersion: literal(1), items: array(obj({ ordinal: safeCirculationId, recordId: safeCirculationId, bookId: safeCirculationId, bookTitle: string, copyId: safeCirculationId, barcode: string, status: loanStatus, borrowDate: nullable(date), dueDate: nullable(date), dueDateKind: kind, returnDate: nullable(date), renewCount: natural })) })(value)) throw Error('订单详情格式异常。')
  const order = value as BorrowOrderDetail; checkOrder(order)
  if (order.items.length !== order.itemCount || new Set(order.items.map(row => row.recordId)).size !== order.itemCount || new Set(order.items.map(row => row.copyId)).size !== order.itemCount || order.items.filter(row => row.status === 'RETURNED').length !== order.returnedCount || order.items.some((row,index) => row.ordinal !== index + 1 || (row.dueDateKind === 'DATED') !== (row.dueDate !== null))) throw Error('订单详情未完整覆盖明细。')
}
const messages: Record<string,string> = { UNKNOWN_RESULT:'网络中断或超时，原办理结果尚未确定。', INVALID_RESPONSE:'返回资料与协议不符，保留原操作编号查询。', REQUEST_NOT_FOUND:'暂未查到原请求，不能据此重新办理；请继续查询或安全取消原编号。', PREVIEW_STALE:'借阅、实体册或库存已经变化，请安全取消原请求后重新核对。', PREVIEW_EXPIRED:'预览已到期或业务日期已变化，请安全取消后重新核对。', MEMBER_DISABLED:'该读者账号已停用，不能办理新借阅。', BORROW_LIMIT:'本次借出后累计在借将超过当前 10 册上限。', DUPLICATE_BOOK:'该读者有同一书目未还，不能重复借阅。', COPY_UNAVAILABLE:'所选实体册已不可借，请核对。', RECORD_NOT_ACTIVE:'所选明细已不在借，请核对原归还结果。', CIRCULATION_BUSY:'借还资料正在处理中，请查询或重试原办理。', OWNER_REQUIRED:'仅原操作者可恢复此请求。', ROLE_REQUIRED:'当前角色不能办理借还。' }
export function circulationProblem(cause: unknown) { const value = cause as { status?: number | null; code?: string; message?: string }; return { status: value?.status ?? null, code: value?.code || '', message: messages[value?.code || ''] || value?.message || '请求失败，请保留原操作编号查询。' } }
