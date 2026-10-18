import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ book: vi.fn(), books: vi.fn(), preview: vi.fn(), confirm: vi.fn(), lookup: vi.fn(), user: {} as any }))
vi.mock('@/api/catalogPublication', async original => ({ ...await original<object>(), getPublicationBook: api.book, previewPublication: api.preview, confirmPublication: api.confirm, getPublicationRequestByKey: api.lookup }))
vi.mock('@/api/catalogAttribute', () => ({ getCatalogBooks: api.books }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import Dialog from './CatalogPublicationDialog.vue'
import { publicationBook, publicationInput, publicationReceipt, publicationRequest } from './publicationFixtures'
import { catalogBook } from '@/components/catalog/catalogFixtures'
const stubs = {
  ElDialog: { template: '<section><slot /><slot name="footer" /></section>' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' }, ElEmpty: { props: ['description'], template: '<p>{{ description }}</p>' },
}
const wrappers: ReturnType<typeof mount>[] = []
function page(props: { bookId?: number | null; batch?: boolean; intakeCompleted?: boolean } = { bookId: 7 }) { const wrapper = mount(Dialog, { props: { visible: true, ...props }, global: { stubs } }); wrappers.push(wrapper); return wrapper }
async function click(wrapper: ReturnType<typeof mount>, label: string, index = 0) { const button = wrapper.findAll('button').filter(item => item.text().trim() === label)[index]; expect(button).toBeTruthy(); await button!.trigger('click'); await flushPromises() }
async function change(wrapper: ReturnType<typeof mount>, id: number, field = '简介', value = '') { await wrapper.get(`select[aria-label="${id} ${field}操作"]`).setValue('SET'); await wrapper.get(`[aria-label="${id} ${field}值"]`).setValue(value); await wrapper.get(`input[aria-label="${id} 依据引用说明"]`).setValue(`合成版权资料 ${id}`) }
beforeEach(() => { vi.resetAllMocks(); sessionStorage.clear(); api.user = reactive({ isAdmin: true, isLibrarian: false, userInfo: { id: 2 } }); api.book.mockImplementation(id => Promise.resolve({ schemaVersion: 1, book: publicationBook(id) })); api.books.mockResolvedValue({ records: [catalogBook(7)], total: 21 }); api.preview.mockImplementation(value => Promise.resolve(publicationRequest(value))); api.confirm.mockImplementation((_id, key) => Promise.resolve(publicationReceipt(api.preview.mock.calls.find(call => call[0].operationKey === key)![0]))); api.lookup.mockRejectedValue(new Error('offline')) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
it('librarian can preview a single book with full NULL/empty/zero before-after, then emit one saved refresh', async () => {
  api.user.isAdmin = false; api.user.isLibrarian = true
  const wrapper = page(); await flushPromises(); await change(wrapper, 7); await wrapper.get('input[aria-label="出版补录原因"]').setValue('单书合成核对'); await click(wrapper, '预览出版资料变更')
  expect(api.books).not.toHaveBeenCalled(); expect(api.preview.mock.calls[0]![0]).toMatchObject({ entries: [{ bookId: 7, changes: { publishDate: null, pages: null, description: { action: 'SET', value: '' } }, evidence: { kind: 'MANUAL_REVIEW', reference: '合成版权资料 7' } }] })
  expect(wrapper.text()).toContain('NULL（未知 / 未设置）'); expect(wrapper.text()).toContain('""（明确空串）'); expect(wrapper.text()).toContain('页数保持原值00'); expect(wrapper.text()).toContain('馆员/管理员填写的依据')
  await click(wrapper, '确认出版资料变更'); expect(wrapper.text()).toContain('出版资料已保存'); expect(wrapper.emitted('success')).toHaveLength(1)
})
it('admin selection is explicit across search pages and each row retains independent fields and evidence', async () => {
  api.books.mockResolvedValueOnce({ records: [catalogBook(8)], total: 21 }).mockResolvedValueOnce({ records: [catalogBook(7)], total: 21 })
  const wrapper = page({ batch: true }); await flushPromises(); expect(api.book).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('已明确选择 0 种书目')
  await click(wrapper, '选择书目'); await change(wrapper, 8, '页数', '0'); await wrapper.get('select[aria-label="8 依据类型"]').setValue('COPYRIGHT_PAGE')
  await click(wrapper, '选书下一页'); expect(api.books).toHaveBeenLastCalledWith({ keyword: undefined, page: 2, size: 20 }); await click(wrapper, '选择书目'); await change(wrapper, 7, '出版日期', '2024-02-29'); await wrapper.get('select[aria-label="7 简介操作"]').setValue('CLEAR')
  await wrapper.get('input[aria-label="出版补录原因"]').setValue('两种书目分别核实'); await click(wrapper, '预览出版资料变更')
  const input = api.preview.mock.calls[0]![0]; expect(input.entries.map((entry: any) => entry.bookId)).toEqual([7, 8]); expect(input.entries[1].changes.pages).toEqual({ action: 'SET', value: 0 }); expect(input.entries[0].changes.publishDate).toEqual({ action: 'SET', value: '2024-02-29' }); expect(input.entries[0].changes.description).toEqual({ action: 'CLEAR' }); expect(input.entries[1].evidence.kind).toBe('COPYRIGHT_PAGE')
  expect(wrapper.text()).toContain('无变化回执'); expect(wrapper.text()).toContain('#7'); expect(wrapper.text()).toContain('#8'); expect(wrapper.find('fieldset').exists()).toBe(false)
  await click(wrapper, '确认出版资料变更'); expect(wrapper.emitted('success')).toHaveLength(1)
})
it('requires every selected row own operation/reference and rejects partial or impossible dates before sending', async () => {
  const wrapper = page(); await flushPromises(); await wrapper.get('input[aria-label="出版补录原因"]').setValue('合成')
  await click(wrapper, '预览出版资料变更'); expect(wrapper.text()).toContain('至少一个字段操作')
  await wrapper.get('select[aria-label="7 出版日期操作"]').setValue('SET'); await wrapper.get('input[aria-label="7 出版日期值"]').setValue('2024-02'); await click(wrapper, '预览出版资料变更'); expect(wrapper.text()).toContain('完整日期 YYYY-MM-DD')
  await wrapper.get('input[aria-label="7 出版日期值"]').setValue('2024-02-29'); await click(wrapper, '预览出版资料变更'); expect(wrapper.text()).toContain('依据引用说明'); expect(api.preview).not.toHaveBeenCalled()
})
it('a reader or librarian batch entry sends no management API and hides all editable controls', async () => {
  api.user.isAdmin = false; let wrapper = page({ batch: true }); await flushPromises(); expect(api.book).not.toHaveBeenCalled(); expect(api.books).not.toHaveBeenCalled(); expect(wrapper.find('fieldset').exists()).toBe(false)
  api.user.isLibrarian = false; wrapper = page(); await flushPromises(); expect(api.book).not.toHaveBeenCalled(); expect(api.lookup).not.toHaveBeenCalled()
})
it('does not substitute the current row for an unresolved original batch, and owner switch suppresses late reads', async () => {
  const original = publicationInput([7, 8]); sessionStorage.setItem('catalog-publications.pending.v1.2', JSON.stringify({ storageVersion: 1, ownerId: 2, input: original, view: null, confirming: true }))
  const wrapper = page({ bookId: 9 }); await flushPromises(); expect(api.book).not.toHaveBeenCalled(); expect(api.lookup).toHaveBeenCalledWith(original.operationKey); expect(wrapper.text()).toContain('原请求书目 #7、#8'); expect(wrapper.find('fieldset').exists()).toBe(false)
  api.user.userInfo.id = 3; let finish!: (result: any) => void; api.book.mockReturnValueOnce(new Promise(resolve => { finish = resolve })); await flushPromises(); api.user.isAdmin = false; api.user.isLibrarian = false; finish({ schemaVersion: 1, book: publicationBook(9) }); await flushPromises(); expect(wrapper.text()).not.toContain('展示名9'); expect(wrapper.text()).toContain('无出版资料管理权限')
})
it('retains local inputs after an explicit conflict and refreshes the version before another preview', async () => {
  api.preview.mockRejectedValueOnce({ response: { status: 409, data: { message: '原值变化', data: { reasonCode: 'PUBLICATION_CHANGED' } } } })
  const wrapper = page(); await flushPromises(); await change(wrapper, 7, '简介', '保留原文\n\t😀'); await wrapper.get('input[aria-label="出版补录原因"]').setValue('合成核实'); await click(wrapper, '预览出版资料变更'); api.book.mockResolvedValue({ schemaVersion: 1, book: { ...publicationBook(), revision: 3 } })
  await click(wrapper, '返回修改本地输入'); expect((wrapper.get('textarea[aria-label="7 简介值"]').element as HTMLTextAreaElement).value).toBe('保留原文\n\t😀'); expect(wrapper.text()).toContain('修订 3')
})
it('stops at 100 explicit choices and never converts search results into selection', async () => {
  api.books.mockResolvedValue({ records: Array.from({ length: 101 }, (_, index) => catalogBook(index + 1)), total: 101 })
  const wrapper = page({ batch: true }); await flushPromises(); const buttons = wrapper.findAll('button').filter(item => item.text() === '选择书目'); expect(buttons).toHaveLength(101)
  // Attempt every explicit click while reads are pending; batch DOM updates and exercise the pending cap.
  await Promise.all(buttons.map(button => button.trigger('click'))); await flushPromises()
  expect(wrapper.text()).toContain('已明确选择 100 种书目'); expect(buttons[100]!.attributes('disabled')).toBeDefined(); expect(api.book).toHaveBeenCalledTimes(100)
})
it.each(['\n', '\t'])('rejects pasted %j in either single-line field with an explicit error before sending', async control => {
  const wrapper = page(); await flushPromises(); await change(wrapper, 7, '简介', '简介第一行\n第二行\t😀')
  await wrapper.get('input[aria-label="出版补录原因"]').setValue('普通单行原因')
  for (const label of ['出版补录原因', '7 依据引用说明']) {
    const field = wrapper.get(`input[aria-label="${label}"]`), oldValue = (field.element as HTMLInputElement).value
    const paste = new Event('paste', { bubbles: true, cancelable: true }); Object.defineProperty(paste, 'clipboardData', { value: { getData: () => `原文${control}内容` } })
    field.element.dispatchEvent(paste); await flushPromises()
    expect(paste.defaultPrevented).toBe(true); expect((field.element as HTMLInputElement).value).toBe(oldValue)
    expect(wrapper.get('[role="alert"]').text()).toContain(label === '出版补录原因' ? '办理原因须为单行' : '依据引用说明须为单行')
    expect(api.preview).not.toHaveBeenCalled()
  }
})
it('validates tab characters already present in either reason or reference before creating a request', async () => {
  const wrapper = page(); await flushPromises(); await change(wrapper, 7)
  await wrapper.get('input[aria-label="出版补录原因"]').setValue('原因\t原文'); await click(wrapper, '预览出版资料变更'); expect(wrapper.text()).toContain('单行办理原因，不能含换行、制表'); expect(api.preview).not.toHaveBeenCalled()
  await wrapper.get('input[aria-label="出版补录原因"]').setValue('普通单行原因'); await wrapper.get('input[aria-label="7 依据引用说明"]').setValue('出处\t原文'); await click(wrapper, '预览出版资料变更'); expect(wrapper.text()).toContain('单行依据引用说明，不能含换行、制表'); expect(api.preview).not.toHaveBeenCalled()
})
it('accepts normal nonempty single-line reason/reference and preserves a multiline description in the preview request', async () => {
  const wrapper = page(); await flushPromises(); await change(wrapper, 7, '简介', '简介第一行\n第二行\t😀'); await wrapper.get('input[aria-label="出版补录原因"]').setValue('普通单行原因'); await click(wrapper, '预览出版资料变更')
  expect(api.preview).toHaveBeenCalledOnce(); expect(api.preview.mock.calls[0]![0]).toMatchObject({ reason: '普通单行原因', entries: [{ changes: { description: { action: 'SET', value: '简介第一行\n第二行\t😀' } }, evidence: { reference: '合成版权资料 7' } }] })
})
