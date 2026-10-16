import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
const api = vi.hoisted(() => ({ list: vi.fn(), storeys: vi.fn(), user: { isAdmin: true, isLibrarian: false } }))
vi.mock('@/api/locationAddress', () => ({ getLocationAddresses: api.list }))
vi.mock('@/api/bookshelf', () => ({ getStoreys: api.storeys }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => api.user }))
import CopyPlacement from './CopyPlacement.vue'
import LocationAddressPicker from './LocationAddressPicker.vue'
import { addressRow, placementSnapshot } from './locationFixtures'
const wrappers: ReturnType<typeof mount>[] = []
beforeEach(() => { vi.resetAllMocks(); Object.assign(api.user,{isAdmin:true,isLibrarian:false}); api.list.mockResolvedValue({records:[addressRow()],total:21,page:1,size:10,pages:3}); api.storeys.mockResolvedValue([]) })
afterEach(() => wrappers.splice(0).forEach(wrapper => wrapper.unmount()))
it('shows the three effective placement branches and never promotes retained raw text into a registered address', async () => {
  const snapshot = placementSnapshot(), wrapper = mount(CopyPlacement,{props:{placement:snapshot.placement,rawText:snapshot.rawText}}); wrappers.push(wrapper)
  const visible = wrapper.findAll('p').filter(p => !p.element.closest('details')).map(p => p.text()).join(' ')
  expect(visible).toContain('合成书架'); expect(visible).not.toContain('旧文本不应作为有效位置')
  expect(wrapper.find('details').text()).toContain('旧文本不应作为有效位置'); expect(wrapper.find('img').exists()).toBe(false)
  await wrapper.setProps({placement:{kind:'UNASSIGNED',text:null,registeredLocation:null},rawText:'\u0000 '}); expect(wrapper.text()).toContain('未分配'); expect(wrapper.find('details').text()).toContain('"\\u0000 "')
  await wrapper.setProps({placement:{kind:'TEXT',text:' <img onerror=x> 😀 ',registeredLocation:null}}); expect(wrapper.text()).toContain('" <img onerror=x> 😀 "'); expect(wrapper.find('img').exists()).toBe(false)
})
it('retains a nonselectable registered identity and anomaly instead of falling back to old text', () => {
  const value = placementSnapshot(); value.placement.registeredLocation!.selectable=false; value.placement.registeredLocation!.unavailableReason='LOCATION_DISABLED'
  const wrapper=mount(CopyPlacement,{props:{placement:value.placement,rawText:value.rawText,anomaly:true}}); wrappers.push(wrapper)
  expect(wrapper.text()).toContain('登记地址 #12'); expect(wrapper.text()).toContain('LOCATION_DISABLED'); expect(wrapper.text()).toContain('LOCATION_REFERENCE_ANOMALY')
})
it('selects an OCCUPIED address with 250 linked copies and requests subsequent pages from server', async () => {
  const wrapper=mount(LocationAddressPicker,{props:{modelValue:null},global:{plugins:[ElementPlus]}});wrappers.push(wrapper); await flushPromises()
  const button=wrapper.findAll('button').find(value=>value.text()==='选择地址 #12')!
  expect(button.attributes('disabled')).toBeUndefined(); await button.trigger('click'); expect(wrapper.emitted('update:modelValue')![0]).toEqual([addressRow()]); expect(wrapper.text()).not.toContain('已满')
  const pagination=wrapper.findComponent({name:'ElPagination'});pagination.vm.$emit('update:currentPage',2);pagination.vm.$emit('current-change',2);await flushPromises();expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({page:2,size:10,selectableOnly:false}))
})
it('does not select a disabled address and sends no address or parent requests for a reader', async () => {
  const row=addressRow();row.address.selectable=false;row.address.unavailableReason='LOCATION_DISABLED';api.list.mockResolvedValue({records:[row],total:1})
  const wrapper=mount(LocationAddressPicker,{props:{modelValue:null},global:{plugins:[ElementPlus]}});wrappers.push(wrapper);await flushPromises();expect(wrapper.findAll('button').find(value=>value.text()==='选择地址 #12')!.attributes('disabled')).toBeDefined()
  vi.clearAllMocks();Object.assign(api.user,{isAdmin:false,isLibrarian:false});const reader=mount(LocationAddressPicker,{props:{modelValue:null},global:{plugins:[ElementPlus]}});wrappers.push(reader);await flushPromises();expect(api.list).not.toHaveBeenCalled();expect(api.storeys).not.toHaveBeenCalled()
})
it.each(['LOCATION_DISABLED','LOCATION_REFERENCE_ANOMALY'])('allows %s as a query filter while retaining its unavailable reason',async reason=>{
  const row=addressRow();row.address.selectable=false;row.address.unavailableReason=reason;api.list.mockResolvedValue({records:[row],total:1})
  const wrapper=mount(LocationAddressPicker,{props:{modelValue:null,requireSelectable:false},global:{plugins:[ElementPlus]}});wrappers.push(wrapper);await flushPromises()
  const button=wrapper.findAll('button').find(value=>value.text()==='选择地址 #12')!;expect(button.attributes('disabled')).toBeUndefined();expect(wrapper.text()).toContain(reason);await button.trigger('click');expect(wrapper.emitted('update:modelValue')![0]).toEqual([row])
})
