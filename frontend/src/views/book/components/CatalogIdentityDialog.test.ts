import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ book: vi.fn(), publishers: vi.fn(), categories: vi.fn(), preview: vi.fn(), confirm: vi.fn(), lookup: vi.fn(), user: {} as any }))
vi.mock('@/api/catalogIdentity', async original => ({ ...await original<object>(), getCatalogIdentityBook: api.book, getCatalogIdentityPublishers: api.publishers, getCatalogIdentityCategories: api.categories, previewCatalogIdentity: api.preview, confirmCatalogIdentity: api.confirm, getCatalogIdentityRequestByKey: api.lookup }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import Dialog from './CatalogIdentityDialog.vue'
import { identityBook, identityCategory, identityInput, identityRef, identityRequest } from './catalogIdentityFixtures'
import backendContract from '@/components/catalog/fixtures/catalogIdentity.backend.json'
const stubs = {
  ElDialog: { template: '<div><slot /><slot name="footer" /></div>' },
  ElInput: { props: ['modelValue', 'placeholder', 'disabled', 'type'], emits: ['update:modelValue'], template: '<textarea v-if="type === \'textarea\'" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" /><input v-else :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' }, ElAlert: { props: ['title'], template: '<p role="alert">{{ title }}</p>' }, ElEmpty: { props: ['description'], template: '<p>{{ description }}</p>' },
}
const wrappers: ReturnType<typeof mount>[] = []
function page(bookId = 7) { const wrapper = mount(Dialog, { props: { visible: true, bookId }, global: { stubs } }); wrappers.push(wrapper); return wrapper }
async function click(wrapper: ReturnType<typeof mount>, label: string) { const button = wrapper.findAll('button').find(item => item.text() === label); expect(button).toBeTruthy(); await button!.trigger('click'); await flushPromises() }
async function reason(wrapper: ReturnType<typeof mount>) { await wrapper.get('textarea[placeholder="身份核实依据（1–500 字）"]').setValue('合成馆藏目录明确核实') }
beforeEach(() => { vi.clearAllMocks(); sessionStorage.clear(); api.user = reactive({ isAdmin: true, isLibrarian: false, userInfo: { id: 2 } }); api.book.mockResolvedValue(identityBook()); api.publishers.mockResolvedValue({ records: [identityRef(8, '出版社八')], total: 1, page: 1, size: 50, pages: 1 }); api.categories.mockResolvedValue({ records: [identityCategory(11)], total: 1, page: 1, size: 50, pages: 1 }); api.preview.mockImplementation(value => Promise.resolve(identityRequest(value))) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))

it('librarian sees effective identities and history without requesting writable lists or by-key', async () => {
  api.user.isAdmin = false; api.user.isLibrarian = true; const wrapper = page(); await flushPromises(); expect(api.book).toHaveBeenCalledWith(7); expect(api.publishers).not.toHaveBeenCalled(); expect(api.categories).not.toHaveBeenCalled(); expect(api.lookup).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('历史登记分类'); expect(wrapper.text()).toContain('馆员只读'); expect(wrapper.find('fieldset').exists()).toBe(false)
})
it('reader and a late role change cause no management reads or recovery', async () => {
  api.user.isAdmin = false; const wrapper = page(); await flushPromises(); expect(api.book).not.toHaveBeenCalled(); expect(api.publishers).not.toHaveBeenCalled(); api.user.isAdmin = true; await flushPromises(); expect(api.book).toHaveBeenCalledOnce(); api.user.isAdmin = false; await flushPromises(); api.book.mockClear(); api.publishers.mockClear(); api.categories.mockClear(); api.lookup.mockClear(); await wrapper.setProps({ bookId: 8 }); await flushPromises(); expect(api.book).not.toHaveBeenCalled(); expect(api.publishers).not.toHaveBeenCalled(); expect(api.lookup).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('当前账号无身份资料权限')
})
it('loads publishers unfiltered by legacy raw text, sends publisher-only complete changes and keeps candidate ID separate', async () => {
  const wrapper = page(); await flushPromises(); expect(api.publishers).toHaveBeenCalledWith({ keyword: undefined, page: 1, size: 50 }); expect(wrapper.text()).toContain('审阅 PENDING'); expect(wrapper.find('option[value="900"]').exists()).toBe(false)
  await wrapper.get('select[aria-label="出版社操作"]').setValue('LINK'); await wrapper.get('select[aria-label="出版社身份"]').setValue('8'); await reason(wrapper); await click(wrapper, '预览出版社/分类身份')
  expect(api.preview.mock.calls[0]![0]).toMatchObject({ mode: 'MANUAL', sourceDigest: null, changes: { publisher: { action: 'LINK', publisherId: 8 }, themes: null, aliases: [], categoryDimensions: [] } }); expect(wrapper.text()).toContain('确认身份变更')
})
it('retries failed lists and sends explicit publisher keyword and bounded page size', async () => {
  api.publishers.mockRejectedValueOnce(new Error('offline')); const wrapper = page(); await flushPromises(); expect(wrapper.text()).toContain('出版社列表读取失败'); await click(wrapper, '重试出版社列表'); expect(api.publishers).toHaveBeenCalledTimes(2); await wrapper.get('input[placeholder="搜索出版社规范名称"]').setValue('出版集团'); await click(wrapper, '搜索出版社'); expect(api.publishers).toHaveBeenLastCalledWith({ keyword: '出版集团', page: 1, size: 50 })
})
it('uses bounded server pagination and keeps an explicitly selected publisher after changing pages', async () => {
  api.publishers.mockResolvedValueOnce({ records: [identityRef(8, '出版社八')], total: 51, page: 1, size: 50, pages: 2 }).mockResolvedValueOnce({ records: [identityRef(9, '出版社九')], total: 51, page: 2, size: 50, pages: 2 })
  const wrapper = page(); await flushPromises(); await wrapper.get('select[aria-label="出版社操作"]').setValue('LINK'); await wrapper.get('select[aria-label="出版社身份"]').setValue('8'); await click(wrapper, '出版社下一页'); expect(api.publishers).toHaveBeenLastCalledWith({ keyword: undefined, page: 2, size: 50 }); expect((wrapper.get('select[aria-label="出版社身份"]').element as HTMLSelectElement).value).toBe('8')
})
it('sends a theme-only operation with complete null/empty keys for an already verified target', async () => {
  api.categories.mockResolvedValue({ records: [identityCategory(11, 'TOPIC')], total: 1, page: 1, size: 50, pages: 1 }); const wrapper = page(); await flushPromises(); await wrapper.get('select[aria-label="主题操作"]').setValue('REPLACE'); await wrapper.get('input[aria-label="关联主题 11"]').setValue(true); await reason(wrapper); await click(wrapper, '预览出版社/分类身份'); expect(api.preview.mock.calls[0]![0].changes).toEqual({ publisher: null, themes: { action: 'REPLACE', categoryIds: [11] }, aliases: [], categoryDimensions: [] })
})
it('retains an existing effective theme from page two and adds a page-one theme without fetching page two', async () => {
  const existing = JSON.parse(JSON.stringify(backendContract.themeReceipt.book.identity.themes[0]))
  api.book.mockResolvedValue({ ...identityBook(), themes: [existing] })
  api.categories.mockResolvedValue({ records: [identityCategory(11, 'TOPIC'), ...Array.from({ length: 49 }, (_, index) => identityCategory(100 + index, 'TOPIC'))], total: 51, page: 1, size: 50, pages: 2 })
  const wrapper = page(); await flushPromises()
  expect(api.categories).toHaveBeenCalledOnce(); expect(api.categories).toHaveBeenCalledWith({ keyword: undefined, page: 1, size: 50 })
  expect((wrapper.get('input[aria-label="关联主题 18"]').element as HTMLInputElement).checked).toBe(true)
  expect(wrapper.find('select[aria-label="核实分类维度 17"]').exists()).toBe(true)
  await wrapper.get('select[aria-label="主题操作"]').setValue('REPLACE'); await wrapper.get('input[aria-label="关联主题 11"]').setValue(true); await reason(wrapper); await click(wrapper, '预览出版社/分类身份')
  expect(api.preview).toHaveBeenCalledOnce(); expect(api.preview.mock.calls[0]![0].changes).toEqual({ publisher: null, themes: { action: 'REPLACE', categoryIds: [11, 18] }, aliases: [], categoryDimensions: [] })
  expect(api.categories).toHaveBeenCalledOnce()
})
it('does not infer an effective theme parent chain when the server did not provide it', async () => {
  const existing = { ...identityCategory(18, 'TOPIC'), parentChainActive: undefined, parentChain: undefined }
  api.book.mockResolvedValue({ ...identityBook(), themes: [existing] }); api.categories.mockResolvedValue({ records: [identityCategory(11, 'TOPIC')], total: 51, page: 1, size: 50, pages: 2 })
  const wrapper = page(); await flushPromises(); await wrapper.get('select[aria-label="主题操作"]').setValue('REPLACE'); await wrapper.get('input[aria-label="关联主题 11"]').setValue(true); await reason(wrapper); await click(wrapper, '预览出版社/分类身份'); expect(api.preview).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('分类 #18 须明确核实为主题且完整父链启用')
})
it('keeps a disabled or incomplete parent chain unavailable for new themes', async () => {
  api.categories.mockResolvedValue({ records: [{ ...identityCategory(11, 'TOPIC'), parentChainActive: false }], total: 1, page: 1, size: 50, pages: 1 }); const wrapper = page(); await flushPromises(); await wrapper.get('select[aria-label="主题操作"]').setValue('REPLACE'); expect(wrapper.get('input[aria-label="关联主题 11"]').attributes('disabled')).toBeDefined(); expect(wrapper.text()).toContain('父链不可用或未读取')
})
it('does not interpret legacy category as a topic and allows same-request explicit dimension verification', async () => {
  const wrapper = page(); await flushPromises(); expect(wrapper.text()).toContain('不解释为主题或年龄'); await wrapper.get('select[aria-label="主题操作"]').setValue('REPLACE'); expect(wrapper.get('input[aria-label="关联主题 11"]').attributes('disabled')).toBeDefined(); await wrapper.get('select[aria-label="核实分类维度 11"]').setValue('TOPIC'); await wrapper.get('input[aria-label="关联主题 11"]').setValue(true); await reason(wrapper); await click(wrapper, '预览出版社/分类身份'); expect(api.preview.mock.calls[0]![0].changes).toEqual({ publisher: null, themes: { action: 'REPLACE', categoryIds: [11] }, aliases: [], categoryDimensions: [{ categoryId: 11, dimension: 'TOPIC' }] })
})
it('shows parent paths and requires explicit TOPIC for all ancestors', async () => {
  const parent = identityCategory(10), child = { ...identityCategory(11), parentId: 10, path: '文学 / 绘本', parentChain: [parent, { ...identityRef(11, '绘本'), dimension: 'UNVERIFIED' }] }; api.categories.mockResolvedValue({ records: [child], total: 1, page: 1, size: 50, pages: 1 }); const wrapper = page(); await flushPromises(); expect(wrapper.text()).toContain('文学 / 绘本'); await wrapper.get('select[aria-label="主题操作"]').setValue('REPLACE'); await wrapper.get('select[aria-label="核实分类维度 11"]').setValue('TOPIC'); expect(wrapper.get('input[aria-label="关联主题 11"]').attributes('disabled')).toBeDefined(); await wrapper.get('select[aria-label="核实分类维度 10"]').setValue('TOPIC'); expect(wrapper.get('input[aria-label="关联主题 11"]').attributes('disabled')).toBeUndefined()
})
it('previews alias-only change with stable owner, action, raw name, and before/after evidence', async () => {
  const wrapper = page(); await flushPromises(); await wrapper.get('select[aria-label="别名归属"]').setValue('8'); await wrapper.get('input[placeholder="别名原文（1–200字）"]').setValue(' 旧名 '); await reason(wrapper); await click(wrapper, '预览出版社/分类身份'); expect(api.preview.mock.calls[0]![0].changes).toEqual({ publisher: null, themes: null, aliases: [{ kind: 'PUBLISHER', ownerId: 8, action: 'ADD', name: ' 旧名 ' }], categoryDimensions: [] }); expect(wrapper.text()).toContain('出版社 #8 出版社八'); expect(wrapper.text()).toContain('" 旧名 "'); expect(wrapper.text()).toContain('变更前全部别名'); expect(wrapper.text()).toContain('确认后全部别名')
})
it('applies the backend category-alias limit before sending a request', async () => {
  const wrapper = page(); await flushPromises(); await wrapper.get('select[aria-label="别名类型"]').setValue('CATEGORY'); await wrapper.get('select[aria-label="别名归属"]').setValue('11'); await wrapper.get('input[placeholder="别名原文（1–100字）"]').setValue('别'.repeat(101)); await reason(wrapper); await click(wrapper, '预览出版社/分类身份'); expect(api.preview).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('1–100 字有效原文')
})
it('uses explicit解除关联 wording and sends a null target', async () => {
  const wrapper = page(); await flushPromises(); await wrapper.get('select[aria-label="出版社操作"]').setValue('CLEAR'); await reason(wrapper); await click(wrapper, '预览出版社/分类身份'); expect(api.preview.mock.calls[0]![0].changes.publisher).toEqual({ action: 'CLEAR', publisherId: null }); expect(wrapper.text()).toContain('明确解除出版社关联')
})
it('restores another book request under its original identity and blocks a new key', async () => {
  const pending = identityInput(); pending.bookId = 6; sessionStorage.setItem('catalog-identities.pending.v1.2', JSON.stringify({ storageVersion: 1, ownerId: 2, input: pending, confirming: true })); api.lookup.mockRejectedValue(new Error('offline')); const wrapper = page(7); await flushPromises(); expect(wrapper.text()).toContain('正在恢复书目 #6'); expect(wrapper.text()).toContain('本请求始终属于书目 #6'); expect(wrapper.find('fieldset').exists()).toBe(false); expect(api.book).not.toHaveBeenCalled(); expect(api.preview).not.toHaveBeenCalled(); expect(api.lookup).toHaveBeenCalledWith(pending.operationKey)
})
