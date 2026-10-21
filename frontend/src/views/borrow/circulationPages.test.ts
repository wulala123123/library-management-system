import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h, reactive } from 'vue'
import ElementPlus from 'element-plus'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const api=vi.hoisted(()=>({users:vi.fn(),catalog:vi.fn(),copies:vi.fn(),copy:vi.fn(),order:vi.fn(),orders:vi.fn(),borrows:vi.fn(),legacyBorrows:vi.fn(),exportFile:vi.fn(),download:vi.fn(),preview:vi.fn(),request:vi.fn(),confirm:vi.fn(),cancel:vi.fn()}))
const user=reactive({isAdmin:true,isLibrarian:false,isReader:false,token:'captured-login',userInfo:{id:1,role:'ADMIN'}})
vi.mock('@/store/modules/user',()=>({useUserStore:()=>user}))
vi.mock('@/api/user',()=>({searchUsers:api.users}))
vi.mock('@/api/catalogAttribute',()=>({getCatalogBooks:api.catalog}))
vi.mock('@/api/bookCopyQuery',()=>({getBookCopies:api.copies,getBookCopy:api.copy}))
vi.mock('@/api/borrow',()=>({getBorrowPage:api.legacyBorrows,returnBook:vi.fn(),adminBorrowBook:vi.fn(),renewBook:vi.fn()}))
vi.mock('@/api/borrowInquiry',async original=>({...await original<object>(),createBorrowInquiryApi:()=>({query:api.borrows,export:api.exportFile})}))
vi.mock('@/utils/statistics',()=>({downloadCsv:api.download}))
vi.mock('@/api/book',()=>({getBookCopies:vi.fn().mockResolvedValue([])}))
vi.mock('@/api/circulation',async original=>({...await original<object>(),createCirculationApi:()=>({order:api.order,orders:api.orders,preview:api.preview,request:api.request,confirm:api.confirm,cancel:api.cancel})}))
import CounterBorrowDialog from './components/CounterBorrowDialog.vue'
import ReturnDialog from './components/ReturnDialog.vue'
import BorrowOrderDrawer from './components/BorrowOrderDrawer.vue'
import CirculationRequestPanel from './components/CirculationRequestPanel.vue'
import BorrowManagement from './index.vue'
import { useCirculationSession } from './circulationSession'
import { CirculationError, type CirculationApi, type ReturnSelection } from '@/api/circulation'
import { borrowRequest, circulationExamples, cloneCirculation, fixtureNow, orderDetail, requestReceipt } from './circulationFixtures'
import { inquiryAsOf, inquiryFile, inquiryFilters, inquiryPage, inquiryRecord } from './borrowInquiryFixtures'
import { BorrowInquiryError } from '@/api/borrowInquiry'

