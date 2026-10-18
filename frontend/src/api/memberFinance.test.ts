import {beforeEach,afterEach,expect,it,vi} from 'vitest'
import {assertFinance,assertFinanceEntries,assertFinanceSource,financeId,financeUtc,getMemberFinance,getMemberFinanceEntries,getMemberFinanceSource,parseFinanceJson,FINANCE_ACCESS_REVOKED} from './memberFinance'
import {financeEnvelope,financeErrorFixture,financeEntriesFixture,financeEntryFixture,financeFixture,financeSnapshot,financeSourceFixture,unknownFinanceAccount} from '@/views/member-finance/financeFixtures'
import examples from '@/views/member-finance/contractReadExamples.json'
import {financeAmountText,financeSourceText} from '@/views/member-finance/financeDisplay'
const response=(payload:unknown,status=200)=>({status,arrayBuffer:async()=>new TextEncoder().encode(typeof payload==='string'?payload:JSON.stringify(payload)).buffer})
beforeEach(()=>{localStorage.clear();vi.stubGlobal('fetch',vi.fn())})
afterEach(()=>vi.unstubAllGlobals())
it.each(examples.cases)('matches frozen contract read example $name',test=>{const check={FinanceEnvelope:assertFinance,EntriesEnvelope:assertFinanceEntries,SourceEnvelope:assertFinanceSource}[test.definition]!;if(test.expectedValid)expect(()=>check(test.value)).not.toThrow();else expect(()=>check(test.value)).toThrow()})
it('sends only three GETs with exact no-query summary/source and stable string cursor query',async()=>{localStorage.setItem('token','synthetic-token');const fetch=vi.mocked(globalThis.fetch);fetch.mockResolvedValueOnce(response(financeEnvelope(financeFixture()))as any).mockResolvedValueOnce(response(financeEnvelope(financeSourceFixture()))as any).mockResolvedValueOnce(response(financeEnvelope(financeEntriesFixture()))as any);await getMemberFinance('1');await getMemberFinanceSource('1');await getMemberFinanceEntries('1',{fund:'MEMBER_FEE',currency:'CNY',beforeId:'9007199254741009',size:20,asRecordedAt:financeSnapshot});expect(fetch.mock.calls.map(call=>call[0])).toEqual(['/api/members/1/finance','/api/members/1/finance/source','/api/members/1/finance/entries?fund=MEMBER_FEE&currency=CNY&size=20&beforeId=9007199254741009&asRecordedAt=2026-10-19T08%3A00%3A00.123456Z']);expect(fetch.mock.calls[0][1]).toMatchObject({method:'GET',headers:{Authorization:'Bearer synthetic-token'},credentials:'same-origin',cache:'no-store'});expect(fetch.mock.calls.every(call=>!('body'in(call[1]||{})))).toBe(true)})
it('omits absent cursor/timestamp parameters on an initial entries query',async()=>{vi.mocked(fetch).mockResolvedValue(response(financeEnvelope(financeEntriesFixture()))as any);await getMemberFinanceEntries('1',{fund:'MEMBER_FEE',currency:'CNY',beforeId:null,size:20,asRecordedAt:null});expect(fetch).toHaveBeenCalledWith('/api/members/1/finance/entries?fund=MEMBER_FEE&currency=CNY&size=20',expect.anything())})
it.each(['0','01','-1','1.2','9223372036854775808','1?fund=DEPOSIT',' 1 ',1,null])('rejects unsafe or noncanonical member id %j without sending',memberId=>{expect(()=>getMemberFinance(memberId as string)).toThrow();expect(fetch).not.toHaveBeenCalled();expect(financeId('9223372036854775807')).toBe(true)})
it.each([{fund:'MEMBER_FEE',currency:'CNY',beforeId:null,size:101,asRecordedAt:null},{fund:'MEMBER_FEE',currency:'CNY',beforeId:null,size:'20',asRecordedAt:null},{fund:'MEMBER_FEE',currency:'CNY',beforeId:'10',size:20,asRecordedAt:null},{fund:'MEMBER_FEE',currency:'CNY',beforeId:null,size:20,asRecordedAt:null,page:1}])('rejects invalid/extra page parameters %j',query=>{expect(()=>getMemberFinanceEntries('1',query as any)).toThrow();expect(fetch).not.toHaveBeenCalled()})
it.each(['{"code":200,"code":200}','{"x":1,"\\u0078":2}','{"s":"\\ud800"}','{"s":"\\udfff"}','{} null','[1,]','{"s":"unterminated}','NaN','{"a":01}'])('rejects duplicate keys or malformed response JSON %j',raw=>expect(()=>parseFinanceJson(raw)).toThrow())
it('keeps escaped quotes, nested arrays, emoji and own __proto__ properties without prototype pollution',()=>{const value:any=parseFinanceJson('{"text":" quote \\" 😀","array":[{"a":1}],"__proto__":{"canary":true}}'.replace('\\"','\\"'));expect(value.text).toContain('😀');expect(value.__proto__).toEqual({canary:true});expect(({}as any).canary).toBeUndefined()})
it.each([100.01,'1e2','1.000','01.00','+1.00',' 0.00 ','-0.00'])('rejects amount format %j rather than normalizing',amount=>{const input:any=financeEnvelope(financeFixture());input.data.accounts[0].balance.amount=amount;expect(()=>assertFinance(input)).toThrow()})
it('preserves large exact money and clear distinction between known zero, unknown, raw empty/null/missing',()=>{const value=financeEnvelope(financeFixture());expect(()=>assertFinance(value)).not.toThrow();expect(financeAmountText(value.data.accounts[0].balance)).toBe('999999999999979.98 元');expect(financeAmountText({state:'KNOWN',amount:'0.00',reasonCode:null})).toBe('0.00 元');expect(financeAmountText(unknownFinanceAccount().balance)).toContain('未知');const source=financeSourceFixture();expect(financeSourceText(source.fields[1]!)).toContain('空字符串');expect(financeSourceText(source.fields[2]!)).toContain('空值');expect(financeSourceText(source.fields[3]!)).toBe('  已缴原文  ')})
it.each(['extra','order','unknownAsZero','depositHeadroom','verifiedNoId','wrongMember','badTimestamp'])('rejects incorrect summary shape %s',mutation=>{const value:any=financeEnvelope(financeFixture());if(mutation==='extra')value.data.policy={};if(mutation==='order')value.data.accounts.reverse();if(mutation==='unknownAsZero')value.data.accounts[1].balance={state:'KNOWN',amount:'0.00',reasonCode:null};if(mutation==='depositHeadroom')value.data.accounts[1].feeHeadroom={state:'UNKNOWN',amount:null,reasonCode:'NOT_ASSESSED'};if(mutation==='verifiedNoId')value.data.accounts[0].openingEntryId=null;if(mutation==='wrongMember')value.data.accounts[0].memberId='2';if(mutation==='badTimestamp')value.data.asRecordedAt='2026-02-30T00:00:00.000000Z';expect(()=>assertFinance(value)).toThrow()})
it.each(['extra','duplicateKey','overlong','statusNumber','missingRaw','falseProof'])('rejects source whitelist violation %s',mutation=>{const value:any=financeEnvelope(financeSourceFixture());if(mutation==='extra')value.data.family='must not enter';if(mutation==='duplicateKey')value.data.fields[1].key=value.data.fields[0].key;if(mutation==='overlong')value.data.fields[0].rawText='1'.repeat(65);if(mutation==='statusNumber')value.data.fields[3].jsonType='NUMBER';if(mutation==='missingRaw')delete value.data.fields[0].rawText;if(mutation==='falseProof')value.data.sourceState='MANUAL';expect(()=>assertFinanceSource(value)).toThrow()})
it.each(['OPENING','RECEIPT','REFUND','REVERSAL'] as const)('accepts exact entry kind %s and requires its structural fields',kind=>{const value=financeEnvelope({...financeEntriesFixture(),entries:[financeEntryFixture('10',kind)],nextBeforeId:null,hasMore:false});expect(()=>assertFinanceEntries(value)).not.toThrow();const bad:any=structuredClone(value);if(kind==='OPENING')bad.data.entries[0].openingReceived=null;if(kind==='RECEIPT')bad.data.entries[0].actualAt=null;if(kind==='REFUND')bad.data.entries[0].allocations=[];if(kind==='REVERSAL')bad.data.entries[0].reversesEntryId=null;expect(()=>assertFinanceEntries(bad)).toThrow()})
it.each([401,403])('invalidates access immediately for HTTP %s and never returns body text',async status=>{const invalidate=vi.fn();window.addEventListener(FINANCE_ACCESS_REVOKED,invalidate);vi.mocked(fetch).mockResolvedValue(response(financeErrorFixture(status,status===401?'UNAUTHENTICATED':'ADMIN_REQUIRED'),status)as any);await expect(getMemberFinance('1')).rejects.toMatchObject({status});expect(invalidate).toHaveBeenCalledOnce();window.removeEventListener(FINANCE_ACCESS_REVOKED,invalidate)})
it('validates HTTP/error envelope agreement, does not expose backend messages, and rejects malformed UTF-8',async()=>{vi.mocked(fetch).mockResolvedValueOnce(response(financeErrorFixture(404,'MEMBER_NOT_FOUND'),404)as any);await expect(getMemberFinance('1')).rejects.toMatchObject({reason:'MEMBER_NOT_FOUND'});vi.mocked(fetch).mockResolvedValueOnce(response({...financeErrorFixture(404,'MEMBER_NOT_FOUND'),message:'private canary'},500)as any);await expect(getMemberFinance('1')).rejects.toMatchObject({reason:'INVALID_RESPONSE'});vi.mocked(fetch).mockResolvedValueOnce({status:200,arrayBuffer:async()=>Uint8Array.of(0xc3,0x28).buffer}as any);await expect(getMemberFinance('1')).rejects.toMatchObject({reason:'INVALID_RESPONSE'})})
it.each([400,404,500])('rejects financial write certainty in a GET error for HTTP %s',async status=>{const payload=financeErrorFixture(status,'INTERNAL_ERROR');payload.data.certainty='SAVED_TERMINAL';vi.mocked(fetch).mockResolvedValue(response(payload,status)as any);await expect(getMemberFinance('1')).rejects.toMatchObject({reason:'INVALID_RESPONSE'})})
it('requires real calendar dates and exact microsecond UTC precision without changing clocks',()=>{expect(financeUtc('2024-02-29T23:59:59.999999Z')).toBe(true);for(const stamp of ['2026-02-29T00:00:00.000000Z','2026-01-01T24:00:00.000000Z','2026-10-19T00:00:00Z','2026-10-19T00:00:00.123456+08:00'])expect(financeUtc(stamp)).toBe(false)})

