import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { createHistoryContextApi, HistoryReadError, historyId } from './historyContext'

const request = vi.fn()
const response = (data: unknown, status = 200) => new Response(JSON.stringify({ code: status, message: 'synthetic', data }), { status })
const record = () => ({ id: '81', sourceSystem: 'huiben', recordType: 'ORDER', sourceKey: ' 00081 ', displayName: '合成原订单', summary: null, userId: '5', bookId: null, sourceTime: null, association: { scope: 'MEMBER_CONTEXT', state: 'UNVERIFIED', basis: '同会员原订单，未与本条借阅建立对应' } })
const context = () => ({ entityType: 'MEMBER', entityId: '5', identity: { memberId: '5', username: 'synthetic', memberName: null, cardNo: '000005', bookId: null, bookTitle: null, isbn: null, borrowId: null, copyId: null, barcode: null, status: null, borrowDate: null, dueDate: null, returnDate: null, dueDateKind: null }, provenance: { classification: 'UNVERIFIED', sourceKey: ' 00005 ', sourceSystem: null, associationState: 'UNVERIFIED', basis: ['已存来源键'], warnings: ['待核实'] }, links: { memberId: '5', bookId: null, copyId: null, counterOrderId: null, memberArchiveId: null }, archiveTypes: [{ recordType: 'unknown <script>', total: 1 }], readOnly: true })
beforeEach(() => { vi.resetAllMocks(); localStorage.clear(); vi.stubGlobal('fetch', request) })
afterEach(() => vi.unstubAllGlobals())

it('uses the captured token and only GET, with explicit bounded filters and exact string IDs', async () => {
  const api = createHistoryContextApi('original-login'); localStorage.setItem('token', 'another-login')
  request.mockResolvedValue(response({ records: [record()], total: 21, page: 2, size: 20, pages: 2 }))
  const signal = new AbortController().signal
  const result = await api.records('borrows', '9223372036854775807', { page: 2, size: 20, type: 'ORDER' }, signal)
  expect(request).toHaveBeenCalledExactlyOnceWith('/api/history-context/borrows/9223372036854775807/records?page=2&size=20&type=ORDER', { method: 'GET', headers: { Authorization: 'Bearer original-login' }, credentials: 'same-origin', cache: 'no-store', signal })
  expect(result.records[0]!.sourceKey).toBe(' 00081 ')
})

it('reads only the explicitly selected archive and preserves malformed JSON and source state bytes', async () => {
  const raw = '{ "原状态": "已取消", "条码": "00081", <bad json>'
  request.mockResolvedValue(response({ ...record(), detailsJson: raw }))
  const result = await createHistoryContextApi('login').record('members', '5', '81')
  expect(result.detailsJson).toBe(raw)
  expect(request.mock.calls[0]![0]).toBe('/api/history-context/members/5/records/81')
  expect(request.mock.calls[0]![1].method).toBe('GET')
})

it('accepts actual nullable context fields and preserves unknown source record types without treating them as facts', async () => {
  request.mockResolvedValueOnce(response(context()))
  expect(await createHistoryContextApi('login').context('members', '5')).toEqual(context())
  request.mockResolvedValueOnce(response({ records: [{ ...record(), recordType: 'order' }], total: 1, page: 1, size: 20, pages: 1 }))
  const result = await createHistoryContextApi('login').records('members', '5', { page: 1, size: 20, type: 'ALL' })
  expect(result.records[0]!.recordType).toBe('order')
})

it('rejects unsafe IDs, URL fragments and invalid pagination before making requests', async () => {
  const api = createHistoryContextApi('login')
  for (const id of ['0', '05', '5/records', '5?type=ORDER', '9223372036854775808', '5\n']) await expect(api.context('members', id)).rejects.toBeInstanceOf(HistoryReadError)
  expect(historyId(Number.MAX_SAFE_INTEGER + 1)).toBeNull()
  for (const query of [{ page: 0, size: 20, type: 'ALL' }, { page: 1, size: 101, type: 'ALL' }, { page: 1, size: 20, type: 'GUESS' }]) await expect(api.records('members', '5', query as any)).rejects.toBeInstanceOf(HistoryReadError)
  expect(request).not.toHaveBeenCalled()
})

it('rejects a different entity or archive identity and pages that violate the requested scope', async () => {
  const api = createHistoryContextApi('login')
  request.mockResolvedValueOnce(response({ entityId: '6', entityType: 'MEMBER' }))
  await expect(api.context('members', '5')).rejects.toBeInstanceOf(HistoryReadError)
  request.mockResolvedValueOnce(response({ ...record(), id: '82', detailsJson: '{}' }))
  await expect(api.record('members', '5', '81')).rejects.toBeInstanceOf(HistoryReadError)
  request.mockResolvedValueOnce(response({ records: [{ ...record(), recordType: 'MEMBER' }], total: 1, page: 1, size: 20, pages: 1 }))
  await expect(api.records('members', '5', { page: 1, size: 20, type: 'ORDER' })).rejects.toBeInstanceOf(HistoryReadError)
})

it('keeps permission rejection local so an old response cannot revoke another login', async () => {
  const listener = vi.fn(); window.addEventListener('auth-access-invalidated', listener)
  try {
    request.mockResolvedValue(response(null, 403))
    await expect(createHistoryContextApi('old-login').context('members', '5')).rejects.toMatchObject({ status: 403 })
    expect(listener).not.toHaveBeenCalled()
  } finally { window.removeEventListener('auth-access-invalidated', listener) }
})

it('distinguishes absent resources, transport failures and invalid envelopes from empty results', async () => {
  const api = createHistoryContextApi('login')
  request.mockResolvedValueOnce(response(null, 404)); await expect(api.context('books', '7')).rejects.toMatchObject({ status: 404 })
  request.mockRejectedValueOnce(Error('offline')); await expect(api.context('books', '7')).rejects.toThrow('读取失败不表示没有资料')
  request.mockResolvedValueOnce(new Response('<html>broken</html>', { status: 200 })); await expect(api.context('books', '7')).rejects.toThrow('返回内容不完整')
})
