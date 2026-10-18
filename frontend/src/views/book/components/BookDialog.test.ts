import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import type { IntakePreviewInput } from '@/api/inventoryIntake'

const api = vi.hoisted(() => ({ update: vi.fn(), detail: vi.fn(), upload: vi.fn(), categories: vi.fn(), preview: vi.fn(), user: { isAdmin: true, isLibrarian: false } }))
vi.mock('@/api/book', () => ({ updateBookMetadata: api.update, getBookMetadata: api.detail, uploadBookCover: api.upload }))
vi.mock('@/api/category', () => ({ getCategoryList: api.categories }))
vi.mock('@/api/inventoryIntake', () => ({ previewIntake: api.preview, confirmIntake: vi.fn(), getIntake: vi.fn(), getIntakeByKey: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import BookDialog from './BookDialog.vue'

const wrappers: ReturnType<typeof mount>[] = []
const select = defineComponent({ props: ['modelValue'], emits: ['update:modelValue'], setup(props, { slots, emit }) {
  return () => h('select', { value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', Number((event.target as HTMLSelectElement).value)) }, [h('option', { value: '' }, '选择分类'), slots.default?.()])
} })
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
const intake = defineComponent({ props: ['visible', 'newBook'], emits: ['success', 'update:visible'], setup(props, { emit }) {
  return () => props.visible ? h('section', { 'data-test': 'new-intake' }, [props.newBook?.title, h('button', { 'data-test': 'receipt', onClick: () => emit('success', { copies: [{ bookId: 10 }] }) }, '合成成功回执')]) : null
} })
const digest = 'a'.repeat(64)
const book = { id: 10, isbn: '9781234567890', title: '已有图书', categoryId: 7, stockTotal: 5, location: 'A区-3层-01架' }
async function dialog(bookId: number | null = null, realIntake = false) {
  const wrapper = mount(BookDialog, { props: { visible: false, bookId }, attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, ElSelect: select, ElOption: option, BookAttributeDialog: true, CatalogPublicationDialog: true, ...(realIntake ? {} : { IntakeDialog: intake }) } } })
  wrappers.push(wrapper); await wrapper.setProps({ visible: true }); await flushPromises(); return wrapper
}
async function required(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[placeholder="请输入ISBN"]').setValue('9781234567890')
  await wrapper.get('input[placeholder="请输入书名"]').setValue('测试图书')
  await wrapper.get('select').setValue('7')
}
async function submit(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find(button => ['下一步：登记实体册', '保存书目资料'].includes(button.text().trim()))!.trigger('click'); await flushPromises()
}
async function previewNewBook(wrapper: ReturnType<typeof mount>) {
  await submit(wrapper)
  await wrapper.get('textarea[placeholder="本次入库来源或实收依据（必填）"]').setValue('合成新书首批实收')
  await wrapper.get('input[placeholder="扫描或输入已有条码，保留原文"]').setValue('00009')
  await wrapper.findAll('button').find(button => button.text().trim() === '预览本次入库')!.trigger('click'); await flushPromises()
  return JSON.parse(JSON.stringify(api.preview.mock.calls[0]![0] as IntakePreviewInput)).lines[0].target.newBook
}
beforeEach(() => {
  vi.clearAllMocks(); Object.assign(api.user, { isAdmin: true, isLibrarian: false })
  api.update.mockResolvedValue({ book, metadataDigest: digest }); api.categories.mockResolvedValue([{ id: 7, name: '测试分类' }])
  api.detail.mockResolvedValue({ book, metadataDigest: digest })
  api.preview.mockImplementation(async (data: IntakePreviewInput) => ({ intakeId: 21, state: 'PREVIEWED', previewDigest: digest,
    expiresAt: new Date(Date.now() + 900000).toISOString(), source: data.source, lines: [], books: [], purchaseProgress: [], warnings: [], receipt: null }))
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

it('validates new metadata and proceeds to the shared intake dialog without creating a book', async () => {
  const wrapper = await dialog(); await required(wrapper); await submit(wrapper)
  expect(wrapper.find('[data-test="new-intake"]').exists()).toBe(true)
  expect(wrapper.findComponent(intake).props('newBook')).toEqual(expect.objectContaining({ title: '测试图书', categoryId: 7 }))
  expect(wrapper.findComponent(intake).props('newBook')).not.toHaveProperty('stockTotal')
  expect(api.update).not.toHaveBeenCalled(); expect(wrapper.emitted('success')).toBeUndefined()
})
it('sends null optional date and numbers through the real new-book intake preview instead of empty date or invented zero', async () => {
  const wrapper = await dialog(null, true); await required(wrapper)
  const metadata = await previewNewBook(wrapper)
  expect(metadata).toEqual(expect.objectContaining({ isbn: '9781234567890', title: '测试图书', categoryId: 7, publishDate: null, price: null, pages: null, status: 1 }))
  expect(metadata).not.toHaveProperty('stockTotal')
})
it('retains a valid publication date and numeric zero while emitting integer pages to the intake contract', async () => {
  const wrapper = await dialog(null, true); await required(wrapper)
  wrapper.findComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', '2020-02-29')
  const numbers = wrapper.findAllComponents({ name: 'ElInputNumber' })
  await numbers[0]!.get('input').setValue('0'); await numbers[0]!.get('input').trigger('change')
  await numbers[1]!.get('input').setValue('12.5'); await numbers[1]!.get('input').trigger('change')
  const metadata = await previewNewBook(wrapper)
  expect(metadata.publishDate).toBe('2020-02-29'); expect(metadata.price).toBe(0)
  expect(typeof metadata.pages).toBe('number'); expect(Number.isInteger(metadata.pages)).toBe(true)
})
it.each(['', null])('clearing the publication date with %s sends only explicit null and preserves untouched zero values', async cleared => {
  api.detail.mockResolvedValue({ book: { ...book, publishDate: '2020-02-29', price: 0, pages: 0 }, metadataDigest: digest })
  const wrapper = await dialog(10)
  wrapper.findComponent({ name: 'ElDatePicker' }).vm.$emit('update:modelValue', cleared); await submit(wrapper)
  expect(api.update).toHaveBeenCalledWith(10, digest, { publishDate: null })
})
it('clearing optional numeric inputs sends explicit null rather than strings or zeros', async () => {
  api.detail.mockResolvedValue({ book: { ...book, publishDate: null, price: 9.99, pages: 12 }, metadataDigest: digest })
  const wrapper = await dialog(10), numbers = wrapper.findAllComponents({ name: 'ElInputNumber' })
  for (const field of numbers) { await field.get('input').setValue(''); await field.get('input').trigger('change') }
  await submit(wrapper)
  expect(api.update).toHaveBeenCalledWith(10, digest, { price: null, pages: null })
})
it('saves only explicit metadata changes with the original digest', async () => {
  const wrapper = await dialog(10)
  await wrapper.get('input[placeholder="未设置，选填，例如：A区-3层-01架"]').setValue(''); await submit(wrapper)
  expect(api.update).toHaveBeenCalledWith(10, digest, { location: '' })
  expect(wrapper.emitted('success')).toHaveLength(1)
  expect(wrapper.find('input[aria-label="总库存"]').exists()).toBe(false)
})
it('unchanged metadata completes without an empty PUT or filling existing NULL/zero', async () => {
  api.detail.mockResolvedValue({ book: { ...book, pages: 0, price: 0, location: null, publishDate: null, description: null }, metadataDigest: digest })
  const wrapper = await dialog(10); await submit(wrapper)
  expect(api.update).not.toHaveBeenCalled(); expect(api.upload).not.toHaveBeenCalled(); expect(wrapper.emitted('success')).toHaveLength(1)
})
it('a cover-only edit uploads independently without an empty metadata PUT', async () => {
  const originalUrl = URL.createObjectURL; URL.createObjectURL = vi.fn(() => 'blob:synthetic-cover')
  try {
    api.upload.mockResolvedValue('/uploads/synthetic-cover.png')
    const wrapper = await dialog(10), file = wrapper.get('input[type="file"]')
    Object.defineProperty(file.element, 'files', { value: [new File(['synthetic'], 'cover.png', { type: 'image/png' })] })
    await file.trigger('change'); await submit(wrapper)
    expect(api.update).not.toHaveBeenCalled(); expect(api.upload).toHaveBeenCalledTimes(1)
    expect(api.upload.mock.calls[0]![0]).toBe(10); expect(wrapper.emitted('success')).toHaveLength(1)
  } finally { URL.createObjectURL = originalUrl }
})
it('retains rejected metadata input and the original digest for a corrected retry', async () => {
  api.update.mockRejectedValueOnce(new Error('位置不合法'))
  const wrapper = await dialog(10)
  await wrapper.get('input[placeholder="未设置，选填，例如：A区-3层-01架"]').setValue('<script>'); await submit(wrapper)
  expect(wrapper.emitted('success')).toBeUndefined(); expect(wrapper.emitted('update:visible')).toBeUndefined()
  expect((wrapper.get('input[placeholder="未设置，选填，例如：A区-3层-01架"]').element as HTMLInputElement).value).toBe('<script>')
  await wrapper.get('input[placeholder="未设置，选填，例如：A区-3层-01架"]').setValue(''); await submit(wrapper)
  expect(api.update).toHaveBeenNthCalledWith(2, 10, digest, { location: '' })
})
it('blocks missing required metadata before entering intake', async () => {
  const wrapper = await dialog(); await submit(wrapper)
  expect(wrapper.find('[data-test="new-intake"]').exists()).toBe(false)
  expect(wrapper.find('.el-form-item.is-error').exists()).toBe(true)
})
it('retains zero and null metadata and never sends inventory fields', async () => {
  api.detail.mockResolvedValue({ book: { ...book, stockTotal: null, pages: 0, price: 0, location: null, publishDate: null, description: null }, metadataDigest: digest })
  const wrapper = await dialog(10)
  await wrapper.get('input[placeholder="请输入书名"]').setValue('只改书名'); await submit(wrapper)
  expect(api.update).toHaveBeenCalledWith(10, digest, { title: '只改书名' })
})
it('a failed metadata load cannot save an empty replacement', async () => {
  api.detail.mockRejectedValue(new Error('不可用'))
  const wrapper = await dialog(10); await submit(wrapper)
  expect(api.update).not.toHaveBeenCalled(); expect(wrapper.text()).toContain('书目资料读取失败')
})
it('cover failure after a receipt keeps successful intake separate and never restarts creation', async () => {
  const originalUrl = URL.createObjectURL; URL.createObjectURL = vi.fn(() => 'blob:synthetic-cover')
  try {
    api.upload.mockRejectedValueOnce(new Error('合成上传失败'))
    const wrapper = await dialog(); await required(wrapper)
    const file = wrapper.get('input[type="file"]')
    Object.defineProperty(file.element, 'files', { value: [new File(['synthetic'], 'cover.png', { type: 'image/png' })] })
    await file.trigger('change'); await submit(wrapper)
    await wrapper.get('[data-test="receipt"]').trigger('click'); await flushPromises()
    expect(api.upload).toHaveBeenCalledTimes(1); expect(api.upload.mock.calls[0]![0]).toBe(10)
    expect(api.update).not.toHaveBeenCalled(); expect(wrapper.emitted('success')).toHaveLength(1)
    expect(wrapper.find('[data-test="new-intake"]').exists()).toBe(true)
  } finally { URL.createObjectURL = originalUrl }
})
it('makes no management requests when a reader opens the form', async () => {
  Object.assign(api.user, { isAdmin: false, isLibrarian: false }); await dialog(10)
  expect(api.detail).not.toHaveBeenCalled(); expect(api.categories).not.toHaveBeenCalled(); expect(api.update).not.toHaveBeenCalled()
})
it('keeps new-book intake success final when the independent attribute step fails or is reopened', async () => {
  const wrapper = await dialog(); await required(wrapper); await submit(wrapper)
  await wrapper.get('[data-test="receipt"]').trigger('click'); await flushPromises()
  wrapper.findComponent(intake).vm.$emit('update:visible', false); await flushPromises()
  expect(wrapper.text()).toContain('入库已完成，属性待补充')
  expect(wrapper.findAll('button').some(button => button.text().trim() === '下一步：登记实体册')).toBe(false)
  await wrapper.findAll('button').find(button => button.text().trim() === '独立编辑书目属性')!.trigger('click'); await flushPromises()
  const attributes = wrapper.findComponent({ name: 'BookAttributeDialog' })
  expect(attributes.props('bookId')).toBe(10); expect(attributes.props('intakeCompleted')).toBe(true)
  attributes.vm.$emit('update:visible', false); await flushPromises()
  await wrapper.findAll('button').find(button => button.text().trim() === '独立编辑书目属性')!.trigger('click'); await flushPromises()
  expect(wrapper.find('[data-test="new-intake"]').exists()).toBe(false)
  expect(api.preview).not.toHaveBeenCalled(); expect(api.update).not.toHaveBeenCalled()
})
it('saving independent attributes keeps unsaved registration metadata input for its own later save', async () => {
  const wrapper = await dialog(10)
  await wrapper.get('input[placeholder="请输入书名"]').setValue('尚未保存的登记名')
  await wrapper.findAll('button').find(button => button.text().trim() === '独立编辑书目属性')!.trigger('click'); await flushPromises()
  wrapper.findComponent({ name: 'BookAttributeDialog' }).vm.$emit('success'); await flushPromises()
  expect((wrapper.get('input[placeholder="请输入书名"]').element as HTMLInputElement).value).toBe('尚未保存的登记名')
  expect(wrapper.text()).toContain('登记资料表单的未保存输入仍保留'); expect(api.update).not.toHaveBeenCalled()
})

it('new-book publication step uses the completed book ID and never restarts intake after failure/reopen', async () => {
  const wrapper = await dialog(); await required(wrapper); await submit(wrapper); await wrapper.get('[data-test="receipt"]').trigger('click'); await flushPromises()
  wrapper.findComponent(intake).vm.$emit('update:visible', false); await flushPromises()
  await wrapper.findAll('button').find(button => button.text().trim() === '出版资料补录')!.trigger('click'); await flushPromises()
  const publication = wrapper.findComponent({ name: 'CatalogPublicationDialog' }); expect(publication.props('bookId')).toBe(10); expect(publication.props('intakeCompleted')).toBe(true)
  publication.vm.$emit('update:visible', false); await flushPromises(); await wrapper.findAll('button').find(button => button.text().trim() === '出版资料补录')!.trigger('click'); await flushPromises()
  expect(wrapper.text()).toContain('第二步失败不重复入库'); expect(wrapper.find('[data-test="new-intake"]').exists()).toBe(false); expect(api.preview).not.toHaveBeenCalled(); expect(api.update).not.toHaveBeenCalled()
})

it('refreshes saved publication fields in the old metadata form while retaining other unsaved inputs', async () => {
  const wrapper = await dialog(10); await wrapper.get('input[placeholder="请输入书名"]').setValue('未保存的登记名')
  api.detail.mockResolvedValue({ book: { ...book, publishDate: '2024-02-29', pages: 0, description: '' }, metadataDigest: 'c'.repeat(64) })
  wrapper.findComponent({ name: 'CatalogPublicationDialog' }).vm.$emit('success'); await flushPromises()
  expect((wrapper.get('input[placeholder="请输入书名"]').element as HTMLInputElement).value).toBe('未保存的登记名')
  await submit(wrapper); expect(api.update).toHaveBeenCalledWith(10, 'c'.repeat(64), { title: '未保存的登记名' })
})
it('blocks metadata submission while the publication refresh response is pending, then saves with the verified new digest', async () => {
  const wrapper = await dialog(10); await wrapper.get('input[placeholder="请输入书名"]').setValue('未保存的登记名')
  let finish!: (value: object) => void; api.detail.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  wrapper.findComponent({ name: 'CatalogPublicationDialog' }).vm.$emit('success'); await flushPromises()
  const save = wrapper.findAll('button').find(button => button.text().trim() === '保存书目资料')!
  expect(save.attributes('disabled')).toBeDefined(); await submit(wrapper); expect(api.update).not.toHaveBeenCalled()
  finish({ book: { ...book, publishDate: '2024-02-29', pages: 0, description: '' }, metadataDigest: 'c'.repeat(64) }); await flushPromises()
  expect(wrapper.text()).not.toContain('其他登记资料已在别处变化')
  expect(wrapper.findAll('button').find(button => button.text().trim() === '保存书目资料')!.attributes('disabled')).toBeUndefined(); await submit(wrapper); expect(api.update).toHaveBeenCalledWith(10, 'c'.repeat(64), { title: '未保存的登记名' })
})
it('keeps local author edits and blocks saving when another actor changed nonpublication metadata during the independent step', async () => {
  api.detail.mockResolvedValueOnce({ book: { ...book, author: '原作者' }, metadataDigest: digest })
  const wrapper = await dialog(10); await wrapper.get('input[placeholder="请输入作者"]').setValue('本地未保存作者'); await wrapper.get('input[placeholder="请输入书名"]').setValue('本地未保存书名')
  api.detail.mockResolvedValueOnce({ book: { ...book, author: '另一馆员更新的作者', pages: 0, description: '' }, metadataDigest: 'c'.repeat(64) })
  wrapper.findComponent({ name: 'CatalogPublicationDialog' }).vm.$emit('success'); await flushPromises()
  expect(wrapper.text()).toContain('其他登记资料已在别处变化'); expect(wrapper.text()).toContain('禁止保存'); expect(wrapper.text()).toContain('重新读取并核对')
  expect((wrapper.get('input[placeholder="请输入作者"]').element as HTMLInputElement).value).toBe('本地未保存作者'); expect((wrapper.get('input[placeholder="请输入书名"]').element as HTMLInputElement).value).toBe('本地未保存书名')
  expect(wrapper.findAll('button').find(button => button.text().trim() === '保存书目资料')!.attributes('disabled')).toBeDefined(); await submit(wrapper); expect(api.update).not.toHaveBeenCalled()
})
