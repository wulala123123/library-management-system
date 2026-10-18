import type { SetPreviewInput, SetRequestView, SetReceipt, SetEvidence, SetBookContext } from '@/api/catalogSet'
import { markerStates, reviewReasons } from '@/api/catalogSet'

export const operationLabels = { CREATE: '新建套装目录', UPDATE_METADATA: '修改套装资料', ADD_MEMBERS: '增加成员书目', RETIRE_MEMBERS: '退役成员关系', RESTORE_MEMBERS: '恢复成员关系', RETIRE_SET: '退役套装目录', RESTORE_SET: '恢复套装目录' }
export const evidenceLabels = { COPYRIGHT_PAGE: '版权页', PUBLISHER_MATERIAL: '出版社资料', LEGACY_RECORD: '原系统记录', MANUAL_REVIEW: '人工核对' }
export const reasonLabels = { MEMBER_MARKER_NO_PARENT: '成员标记缺少明确父套装', SOURCE_CONFLICT: '来源值冲突', ASSOCIATION_INVALID: '来源关联异常', UNMAPPED_MARKER: '来源标记未识别', NO_SOURCE: '无来源', INCOMPLETE_SOURCE_VALUE: '来源值不完整', SOURCE_LIMIT_EXCEEDED: '来源数量超出本次核对范围' }
export const markerLabels = { NO_SOURCE: '无来源', CONFLICT: '冲突', MISSING: '缺字段', NULL: '空值', EMPTY: '空字符串', WHITESPACE: '全空白', UNSET: '原值暂无设置', WRONG_TYPE: '类型异常', MEMBER_MARKER: '套装中单本', UNMAPPED: '未识别值' }
export function setCanonical(value: unknown): string { if (Array.isArray(value)) return `[${value.map(setCanonical).join(',')}]`; if (value && typeof value === 'object') return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${setCanonical((value as Record<string, unknown>)[key])}`).join(',')}}`; return JSON.stringify(value) }
export function normalizeSetInput(input: SetPreviewInput): SetPreviewInput { const result = JSON.parse(JSON.stringify(input)) as SetPreviewInput; if ('members' in result) result.members.sort((a, b) => a.bookId - b.bookId); return result }
export function frozenCopy<T>(value: T): T { const copy = JSON.parse(JSON.stringify(value)); function freeze(item: any) { if (item && typeof item === 'object') { Object.values(item).forEach(freeze); Object.freeze(item) } } freeze(copy); return copy }

