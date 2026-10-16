import { getStocktakeOperation, writeStocktakeOperation, type ObservedPlacement, type OperationReceipt } from '@/api/stocktake'
import type { OperationTransport } from './stocktakeOperation'
import type { ObservationPlaceDraft } from './StocktakePlacementInput.vue'

export function observedPlacement(draft: ObservationPlaceDraft): ObservedPlacement {
  if (draft.kind === 'UNASSIGNED') return { kind: 'UNASSIGNED' }
  if (draft.kind === 'TEXT') {
    if (!draft.text.length || [...draft.text].length > 200 || /[\p{Cc}\p{Cs}]/u.test(draft.text)) throw new Error('观察位置原文需为 1–200 字的有效文本，保留空白。')
    return { kind: 'TEXT', text: draft.text }
  }
  const address = draft.address?.address
  if (!address) throw new Error('请明确选择实际观察的地址。')
  return { kind: 'REGISTERED', locationId: address.locationId, expectedAddressVersion: { location: address.locationVersion, storey: address.storeyVersion, bookshelf: address.bookshelfVersion } }
}
export const stocktakeTransport: OperationTransport<OperationReceipt> = {
  send: operation => writeStocktakeOperation(operation.path, operation.body),
  async read(operation) {
    try { return { state: 'COMMITTED', result: await getStocktakeOperation(operation.operationKey) } }
    catch (cause) {
      const issue = cause as { response?: { status?: number; data?: { data?: { reasonCode?: string } } } }
      if (issue.response?.status === 404 && issue.response.data?.data?.reasonCode === 'OPERATION_NOT_FOUND') return { state: 'ABSENT' }
      throw cause
    }
  },
  validate(operation, receipt) {
    const sessionId = operation.body.sessionId ?? /^\/stocktakes\/(\d+)(?:\/|$)/u.exec(operation.path)?.[1]
    if (receipt.schemaVersion !== 1 || receipt.operationKey !== operation.operationKey || !Number.isSafeInteger(receipt.sessionId) || !Number.isSafeInteger(receipt.revision) || (sessionId !== undefined && receipt.sessionId !== Number(sessionId)) || !receipt.result) throw new Error('回执与冻结的原请求身份不一致，请继续查询原请求。')
    const names: Record<string, string> = { observations: 'OBSERVE', corrections: 'CORRECT', coverage: 'COVERAGE', 'change-checks': 'CHECK', rechecks: 'RECHECK', prepares: 'PREPARE', confirm: 'CONFIRM', cancel: 'CANCEL', link: 'LINK', revoke: 'REVOKE' }
    const segments = operation.path.split('/')
    const expected = operation.path === '/stocktakes' ? 'CREATE' : operation.path === '/stocktake-resolutions' ? 'RESOLVE' : names[segments[segments.length - 1] || '']
    if (!expected || receipt.operation !== expected || (receipt.result.session && receipt.result.session.sessionId !== receipt.sessionId) || (receipt.result.resolution && receipt.result.resolution.sessionId !== receipt.sessionId)) throw new Error('回执操作或会话与原请求不一致，请继续查询。')
    const final = receipt.result.receipt
    if (final && (final.schemaVersion !== 1 || final.sessionId !== receipt.sessionId)) throw new Error('封存回执身份不一致，请查询原请求。')
    if (expected === 'CONFIRM' && (!final || final.reviewSnapshotId !== operation.body.reviewSnapshotId)) throw new Error('封存回执快照与原确认不一致，请查询原请求。')
    const resolutionId = /^\/stocktake-resolutions\/(\d+)\//u.exec(operation.path)?.[1]
    if (resolutionId && (!receipt.result.resolution || receipt.result.resolution.resolutionId !== Number(resolutionId))) throw new Error('关联回执与原关联不一致，请查询原请求。')
    if (expected === 'RESOLVE') {
      const resolution = receipt.result.resolution
      if (!resolution || resolution.decision !== operation.body.decision || resolution.workflow !== operation.body.workflow || resolution.candidateId !== operation.body.candidateId || JSON.stringify([...resolution.differenceIds].sort((a, b) => a - b)) !== JSON.stringify([...(operation.body.differenceIds as number[])].sort((a, b) => a - b))) throw new Error('核实回执与原意图不一致，请查询原请求。')
    }
  },
}
