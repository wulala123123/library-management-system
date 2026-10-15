import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({ create: vi.fn(), update: vi.fn(), detail: vi.fn(), upload: vi.fn(), categories: vi.fn() }))
vi.mock('@/api/book', () => ({ createBook: api.create, updateBook: api.update, getBookById: api.detail, uploadBookCover: api.upload }))
vi.mock('@/api/category', () => ({ getCategoryList: api.categories }))
import BookDialog from './BookDialog.vue'

const wrappers: ReturnType<typeof mount>[] = []
const select = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    return () => h('select', {
      value: props.modelValue,
      onChange: (event: Event) => emit('update:modelValue', Number((event.target as HTMLSelectElement).value)),
    }, [h('option', { value: '' }, '选择分类'), slots.default?.()])
  },
})
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })

async function dialog(bookId: number | null = null) {
  const wrapper = mount(BookDialog, {
    props: { visible: false, bookId }, attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, ElSelect: select, ElOption: option } },
  })
  wrappers.push(wrapper)
  await wrapper.setProps({ visible: true })
  await flushPromises()
  return wrapper
}
async function fillRequired(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[placeholder="请输入ISBN"]').setValue('9781234567890')
  await wrapper.get('input[placeholder="请输入书名"]').setValue('测试图书')
  await wrapper.get('select').setValue('7')
}
async function submit(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find(button => button.text().trim() === '确定')!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  api.create.mockResolvedValue({ id: 10 })
  api.update.mockResolvedValue({ id: 10 })
  api.categories.mockResolvedValue([{ id: 7, name: '测试分类' }])
  api.detail.mockResolvedValue({ id: 10, isbn: '9781234567890', title: '已有图书', categoryId: 7, stockTotal: 5, location: 'A区-3层-01架' })
})
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

it('creates a book with its optional location left empty using real form validation', async () => {
  const wrapper = await dialog()
  await fillRequired(wrapper)
  await submit(wrapper)
  expect(api.create).toHaveBeenCalledWith(expect.objectContaining({ isbn: '9781234567890', title: '测试图书', categoryId: 7, location: '' }))
  expect(wrapper.emitted('success')).toHaveLength(1)
})

it('clears an existing book location when editing', async () => {
  const wrapper = await dialog(10)
  await wrapper.get('input[placeholder="选填，例如：A区-3层-01架"]').setValue('')
  await submit(wrapper)
  expect(api.update).toHaveBeenCalledWith(expect.objectContaining({ id: 10, location: '' }))
  expect(wrapper.emitted('success')).toHaveLength(1)
})

it('handles a rejected save without closing the dialog and permits a corrected retry', async () => {
  api.create.mockRejectedValueOnce(new Error('location: 馆藏位置包含非法字符'))
  const wrapper = await dialog()
  await fillRequired(wrapper)
  await wrapper.get('input[placeholder="选填，例如：A区-3层-01架"]').setValue('<script>')
  await submit(wrapper)
  expect(wrapper.emitted('success')).toBeUndefined()
  expect(wrapper.emitted('update:visible')).toBeUndefined()
  await wrapper.get('input[placeholder="选填，例如：A区-3层-01架"]').setValue('')
  await submit(wrapper)
  expect(api.create).toHaveBeenNthCalledWith(2, expect.objectContaining({ location: '' }))
  expect(wrapper.emitted('success')).toHaveLength(1)
})

it('blocks missing required book fields without an unhandled validation rejection', async () => {
  const wrapper = await dialog()
  await submit(wrapper)
  expect(api.create).not.toHaveBeenCalled()
  expect(wrapper.find('.el-form-item.is-error').exists()).toBe(true)
  expect(wrapper.emitted('update:visible')).toBeUndefined()
})
