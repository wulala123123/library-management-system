import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, type ComputedRef } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api=vi.hoisted(()=>({shelves:vi.fn(),storeys:vi.fn(),addresses:vi.fn(),user:{isAdmin:true,isLibrarian:false}}))
vi.mock('@/api/bookshelf',()=>({getBookshelves:api.shelves,getStoreys:api.storeys}))
vi.mock('@/api/locationAddress',()=>({getLocationAddresses:api.addresses}))
vi.mock('@/store/modules/user',()=>({useUserStore:()=>api.user}))
import Bookshelf from './index.vue'
import { addressRow } from '@/components/locations/locationFixtures'
const slot=defineComponent({setup(_,{slots}){return()=>h('div',slots.default?.())}}),provider=defineComponent({props:['row'],setup(props,{slots}){provide('row',computed(()=>props.row));return()=>h('div',slots.default?.())}}),table=defineComponent({props:['data'],setup(props,{slots}){return()=>h('div',(props.data||[]).map((row:object)=>h(provider,{row},slots.default)))}}),column=defineComponent({props:['prop','label'],setup(props,{slots}){const row=inject<ComputedRef<Record<string,unknown>>>('row')!;return()=>h('div',[props.label,slots.default?.({row:row.value})||String(row.value[props.prop]??'')])}}),button=defineComponent({setup(_,{slots,attrs}){return()=>h('button',attrs,slots.default?.())}})
const wrappers:ReturnType<typeof mount>[]=[]
function page(){const wrapper=mount(Bookshelf,{global:{stubs:{ElTable:table,ElTableColumn:column,ElButton:button,ElDialog:slot,ElInput:true,ElInputNumber:true,ElPagination:true,ElAlert:true,ElEmpty:true,StructureMaintenanceDialog:true,StructureEventList:true},directives:{loading:()=>{}}}});wrappers.push(wrapper);return wrapper}
beforeEach(()=>{vi.resetAllMocks();Object.assign(api.user,{isAdmin:true,isLibrarian:false});api.shelves.mockResolvedValue({records:[{id:1,name:'合成架',layoutVersion:2}],total:1});api.storeys.mockResolvedValue([]);const normal=addressRow(),disabled=addressRow(13),unknown=addressRow(14);disabled.address.selectable=false;disabled.legacyBinding={bookId:88,rawStatus:'DISABLED',needsReview:true};unknown.legacyBinding={bookId:77,rawStatus:'legacy',needsReview:true};api.addresses.mockResolvedValue({records:[normal,disabled,unknown],total:3})})
afterEach(()=>wrappers.splice(0).forEach(wrapper=>wrapper.unmount()))
it('separates registered address, raw legacy binding and linked copy count; librarian gets clear but no delete',async()=>{
  Object.assign(api.user,{isAdmin:false,isLibrarian:true});const wrapper=page();await flushPromises();expect(wrapper.text()).toContain('旧 book_id：99');expect(wrapper.text()).toContain('250 册（含在借及报损）');expect(wrapper.text()).toContain('登记地址 #12')
  expect(wrapper.findAll('button').filter(button=>button.text().includes('删除'))).toHaveLength(0);const clear=wrapper.findAll('button').filter(button=>button.text()==='解除旧书目绑定');expect(clear).toHaveLength(2)
  await clear[1]!.trigger('click');const dialog=wrapper.findComponent({name:'StructureMaintenanceDialog'});expect(dialog.props('operation')).toBe('CLEAR');expect(dialog.props('original')).toEqual(expect.objectContaining({id:13,status:'DISABLED',bookId:88,layoutVersion:3}))
})
it('does not issue structure/address queries or display management actions for readers',async()=>{
  Object.assign(api.user,{isAdmin:false,isLibrarian:false});const wrapper=page();await flushPromises();expect(api.shelves).not.toHaveBeenCalled();expect(api.addresses).not.toHaveBeenCalled();expect(wrapper.findAll('button')).toHaveLength(0)
})