const wrappers:VueWrapper[]=[],sessions:ReturnType<typeof useCirculationSession>[]=[]
// ElementPlus dynamic Select recursively updates under jsdom; retain the actual business components,
// dialogs, number fields, buttons, tables and drawers and adapt only the select DOM surface.
const select=defineComponent({name:'ElSelect',props:['modelValue','placeholder','remoteMethod','disabled','loading'],emits:['update:modelValue','change'],setup(p,{slots,attrs,emit}){return()=>h('div',[h('input',{placeholder:p.placeholder,onInput:(e:any)=>p.remoteMethod?.(e.target.value)}),h('select',{...attrs,value:p.modelValue,disabled:p.disabled,onChange:(e:any)=>{const raw=e.target.value,value=/^[1-9]\d*$/.test(raw)?Number(raw):raw;emit('update:modelValue',value);emit('change',value)}},slots.default?.())])}})
const option=defineComponent({name:'ElOption',props:['value','label','disabled'],setup:p=>()=>h('option',{value:p.value,disabled:p.disabled},p.label)})
const page=async(component:any,props:object={})=>{const w=mount(component,{props,attachTo:document.body,global:{plugins:[ElementPlus],stubs:{teleport:true,transition:false,ElSelect:select,ElOption:option,HistoryContextDrawer:true}}});wrappers.push(w);await flushPromises();return w}
const click=async(w:VueWrapper,text:string)=>{const button=w.findAll('button').find(item=>item.text().trim()===text);expect(button,`button ${text}`).toBeDefined();await button!.trigger('click');await flushPromises()}
const copy=(id=301,bookId=201,barcode='000Aa '):any=>({id,bookId,barcode,status:'AVAILABLE',location:null,unassignedLocation:true,book:{id:bookId,title:`登记图书${bookId}`,isbn:'000ISBN',author:null,publisher:null,publishDate:null,pages:null,coverImage:null,location:null,status:1},actionVersion:0,lastStateActionId:null,stateClassification:'AVAILABLE',placement:{kind:'UNASSIGNED',text:null,registeredLocation:null},lastLocationActionId:null,locationReferenceAnomaly:false})
const initial=():ReturnSelection=>({id:501,bookTitle:'历史图书',userName:null,copyBarcode:null,dueDate:null,dueDateKind:'UNLIMITED'})
beforeEach(()=>{
  vi.resetAllMocks();sessionStorage.clear();localStorage.clear();Object.assign(user,{isAdmin:true,isLibrarian:false,isReader:false,token:'captured-login',userInfo:{id:1,role:'ADMIN'}})
  vi.stubGlobal('ResizeObserver',class{observe(){}unobserve(){}disconnect(){}})
  api.users.mockResolvedValue({records:[{id:101,username:'reader101',realName:'合成甲',phone:'13800000000',cardNo:'000101'},{id:102,username:'reader102',realName:'合成乙',phone:'13800000000',cardNo:'000102'}],total:2})
  api.catalog.mockResolvedValue({records:[],total:0});api.copies.mockResolvedValue({records:[copy()],total:1});api.copy.mockResolvedValue({copy:{...copy(),status:'BORROWED'},needsVerification:false,currentLoans:[{borrowId:501,status:'BORROWING',borrowDate:'2026-10-20',dueDate:'2026-11-19',dueDateKind:'DATED',memberName:null,cardNo:null,bookMatches:true}]})
  api.order.mockResolvedValue(orderDetail());api.orders.mockResolvedValue(cloneCirculation(circulationExamples.orderPage));api.borrows.mockImplementation(query=>Promise.resolve(inquiryPage([],query,query.page,0,query.size)));api.exportFile.mockResolvedValue(inquiryFile());api.preview.mockImplementation((key,input)=>Promise.resolve(borrowRequest(key,input)));api.request.mockRejectedValue(new CirculationError(404,'REQUEST_NOT_FOUND'))
})
afterEach(async()=>{wrappers.splice(0).forEach(w=>w.unmount());sessions.splice(0).forEach(s=>s.dispose());await flushPromises();document.body.innerHTML='';vi.useRealTimers();vi.unstubAllGlobals()})

