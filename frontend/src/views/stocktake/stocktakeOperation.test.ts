import { beforeEach, expect, it, vi } from 'vitest'
import { useStocktakeOperation, type OperationTransport } from './stocktakeOperation'

const request = () => ({ operationKey: 'original-key', label: '追加观察', path: '/stocktakes/7/observations', body: { schemaVersion: 1, operationKey: 'original-key', expectedRevision: 3, observations: [{ rawBarcode: ' 00012 ', clientEventKey: 'scan-1' }] } })
function setup() {
  const receipt = { operationKey: 'original-key', id: 7 }
  const transport: OperationTransport<typeof receipt> = {
    send: vi.fn().mockResolvedValue(receipt), read: vi.fn().mockResolvedValue({ state: 'ABSENT' }),
    validate: (operation, value) => { if (value.operationKey !== operation.operationKey) throw new Error('wrong receipt') },
  }
  return { transport, receipt, session: useStocktakeOperation(2, transport) }
}
beforeEach(() => sessionStorage.clear())
it('freezes key/schema/revision/raw input before the first write and forbids a second operation', async () => {
  const { session, transport } = setup(), input = request()
  expect(await session.start(input)).toBe(true)
  input.body.expectedRevision = 99; input.body.observations[0]!.rawBarcode = '12'
  const frozen = vi.mocked(transport.send).mock.calls[0]![0]
  expect(frozen.body.expectedRevision).toBe(3)
  expect((frozen.body.observations as { rawBarcode: string }[])[0]!.rawBarcode).toBe(' 00012 ')
  expect(Object.isFrozen(frozen.body)).toBe(true)
  expect(await session.start(request())).toBe(false)
  expect(session.result.value).toEqual({ operationKey: 'original-key', id: 7 })
  expect(session.acknowledge()).toBe(true)
})
it('holds unknown through absent/PENDING reads, survives reload, and retries the exact frozen body', async () => {
  const { session, transport, receipt } = setup()
  vi.mocked(transport.send).mockRejectedValueOnce(new Error('timeout'))
  expect(await session.start(request())).toBe(false)
  expect(session.state.value).toBe('UNCERTAIN'); expect(session.error.value).toContain('仍可能正在执行')
  expect(session.acknowledge()).toBe(false)
  const reloaded = useStocktakeOperation(2, transport)
  expect(reloaded.pending.value?.operationKey).toBe('original-key')
  vi.mocked(transport.read).mockResolvedValueOnce({ state: 'PENDING' })
  await reloaded.recover(); expect(reloaded.state.value).toBe('UNCERTAIN')
  await reloaded.retry()
  expect(transport.send).toHaveBeenCalledTimes(2)
  expect(vi.mocked(transport.send).mock.calls[1]![0]).toEqual(vi.mocked(transport.send).mock.calls[0]![0])
  expect(reloaded.result.value).toEqual(receipt)
})
it('uses the committed original receipt after a 500 and does not repeat the write', async () => {
  const { session, transport, receipt } = setup()
  vi.mocked(transport.send).mockRejectedValueOnce({ response: { status: 500 } })
  vi.mocked(transport.read).mockResolvedValueOnce({ state: 'COMMITTED', result: receipt })
  expect(await session.start(request())).toBe(true)
  await session.retry(); expect(transport.send).toHaveBeenCalledOnce()
})
it('keeps a mismatched receipt unresolved and preserves input on conflicts until deliberate acknowledgment', async () => {
  const { session, transport } = setup()
  vi.mocked(transport.send).mockResolvedValueOnce({ operationKey: 'wrong', id: 7 })
  await session.start(request()); expect(session.state.value).toBe('UNCERTAIN'); expect(session.result.value).toBeNull()
  vi.mocked(transport.read).mockResolvedValueOnce({ state: 'REJECTED', message: '版本已变化，请逐项重新核对' })
  await session.recover(); expect(session.pending.value?.body).toEqual(request().body)
  expect(session.acknowledge()).toBe(true)
  vi.mocked(transport.send).mockRejectedValueOnce({ response: { status: 409, data: { message: '范围变化', data: { reasonCode: 'STOCKTAKE_CHANGED' } } } })
  await session.start(request()); expect(session.state.value).toBe('REJECTED'); expect(session.error.value).toContain('STOCKTAKE_CHANGED')
})
it('isolates accounts and refuses writes when original request persistence fails', async () => {
  const { session, transport } = setup()
  vi.mocked(transport.send).mockRejectedValueOnce(new Error('timeout')); await session.start(request())
  expect(useStocktakeOperation(3, transport).pending.value).toBeNull()
  const broken = { getItem: () => null, setItem: () => { throw new Error('storage unavailable') } } as unknown as Storage
  const other = useStocktakeOperation(4, transport, broken)
  expect(await other.start(request())).toBe(false); expect(transport.send).toHaveBeenCalledOnce()
})
