import { computed, onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue'
import { FINANCE_ACCESS_REVOKED, financeId, type MemberFinance } from '@/api/memberFinance'
import { assertOperationBinding, createFinanceWriteApi, FinanceWriteError, type CancelReceipt, type Editor, type Operation, type ReceiptInput, type RecoveryRecord } from '@/api/memberFinanceWrite'
import { assertWriteDefinition } from '@/api/memberFinanceWriteValidation'
import { createFinanceRecoveryStore, FinanceStorageError, type FinanceRecoveryStore } from './financeRecoveryStore'
import type { FinanceReaderIdentity } from './financeReadSession'

const terminalState=(s:string)=>['COMMITTED','CANCELLED','EXPIRED','REJECTED'].includes(s)
const uuid=()=>crypto.randomUUID()
const whole=(pattern:RegExp,value:string)=>{const match=pattern.exec(value);return match?.[0]===value}
export const beijingTime=(utc:string)=>new Date(Date.parse(utc)+8*3600000).toISOString().slice(0,19).replace('T',' ')
export function beijingInputToUtc(text:string):string {
  if(!whole(/^\d{4}-\d\d-\d\dT(?:[01]\d|2[0-3]):[0-5]\d(?::[0-5]\d)?$/,text))throw Error('INVALID_ACTUAL_TIME')
  const seconds=text.length===16?text+':00':text,date=new Date(seconds+'+08:00')
  if(isNaN(date.getTime())||beijingTime(date.toISOString()).replace(' ','T')!==seconds)throw Error('INVALID_ACTUAL_TIME')
  return date.toISOString().replace(/\.(\d{3})Z$/,'.$1000Z')
}
export function receiptMoney(input:string):string {
  if(!whole(/^(?:0|[1-9][0-9]{0,14})(?:\.[0-9]{1,2})?$/,input)||!/[1-9]/.test(input))throw Error('MONEY_PRECISION')
  const [integer,fraction='']=input.split('.');return integer+'.'+fraction.padEnd(2,'0')
}
export function useFinanceReceiptSession(member:Ref<string|null>,identity:Ref<FinanceReaderIdentity>,summary:Ref<MemberFinance|null>,active:Ref<boolean>,store:FinanceRecoveryStore=createFinanceRecoveryStore()) {
  const record=shallowRef<RecoveryRecord|null>(null),operation=shallowRef<Operation|null>(null),cancelReceipt=shallowRef<CancelReceipt|null>(null)
  const error=ref(''),busy=ref(false),storageReady=ref(false),scanned=ref(false),revoked=ref(false),ownFence=shallowRef<Editor|null>(null),factKey=ref(''),reentering=ref(false)
  const form=ref({fund:'MEMBER_FEE' as 'MEMBER_FEE'|'DEPOSIT',amount:'',actualAt:'',methodCode:'CASH' as 'CASH'|'WECHAT',reason:'',evidence:''})
  let editorId=uuid(),generation=0,sequence=0,controller=new AbortController(),pendingInput:ReceiptInput|null=null,channel:BroadcastChannel|null=null,disposed=false
  const canAccess=computed(()=>active.value&&identity.value.role==='ADMIN'&&financeId(identity.value.id)&&!!identity.value.token&&!revoked.value)
  const owns=computed(()=>!!ownFence.value&&operation.value?.editor.editorId===ownFence.value.editorId&&operation.value?.editor.epoch===ownFence.value.epoch)
  const terminal=computed(()=>cancelReceipt.value?.state??operation.value?.result?.state??null)
  const receipt=computed(()=>cancelReceipt.value?.receipt??operation.value?.result?.receipt??null)
  const preview=computed(()=>operation.value?.request?.preview??null)
  const targetMatches=computed(()=>record.value?.memberId===member.value)
  const canStart=computed(()=>canAccess.value&&storageReady.value&&scanned.value&&(!record.value||reentering.value&&terminal.value&&terminal.value!=='COMMITTED')&&!busy.value&&summary.value?.memberId===member.value)
  const serverDate=computed(()=>operation.value?beijingTime(operation.value.serverNow).slice(0,10):null)
  const canConfirm=computed(()=>owns.value&&!busy.value&&operation.value?.state==='PREVIEWED'&&operation.value.serverNow<operation.value.request!.expiresAt&&operation.value.input?.action==='RECEIPT'&&preview.value?.canConfirm===true&&preview.value.blocks.length===0&&preview.value.identity.accountStatus===1&&preview.value.policy.enabledActions.length===1&&preview.value.policy.enabledActions[0]==='RECEIPT'&&preview.value.policy.financialTimeZone==='Asia/Shanghai'&&preview.value.policy.paymentMethodsState==='CONFIRMED'&&preview.value.policy.datePolicyState==='CONFIRMED'&&['CASH','WECHAT'].includes(operation.value.input.payload.methodCode)&&beijingTime(operation.value.input.payload.actualAt).slice(0,10)===serverDate.value&&!!preview.value.policy.cutoffAt&&operation.value.input.payload.actualAt>=preview.value.policy.cutoffAt&&operation.value.input.payload.actualAt<=operation.value.serverNow)
  function clearMemory(){++generation;++sequence;controller.abort();controller=new AbortController();record.value=null;operation.value=null;cancelReceipt.value=null;ownFence.value=null;pendingInput=null;factKey.value='';reentering.value=false;form.value={fund:'MEMBER_FEE',amount:'',actualAt:'',methodCode:'CASH',reason:'',evidence:''};error.value='';busy.value=false;storageReady.value=false;scanned.value=false;editorId=uuid()}
  function revoke(){revoked.value=true;clearMemory()}
  const context=()=>({generation,actor:identity.value.id!,member:member.value,token:identity.value.token,seq:++sequence})
  type Context=ReturnType<typeof context>
  const current=(c:Context)=>!disposed&&canAccess.value&&c.generation===generation&&c.seq===sequence&&c.actor===identity.value.id&&c.member===member.value&&c.token===identity.value.token
  const api=(c:Context)=>createFinanceWriteApi(c.token,controller.signal)
  const notify=()=>{try{channel?.postMessage({schemaVersion:1,actorId:identity.value.id})}catch{/* Notification is optional; server fences remain authoritative. */}}
  function metadata(op:Operation,old:RecoveryRecord|null):RecoveryRecord{return {schemaVersion:1,actorId:op.actorId,memberId:op.memberId,operationKey:op.operationKey,stage:terminalState(op.state)?'TERMINAL_UNACKNOWLEDGED':'ACTIVE',factKey:op.input?.action==='RECEIPT'?op.input.payload.factKey:old?.factKey??null,draftHash:op.draftHash,previewDigest:op.request?.previewDigest??null,requestId:op.request?.requestId??null,lastEditor:op.editor,confirmIntent:old?.confirmIntent??null,cancelIntent:old?.cancelIntent??null,updatedAt:op.updatedAt}}
  async function persist(c:Context,next:RecoveryRecord,create=false,previousKey?:string){assertWriteDefinition('RecoveryRecord',next);if(previousKey)await store.replaceTerminal(next,previousKey);else await store.save(next,create);if(!current(c))return false;record.value=next;return true}
  async function accept(c:Context,op:Operation,key?:string,claim=false,changed=false){if(!current(c))return;assertOperationBinding(op,c.actor,member.value??undefined,key);if(operation.value&&operation.value.operationKey===op.operationKey&&BigInt(op.editor.epoch)<BigInt(operation.value.editor.epoch))return
    if(record.value?.factKey&&op.input?.action==='RECEIPT'&&record.value.factKey!==op.input.payload.factKey)throw new FinanceWriteError(null,'INVALID_RESPONSE')
    const next=metadata(op,record.value);if(!await persist(c,next,!record.value))return
    operation.value=op;cancelReceipt.value=null
    if(claim&&op.editor.editorId===editorId&&!terminalState(op.state))ownFence.value={...op.editor}
    if(op.input?.action==='RECEIPT')factKey.value=op.input.payload.factKey
    if(claim||changed)notify()
  }
  function failed(c:Context,cause:unknown){if(!current(c))return;if(cause instanceof FinanceWriteError&&(cause.status===401||cause.status===403)){revoke();return}
    if(cause instanceof FinanceStorageError){storageReady.value=false;error.value=cause.message;return}
    const code=cause instanceof FinanceWriteError?cause.code:cause instanceof Error&&['MONEY_PRECISION','INVALID_ACTUAL_TIME'].includes(cause.message)?cause.message:'UNKNOWN_RESULT'
    if(code==='UNKNOWN_RESULT'||code==='INVALID_RESPONSE'||cause instanceof FinanceWriteError&&(cause.status??0)>=500)ownFence.value=null
    if(code==='EDITOR_CHANGED'||code==='OWNER_REQUIRED'){ownFence.value=null;operation.value=null;pendingInput=null}
    error.value=code==='MONEY_PRECISION'?'金额须为大于零的人民币金额，最多两位小数，不舍入，不接受空白、指数或前导零。':code==='INVALID_ACTUAL_TIME'?'请填写真实的实际收款北京时间，不能使用本机时间自动代填。':code==='RULE_NOT_CONFIRMED'?'服务器尚未启用收款政策或相关规则未确认。请核对原办理，不能绕过阻断。':code==='OPERATION_NOT_FOUND'?'暂未查询到登记结果，仍可能已处理。请核对并取消原登记后再重新录入。':code==='PREVIEW_BINDING_REQUIRED'?'取消尚未接纳，预览已先保存。请重新读取并接管，再核对取消。':code==='ACTIVE_OPERATION_EXISTS'?'已有未决办理，请读取并恢复原办理。':code==='INVALID_RESPONSE'?'返回资料校验失败，结果未知；请恢复原办理，不要重复登记。':'登记结果尚需核对。请恢复登记；不要重复登记同一笔款项。'
  }
  async function run(action:(c:Context)=>Promise<void>){if(!canAccess.value||busy.value)return;const c=context();busy.value=true;error.value='';try{await action(c)}catch(e){failed(c,e)}finally{if(current(c))busy.value=false}}
  async function observe(){await run(async c=>{if(!storageReady.value)return;const local=await store.read(c.actor);if(!current(c))return;record.value=local;if(local&&local.memberId===member.value)factKey.value=local.factKey??''
    const activeOp=await api(c).active();if(!current(c))return
    if(activeOp){assertOperationBinding(activeOp,c.actor);if(local&&local.operationKey!==activeOp.operationKey){error.value='本地恢复键与服务端开放办理不同，请先核对原键；不能开始第二笔。';scanned.value=false;return}
      if(activeOp.memberId!==member.value){if(!local){await store.save(metadata(activeOp,null),true);if(current(c))record.value=metadata(activeOp,null)}scanned.value=true;return}
      await accept(c,activeOp,activeOp.operationKey)
    }else if(local&&local.memberId===member.value){await accept(c,await api(c).byKey(local.operationKey),local.operationKey)}
    if(current(c))scanned.value=true
  })}
  async function initialize(){if(!canAccess.value)return;const c=context();busy.value=true;try{await store.read(c.actor);if(!current(c))return;storageReady.value=true}catch(e){failed(c,e)}finally{if(current(c))busy.value=false}if(current(c)&&storageReady.value)await observe()}
  async function start(){if(!canStart.value)return;await run(async c=>{
    const s=summary.value!,fund=form.value.fund,input:ReceiptInput={memberId:c.member!,memberRevision:s.memberRevision,financeRevision:s.accounts.find(a=>a.fund===fund)!.financeRevision,fund,currency:'CNY',action:'RECEIPT',reason:form.value.reason,evidenceRefs:[{type:'COUNTER_VOUCHER',reference:form.value.evidence,digest:null}],payload:{factKey:reentering.value?(record.value!.factKey??(factKey.value||uuid())):(factKey.value||uuid()),amount:receiptMoney(form.value.amount),actualAt:beijingInputToUtc(form.value.actualAt),methodCode:form.value.methodCode,paymentState:'RECORDED',businessRef:{type:'COUNTER_REGISTRATION',id:''}}}
    input.payload.businessRef.id=input.payload.factKey;assertWriteDefinition('ReceiptInput',input);factKey.value=input.payload.factKey;pendingInput=input
    const key=uuid(),next:RecoveryRecord={schemaVersion:1,actorId:c.actor,operationKey:key,memberId:c.member!,stage:'RESERVING',factKey:input.payload.factKey,draftHash:null,previewDigest:null,requestId:null,lastEditor:null,confirmIntent:null,cancelIntent:null,updatedAt:new Date().toISOString()}
    if(!await persist(c,next,true,reentering.value?record.value!.operationKey:undefined))return
    reentering.value=false;operation.value=null;cancelReceipt.value=null;ownFence.value=null
    const op=await api(c).reserve({schemaVersion:1,operationKey:key,memberId:c.member!,editorId});await accept(c,op,key,true)
    if(current(c)&&owns.value&&op.state==='RESERVED')await saveDraft(c)
  })}
  async function saveDraft(c:Context){if(!pendingInput||!owns.value||!record.value||!ownFence.value) return
    const key=record.value.operationKey;if(!await persist(c,{...record.value,stage:'DRAFTING'}))return
    await accept(c,await api(c).draft({schemaVersion:1,operationKey:key,editor:{...ownFence.value},input:pendingInput}),key,false,true)
  }
  async function claim(){if(!targetMatches.value||!operation.value||terminal.value)return;await run(async c=>{const op=operation.value!,result=await api(c).claim({schemaVersion:1,operationKey:op.operationKey,memberId:op.memberId,expectedEpoch:op.editor.epoch,editorId,claimKey:uuid()});await accept(c,result,op.operationKey,true)
    if(current(c)&&record.value&&owns.value&&!terminal.value){await persist(c,{...record.value,confirmIntent:null,cancelIntent:null})}
  })}
  async function previewDraft(){if(!owns.value||operation.value?.state!=='DRAFT_READY'||!operation.value.draftHash)return;await run(async c=>{const op=operation.value!;if(!await persist(c,{...record.value!,stage:'PREVIEWING'}))return;await accept(c,await api(c).preview({schemaVersion:1,operationKey:op.operationKey,editor:{...ownFence.value!},draftHash:op.draftHash!}),op.operationKey,false,true)})}
  async function confirm(){if(!canConfirm.value)return;await run(async c=>{const op=operation.value!,body={schemaVersion:1 as const,operationKey:op.operationKey,editor:{...ownFence.value!},previewDigest:op.request!.previewDigest};if(!await persist(c,{...record.value!,stage:'CONFIRMING',confirmIntent:body}))return;await accept(c,await api(c).confirm(body),op.operationKey,false,true)})}
  async function cancel(){if(!record.value||!targetMatches.value||terminal.value)return;await run(async c=>{const rec=record.value!;let body=rec.cancelIntent
    if(!body){if(operation.value&&!owns.value)throw new FinanceWriteError(null,'EDITOR_CHANGED');body={schemaVersion:1,operationKey:rec.operationKey,memberId:rec.memberId,editor:operation.value?{...ownFence.value!}:{editorId,epoch:'0'},cancelKey:uuid(),previewDigest:operation.value?.request?.previewDigest??null,reason:operation.value?'USER_CANCELLED':'RESTART_AFTER_UNKNOWN'}}
    if(!await persist(c,{...rec,stage:'CANCELLING',cancelIntent:body}))return
    const result=await api(c).cancel(body);if(!current(c))return
    if(result.memberId!==rec.memberId||result.operationKey!==rec.operationKey||result.cancelKey!==body.cancelKey||result.previewDigest!==body.previewDigest||result.reason!==body.reason||result.receipt&&(result.receipt.memberId!==rec.memberId||result.receipt.operationKey!==rec.operationKey||result.receipt.requestId!==result.requestId||result.receipt.audit.operatorId!==c.actor))throw new FinanceWriteError(null,'INVALID_RESPONSE')
    let resultFact=record.value!.factKey
    if(result.receipt?.action==='RECEIPT'){const entry=result.receipt.entriesAtCommit[0];assertWriteDefinition('Uuid',entry.rootFactKey);if(result.receipt.entriesAtCommit.length!==1||entry.kind!=='RECEIPT'||resultFact&&entry.rootFactKey!==resultFact||result.receipt.previewDigest!==result.previewDigest)throw new FinanceWriteError(null,'INVALID_RESPONSE');resultFact=entry.rootFactKey}
    if(!await persist(c,{...record.value!,factKey:resultFact,stage:'TERMINAL_UNACKNOWLEDGED',requestId:result.requestId}))return;cancelReceipt.value=result;ownFence.value=null;pendingInput=null;notify()
  })}
  async function resolve(){if(!record.value||!targetMatches.value)return;await run(async c=>{const rec=record.value!;await accept(c,await api(c).resolve({schemaVersion:1,operationKey:rec.operationKey,memberId:rec.memberId}),rec.operationKey,false,true)})}
  function reenterSame(){if(!record.value||!terminal.value||terminal.value==='COMMITTED'||receipt.value||!targetMatches.value)return;factKey.value=record.value.factKey??uuid();form.value={fund:'MEMBER_FEE',amount:'',actualAt:'',methodCode:'CASH',reason:'',evidence:''};reentering.value=true;scanned.value=true}
  async function acknowledge(){if(!record.value||!terminal.value)return;await run(async c=>{await store.acknowledge(c.actor,record.value!.operationKey);if(!current(c))return;record.value=null;operation.value=null;cancelReceipt.value=null;ownFence.value=null;pendingInput=null;factKey.value='';reentering.value=false;form.value={fund:'MEMBER_FEE',amount:'',actualAt:'',methodCode:'CASH',reason:'',evidence:''};scanned.value=false;notify()});if(canAccess.value)await observe()}
  watch(()=>[member.value,identity.value.id,identity.value.role,identity.value.token,active.value],()=>{clearMemory();revoked.value=false;void initialize()},{immediate:true,flush:'sync'})
  const storageChanged=(e:StorageEvent)=>{if(e.key===null||e.key==='token'||e.key==='userInfo')revoke()}
  const focus=()=>{if(identity.value.token&&localStorage.getItem('token')!==identity.value.token)revoke()}
  if(typeof BroadcastChannel!=='undefined'){try{channel=new BroadcastChannel('member-finance-recovery-v1');channel.onmessage=e=>{const v=e.data;if(v&&Object.keys(v).length===2&&v.schemaVersion===1&&v.actorId===identity.value.id){clearMemory();void initialize()}}}catch{channel=null}}
  window.addEventListener(FINANCE_ACCESS_REVOKED,revoke);window.addEventListener('auth-access-invalidated',revoke);window.addEventListener('storage',storageChanged);window.addEventListener('focus',focus)
  onBeforeUnmount(()=>{disposed=true;clearMemory();store.close();channel?.close();window.removeEventListener(FINANCE_ACCESS_REVOKED,revoke);window.removeEventListener('auth-access-invalidated',revoke);window.removeEventListener('storage',storageChanged);window.removeEventListener('focus',focus)})
  return {form,factKey,reentering,reenterSame,record,operation,cancelReceipt,preview,receipt,error,busy,storageReady,scanned,canAccess,canStart,owns,terminal,targetMatches,canConfirm,serverDate,start,observe,claim,previewDraft,confirm,cancel,resolve,acknowledge,retryDraft:()=>run(saveDraft)}
}