it('keeps shared-phone member identities distinct and previews multiple explicit copies in selected order',async()=>{
  const w=await page(CounterBorrowDialog,{visible:true,canStart:true});const selects=w.findAllComponents({name:'ElSelect'})
  await selects[0]!.props('remoteMethod')('13800000000');await flushPromises();await selects[0]!.vm.$emit('update:modelValue',102)
  expect(api.users).toHaveBeenCalledWith('13800000000');expect(selects[0]!.findAllComponents({name:'ElOption'}).map(option=>option.props('label'))).toEqual(expect.arrayContaining([expect.stringContaining('reader101 · 卡号 000101'),expect.stringContaining('reader102 · 卡号 000102')]))
  const scanner=w.get('input[placeholder="完整条码，保留前导零和首尾空格"]');await scanner.setValue('000Aa ');await click(w,'按精确条码加入');expect(api.copies).toHaveBeenLastCalledWith({page:1,size:2,barcode:'000Aa '})
  api.copies.mockResolvedValue({records:[copy(302,202,' 000Bb')],total:1});await scanner.setValue(' 000Bb');await click(w,'按精确条码加入');expect(w.text()).toContain('本次明确选择 2 / 10 册');expect(w.text()).toContain('"000Aa "')
  await click(w,'预览本次全部借出');expect(w.emitted('preview')![0]![0]).toEqual({operation:'BORROW',userId:102,items:[{bookId:201,copyId:301,borrowDays:30},{bookId:202,copyId:302,borrowDays:30}],remark:''})
})
it('does not duplicate a scan, borrow two copies of one title, auto-allocate on load failure or silently default cleared days',async()=>{
  const w=await page(CounterBorrowDialog,{visible:true,canStart:true});w.findAllComponents({name:'ElSelect'})[0]!.vm.$emit('update:modelValue',101);await flushPromises()
  const scanner=w.get('input[placeholder="完整条码，保留前导零和首尾空格"]');await scanner.setValue('000Aa ');await click(w,'按精确条码加入');await scanner.setValue('000Aa ');await click(w,'按精确条码加入');expect(w.text()).toContain('此实体册已在本次清单中')
  api.copies.mockResolvedValue({records:[copy(302,201,'other')],total:1});await scanner.setValue('other');await click(w,'按精确条码加入');expect(w.text()).toContain('不能重复借阅同一书目')
  const days=w.findAll('input[role="spinbutton"]');await days[1]!.setValue('');await days[1]!.trigger('change');await click(w,'预览本次全部借出');expect(w.emitted('preview')).toBeUndefined()
  api.copies.mockRejectedValue(Error('lost'));await w.findAllComponents({name:'ElSelect'})[1]!.vm.$emit('update:modelValue',201);await w.findAllComponents({name:'ElSelect'})[1]!.vm.$emit('change',201);await flushPromises();expect(w.text()).toContain('不能自动分配')
})
it('refuses unknown/disabled scan results and hides late member responses after identity change',async()=>{
  const w=await page(CounterBorrowDialog,{visible:true,canStart:true});api.copies.mockResolvedValue({records:[{...copy(),status:'DISABLED',stateClassification:'DISABLED'}],total:1});await w.get('input[placeholder="完整条码，保留前导零和首尾空格"]').setValue('000Aa ');await click(w,'按精确条码加入');expect(w.text()).toContain('不是已核实的可借实体册')
  let finish!:(value:any)=>void;api.users.mockReturnValue(new Promise(resolve=>finish=resolve));const pending=w.findAllComponents({name:'ElSelect'})[0]!.props('remoteMethod')('甲');user.isAdmin=false;user.userInfo={id:2,role:'READER'};await flushPromises();finish({records:[{id:999,realName:'旧身份资料',username:'secret'}]});await pending;await flushPromises();expect(w.text()).not.toContain('旧身份资料');expect(w.emitted('preview')).toBeUndefined()
})
it('keeps ten explicit copies and refuses an eleventh without changing the chosen set',async()=>{
  const w=await page(CounterBorrowDialog,{visible:true,canStart:true});w.findAllComponents({name:'ElSelect'})[0]!.vm.$emit('update:modelValue',101);await flushPromises()
  const scanner=w.get('input[placeholder="完整条码，保留前导零和首尾空格"]')
  for(let i=0;i<11;i++){const raw=`000${301+i}`;api.copies.mockResolvedValue({records:[copy(301+i,201+i,raw)],total:1});await scanner.setValue(raw);await click(w,'按精确条码加入')}
  expect(w.text()).toContain('本次明确选择 10 / 10 册');expect(w.text()).toContain('本次最多明确选择 10 册');await click(w,'预览本次全部借出');const input:any=w.emitted('preview')![0]![0];expect(input.items.map((row:any)=>row.copyId)).toEqual(Array.from({length:10},(_,i)=>301+i))
})
it('previews explicit legacy no-copy returns without changing unknown/unlimited dates or adding finance fields',async()=>{
  const w=await page(ReturnDialog,{visible:true,canStart:true,initialRecords:[initial(),{...initial(),id:502,bookTitle:'未知期限',dueDateKind:'UNKNOWN'}]});expect(w.text()).toContain('原姓名未提供');expect(w.text()).toContain('不限制');expect(w.text()).toContain('原记录未提供');expect(w.text()).toContain('原记录无实体册关联')
  await click(w,'预览本次明确归还');expect(w.emitted('preview')![0]![0]).toEqual({operation:'RETURN',recordIds:[501,502],remark:''});for(const word of ['退款','押金','扣次','收款'])expect(w.text()).not.toContain(word)
})
it('preserves raw return barcode, rejects duplicate or ambiguous loans, and never auto-returns on scan',async()=>{
  const w=await page(ReturnDialog,{visible:true,canStart:true});const input=w.get('input[placeholder="完整条码，保留前导零和首尾空格"]');await input.setValue('000Aa ');await click(w,'查找在借明细并加入');expect(api.copies).toHaveBeenLastCalledWith({page:1,size:2,barcode:'000Aa '});expect(w.emitted('preview')).toBeUndefined();expect(w.text()).toContain('本次明确归还 1 / 100 册')
  await input.setValue('000Aa ');await click(w,'查找在借明细并加入');expect(w.text()).toContain('此借阅明细已在本次清单中')
  api.copy.mockResolvedValue({copy:copy(),needsVerification:true,currentLoans:[{borrowId:999,bookMatches:false}]});await input.setValue('000Aa ');await click(w,'查找在借明细并加入');expect(w.text()).toContain('借阅关联需要核实');await click(w,'预览本次明确归还');expect((w.emitted('preview')![0]![0] as any).recordIds).toEqual([501])
})
it('rejects an oversized initial return selection as a whole instead of silently truncating',async()=>{
  const w=await page(ReturnDialog,{visible:true,canStart:true,initialRecords:Array.from({length:101},(_,i)=>({...initial(),id:i+1}))});expect(w.text()).toContain('未截断办理');await click(w,'预览本次明确归还');expect(w.emitted('preview')).toBeUndefined()
})
it('returns one, a partial selection or the complete explicit unreturned order set including nonvisible-page items',async()=>{
  const order=orderDetail();order.items.push({...order.items[0]!,ordinal:2,recordId:502,copyId:302,bookId:202,barcode:'000302'});order.items.push({...order.items[0]!,ordinal:3,recordId:503,copyId:303,bookId:203,status:'RETURNED',returnDate:'2026-10-20'});Object.assign(order,{itemCount:3,activeCount:2,returnedCount:1,state:'PARTIALLY_RETURNED'});api.order.mockResolvedValue(order)
  const w=await page(BorrowOrderDrawer,{visible:true,orderId:401,canStart:true});await flushPromises();expect(w.text()).toContain('部分归还');expect(w.text()).toContain('借出时快照')
  await w.get('input[aria-label="选择归还明细502"]').setValue(true);await click(w,'归还明确所选 1 册');expect((w.emitted('return-records')![0]![0] as any[]).map(row=>row.id)).toEqual([502])
  await click(w,'归还整单全部未还册');expect((w.emitted('return-records')![1]![0] as any[]).map(row=>row.id)).toEqual([501,502]);expect(w.get('input[aria-label="选择归还明细503"]').attributes('disabled')).toBeDefined()
})
it('suppresses late order results after member/role loss and sends no reader request',async()=>{
  let finish!:(value:any)=>void;api.order.mockReturnValue(new Promise(resolve=>finish=resolve));const w=await page(BorrowOrderDrawer,{visible:true,orderId:401,canStart:true});user.isAdmin=false;user.userInfo.role='READER';await flushPromises();finish(orderDetail());await flushPromises();expect(w.text()).not.toContain('合成会员');expect(w.emitted('return-records')).toBeUndefined();api.order.mockClear();await page(BorrowOrderDrawer,{visible:true,orderId:401,canStart:true});expect(api.order).not.toHaveBeenCalled()
})
it('shows original frozen preview, retains unknown-confirm lock and emits success only after valid recovery',async()=>{
  vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(fixtureNow);const session=useCirculationSession(1,()=>true,api as CirculationApi,sessionStorage);sessions.push(session);await session.start(circulationExamples.requestView.input as any)
  const w=await page(CirculationRequestPanel,{session});expect(w.text()).toContain('"000Aa "');expect(w.text()).toContain('2026-11-19');expect(w.emitted('success')).toBeUndefined()
  api.confirm.mockRejectedValue(new CirculationError(null,'UNKNOWN_RESULT'));await click(w,'确认本次全部借还');expect(w.text()).toContain('原确认结果尚未查清');expect(w.findAll('button').some(button=>button.text()==='请求取消原办理（已成功则显示回执）')).toBe(true);expect(w.text()).toContain('取得确定终态前不能编辑或另办')
  const view=session.view.value!;api.request.mockResolvedValue({...view,state:'COMMITTED',receipt:requestReceipt(view)});await click(w,'查询原借还办理');expect(w.text()).toContain('借阅订单 #401');expect(w.emitted('success')).toHaveLength(1);await click(w,'查询原借还办理');expect(w.emitted('success')).toHaveLength(1)
})
it('keeps legacy single-book entry and records while adding isolated orders with only allowed list filters',async()=>{
  const w=await page(BorrowManagement);await flushPromises();expect(w.text()).toContain('办理借阅');expect(w.text()).toContain('全部借阅明细');expect(w.text()).toContain('柜台多本借阅');expect(w.text()).toContain('扫码 / 批量归还')
  expect(api.orders).toHaveBeenCalledWith({page:1,size:20,userId:undefined,state:undefined});expect(api.borrows).toHaveBeenCalledOnce();await click(w,'办理借阅');expect(w.findComponent({name:'BorrowDialog'}).props('visible')).toBe(true)
  user.isAdmin=false;user.userInfo.role='READER';await flushPromises();expect(w.text()).not.toContain('合成会员');expect(w.text()).toContain('仅当前有效的管理员和馆员')
})
it('wires history to the explicit borrow ID without opening an order or a return, and hides it from librarians',async()=>{
  api.borrows.mockResolvedValue(inquiryPage([{...inquiryRecord(),status:'RETURNED'}]))
  const w=await page(BorrowManagement);const history=w.findComponent({name:'HistoryContextDrawer'});expect(history.props('visible')).toBe(false);await click(w,'历史资料');expect(history.props()).toMatchObject({visible:true,entityType:'borrows',entityId:501});expect(api.order).not.toHaveBeenCalled();expect(api.preview).not.toHaveBeenCalled();expect(api.confirm).not.toHaveBeenCalled();expect(api.cancel).not.toHaveBeenCalled()
  user.token='another-login';await flushPromises();expect(history.props()).toMatchObject({visible:false,entityId:null});user.isAdmin=false;user.isLibrarian=true;user.userInfo.role='LIBRARIAN';await flushPromises();expect(w.findAll('button').some(button=>button.text()==='历史资料')).toBe(false);expect(w.findComponent({name:'HistoryContextDrawer'}).exists()).toBe(false)
})

