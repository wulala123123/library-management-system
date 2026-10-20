import { assertWriteDefinition } from '@/api/memberFinanceWriteValidation'
import { compareFinanceIds, financeId } from '@/api/memberFinance'
import type { RecoveryRecord } from '@/api/memberFinanceWrite'
const STORE='recovery',NAME='member-finance-recovery-v1'
export class FinanceStorageError extends Error { constructor(){super('本机无法安全保存办理恢复标识，财务写入已停用；只读台账仍可使用。')} }
export interface FinanceRecoveryStore {
  read(actor:string):Promise<RecoveryRecord|null>
  save(record:RecoveryRecord,create?:boolean):Promise<void>
  replaceTerminal(record:RecoveryRecord,previousKey:string):Promise<void>
  acknowledge(actor:string,key:string):Promise<void>
  close():void
}
/** Browser origin isolates the database; actorId is the sole primary key. No input is stored. */
export function createFinanceRecoveryStore(factory:IDBFactory=globalThis.indexedDB):FinanceRecoveryStore {
  let db:IDBDatabase|null=null,opening:Promise<IDBDatabase>|null=null,closed=false
  const open=()=>opening??=(new Promise<IDBDatabase>((resolve,reject)=>{
    if(!factory||closed){reject(new FinanceStorageError());return}
    const r=factory.open(NAME,1)
    r.onupgradeneeded=()=>r.result.createObjectStore(STORE,{keyPath:'actorId'})
    r.onblocked=r.onerror=()=>reject(new FinanceStorageError())
    r.onsuccess=()=>{if(closed){r.result.close();reject(new FinanceStorageError());return}db=r.result;db.onversionchange=()=>db?.close();resolve(db)}
  }))
  async function transaction<T>(actor:string, mode:IDBTransactionMode, apply:(store:IDBObjectStore,old:RecoveryRecord|null,set:(v:T)=>void)=>void):Promise<T>{
    if(!financeId(actor))throw new FinanceStorageError()
    try {const db=await open();return await new Promise<T>((resolve,reject)=>{
      const tx=db.transaction(STORE,mode),store=tx.objectStore(STORE);let answer:T
      tx.oncomplete=()=>resolve(answer);tx.onabort=tx.onerror=()=>reject(new FinanceStorageError())
      const request=store.get(actor)
      request.onsuccess=()=>{try{let old=request.result??null;if(old){if(!Object.prototype.hasOwnProperty.call(old,'factKey'))old={...old,factKey:null};assertWriteDefinition('RecoveryRecord',old);if(old.actorId!==actor||old.confirmIntent&&old.confirmIntent.operationKey!==old.operationKey||old.cancelIntent&&(old.cancelIntent.operationKey!==old.operationKey||old.cancelIntent.memberId!==old.memberId))throw Error()}apply(store,old,v=>{answer=v})}catch{tx.abort()}}
    })}catch{throw new FinanceStorageError()}
  }
  return {
    read:actor=>transaction(actor,'readonly',(_s,old,set)=>set(old)),
    async save(record,create=false){assertWriteDefinition('RecoveryRecord',record);await transaction<void>(record.actorId,'readwrite',(store,old)=>{
      if(create&&old||!create&&old?.operationKey!==record.operationKey||old?.factKey&&record.factKey!==old.factKey||old?.lastEditor&&record.lastEditor&&compareFinanceIds(old.lastEditor.epoch,record.lastEditor.epoch)>0)throw Error()
      store.put(record)
    })},
    async acknowledge(actor,key){await transaction<void>(actor,'readwrite',(store,old)=>{if(!old||old.operationKey!==key||old.stage!=='TERMINAL_UNACKNOWLEDGED')throw Error();store.delete(actor)})},
    async replaceTerminal(record,previousKey){assertWriteDefinition('RecoveryRecord',record);await transaction<void>(record.actorId,'readwrite',(store,old)=>{
      if(!old||old.operationKey!==previousKey||old.stage!=='TERMINAL_UNACKNOWLEDGED'||record.operationKey===previousKey||record.memberId!==old.memberId||record.stage!=='RESERVING'||record.factKey===null||old.factKey!==null&&record.factKey!==old.factKey)throw Error()
      store.put(record)
    })},
    close(){closed=true;db?.close()},
  }
}