type Check = (value: any) => boolean
const literal = (...values: unknown[]): Check => value => values.includes(value)
const nullable = (check: Check): Check => value => value === null || check(value)
const array = (check: Check): Check => value => Array.isArray(value) && value.every(check)
export const safeId: Check = value => Number.isSafeInteger(value) && value > 0
const revision: Check = value => Number.isSafeInteger(value) && value >= 0
const bool: Check = value => typeof value === 'boolean'
const str: Check = value => typeof value === 'string'
export const strictSetKey: Check = value => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(value)
const digest: Check = value => typeof value === 'string' && /^[0-9a-f]{64}$/.test(value)
const stamp: Check = value => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value) && Number.isFinite(Date.parse(value))
const object = (shape: Record<string, Check>): Check => value => value !== null && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === Object.keys(shape).length && Object.entries(shape).every(([key, check]) => Object.prototype.hasOwnProperty.call(value, key) && check(value[key]))
export function legalSetText(value: unknown, max: number, required = false, note = false): value is string {
  if (typeof value !== 'string' || [...value].length > max || [...value].some(char => char.length === 1 && char.charCodeAt(0) >= 0xd800 && char.charCodeAt(0) <= 0xdfff)) return false
  if (note ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/u.test(value) : /[\u0000-\u001f\u007f-\u009f]/u.test(value)) return false
  return !required || !/^[\u0009-\u000d\u001c-\u001f\u0020\u00a0\u1680\u2000-\u200a\u2028\u2029\u202f\u205f\u3000]*$/u.test(value)
}
const name: Check = value => legalSetText(value, 200, true)
const note: Check = nullable(value => legalSetText(value, 2000, false, true))
const evidence = object({ kind: literal(...Object.keys(evidenceLabels)), reference: value => legalSetText(value, 1000, true) })
const expected = object({ revision, digest })
const expectedBook = object({ revision, attributeRevision: revision, digest, sourceDigest: digest })
const newMember = object({ bookId: safeId, expectedBook, evidence })
const existingMember = object({ membershipId: safeId, bookId: safeId, expectedMember: expected, expectedBook, evidence })
const change = (value: Check, clear = true): Check => item => object({ action: literal('KEEP') })(item) || object({ action: literal('SET'), value })(item) || (clear && object({ action: literal('CLEAR') })(item))
const header = { schemaVersion: literal(1), operationKey: strictSetKey, mode: literal('MANUAL'), reason: (value: unknown) => legalSetText(value, 500, true) }
export function validSetInput(value: unknown): value is SetPreviewInput {
  const input = value as SetPreviewInput
  if (!input || typeof input !== 'object') return false
  const op = input.operation
  let valid = false
  if (op === 'CREATE') valid = object({ ...header, operation: literal(op), clientRef: v => typeof v === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(v), name, note, evidence, members: array(newMember) })(input) && input.members.length <= 100
  else if (op === 'UPDATE_METADATA') valid = object({ ...header, operation: literal(op), setId: safeId, expectedSet: expected, changes: object({ name: change(name, false), note: change(v => legalSetText(v, 2000, false, true)) }), evidence })(input) && (input.changes.name.action !== 'KEEP' || input.changes.note.action !== 'KEEP')
  else if (op === 'ADD_MEMBERS' || op === 'RETIRE_MEMBERS' || op === 'RESTORE_MEMBERS') valid = object({ ...header, operation: literal(op), setId: safeId, expectedSet: expected, members: array(op === 'ADD_MEMBERS' ? newMember : existingMember) })(input) && input.members.length >= 1 && input.members.length <= 100
  else if (op === 'RETIRE_SET' || op === 'RESTORE_SET') valid = object({ ...header, operation: literal(op), setId: safeId, expectedSet: expected, evidence })(input)
  if (!valid) return false
  if ('members' in input && (new Set(input.members.map(row => row.bookId)).size !== input.members.length || new Set(input.members.filter(row => 'membershipId' in row).map(row => (row as any).membershipId)).size !== input.members.filter(row => 'membershipId' in row).length)) return false
  return true
}
const status = literal('ACTIVE', 'RETIRED')
const snapshot = object({ setId: nullable(safeId), clientRef: nullable(str), name, note, status, revision, activeMemberCount: revision, retiredMemberCount: revision, memberCompleteness: literal('UNVERIFIED'), physicalAssembly: literal('UNVERIFIED') })
const member = object({ membershipId: nullable(safeId), setId: nullable(safeId), bookId: safeId, status, revision, evidence })
const book = object({ bookId: safeId, isbn: str, catalogTitle: str, displayTitle: str, revision, attributeRevision: revision, deleted: bool })
const context = object({ book, bookDigest: digest, source: object({ parserVersion: literal('huiben-book-set-v1'), sourceCount: revision, associationState: literal('NO_SOURCE', 'VALID', 'ASSOCIATION_INVALID'), markerState: literal(...markerStates), reasons: array(literal(...reviewReasons)), digest, readAt: stamp }) })
export function assertSetContexts(value: unknown): asserts value is { schemaVersion: 1; records: SetBookContext[] } { if (!object({ schemaVersion: literal(1), records: array(context) })(value)) throw new Error('书目核对资料格式异常，请重新读取。') }
const side = object({ set: nullable(snapshot), members: array(object({ bookId: safeId, member: nullable(member) })) })
const after = object({ set: snapshot, members: array(object({ bookId: safeId, member })) })
const impact = object({ newSets: revision, addedMembers: revision, retiredMembers: revision, restoredMembers: revision, changedMembers: revision, unchangedActiveMembers: revision, unchangedRetiredMembers: revision, copies: literal(0), stockTotal: literal(0), stockAvailable: literal(0), loans: literal(0), charges: literal(0), uses: literal(0) })
const operations = literal(...Object.keys(operationLabels))
const preview = object({ schemaVersion: literal(1), operation: operations, mode: literal('MANUAL'), reason: str, createdAt: stamp, expiresAt: stamp, before: side, after, books: array(context), applied: validSetInput, changedFields: array(literal('set.create', 'set.name', 'set.note', 'set.status', 'members.add', 'members.retire', 'members.restore')), willChange: bool, impact, warnings: array(object({ code: literal('SIMILAR_NAME', 'COMPLETENESS_UNVERIFIED', 'PHYSICAL_ASSEMBLY_UNVERIFIED'), setIds: array(safeId), total: revision, truncated: bool })) })
const receipt = object({ schemaVersion: literal(1), requestId: safeId, operationKey: strictSetKey, mode: literal('MANUAL'), operation: operations, actorId: safeId, confirmedAt: stamp, changed: bool, eventIds: array(safeId), before: side, impact, result: object({ set: snapshot, members: array(member), createdSet: nullable(object({ clientRef: str, setId: safeId })) }), previewDigest: digest })
export function assertSetReceipt(value: unknown): asserts value is SetReceipt { if (!receipt(value)) throw new Error('成功回执格式异常，保留原请求并查询。') }
export function assertSetRequest(value: unknown): asserts value is SetRequestView {
  if (!object({ schemaVersion: literal(1), requestId: safeId, operationKey: strictSetKey, mode: literal('MANUAL'), operation: operations, state: literal('PREVIEWED', 'COMMITTED', 'CANCELLED'), createdAt: stamp, expiresAt: stamp, previewDigest: digest, preview, receipt: nullable(receipt), cancelledAt: nullable(stamp), cancelReason: nullable(literal('EDIT', 'ABANDON')) })(value)) throw new Error('请求恢复资料格式异常，原操作编号已保留。')
  const view = value as SetRequestView
  if (view.state === 'COMMITTED' ? !view.receipt || view.cancelledAt !== null || view.cancelReason !== null : view.receipt !== null || (view.state === 'CANCELLED' ? view.cancelledAt === null || view.cancelReason === null : view.cancelledAt !== null || view.cancelReason !== null)) throw new Error('请求状态与回执不一致。')
}
export function expectedSetBook(context: SetBookContext) { return { revision: context.book.revision, attributeRevision: context.book.attributeRevision, digest: context.bookDigest, sourceDigest: context.source.digest } }
export function emptySetEvidence(): SetEvidence { return { kind: 'MANUAL_REVIEW', reference: '' } }
const problemMessages: Record<string,string> = { INVALID_INPUT:'资料未通过校验，请核对本次填写内容。', CATALOG_SET_ROLE_REQUIRED:'当前角色不能办理套装目录请求。', OWNER_REQUIRED:'仅原操作者且当前为管理员可读取此请求。', REQUEST_NOT_FOUND:'暂未查到原请求；原操作编号继续保留，请查询或重试原动作。', BOOK_NOT_FOUND:'所选书目不存在或已删除，请重新核对。', SET_NOT_FOUND:'套装目录不存在，请重新核对。', CASE_NOT_FOUND:'来源对象不存在，请重新查询。', IDEMPOTENCY_KEY_REUSED:'原操作编号与请求资料不一致，请保留原编号恢复。', PREVIEW_EXPIRED:'原预览已到期，请安全取消后重新核对。', PREVIEW_CANCELLED:'原预览已经取消，请查询原取消结果。', CATALOG_SET_CHANGED:'套装目录或成员关系已变化，请重新核对。', BOOK_IDENTITY_CHANGED:'书目登记身份或展示资料已变化，请重新核对。', SOURCE_CHANGED:'来源资料已变化，请重新核对。', MEMBERSHIP_EXISTS:'本套装已存在此书目的稳定关系，请查看原关系；退役关系使用恢复动作。', MEMBERSHIP_STATE_CONFLICT:'所选成员关系的身份或状态已变化，请重新读取。', SET_RETIRED:'套装已退役，成员操作前须明确恢复套装。', SOURCE_LIMIT_EXCEEDED:'所选书目的来源超过本次核对范围，请保留资料另行核实。', REVISION_EXHAUSTED:'此资料暂不能继续变更，请联系管理员。', CATALOG_BUSY:'目录正在办理其他请求，请查询或重试原操作编号。', CATALOG_SET_SAVE_FAILED:'办理结果暂不明确，请保留原操作编号查询。' }
export function setProblem(cause: unknown) { const error = cause as { message?: string; response?: { status?: number; data?: { message?: string; data?: { reasonCode?: string } } } }; const code=error?.response?.data?.data?.reasonCode; return { status: error?.response?.status, code, message: (code && problemMessages[code]) || error?.response?.data?.message || error?.message || '套装目录请求失败，请保留原操作编号查询。' } }
