import { mount, flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'

const api = vi.hoisted(() => ({ create: vi.fn(), update: vi.fn(), detail: vi.fn() }))
vi.mock('@/api/user', () => ({ createUser: api.create, updateUser: api.update, getUserById: api.detail }))
import UserDialog from './UserDialog.vue'

const wrappers: ReturnType<typeof mount>[] = []
const select = defineComponent({
  props: ['modelValue'], emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    return () => h('select', { value: props.modelValue, onChange: (event: Event) => emit('update:modelValue', (event.target as HTMLSelectElement).value) }, slots.default?.())
  },
})
const option = defineComponent({ props: ['value', 'label'], setup(props) { return () => h('option', { value: props.value }, props.label) } })
async function dialog(id: number | null = null) {
  const wrapper = mount(UserDialog, {
    props: { visible: false, userId: id },
    attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false, ElSelect: select, ElOption: option } },
  })
  wrappers.push(wrapper)
  await wrapper.setProps({ visible: true })
  await flushPromises()
  return wrapper
}
async function fillRequired(wrapper: ReturnType<typeof mount>) {
  await wrapper.get('input[placeholder="请输入用户名"]').setValue('new_reader')
  await wrapper.get('input[placeholder="请输入密码"]').setValue('ReaderPassword123')
  await wrapper.get('input[placeholder="请输入真实姓名"]').setValue('测试读者')
}
async function submit(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find(button => button.text().trim() === '确定')!.trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  api.create.mockResolvedValue({ id: 10 })
  api.update.mockResolvedValue({ id: 10 })
  api.detail.mockResolvedValue({ id: 10, username: 'existing_reader', realName: '原姓名', role: 'READER', phone: '13800138000', email: '' })
})
afterEach(() => {
  wrappers.splice(0).forEach(wrapper => wrapper.unmount())
  document.body.innerHTML = ''
})

it('creates a user with phone and email left blank using real form validation', async () => {
  const wrapper = await dialog()
  await fillRequired(wrapper)
  await submit(wrapper)
  expect(api.create).toHaveBeenCalledWith({ username: 'new_reader', password: 'ReaderPassword123', realName: '测试读者', role: 'READER', phone: '', email: '' })
  expect(wrapper.emitted('success')).toHaveLength(1)
})

it('blocks an invalid nonempty phone before submitting', async () => {
  const wrapper = await dialog()
  await fillRequired(wrapper)
  await wrapper.get('input[placeholder="手机号（选填）"]').setValue('12345')
  await wrapper.get('input[placeholder="手机号（选填）"]').trigger('blur')
  await flushPromises()
  await submit(wrapper)
  expect(api.create).not.toHaveBeenCalled()
  expect(wrapper.find('.el-form-item.is-error').exists()).toBe(true)
})

it('edits a user and clears phone without submitting an empty password', async () => {
  const wrapper = await dialog(10)
  await wrapper.get('input[placeholder="请输入真实姓名"]').setValue('修改姓名')
  await wrapper.get('input[placeholder="手机号（选填）"]').setValue('')
  await submit(wrapper)
  expect(api.update).toHaveBeenCalledWith({ id: 10, username: 'existing_reader', realName: '修改姓名', role: 'READER', phone: '', email: '' })
  expect(api.update.mock.calls[0]![0]).not.toHaveProperty('password')
})

it('keeps the form open after a handled API error and allows correction and retry', async () => {
  api.create.mockRejectedValueOnce(new Error('用户名已存在'))
  const wrapper = await dialog()
  await fillRequired(wrapper)
  await submit(wrapper)
  expect(wrapper.emitted('success')).toBeUndefined()
  expect(wrapper.emitted('update:visible')).toBeUndefined()
  await wrapper.get('input[placeholder="请输入用户名"]').setValue('another_reader')
  await submit(wrapper)
  expect(api.create).toHaveBeenCalledTimes(2)
  expect(wrapper.emitted('success')).toHaveLength(1)
})