it('shows four separate origin counts, day precision, explicit counter order links and safe unknown states',async()=>{
  const records=[{...inquiryRecord(),origin:'IMPORTED' as const,dueDateKind:'UNLIMITED'}, {...inquiryRecord(502),origin:'COUNTER_ORDER' as const,counterOrderId:401}, {...inquiryRecord(503),origin:'SYSTEM' as const}, {...inquiryRecord(504),origin:'UNVERIFIED' as const,status:'来源未知状态',userName:null,bookTitle:null}]
  api.borrows.mockResolvedValue(inquiryPage(records));const w=await page(BorrowManagement);expect(w.text()).toContain('筛选结果共 4 条借阅明细');expect(w.text()).toContain('迁入 1 条 · 新柜台 1 条 · 本系统 1 条 · 待核实 1 条');expect(w.text()).toContain(inquiryAsOf);expect(w.text()).toContain('原系统累计借阅数、逐条明细与柜台订单数分别核对，不能混加');expect(w.text()).toContain('当前仅保留日精度');expect(w.text()).toContain('不限制');expect(w.text()).toContain('原记录未提供');expect(w.text()).toContain('来源未知状态');expect(w.text()).toContain('原姓名未提供');expect(api.legacyBorrows).not.toHaveBeenCalled()
  await click(w,'柜台订单 #401');expect(api.order).toHaveBeenCalledWith(401);expect(api.preview).not.toHaveBeenCalled()
})

