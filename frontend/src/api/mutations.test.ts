import { expect, it, vi } from 'vitest'
const request = vi.hoisted(() => ({ get: vi.fn(), put: vi.fn(), post: vi.fn(), del: vi.fn() }))
vi.mock('./request', () => request)
import { selfReturnBook, renewBook } from './borrow'
import { quickInstock, approvePurchaseOrder } from './purchase'

it('reader return and renewal call the matching backend endpoints', () => {
  selfReturnBook(7); renewBook(8)
  expect(request.put).toHaveBeenCalledWith('/borrows/7/self-return', {})
  expect(request.put).toHaveBeenCalledWith('/borrows/8/renew', { renewDays: 30 })
})
it('purchase approval and stock intake use the order ID without creating a book separately', () => {
  approvePurchaseOrder(4, { action: 'APPROVE', comment: '通过' }); quickInstock(4)
  expect(request.put).toHaveBeenCalledWith('/purchases/4/approve', { action: 'APPROVE', comment: '通过' })
  expect(request.put).toHaveBeenCalledWith('/purchases/4/instock', {})
  expect(request.post).not.toHaveBeenCalled()
})