const invalidScalarSuffixes=['\n','\r','\r\n','\u2028','\u2029',' ','\t','\v','\f','\u00a0','\u1680',...Array.from({length:11},(_,index)=>String.fromCharCode(0x2000+index)),'\u202f','\u205f','\u3000','\ufeff']
it.each(invalidScalarSuffixes)('rejects suffix %j on path IDs and cursor scalars before fetch',suffix=>{
  const query={fund:'MEMBER_FEE' as const,currency:'CNY' as const,beforeId:null,size:20,asRecordedAt:financeSnapshot}
  const id='1'+suffix
  expect(financeId(id)).toBe(false)
  expect(()=>getMemberFinance(id)).toThrow()
  expect(()=>getMemberFinanceSource(id)).toThrow()
  expect(()=>getMemberFinanceEntries(id,query)).toThrow()
  expect(()=>getMemberFinanceEntries('1',{...query,beforeId:'10'+suffix})).toThrow()
  expect(()=>getMemberFinanceEntries('1',{...query,asRecordedAt:financeSnapshot+suffix})).toThrow()
  expect(fetch).not.toHaveBeenCalled()
})
const scalarResponseCases=[
  {definition:'finance',paths:['memberId','memberRevision','accounts.0.accountId','accounts.0.financeRevision','accounts.0.openingEntryId','accounts.0.verifiedPaid.amount','accounts.0.verifiedRefunded.amount','accounts.0.balance.amount','accounts.0.feeHeadroom.amount','accounts.0.newReceipts','accounts.0.newRefunds','accounts.0.newNet','accounts.0.scopeCutoffAt','asRecordedAt']},
  {definition:'entries',paths:['entries.0.id','entries.0.accountId','entries.0.requestId','entries.0.policyDigest','entries.0.reason','entries.0.operatorId','entries.0.recordedAt','entries.0.asOf','entries.1.methodCode','entries.1.actualAt','nextBeforeId']},
  {definition:'source',paths:['proofDigest','archive.archiveHash','archive.archiveId','profile.sourceArchiveId','fields.0.reasonCode','asOf']},
].flatMap(group=>group.paths.map(path=>({definition:group.definition,path})))
it.each(scalarResponseCases)('rejects a suffixed frozen response scalar $definition.$path',({definition,path})=>{
  const check={finance:assertFinance,entries:assertFinanceEntries,source:assertFinanceSource}[definition]!
  const fixture={finance:financeFixture,entries:financeEntriesFixture,source:financeSourceFixture}[definition]!
  for(const suffix of invalidScalarSuffixes){
    const value:any=financeEnvelope(fixture())
    const keys=path.split('.'),leaf=keys.pop()!,owner=keys.reduce((current,key)=>current[key],value.data)
    expect(typeof owner[leaf]).toBe('string')
    owner[leaf]+=suffix
    expect(()=>check(value),`${path} + ${JSON.stringify(suffix)}`).toThrow()
  }
})
it('preserves multiline source STRING values and valid internal reason newlines verbatim',async()=>{
  const source=financeSourceFixture(),amountText=' 00100\n.00\r\n\u2028',statusText=' \n已缴\r\n原文\u2028\u2029 '
  source.fields[0]!.rawText=amountText;source.fields[3]!.rawText=statusText
  expect(()=>assertFinanceSource(financeEnvelope(source))).not.toThrow()
  vi.mocked(fetch).mockResolvedValue(response(financeEnvelope(source))as any)
  const result=await getMemberFinanceSource('1')
  expect(result.fields[0]!.rawText).toBe(amountText);expect(result.fields[3]!.rawText).toBe(statusText)
  expect(financeSourceText(result.fields[3]!)).toBe(statusText)
  const entries=financeEntriesFixture();entries.entries[0]!.reason='核实依据\n跨行说明\r\n结束'
  expect(()=>assertFinanceEntries(financeEnvelope(entries))).not.toThrow()
})