it('applies origin/status/keyword explicitly and exports the applied complete range while pending inputs are visible',async()=>{
  api.borrows.mockImplementation(query=>Promise.resolve(inquiryPage([inquiryRecord()],query,query.page,21,query.size)));const w=await page(BorrowManagement)
  await w.get('input[placeholder="搜索读者/图书名称/ISBN"]').setValue(' 000Aa ');await w.get('select[aria-label="借阅明细来源筛选"]').setValue('IMPORTED');await w.get('select[aria-label="借阅明细状态筛选"]').setValue('ACTIVE');expect(api.borrows).toHaveBeenCalledOnce();await click(w,'搜索');expect(api.borrows).toHaveBeenLastCalledWith({keyword:' 000Aa ',status:'ACTIVE',origin:'IMPORTED',page:1,size:10},expect.any(AbortSignal))
  await w.get('input[placeholder="搜索读者/图书名称/ISBN"]').setValue('尚未应用');expect(w.text()).toContain('筛选输入尚未应用');await click(w,'导出全部筛选结果');expect(api.exportFile).toHaveBeenCalledExactlyOnceWith({keyword:' 000Aa ',status:'ACTIVE',origin:'IMPORTED'},expect.any(AbortSignal));expect(api.download).toHaveBeenCalledOnce();expect(w.text()).toContain('已导出 21 条');expect(w.text()).toContain('导出时点')
})

