import { beforeEach, expect, it, vi } from 'vitest'
const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }))
vi.mock('./request', () => ({ ...request, default: request }))
import { getLocationAddresses, getLocationAddress, getStructureEvent, getStructureEvents } from './locationAddress'
import { createBookshelf, updateBookshelf, deleteBookshelf, createStorey, updateStorey, deleteStorey, createLocation, updateLocation, deleteLocation, assignBookToLocation, clearLocation } from './bookshelf'
beforeEach(() => vi.clearAllMocks())
it('uses server pagination and precise address/history endpoints without capacity filters', () => {
  const query = { page: 3, size: 10, keyword: ' A%_ ', bookshelfId: 1, storeyId: 2, selectableOnly: false }
  getLocationAddresses(query); getLocationAddress(12); getStructureEvents('LOCATION', 12, 2, 20); getStructureEvent(18)
  expect(request.get.mock.calls).toEqual([['/location-addresses', query], ['/location-addresses/12'], ['/location-structure-events', {entityType:'LOCATION',entityId:12,page:2,size:20}], ['/location-structure-events/18']])
})
it('submits delete version and raw reason as a JSON body and retains null-returning binding routes', () => {
  const identity = { expectedVersion: 3, reason: ' 核实旧绑定 😀 ' }
  deleteBookshelf(1, identity); deleteStorey(2, identity); deleteLocation(12, identity); assignBookToLocation(12, 99, identity); clearLocation(12, identity)
  expect(request.delete.mock.calls).toEqual([['/bookshelves/1',{data:identity}], ['/bookshelves/storeys/2',{data:identity}], ['/bookshelves/locations/12',{data:identity}]])
  expect(request.put.mock.calls).toEqual([['/bookshelves/locations/12/assign/99',identity], ['/bookshelves/locations/12/clear',identity]])
})
it('keeps create/update field sets explicit with no entity spreading into requests', () => {
  const shelf = { name:'书架',code:'A',deptId:null,location:null,capacity:1,status:1,reason:'创建依据' }, storey = { bookshelfId:1,name:'层',levelNum:0,capacity:1,status:1,reason:'创建依据' }, location = { storeyId:2,code:'L',status:'EMPTY',reason:'创建依据' }
  createBookshelf(shelf); updateBookshelf({...shelf,id:1,expectedVersion:0}); createStorey(storey); updateStorey({...storey,id:2,expectedVersion:1}); createLocation(location); updateLocation({...location,id:12,expectedVersion:2})
  expect(request.post.mock.calls).toEqual([['/bookshelves',shelf],['/bookshelves/storeys',storey],['/bookshelves/locations',location]])
  expect(request.put.mock.calls[2]![1]).toEqual({...location,id:12,expectedVersion:2})
})
