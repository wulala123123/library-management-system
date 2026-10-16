import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
const api = vi.hoisted(() => ({ history: vi.fn(), user: { isAdmin: true, isLibrarian: false } }))
vi.mock('@/api/bookCopyAction', () => ({ getCopyActionHistory: api.history }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import CopyActionHistory from './CopyActionHistory.vue'
import { actionReceipt } from './copyActionFixtures'
const wrappers: ReturnType<typeof mount>[] = []
async function history(visible = true) { const wrapper = mount(CopyActionHistory, { props: { copyId: 7, visible }, global: { plugins: [ElementPlus] } }); wrappers.push(wrapper); await flushPromises(); return wrapper }
beforeEach(() => { vi.resetAllMocks(); Object.assign(api.user, { isAdmin: true, isLibrarian: false }); api.history.mockResolvedValue({ records: [], total: 0, page: 1, size: 20, intakeSource: null }) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
it('shows immutable event/source IDs, raw text and restore chain without injecting markup or editing controls', async () => {
  const receipt = actionReceipt()
  api.history.mockResolvedValue({ records: [{ ...receipt.copies[0], requestId: 21, action: 'RESTORE', reason: '<img onerror=x> 核实找到', operatorId: 1, occurredAt: receipt.committedAt, restoredByActionId: 102, restoreOfActionId: 31 }], total: 25, page: 1, size: 20, intakeSource: { intakeId: 2, sourceType: 'PURCHASE', purchaseOrderId: 3, purchaseItemId: 4, stockFlowId: 5, operatorId: 1, confirmedAt: receipt.committedAt } })
  const wrapper = await history()
  for (const value of ['事件 101', '恢复来源事件 31', '已由事件 102 恢复', '入库回执 2', '采购单 3 / 项 4', '<img onerror=x> 核实找到', '" 00007 "']) expect(wrapper.text()).toContain(value)
  expect(wrapper.find('img').exists()).toBe(false); expect(wrapper.text()).not.toContain('删除')
  wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('update:currentPage', 2); wrapper.findComponent({ name: 'ElPagination' }).vm.$emit('current-change', 2); await flushPromises()
  expect(api.history).toHaveBeenLastCalledWith(7, 2, 20)
})
it('ignores a stale history result after changing copy, and requests nothing when hidden or reader', async () => {
  let finish!: (value: unknown) => void; api.history.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const wrapper = await history(); await wrapper.setProps({ copyId: 8 }); await flushPromises()
  finish({ records: [{ eventId: 999, reason: '过期结果' }], total: 1 }); await flushPromises(); expect(wrapper.text()).not.toContain('过期结果')
  vi.clearAllMocks(); await history(false); Object.assign(api.user, { isAdmin: false, isLibrarian: false }); await history()
  expect(api.history).not.toHaveBeenCalled()
})
