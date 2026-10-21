import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import type { HistoryContext, HistoryEntity, HistoryRecord, HistoryRecordDetail } from '@/api/historyContext'

const api = vi.hoisted(() => ({ context: vi.fn(), records: vi.fn(), record: vi.fn(), tokens: vi.fn() }))
const user = reactive({ isAdmin: true, token: 'login-one', userInfo: { id: 1, role: 'ADMIN' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
vi.mock('@/api/historyContext', async original => ({ ...await original<object>(), createHistoryContextApi: (token: string) => { api.tokens(token); return api } }))
import HistoryContextDrawer from './HistoryContextDrawer.vue'
import { HistoryReadError } from '@/api/historyContext'

const drawer = defineComponent({ name: 'ElDrawer', props: ['modelValue', 'title'], emits: ['update:modelValue'], setup(p, { slots, emit }) { return () => p.modelValue ? h('aside', [h('h2', p.title), slots.default?.(), h('button', { onClick: () => emit('update:modelValue', false) }, '关闭抽屉')]) : null } })
const button = defineComponent({ setup(_, { slots, attrs }) { return () => h('button', attrs, slots.default?.()) } })
const pagination = defineComponent({ name: 'ElPagination', props: ['currentPage', 'pageSize', 'total'], emits: ['current-change'], setup(p) { return () => h('div', `第${p.currentPage}页，共${p.total}条`) } })
const wrappers: VueWrapper[] = []
const page = (props: object = {}) => { const w = mount(HistoryContextDrawer, { props: { visible: true, entityType: 'members', entityId: 5, ...props }, global: { stubs: { ElDrawer: drawer, ElButton: button, ElPagination: pagination } } }); wrappers.push(w); return w }
const click = async (w: VueWrapper, text: string) => { const button = w.findAll('button').find(item => item.text().trim() === text); expect(button, text).toBeDefined(); await button!.trigger('click'); await flushPromises() }
const pending = <T,>() => { let resolve!: (value: T) => void, reject!: (cause: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function context(entity: HistoryEntity = 'members', id = '5'): HistoryContext {
  return { entityType: entity === 'members' ? 'MEMBER' : entity === 'books' ? 'BOOK' : 'BORROW', entityId: id,
    identity: { memberId: entity === 'members' ? id : '5', username: 'reader_5', memberName: '合成会员', cardNo: '000005', bookId: entity === 'books' ? id : '7', bookTitle: '合成登记书名', isbn: '000ISBN', borrowId: entity === 'borrows' ? id : null, copyId: entity === 'borrows' ? '9' : null, barcode: entity === 'borrows' ? ' 00009 ' : null, status: 'RETURNED', borrowDate: '2026-10-01', dueDate: null, returnDate: '2026-10-02', dueDateKind: 'UNKNOWN' },
    provenance: { classification: 'IMPORTED', sourceSystem: 'huiben', sourceKey: ' 0005 ', associationState: 'VERIFIED', basis: ['按已保存来源编号关联，不按姓名匹配'], warnings: ['保留原始资料'] }, links: { memberId: '5', bookId: null, copyId: null, counterOrderId: null, memberArchiveId: '80' }, archiveTypes: [{ recordType: 'ORDER', total: 41 }], readOnly: true }
}
const record = (id = '81'): HistoryRecord => ({ id, sourceSystem: 'huiben', recordType: 'ORDER', sourceKey: ' 00081 ', displayName: '合成原订单', summary: '原状态：已取消', userId: '5', bookId: null, sourceTime: '仅来源原文', association: { scope: 'MEMBER_CONTEXT', state: 'UNVERIFIED', basis: '同会员原订单，未与本条借阅建立对应' } })
const detail = (id = '81', raw = '{"原状态":"已取消","条码":"00081","零值":0,"未设置":null,"启用":false,"嵌套":{"说明":"<script>unsafe</script>"}}'): HistoryRecordDetail => ({ ...record(id), detailsJson: raw })
const recordsPage = (records: HistoryRecord[] = [record()], page = 1) => ({ records, page, size: 20, pages: 3, total: 41 })

beforeEach(() => { vi.resetAllMocks(); Object.assign(user, { isAdmin: true, token: 'login-one', userInfo: { id: 1, role: 'ADMIN' } }); api.context.mockImplementation((entity, id) => Promise.resolve(context(entity, id))); api.records.mockResolvedValue(recordsPage()); api.record.mockResolvedValue(detail()) })
afterEach(() => wrappers.splice(0).forEach(w => w.unmount()))

it.each([{ isAdmin: false, role: 'LIBRARIAN', token: 'staff' }, { isAdmin: false, role: 'READER', token: 'reader' }, { isAdmin: true, role: 'ADMIN', token: '' }])('does not render or request source data without an active admin login: $role/$token', async ({ isAdmin, role, token }) => {
  Object.assign(user, { isAdmin, token, userInfo: { id: 1, role } }); const w = page(); await flushPromises()
  expect(w.find('aside').exists()).toBe(false); expect(api.context).not.toHaveBeenCalled(); expect(api.records).not.toHaveBeenCalled(); expect(api.record).not.toHaveBeenCalled()
})

it('loads on demand, shows stable identities and explicit provenance, and does not prefetch archive details', async () => {
  const w = page({ visible: false }); await flushPromises(); expect(api.context).not.toHaveBeenCalled()
  await w.setProps({ visible: true }); await flushPromises()
  expect(w.text()).toContain('会员编号：5'); expect(w.text()).toContain('000005'); expect(w.text()).toContain('迁入资料'); expect(w.text()).toContain('按已保存来源编号关联')
  expect(w.text()).toContain(' 0005 '); expect(w.text()).toContain('原状态：已取消'); expect(api.context).toHaveBeenCalledOnce(); expect(api.records).toHaveBeenCalledExactlyOnceWith('members', '5', { page: 1, size: 20, type: 'ALL' }, expect.any(AbortSignal)); expect(api.record).not.toHaveBeenCalled()
})

it('presents source keys/values and original state as safe text, while retaining the complete original', async () => {
  const raw = detail().detailsJson; const w = page(); await flushPromises(); await click(w, '查看原始内容')
  expect(w.text()).toContain('原字段浏览'); expect(w.get('.source-fields').text()).toContain('原状态'); expect(w.get('.source-fields').text()).toContain('已取消')
  expect(w.text()).toContain('JSON 数值投影0'); expect(w.text()).toContain('null 原值null'); expect(w.text()).toContain('布尔值false'); expect(w.text()).toContain('<script>unsafe</script>')
  expect(w.find('script').exists()).toBe(false); expect(w.get('.original-text').element.textContent).toBe(raw); expect(w.get('details summary').text()).toContain('完整原文')
  expect(api.record).toHaveBeenCalledExactlyOnceWith('members', '5', '81', expect.any(AbortSignal))
})

it('keeps malformed JSON readable and distinguishes empty data from read failures', async () => {
  api.record.mockResolvedValue(detail('81', '{<img src=x onerror=unsafe()> 原状态=退还'))
  const w = page(); await flushPromises(); await click(w, '查看原始内容')
  expect(w.text()).toContain('不是有效 JSON'); expect(w.get('.original-text').element.textContent).toBe('{<img src=x onerror=unsafe()> 原状态=退还'); expect(w.find('img').exists()).toBe(false)
  api.records.mockRejectedValueOnce(Error('lost')); w.findComponent(pagination).vm.$emit('current-change', 2); await flushPromises()
  expect(w.text()).toContain('读取失败不表示没有资料'); expect(w.text()).not.toContain('此范围暂无'); expect(w.find('.original-text').exists()).toBe(false)
  api.records.mockResolvedValueOnce(recordsPage([], 2)); await click(w, '重试本页资料'); expect(w.text()).toContain('此范围暂无相关来源档案；不表示历史业务未发生')
})

it('keeps page/filter per entity, resets pagination on filters and entity changes, and never requests all archives', async () => {
  const w = page(); await flushPromises(); api.records.mockResolvedValueOnce(recordsPage([record('82')], 2)); w.findComponent(pagination).vm.$emit('current-change', 2); await flushPromises()
  expect(api.records).toHaveBeenLastCalledWith('members', '5', { page: 2, size: 20, type: 'ALL' }, expect.any(AbortSignal))
  await w.get('select').setValue('PLAN'); await flushPromises(); expect(api.records).toHaveBeenLastCalledWith('members', '5', { page: 1, size: 20, type: 'PLAN' }, expect.any(AbortSignal))
  await w.setProps({ entityType: 'books', entityId: 7 }); await flushPromises(); expect(api.records).toHaveBeenLastCalledWith('books', '7', { page: 1, size: 20, type: 'ALL' }, expect.any(AbortSignal)); expect(w.get('select').element.value).toBe('ALL')
})

it('keeps the requested page when the actual paginator is waiting for its next bounded page', async () => {
  const w = mount(HistoryContextDrawer, { props: { visible: true, entityType: 'members', entityId: 5 }, global: { plugins: [ElementPlus], stubs: { ElDrawer: drawer, ElButton: button } } }); wrappers.push(w); await flushPromises()
  const next = pending<ReturnType<typeof recordsPage>>(); api.records.mockReturnValueOnce(next.promise); await w.get('.btn-next').trigger('click'); await flushPromises()
  expect(api.records).toHaveBeenLastCalledWith('members', '5', { page: 2, size: 20, type: 'ALL' }, expect.any(AbortSignal)); expect(w.text()).toContain('正在读取第 2 页')
  next.resolve(recordsPage([record('82')], 2)); await flushPromises(); expect(w.get('.el-pager .is-active').text()).toBe('2'); expect(api.records).toHaveBeenCalledTimes(2)
})

it('rejects late page responses after a filter change and clears selected details', async () => {
  const w = page(); await flushPromises(); await click(w, '查看原始内容')
  const old = pending<ReturnType<typeof recordsPage>>(); api.records.mockReturnValueOnce(old.promise); w.findComponent(pagination).vm.$emit('current-change', 2); await flushPromises()
  api.records.mockResolvedValueOnce(recordsPage([{ ...record('83'), recordType: 'MEMBER', displayName: '当前筛选资料' }])); await w.get('select').setValue('MEMBER'); await flushPromises()
  old.resolve(recordsPage([{ ...record('82'), displayName: '迟到旧页' }], 2)); await flushPromises()
  expect(w.text()).toContain('当前筛选资料'); expect(w.text()).not.toContain('迟到旧页'); expect(w.find('.original-text').exists()).toBe(false)
})

it('does not let a late detail or context response populate a switched entity or a closed drawer', async () => {
  const w = page(); await flushPromises(); const old = pending<HistoryRecordDetail>(); api.record.mockReturnValueOnce(old.promise); await click(w, '查看原始内容')
  await w.setProps({ entityId: 6 }); await flushPromises(); old.resolve(detail('81', '{"secret":"旧会员原文"}')); await flushPromises()
  expect(w.text()).toContain('会员编号：6'); expect(w.text()).not.toContain('旧会员原文'); expect(w.find('.original-text').exists()).toBe(false)
  const oldContext = pending<HistoryContext>(); api.context.mockReturnValueOnce(oldContext.promise); await w.setProps({ entityId: 7 }); await w.setProps({ visible: false }); oldContext.resolve(context('members', '7')); await flushPromises()
  expect(w.find('aside').exists()).toBe(false); await w.setProps({ visible: true }); await flushPromises(); expect(api.records).toHaveBeenLastCalledWith('members', '7', { page: 1, size: 20, type: 'ALL' }, expect.any(AbortSignal))
})

it.each(['identity', 'token', 'role'] as const)('clears prior source data on %s change and rejects late content', async kind => {
  const w = page(); await flushPromises(); const old = pending<HistoryRecordDetail>(); api.record.mockReturnValueOnce(old.promise); await click(w, '查看原始内容')
  if (kind === 'identity') user.userInfo = { id: 2, role: 'ADMIN' }
  if (kind === 'token') user.token = 'login-two'
  if (kind === 'role') { user.isAdmin = false; user.userInfo.role = 'LIBRARIAN' }
  await flushPromises(); old.resolve(detail('81', '{"secret":"旧登录私有资料"}')); await flushPromises()
  expect(w.text()).not.toContain('旧登录私有资料'); expect(w.text()).not.toContain('合成会员'); expect(w.emitted('update:visible')).toContainEqual([false]); expect(api.context).toHaveBeenCalledOnce()
})

it('clears and closes on current access rejection while ignoring an old login rejection', async () => {
  const old = pending<HistoryContext>(); api.context.mockReturnValueOnce(old.promise); const w = page(); user.token = 'login-two'; await flushPromises(); await w.setProps({ visible: false }); await w.setProps({ visible: true }); await flushPromises()
  old.reject(new HistoryReadError(403)); await flushPromises(); expect(w.text()).toContain('合成会员'); expect(api.tokens).toHaveBeenLastCalledWith('login-two')
  api.record.mockRejectedValueOnce(new HistoryReadError(403)); await click(w, '查看原始内容'); expect(w.find('aside').exists()).toBe(false); expect(w.emitted('update:visible')).toContainEqual([false])
})

it('identifies old same-member orders as unlinked to the borrow, and displays only the server counter link', async () => {
  api.context.mockResolvedValue({ ...context('borrows', '31'), provenance: { ...context().provenance, classification: 'COUNTER_ORDER' }, links: { ...context().links, counterOrderId: '99' } })
  const w = page({ entityType: 'borrows', entityId: 31 }); await flushPromises()
  expect(w.text()).toContain('新柜台订单'); expect(w.text()).toContain('新柜台订单编号 99'); expect(w.text()).toContain('同会员原订单，未与本条借阅建立对应'); expect(w.text()).toContain('不按姓名、手机号或日期推断关联')
  expect(w.text()).toContain(' 00009 '); expect(api.record).not.toHaveBeenCalled()
})

it('preserves unknown source type names as text and clears access immediately on an auth invalidation event', async () => {
  api.context.mockResolvedValue({ ...context(), archiveTypes: [{ recordType: '__proto__', total: 1 }, { recordType: '<img src=x>', total: 1 }] })
  api.records.mockResolvedValue(recordsPage([{ ...record(), recordType: '__proto__' }]))
  const w = page(); await flushPromises(); expect(w.text()).toContain('其他类型（原文：__proto__）'); expect(w.text()).toContain('<img src=x>'); expect(w.find('img').exists()).toBe(false)
  window.dispatchEvent(new Event('auth-access-invalidated')); await flushPromises(); expect(w.find('aside').exists()).toBe(false)
  await w.setProps({ visible: false }); await w.setProps({ visible: true }); await flushPromises(); expect(api.context).toHaveBeenCalledOnce()
})

it('shows an overview read error and retries without presenting stale data or an empty history claim', async () => {
  api.context.mockRejectedValueOnce(new HistoryReadError(404)); const w = page(); await flushPromises()
  expect(w.get('[role="alert"]').text()).toContain('读取失败'); expect(w.text()).not.toContain('此范围暂无'); expect(api.records).not.toHaveBeenCalled()
  await click(w, '重新读取历史关联'); expect(w.text()).toContain('合成会员')
})
