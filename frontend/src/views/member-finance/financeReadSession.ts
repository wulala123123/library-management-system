import { computed, onBeforeUnmount, ref, shallowRef, watch, type Ref } from 'vue'
import { FINANCE_ACCESS_REVOKED, FinanceReadError, compareFinanceIds, financeId, getMemberFinance, getMemberFinanceEntries, getMemberFinanceSource, type FinanceEntries, type FinanceFund, type FinanceSource, type MemberFinance } from '@/api/memberFinance'

export interface FinanceReaderIdentity { id: string | null; role: string | null; token: string }
/** Memory only. Each target, refresh and page has its own response fence. */
export function useFinanceReadSession(memberId: Ref<string | null>, identity: Ref<FinanceReaderIdentity>, active: Ref<boolean> = ref(true)) {
  const finance = shallowRef<MemberFinance | null>(null), source = shallowRef<FinanceSource | null>(null), page = shallowRef<FinanceEntries | null>(null)
  const fund = ref<FinanceFund>('MEMBER_FEE'), beforeIds = ref<(string | null)[]>([null]), pageIndex = ref(0)
  const financeLoading = ref(false), sourceLoading = ref(false), entriesLoading = ref(false)
  const financeError = ref(''), sourceError = ref(''), entriesError = ref(''), accessRevoked = ref(false)
  const canRead = computed(() => active.value && identity.value.role === 'ADMIN' && !!identity.value.id && !!identity.value.token && !accessRevoked.value)
  let generation=0, entriesGeneration=0, sourceGeneration=0, controller: AbortController | null=null, entriesController:AbortController|null=null
  function clear() { ++generation; ++entriesGeneration; ++sourceGeneration; controller?.abort(); entriesController?.abort(); controller=null; entriesController=null; finance.value=null; source.value=null; page.value=null; beforeIds.value=[null];pageIndex.value=0;financeLoading.value=false;sourceLoading.value=false;entriesLoading.value=false;financeError.value='';sourceError.value='';entriesError.value='' }
  function revoke() { accessRevoked.value=true;clear() }
  function problem(cause: unknown) { return cause instanceof FinanceReadError ? cause.message : '财务资料读取失败，请重试；本次失败不表示账本为空。' }
  function failedAccess(cause:unknown) { if(cause instanceof FinanceReadError && (cause.status===401||cause.status===403)){revoke();return true}return false }
  function target() { return `${identity.value.id ?? ''}/${identity.value.role ?? ''}/${identity.value.token}/${memberId.value ?? ''}` }
  async function refresh() {
    clear()
    if(!canRead.value || !memberId.value || !financeId(memberId.value))return
    const request=generation, bound=target(), id=memberId.value, signal=(controller=new AbortController()).signal
    const current=()=>request===generation&&canRead.value&&target()===bound
    financeLoading.value=true
    try {
      const result=await getMemberFinance(id,signal)
      if(!current())return
      if(result.memberId!==id)throw new FinanceReadError(null,'INVALID_RESPONSE')
      finance.value=result
      await Promise.all([loadSource(request,bound,id,signal),loadEntries()])
    } catch(cause) { if(current()&&!failedAccess(cause))financeError.value=problem(cause) }
    finally { if(current())financeLoading.value=false }
  }
  async function loadSource(request=generation,bound=target(),id=memberId.value,signal=controller?.signal) {
    if(!canRead.value||!id)return
    const sourceRequest=++sourceGeneration,current=()=>request===generation&&sourceRequest===sourceGeneration&&canRead.value&&target()===bound
    source.value=null;sourceError.value='';sourceLoading.value=true
    try {const result=await getMemberFinanceSource(id,signal);if(!current())return;if(result.memberId!==id)throw new FinanceReadError(null,'INVALID_RESPONSE');source.value=result}
    catch(cause){if(current()&&!failedAccess(cause))sourceError.value=problem(cause)}finally{if(current())sourceLoading.value=false}
  }
  async function loadEntries() {
    const summary=finance.value,id=memberId.value,selectedFund=fund.value,beforeId=beforeIds.value[pageIndex.value]??null,snapshot=summary?.asRecordedAt
    ++entriesGeneration;entriesController?.abort();page.value=null;entriesError.value='';entriesLoading.value=false
    if(!canRead.value||!summary||!id||!snapshot)return
    const request=entriesGeneration,parent=generation,bound=target(),signal=(entriesController=new AbortController()).signal
    const current=()=>request===entriesGeneration&&parent===generation&&canRead.value&&target()===bound&&fund.value===selectedFund&&finance.value?.asRecordedAt===snapshot
    entriesLoading.value=true
    try {
      const result=await getMemberFinanceEntries(id,{fund:selectedFund,currency:'CNY',beforeId,size:20,asRecordedAt:snapshot},signal)
      if(!current())return
      if(result.memberId!==id||result.fund!==selectedFund||result.currency!=='CNY'||result.beforeId!==beforeId||result.asRecordedAt!==snapshot||result.size!==20||result.entries.length>result.size||result.entries.some((entry,index)=>entry.asOf!==snapshot||entry.recordedAt>snapshot||(beforeId!==null&&compareFinanceIds(entry.id,beforeId)>=0)||(index>0&&compareFinanceIds(result.entries[index-1]!.id,entry.id)<=0))||(result.hasMore?(!result.entries.length||result.nextBeforeId!==result.entries[result.entries.length-1]?.id):result.nextBeforeId!==null))throw new FinanceReadError(null,'INVALID_RESPONSE')
      const account=summary.accounts.find(account=>account.fund===selectedFund)
      if(result.entries.some(entry=>entry.accountId!==account?.accountId))throw new FinanceReadError(null,'INVALID_RESPONSE')
      page.value=result
    } catch(cause){if(current()&&!failedAccess(cause))entriesError.value=problem(cause)}finally{if(current())entriesLoading.value=false}
  }
  function next(){if(!page.value?.hasMore||!page.value.nextBeforeId||entriesLoading.value)return;beforeIds.value=[...beforeIds.value.slice(0,pageIndex.value+1),page.value.nextBeforeId];pageIndex.value++;loadEntries()}
  function previous(){if(pageIndex.value<1||entriesLoading.value)return;pageIndex.value--;loadEntries()}
  watch(()=>[memberId.value,identity.value.id,identity.value.role,identity.value.token,active.value],()=>{clear();accessRevoked.value=false;fund.value='MEMBER_FEE';refresh()},{immediate:true,flush:'sync'})
  watch(fund,()=>{beforeIds.value=[null];pageIndex.value=0;loadEntries()},{flush:'sync'})
  const storageChanged=(event:StorageEvent)=>{if(event.key===null||event.key==='token'||event.key==='userInfo')revoke()}
  const storageCheck=()=>{if(identity.value.token&&localStorage.getItem('token')!==identity.value.token)revoke()}
  window.addEventListener(FINANCE_ACCESS_REVOKED,revoke);window.addEventListener('auth-access-invalidated',revoke);window.addEventListener('storage',storageChanged);window.addEventListener('focus',storageCheck)
  onBeforeUnmount(()=>{clear();window.removeEventListener(FINANCE_ACCESS_REVOKED,revoke);window.removeEventListener('auth-access-invalidated',revoke);window.removeEventListener('storage',storageChanged);window.removeEventListener('focus',storageCheck)})
  return {finance,source,page,fund,pageIndex,canRead,accessRevoked,financeLoading,sourceLoading,entriesLoading,financeError,sourceError,entriesError,refresh,retrySource:()=>loadSource(),retryEntries:loadEntries,next,previous}
}