it('resets chosen returns after a page/filter change and keeps read errors distinct from a real empty page',async()=>{
  api.borrows.mockImplementation(query=>Promise.resolve(inquiryPage([inquiryRecord()],query,query.page,21,query.size)));const w=await page(BorrowManagement);const table=w.findAllComponents({name:'ElTable'})[1]!;table.vm.$emit('selection-change',[inquiryRecord()]);await flushPromises();expect(w.text()).toContain('预览归还所选 1 册')
  const pagination=w.findAllComponents({name:'ElPagination'})[1]!;pagination.vm.$emit('current-change',2);await flushPromises();expect(api.borrows).toHaveBeenLastCalledWith({...inquiryFilters(),page:2,size:10},expect.any(AbortSignal));expect(w.text()).toContain('预览归还所选 0 册')
  table.vm.$emit('selection-change',[inquiryRecord()]);await flushPromises();api.borrows.mockRejectedValueOnce(Error('lost'));await click(w,'搜索');expect(w.text()).toContain('读取失败不表示没有借阅明细');expect(w.text()).not.toContain('当前筛选暂无');expect(w.text()).not.toContain('筛选结果共 0');expect(w.text()).toContain('预览归还所选 0 册');const exports=w.findAll('button').find(button=>button.text()==='导出全部筛选结果')!;expect(exports.attributes('disabled')).toBeDefined()
  api.borrows.mockResolvedValueOnce(inquiryPage([]));await click(w,'重新读取借阅明细');expect(w.text()).toContain('当前筛选暂无借阅明细');expect(w.text()).toContain('筛选结果共 0 条借阅明细')
})

