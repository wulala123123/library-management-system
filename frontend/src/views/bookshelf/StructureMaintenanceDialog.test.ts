import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import ElementPlus from 'element-plus'
import { defineComponent, h } from 'vue'
const api=vi.hoisted(()=>({createShelf:vi.fn(),updateShelf:vi.fn(),deleteShelf:vi.fn(),createStorey:vi.fn(),updateStorey:vi.fn(),deleteStorey:vi.fn(),createLocation:vi.fn(),updateLocation:vi.fn(),deleteLocation:vi.fn(),assign:vi.fn(),clear:vi.fn(),shelves:vi.fn(),shelf:vi.fn(),storeys:vi.fn(),locations:vi.fn(),events:vi.fn(),user:{isAdmin:true,isLibrarian:false}}))
vi.mock('@/api/bookshelf',()=>({createBookshelf:api.createShelf,updateBookshelf:api.updateShelf,deleteBookshelf:api.deleteShelf,createStorey:api.createStorey,updateStorey:api.updateStorey,deleteStorey:api.deleteStorey,createLocation:api.createLocation,updateLocation:api.updateLocation,deleteLocation:api.deleteLocation,assignBookToLocation:api.assign,clearLocation:api.clear,getBookshelves:api.shelves,getBookshelfById:api.shelf,getStoreys:api.storeys,getLocations:api.locations}))
vi.mock('@/api/locationAddress',()=>({getStructureEvents:api.events}))
vi.mock('@/store/modules/user',()=>({useUserStore:()=>api.user}))
import StructureMaintenanceDialog from './StructureMaintenanceDialog.vue'
import { structureEvent, structureSnapshot } from '@/components/locations/locationFixtures'
const wrappers:ReturnType<typeof mount>[]=[]
const select=defineComponent({props:['modelValue'],setup(props,{slots}){return()=>h('select',{value:props.modelValue},slots.default?.())}}),option=defineComponent({props:['value','label'],setup(props){return()=>h('option',{value:props.value},props.label)}})
async function dialog(entityType:'BOOKSHELF'|'STOREY'|'LOCATION'='LOCATION',operation:'CREATE'|'UPDATE'|'DELETE'|'ASSIGN'|'CLEAR'='CLEAR',original:Record<string,unknown>|null={...structureSnapshot()}){const wrapper=mount(StructureMaintenanceDialog,{props:{visible:true,entityType,operation,original},global:{plugins:[ElementPlus],stubs:{teleport:true,transition:false,ElSelect:select,ElOption:option}}});wrappers.push(wrapper);await flushPromises();return wrapper}
async function click(wrapper:ReturnType<typeof mount>,text:string){await wrapper.findAll('button').find(button=>button.text()===text)!.trigger('click');await flushPromises()}
beforeEach(()=>{vi.resetAllMocks();Object.assign(api.user,{isAdmin:true,isLibrarian:false});for(const method of [api.createShelf,api.updateShelf,api.deleteShelf,api.createStorey,api.updateStorey,api.deleteStorey,api.createLocation,api.updateLocation,api.deleteLocation,api.assign,api.clear])method.mockResolvedValue(null);api.events.mockResolvedValue({records:[structureEvent()],total:1});api.locations.mockResolvedValue({records:[{...structureSnapshot(3),bookId:null}],total:1});api.storeys.mockResolvedValue([]);api.shelves.mockResolvedValue({records:[],total:0})})
afterEach(()=>{wrappers.splice(0).forEach(wrapper=>wrapper.unmount());document.body.innerHTML=''})
it('shows disabled legacy book/status explicitly and clears with only version/reason without enabling or moving copies',async()=>{
  const wrapper=await dialog();expect(wrapper.text()).toContain('旧 book_id：99');expect(wrapper.text()).toContain('原 status："DISABLED"');await wrapper.get('textarea').setValue(' 合成解除旧绑定 😀 ');await click(wrapper,'解除上述旧书目绑定')
  expect(api.clear).toHaveBeenCalledWith(12,{expectedVersion:2,reason:' 合成解除旧绑定 😀 '});expect(wrapper.emitted('success')).toHaveLength(1)
})
it('blocks unknown clear and hides delete ability from librarian, with no reader writes',async()=>{
  const unknown=await dialog('LOCATION','CLEAR',{...structureSnapshot(),status:'legacy'});await unknown.get('textarea').setValue('核实');await click(unknown,'解除上述旧书目绑定');expect(api.clear).not.toHaveBeenCalled();expect(unknown.text()).toContain('原地址状态未知')
  Object.assign(api.user,{isAdmin:false,isLibrarian:true});const librarian=await dialog('LOCATION','DELETE');expect(librarian.findAll('button').find(button=>button.text()==='按原值删除')!.attributes('disabled')).toBeDefined();expect(api.deleteLocation).not.toHaveBeenCalled()
  Object.assign(api.user,{isLibrarian:false});const reader=await dialog();expect(reader.find('textarea').exists()).toBe(false);expect(api.events).not.toHaveBeenCalled()
})
it('preserves input after timeout and requires explicit review before a later versioned submission',async()=>{
  api.clear.mockRejectedValueOnce(new Error('timeout'));const wrapper=await dialog();await wrapper.get('textarea').setValue(' 保留依据 ');await click(wrapper,'解除上述旧书目绑定')
  expect(api.clear).toHaveBeenCalledTimes(1);expect(api.events).toHaveBeenCalledWith('LOCATION',12,1,5);expect(wrapper.text()).toContain('最新事件 18');expect(wrapper.findAll('button').some(button=>button.text()==='解除上述旧书目绑定')).toBe(false)
  expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe(' 保留依据 ');await click(wrapper,'已核对，以当前版本继续编辑');await click(wrapper,'解除上述旧书目绑定');expect(api.clear).toHaveBeenLastCalledWith(12,{expectedVersion:3,reason:' 保留依据 '})
})
it('creates a shelf using all required fields and no id/version/entity properties',async()=>{
  const wrapper=await dialog('BOOKSHELF','CREATE',null);await wrapper.get('input[aria-label="维护编号"]').setValue('A_1');await wrapper.get('input[aria-label="维护名称"]').setValue(' 原书架 😀 ');await wrapper.get('textarea').setValue('😀'.repeat(500));await click(wrapper,'提交本次维护')
  expect(api.createShelf).toHaveBeenCalledWith({name:' 原书架 😀 ',code:'A_1',deptId:null,location:'',capacity:50,status:1,reason:'😀'.repeat(500)});expect(wrapper.get('textarea').attributes('maxlength')).toBeUndefined()
})
it('preserves omitted nullable shelf fields as explicit null when editing only its name',async()=>{
  const original={id:1,code:'A',name:'原书架',capacity:50,status:1,layoutVersion:4}
  const wrapper=await dialog('BOOKSHELF','UPDATE',original);await wrapper.get('input[aria-label="维护名称"]').setValue('只改名称');await wrapper.get('textarea').setValue('合成名称核对');await click(wrapper,'提交本次维护')
  expect(api.updateShelf).toHaveBeenCalledWith({id:1,expectedVersion:4,name:'只改名称',code:'A',deptId:null,location:null,capacity:50,status:1,reason:'合成名称核对'})
})
it('requires an explicit parent for a new address instead of assuming ID 1',async()=>{
  const wrapper=await dialog('LOCATION','CREATE',null);await wrapper.get('input[aria-label="维护编号"]').setValue('NEW-1');await wrapper.get('textarea').setValue('明确新地址');await click(wrapper,'提交本次维护');expect(api.createLocation).not.toHaveBeenCalled();expect(wrapper.text()).toContain('请核对所属层')
  await wrapper.get('input[role="spinbutton"]').setValue('2');await click(wrapper,'提交本次维护');expect(api.createLocation).toHaveBeenCalledWith({storeyId:2,code:'NEW-1',status:'EMPTY',reason:'明确新地址'})
})
