import { mount, flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import type { BorrowRecord } from '@/types'

const api = vi.hoisted(() => ({ renew: vi.fn() }))
vi.mock('@/api/borrow', () => ({ renewBook: api.renew }))
import RenewDialog from './RenewDialog.vue'

const wrappers: ReturnType<typeof mount>[] = []
const record = { id: 8, userName: '测试读者', bookTitle: '测试图书', dueDate: '2026-11-13', renewCount: 0 } as BorrowRecord

async function dialog() {
  const wrapper = mount(RenewDialog, {
    props: { visible: false, record, showReader: true }, attachTo: document.body,
    global: { plugins: [ElementPlus], stubs: { teleport: true, transition: false } },
  })
  wrappers.push(wrapper)
  await wrapper.setProps({ visible: true })
  await flushPromises()
  return wrapper
}

async function submit(wrapper: ReturnType<typeof mount>) {
  await wrapper.findAll('button').find(button => button.text().trim() === '确认续借')!.trigger('click')
  await flushPromises()
}

beforeEach(() => { vi.clearAllMocks(); api.renew.mockResolvedValue({ ...record, renewCount: 1 }) })
afterEach(() => { wrappers.splice(0).forEach(wrapper => wrapper.unmount()); document.body.innerHTML = '' })

it('defaults to thirty days and resets the field each time the dialog opens', async () => {
  const wrapper = await dialog()
  expect((wrapper.get('input[role="spinbutton"]').element as HTMLInputElement).value).toBe('30')
  await wrapper.get('input[role="spinbutton"]').setValue('7')
  await wrapper.setProps({ visible: false })
  await wrapper.setProps({ visible: true })
  await flushPromises()
  await submit(wrapper)
  expect(api.renew).toHaveBeenCalledWith(8, 30)
  expect(wrapper.emitted('success')).toHaveLength(1)
})

it('submits the selected days and closes after a successful renewal', async () => {
  const wrapper = await dialog()
  expect(wrapper.text()).toContain('测试读者')
  expect(wrapper.text()).toContain('2026-11-13')
  await wrapper.get('input[role="spinbutton"]').setValue('7')
  await wrapper.get('input[role="spinbutton"]').trigger('change')
  await submit(wrapper)
  expect(api.renew).toHaveBeenCalledWith(8, 7)
  expect(wrapper.emitted('update:visible')).toEqual([[false]])
})

it('does not silently submit thirty days when the required field is cleared', async () => {
  const wrapper = await dialog()
  await wrapper.get('input[role="spinbutton"]').setValue('')
  await wrapper.get('input[role="spinbutton"]').trigger('change')
  await submit(wrapper)
  expect(api.renew).not.toHaveBeenCalled()
  expect(wrapper.find('.el-form-item.is-error').exists()).toBe(true)
})

it('retains the chosen days after an API error and permits retry', async () => {
  api.renew.mockRejectedValueOnce(new Error('服务暂不可用'))
  const wrapper = await dialog()
  await wrapper.get('input[role="spinbutton"]').setValue('14')
  await wrapper.get('input[role="spinbutton"]').trigger('change')
  await submit(wrapper)
  expect(wrapper.emitted('success')).toBeUndefined()
  expect(wrapper.emitted('update:visible')).toBeUndefined()
  await submit(wrapper)
  expect(api.renew).toHaveBeenNthCalledWith(2, 8, 14)
  expect(wrapper.emitted('success')).toHaveLength(1)
})
