import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ preview: vi.fn(), confirm: vi.fn(), lookup: vi.fn() }))
vi.mock('@/api/catalogPublication', async original => ({ ...await original<object>(), previewPublication: api.preview, confirmPublication: api.confirm, getPublicationRequestByKey: api.lookup }))
import fixture from '@/components/catalog/fixtures/catalogPublication.backend.json'
import serviceFixture from '@/components/catalog/fixtures/catalogPublication.service.backend.json'
import type { PublicationEvents, PublicationPreviewInput, PublicationReceipt, PublicationRequestView } from '@/api/catalogPublication'
import { usePublicationSession } from './publicationSession'
import Panel from './PublicationRequestPanel.vue'
const input = () => structuredClone(fixture.previewInput) as PublicationPreviewInput
const preview = () => structuredClone(fixture.preview.data) as PublicationRequestView
const receipt = () => structuredClone(fixture.receipt.data) as PublicationReceipt
beforeEach(() => { vi.resetAllMocks(); sessionStorage.clear(); api.preview.mockResolvedValue(preview()); api.confirm.mockResolvedValue(receipt()); api.lookup.mockRejectedValue(new Error('offline')) })
it('accepts the real Spring MVC/filter/service/MySQL batch response and exact committed values', async () => {
  expect(fixture.synthetic).toBe(true); expect(fixture.transport).toContain('database')
  const session = usePublicationSession(fixture.receipt.data.actorId, () => true)
  await session.preview(input()); expect(session.view.value).not.toBeNull(); expect(api.preview.mock.calls[0]![0].entries.map((row: any) => row.bookId)).toEqual(fixture.preview.data.preview.books.map(row => row.bookId))
  await session.confirm(); expect(session.error.value).toBe(''); expect(session.receipt.value).toEqual(fixture.receipt.data)
  expect(session.receipt.value!.books[0]!.publication).toMatchObject({ publishDate: '2024-02-29', pages: 0, description: '合成简介\n第二行\t📚' })
  expect(session.receipt.value!.books[1]!.publication.description).toBe('')
})
it('renders the full real batch preview with NULL, zero, explicit empty string and per-book evidence', async () => {
  const session = usePublicationSession(fixture.receipt.data.actorId, () => true); await session.preview(input())
  const wrapper = mount(Panel, { props: { session }, global: { stubs: { ElAlert: true, ElButton: { template: '<button><slot /></button>' } } } })
  try {
    await flushPromises(); expect(wrapper.findAll('.publication-book')).toHaveLength(2)
    expect(wrapper.text()).toContain('NULL（未知 / 未设置）'); expect(wrapper.text()).toContain('""（明确空串）'); expect(wrapper.text()).toContain('合成简介\\n第二行\\t📚'); expect(wrapper.text()).toContain('版权页')
    for (const row of fixture.preview.data.preview.books) expect(wrapper.text()).toContain(String(row.bookId))
  } finally { wrapper.unmount() }
})
it('recovers a lost real confirm response using the original key and validates the safe own-event schema', async () => {
  api.confirm.mockRejectedValue(new Error('lost confirm')); api.lookup.mockResolvedValue({ ...preview(), state: 'COMMITTED', receipt: receipt() })
  const session = usePublicationSession(fixture.receipt.data.actorId, () => true); await session.preview(input()); await session.confirm(); await session.retry()
  expect(api.confirm).toHaveBeenCalledOnce(); expect(api.lookup).toHaveBeenCalledWith(fixture.previewInput.operationKey); expect(session.receipt.value).toEqual(fixture.receipt.data)
  const events = fixture.events.data as PublicationEvents
  expect(events.records[0]).toMatchObject({ actorId: fixture.receipt.data.actorId, kind: 'MANUAL', beforeRevision: 0, afterRevision: 1, evidence: fixture.previewInput.entries[0]!.evidence })
  expect(fixture.otherActorEvents.data.records).toEqual([])
})

it('consumes the real TCP/Tomcat/MySQL batch, replayed receipt and original-key response without rewriting its JSON', async () => {
  api.preview.mockResolvedValue(structuredClone(serviceFixture.preview.data)); api.confirm.mockRejectedValue(new Error('lost response')); api.lookup.mockResolvedValue(structuredClone(serviceFixture.byKey.data))
  const session = usePublicationSession(serviceFixture.receipt.data.actorId, () => true)
  await session.preview(structuredClone(serviceFixture.previewInput) as PublicationPreviewInput); await session.confirm()
  expect(serviceFixture.transport).toContain('real TCP HTTP'); expect(session.receipt.value).toEqual(serviceFixture.receipt.data); expect(serviceFixture.replayedReceipt.data).toEqual(serviceFixture.receipt.data)
  expect(session.receipt.value!.books[0]!.publication.description).toBe('简介原文\n第二行\t📚 <script>原文</script>')
  const wrapper = mount(Panel, { props: { session }, global: { stubs: { ElAlert: true, ElButton: true } } })
  try { await flushPromises(); expect(wrapper.find('script').exists()).toBe(false); expect(wrapper.text()).toContain('<script>原文</script>') } finally { wrapper.unmount() }
  expect(serviceFixture.ownerError.code).toBe(403); expect(serviceFixture.anonymousError.code).toBe(403); expect(serviceFixture.invalidInputError.code).toBe(400)
})