it('permits librarians to query/export safe detail rows while hiding archive entry and preserving circulation actions',async()=>{
  user.isAdmin=false;user.isLibrarian=true;user.userInfo.role='LIBRARIAN';api.borrows.mockResolvedValue(inquiryPage([inquiryRecord()]));const w=await page(BorrowManagement);await click(w,'导出全部筛选结果');expect(api.download).toHaveBeenCalledOnce();expect(w.findAll('button').some(button=>button.text()==='历史资料')).toBe(false);expect(w.findComponent({name:'HistoryContextDrawer'}).exists()).toBe(false);expect(w.text()).toContain('办理借阅');expect(w.text()).toContain('柜台多本借阅');expect(w.text()).toContain('扫码 / 批量归还');expect(api.preview).not.toHaveBeenCalled();expect(api.confirm).not.toHaveBeenCalled();expect(api.cancel).not.toHaveBeenCalled()
})

it('does not turn an export limit failure into a download or an empty query',async()=>{
  api.borrows.mockResolvedValue(inquiryPage([inquiryRecord()]));api.exportFile.mockRejectedValueOnce(new BorrowInquiryError(400,'结果超过20000条，请缩小筛选范围'));const w=await page(BorrowManagement);await click(w,'导出全部筛选结果');expect(w.text()).toContain('请缩小筛选范围');expect(w.text()).toContain('合成绘本');expect(api.download).not.toHaveBeenCalled()
})

it('keeps a dated renewal and explicit multi-return selections on the original record IDs and date meanings',async()=>{
  const dated={...inquiryRecord(),dueDate:'2026-11-01',dueDateKind:'DATED',remark:'原备注'},unknown={...inquiryRecord(502),copyId:null,copyBarcode:null,dueDateKind:'UNKNOWN'}
  api.borrows.mockResolvedValue(inquiryPage([dated,unknown]));const w=await page(BorrowManagement);await click(w,'续借');const renewal=w.findComponent({name:'RenewDialog'});expect(renewal.props('record')).toMatchObject({id:501,dueDate:'2026-11-01',dueDateKind:'DATED',remark:'原备注',userId:101,bookId:201});renewal.vm.$emit('update:visible',false);await flushPromises()
  w.findAllComponents({name:'ElTable'})[1]!.vm.$emit('selection-change',[dated,unknown]);await flushPromises();await click(w,'预览归还所选 2 册');expect(w.findComponent({name:'ReturnDialog'}).props('initialRecords')).toEqual([{id:501,bookTitle:'合成绘本',copyBarcode:' 000Aa ',userName:'合成会员',dueDate:'2026-11-01',dueDateKind:'DATED'},{id:502,bookTitle:'合成绘本',copyBarcode:null,userName:'合成会员',dueDate:null,dueDateKind:'UNKNOWN'}]);expect(api.preview).not.toHaveBeenCalled();expect(api.confirm).not.toHaveBeenCalled()
})

it('keeps the next query page stable while its actual pagination component waits on the request',async()=>{
  let release!:(value:ReturnType<typeof inquiryPage>)=>void
  api.borrows.mockImplementation(query=>Promise.resolve(inquiryPage([inquiryRecord()],query,query.page,21,query.size)));const w=await page(BorrowManagement);api.borrows.mockImplementationOnce(()=>new Promise(resolve=>release=resolve));await w.findAll('.btn-next')[1]!.trigger('click');await flushPromises();expect(api.borrows).toHaveBeenLastCalledWith({...inquiryFilters(),page:2,size:10},expect.any(AbortSignal));release(inquiryPage([inquiryRecord(502)],inquiryFilters(),2,21));await flushPromises();expect(w.findAll('.el-pager')[1]!.find('.is-active').text()).toBe('2');expect(api.borrows).toHaveBeenCalledTimes(2)
})
