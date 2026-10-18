package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.LinkedMultiValueMap;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:finance-read;DB_CLOSE_DELAY=-1;MODE=MYSQL","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public class FinanceReadServiceTest {
    @Autowired protected FinanceReadService service;@Autowired protected JdbcTemplate jdbc;@Autowired protected MockMvc mvc;
    protected final long admin=940001,clerk=940002,reader=940003;
    protected static final LocalDateTime CUTOFF=LocalDateTime.parse("2025-01-01T00:00:00"),T0=LocalDateTime.parse("2026-01-01T00:00:00"),T1=T0.plusDays(1),T2=T1.plusDays(1);
    protected UsernamePasswordAuthenticationToken auth(long id,String role) {return new UsernamePasswordAuthenticationToken(id,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));}
    protected LinkedMultiValueMap<String,String> q(String... args) {var q=new LinkedMultiValueMap<String,String>();for(int i=0;i<args.length;i+=2)q.add(args[i],args[i+1]);return q;}
    @BeforeEach void prepare()throws Exception {
        boolean mysql;try(var c=jdbc.getDataSource().getConnection()){mysql=c.getMetaData().getDatabaseProductName().equals("MySQL");}
        if(!mysql&&jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_name='MEMBER_FINANCE_ACCOUNT'",Integer.class)==0) {
            for(String[] alias:new String[][]{{"JSON_VALID","valid"},{"JSON_TYPE","type"},{"JSON_EXTRACT","extract"},{"JSON_UNQUOTE","unquote"},{"SHA2","sha"},{"CHAR_LENGTH","length"}})
                jdbc.execute("CREATE ALIAS IF NOT EXISTS FIN_"+alias[0]+" FOR 'com.library.finance.FinanceH2Json."+alias[1]+"'");
            String sql=new String(new ClassPathResource("db/migration/V16__member_finance_ledger.sql").getInputStream().readAllBytes(),StandardCharsets.UTF_8)
                    .replaceAll("CHARACTER SET ascii COLLATE ascii_bin","").replaceAll("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4","").replace(" STORED","").replace("TINYINT UNSIGNED","SMALLINT");
            try(var c=jdbc.getDataSource().getConnection()){ScriptUtils.executeSqlScript(c,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));}
        }
        for(long id:List.of(admin,clerk,reader))if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=?",Integer.class,id)==0)
            jdbc.update("INSERT INTO sys_user(id,username,password,role,status) VALUES (?,?,?, ?,1)",id,"b40_synthetic_"+id,"synthetic-hash",id==admin?"ADMIN":id==clerk?"LIBRARIAN":"READER");
        jdbc.update("UPDATE sys_user SET role='ADMIN',status=1,deleted=0 WHERE id=?",admin);
    }
    protected long insert(String sql,Object... args) {var key=new org.springframework.jdbc.support.GeneratedKeyHolder();jdbc.update(c->{var p=c.prepareStatement(sql,new String[]{"id"});for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);return p;},key);return key.getKey().longValue();}
    protected long member() {return insert("INSERT INTO sys_user(username,password,role,status) VALUES (?,?,'READER',1)","b40_"+UUID.randomUUID(),"synthetic-hash");}
    protected long account(long member,String fund) {return insert("INSERT INTO member_finance_account(user_id,fund,currency,revision,scope_cutoff_at) VALUES (?,?,'CNY',7,?)",member,fund,CUTOFF);}
    protected long entry(long account,String kind,String amount,String paid,String refunded,Long reverses,Long replaces,LocalDateTime time) {
        String op=UUID.randomUUID().toString(),request=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
        long member=jdbc.queryForObject("SELECT user_id FROM member_finance_account WHERE id=?",Long.class,account);
        jdbc.update("INSERT INTO member_finance_operation(id,actor_id,user_id,operation_key,reserve_hash,editor_id,editor_epoch,state,draft_hash,created_at,draft_expires_at,updated_at,resolved_at,result_json) VALUES (?,?,?,?,?,?,1,'REJECTED',?,?,?,?,?, '{}')",bytes(op),admin,member,bytes(key),"a".repeat(64),bytes(UUID.randomUUID().toString()),"b".repeat(64),time,time.plusDays(1),time,time);
        jdbc.update("INSERT INTO member_finance_request(id,account_id,operation_id,actor_id,operation_key,action,request_hash,policy_digest,preview_digest,input_json,dependencies_json,preview_json,state,result_json,created_at,expires_at,resolved_at) VALUES (?,?,?,?,?, ?,?,?,?,'{}','{}','{}','COMMITTED','{}',?,?,?)",bytes(request),account,bytes(op),admin,bytes(key),kind.equals("REVERSAL")?"CORRECT":kind,"a".repeat(64),"b".repeat(64),"c".repeat(64),time,time.plusDays(1),time);
        jdbc.update("UPDATE member_finance_operation SET state='COMMITTED',request_id=?,preview_digest=? WHERE id=?",bytes(request),"c".repeat(64),bytes(op));
        long id=insert("INSERT INTO member_finance_entry(account_id,request_id,ordinal,kind,amount,opening_received,opening_refunded,cutoff_at,actual_at,method_code,root_fact_key,reverses_entry_id,replaces_entry_id,business_type,business_key,policy_digest,evidence_json,reason,operator_id,recorded_at) VALUES (?,?,1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",account,bytes(request),kind,new BigDecimal(amount),paid==null?null:new BigDecimal(paid),refunded==null?null:new BigDecimal(refunded),kind.equals("OPENING")?CUTOFF:null,Set.of("RECEIPT","REFUND").contains(kind)?time:null,Set.of("RECEIPT","REFUND").contains(kind)?"SYNTHETIC":null,kind.equals("REVERSAL")||replaces!=null?null:bytes(key),reverses,replaces,kind.equals("OPENING")?"VERIFIED_OPENING":kind.equals("REVERSAL")?"LEDGER_CORRECTION":"COUNTER_REGISTRATION",bytes(key),"b".repeat(64),"[{\"type\":\"VERIFICATION_NOTE\",\"reference\":\"SYNTHETIC_ONLY\",\"digest\":null}]","合成核实依据",admin,time);
        if(kind.equals("OPENING"))jdbc.update("UPDATE member_finance_account SET opening_entry_id=? WHERE id=?",id,account);
        return id;
    }
    protected long source(long member,String uid,String json) {
        long id=insert("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,user_id,details_json) VALUES ('huiben','MEMBER',?,'合成财务来源',?,?)",uid,member,json);
        jdbc.update("INSERT INTO member_profile(user_id,source_key,source_archive_id,paid_fee,deposit_amount) VALUES (?,?,?,0,0)",member,"huiben:"+uid,id);return id;
    }
    protected JsonNode finance(long id) {return service.finance(""+id,q(),auth(admin,"ADMIN"));}
    protected JsonNode source(long id) {return service.source(""+id,q(),auth(admin,"ADMIN"));}
    protected JsonNode page(long member,String... args) {var params=q("fund","MEMBER_FEE","currency","CNY");params.putAll(q(args));return service.entries(""+member,params,auth(admin,"ADMIN"));}
    static void reason(String code,Runnable r) {assertEquals(code,assertThrows(Failure.class,r::run).code);}
    @Test void unknownAccountsRemainUnknownAndGetDoesNotCreateAnything() {
        long member=member();var before=counts();JsonNode f=finance(member);assertEquals("UNKNOWN",f.path("historicalCoverage").asText());
        for(var b:f.path("accounts")) {assertTrue(b.get("accountId").isNull());assertEquals("UNKNOWN",b.path("balance").path("state").asText());assertTrue(b.path("balance").get("amount").isNull());assertEquals("0.00",b.path("newReceipts").asText());}
        assertEquals("NO_PROFILE",source(member).path("sourceState").asText());assertTrue(page(member).path("entries").isEmpty());assertEquals(before,counts());
    }
    protected Map<String,Long> counts(){var c=new TreeMap<String,Long>();for(String table:List.of("member_finance_account","member_finance_entry","member_finance_allocation","member_finance_request","member_finance_operation","member_finance_audit"))c.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class));return c;}
    @Test void nonemptyOpeningReceiptPartialRefundAndSeparateFundTotals() {
        long m=member(),a=account(m,"MEMBER_FEE"),d=account(m,"DEPOSIT");long o=entry(a,"OPENING","80","100","20",null,null,T0);long r=entry(a,"RECEIPT","12.34",null,null,null,null,T1);long f=entry(a,"REFUND","15.01",null,null,null,null,T2);
        jdbc.update("INSERT INTO member_finance_allocation VALUES (?,?,?,?)",f,o,a,new BigDecimal("10.00"));jdbc.update("INSERT INTO member_finance_allocation VALUES (?,?,?,?)",f,r,a,new BigDecimal("5.01"));entry(d,"OPENING","0","0","0",null,null,T0);
        var before=counts();var result=finance(m);var b=result.path("accounts").get(0);assertEquals("COMPLETE",result.path("historicalCoverage").asText());assertEquals("77.33",b.path("balance").path("amount").asText());assertEquals("112.34",b.path("verifiedPaid").path("amount").asText());assertEquals("35.01",b.path("verifiedRefunded").path("amount").asText());assertEquals("-2.67",b.path("newNet").asText());assertEquals("0.00",result.path("accounts").get(1).path("balance").path("amount").asText());assertTrue(result.path("accounts").get(1).get("feeHeadroom").isNull());assertEquals(2,page(m).path("entries").get(0).path("allocations").size());assertEquals(before,counts());
    }
    @Test void receiptWithoutVerifiedOpeningKeepsTotalUnknownAndNewSubtotalKnown() {
        long m=member(),a=account(m,"MEMBER_FEE");entry(a,"RECEIPT","900719925474099.91",null,null,null,null,T0);
        var b=finance(m).path("accounts").get(0);assertEquals("900719925474099.91",b.path("newReceipts").asText());assertTrue(b.path("balance").get("amount").isNull());assertEquals("UNKNOWN",b.path("feeHeadroom").path("state").asText());
    }
    @Test void reversalsRestoreLotsAndHistoricalPagesKeepOriginalEffectiveness() {
        long m=member(),a=account(m,"MEMBER_FEE");long o=entry(a,"OPENING","100","100","0",null,null,T0);long r=entry(a,"REFUND","40",null,null,null,null,T1);jdbc.update("INSERT INTO member_finance_allocation VALUES (?,?,?,40)",r,o,a);
        JsonNode past=page(m,"asRecordedAt",utc(T1));assertTrue(past.path("entries").get(0).path("effectiveAtAsOf").asBoolean());long reversal=entry(a,"REVERSAL","40",null,null,r,null,T2);long replacement=entry(a,"REFUND","10",null,null,null,r,T2);jdbc.update("INSERT INTO member_finance_allocation VALUES (?,?,?,10)",replacement,o,a);
        var b=finance(m).path("accounts").get(0);assertEquals("90.00",b.path("balance").path("amount").asText());assertEquals("10.00",b.path("newRefunds").asText());assertEquals(past,page(m,"asRecordedAt",utc(T1)));
        var all=page(m).path("entries");assertFalse(all.get(1).path("effectiveAtAsOf").asBoolean());assertEquals(""+reversal,all.get(2).path("reversalEntryId").asText());assertFalse(all.get(2).path("effectiveAtAsOf").asBoolean());
    }
    @Test void openingCorrectionKeepsHistoryAndRebuildsFromReplacement() {
        long m=member(),a=account(m,"MEMBER_FEE"),o=entry(a,"OPENING","0","0","0",null,null,T0);entry(a,"REVERSAL","0",null,null,o,null,T1);entry(a,"OPENING","50","60","10",null,o,T1);assertEquals("50.00",finance(m).path("accounts").get(0).path("balance").path("amount").asText());assertEquals(3,page(m).path("entries").size());assertEquals(1,page(m,"asRecordedAt",utc(T0)).path("entries").size());
    }
    @Test void invalidAllocationsAndOpeningPointerNeverBecomeKnownBalance() {
        long m=member(),a=account(m,"MEMBER_FEE"),o=entry(a,"OPENING","5","5","0",null,null,T0);entry(a,"REFUND","1",null,null,null,null,T1);assertEquals("LEDGER_ANOMALY",finance(m).path("accounts").get(0).path("balance").path("reasonCode").asText());jdbc.update("UPDATE member_finance_account SET opening_entry_id=NULL WHERE id=?",a);assertEquals("UNKNOWN",finance(m).path("accounts").get(0).path("openingState").asText());
    }
    @Test void stableKeysetPagesAndCursorMembershipAreStrict() {
        long m=member(),a=account(m,"MEMBER_FEE");for(int i=0;i<4;i++)entry(a,"RECEIPT","1",null,null,null,null,T0.plusSeconds(i));
        JsonNode first=page(m,"size","2");assertTrue(first.path("hasMore").asBoolean());String before=first.path("nextBeforeId").asText(),as=first.path("asRecordedAt").asText();entry(a,"RECEIPT","1",null,null,null,null,LocalDateTime.parse(as.substring(0,as.length()-1)).plusSeconds(5));var second=page(m,"beforeId",before,"asRecordedAt",as,"size","2");assertEquals(2,second.path("entries").size());assertFalse(second.path("hasMore").asBoolean());assertTrue(second.get("nextBeforeId").isNull());
        reason("BAD_CURSOR",()->page(m,"beforeId",before));reason("BAD_CURSOR",()->page(m,"beforeId",before,"asRecordedAt",utc(T0.minusDays(1))));reason("BAD_CURSOR",()->page(member(),"beforeId",before,"asRecordedAt",as));reason("BAD_CURSOR",()->page(m,"asRecordedAt","9999-01-01T00:00:00.000000Z"));
        for(String s:List.of("0","01","1.0","101","+1"," 1","1000"))reason("INVALID_INPUT",()->page(m,"size",s));
    }
    @Test void sourceProjectionIsWhitelistExactAndHashCoversEntireArchive() {
        long m=member();String uid="000Aa📚"+UUID.randomUUID();long archive=source(m,uid,"{\"实际缴纳费用\":\"001.230\",\"实际缴纳押金\":12.34,\"缴费状态\":\"\",\"押金缴纳状态\":null,\"押金\":\"待核实原文\",\"家庭宝宝电话\":\"MUST_NEVER_LEAK\"}");
        var s=source(m);assertEquals("VERIFIED_LINK",s.path("sourceState").asText());assertEquals("001.230",s.path("fields").get(0).path("rawText").asText());assertEquals("NUMBER",s.path("fields").get(1).path("jsonType").asText());assertEquals("EMPTY",s.path("fields").get(2).path("presence").asText());assertEquals("NULL",s.path("fields").get(3).path("presence").asText());assertFalse(s.toString().contains("MUST_NEVER_LEAK"));assertEquals("0.00",s.path("profile").path("legacyPaidFee").asText());assertTrue(finance(m).path("accounts").get(0).path("balance").get("amount").isNull());
        jdbc.update("UPDATE legacy_record SET details_json=? WHERE id=?","{\"实际缴纳费用\":\"001.230\",\"实际缴纳押金\":12.34,\"缴费状态\":\"\",\"押金缴纳状态\":null,\"押金\":\"待核实原文\",\"家庭宝宝电话\":\"CHANGED_PRIVATE\"}",archive);var changed=source(m);assertEquals(s.get("fields"),changed.get("fields"));assertNotEquals(s.get("proofDigest"),changed.get("proofDigest"));
        jdbc.update("UPDATE member_profile SET source_system=NULL WHERE user_id=?",m);assertEquals("VERIFIED_LINK",source(m).path("sourceState").asText());jdbc.update("UPDATE member_profile SET source_key=? WHERE user_id=?","huiben:"+uid.toLowerCase(Locale.ROOT),m);assertEquals("UNVERIFIED_LINK",source(m).path("sourceState").asText());
    }
    @Test void sourceInvalidRootsTypesLengthsMissingAndAssociationConflicts() {
        for(String json:List.of("broken","[]","null","123")) {long m=member();source(m,UUID.randomUUID().toString(),json);var s=source(m);assertEquals("INVALID_ARCHIVE",s.path("sourceState").asText());assertTrue(s.get("proofDigest").isNull());assertTrue(s.path("fields").get(0).get("rawText").isNull());}
        long m=member();source(m,UUID.randomUUID().toString(),"{\"实际缴纳费用\":true,\"实际缴纳押金\":\""+"📚".repeat(65)+"\",\"缴费状态\":123,\"押金缴纳状态\":{},\"押金\":[]}");var fields=source(m).path("fields");assertEquals("INVALID_TYPE",fields.get(0).path("presence").asText());assertEquals("TOO_LONG",fields.get(1).path("presence").asText());assertEquals("INVALID_TYPE",fields.get(2).path("presence").asText());
        jdbc.update("UPDATE member_profile SET source_archive_id=NULL,source_system='huiben ' WHERE user_id=?",m);assertEquals("UNVERIFIED_LINK",source(m).path("sourceState").asText());jdbc.update("UPDATE member_profile SET source_system='manual' WHERE user_id=?",m);assertEquals("MANUAL",source(m).path("sourceState").asText());
        long x=member();long archive=source(x,UUID.randomUUID().toString(),"{}");jdbc.update("UPDATE legacy_record SET user_id=? WHERE id=?",m,archive);assertEquals("UNVERIFIED_LINK",source(x).path("sourceState").asText());
    }
    @Test void adminOnlyCurrentDbRoleAndSafeErrorsBeforeMalformedQuery()throws Exception {
        long m=member();for(String path:List.of("","/source","/entries"))for(String role:List.of("ANONYMOUS","LIBRARIAN","READER")) {
            var request=get("/members/"+m+"/finance"+path+"?unknown=union%20select");if(!role.equals("ANONYMOUS"))request.with(authentication(auth(role.equals("LIBRARIAN")?clerk:reader,role)));
            var response=mvc.perform(request).andReturn().getResponse();assertEquals(role.equals("ANONYMOUS")?401:403,response.getStatus());var e=read(response.getContentAsString());assertEquals(1,e.path("schemaVersion").asInt());assertTrue(e.path("data").get("operation").isNull());assertTrue(e.path("data").path("fieldErrors").isEmpty());
        }
        jdbc.update("UPDATE sys_user SET role='LIBRARIAN' WHERE id=?",admin);reason("ADMIN_REQUIRED",()->finance(m));jdbc.update("UPDATE sys_user SET role='ADMIN',status=0 WHERE id=?",admin);reason("ADMIN_REQUIRED",()->source(m));
    }
    @Test void retiredMemberHistoricalReadAndNoSharedSummaryFinanceFields()throws Exception {
        long m=member(),a=account(m,"DEPOSIT");entry(a,"OPENING","2","2","0",null,null,T0);jdbc.update("UPDATE sys_user SET deleted=1,role='LIBRARIAN' WHERE id=?",m);assertEquals("2.00",finance(m).path("accounts").get(1).path("balance").path("amount").asText());
        var summary=mvc.perform(get("/members/"+reader).with(authentication(auth(clerk,"LIBRARIAN")))).andReturn().getResponse();assertEquals(200,summary.getStatus());assertFalse(summary.getContentAsString().contains("verifiedPaid"));assertFalse(summary.getContentAsString().contains("member_finance"));
    }
    @Test void strictMoneyAndUnconfirmedPolicyNoWriteController() {
        for(String value:List.of("0","1","1.2","1.23","999999999999999.99"))assertEquals(2,parseMoney(JSON.valueToTree(value)).scale());
        for(String value:List.of("+1","-0","01","1e2"," 1","1 ",".2","1.","１","NaN"))reason("INVALID_INPUT",()->parseMoney(JSON.valueToTree(value)));
        reason("MONEY_PRECISION",()->parseMoney(JSON.valueToTree("1.000")));reason("STORAGE_RANGE",()->parseMoney(JSON.valueToTree("1000000000000000")));reason("INVALID_INPUT",()->parseMoney(JSON.valueToTree(1.23)));
        var p=FinancePolicy.current();assertEquals("UNCONFIRMED",p.path("datePolicyState").asText());assertEquals("UNCONFIRMED",p.path("debtPolicyState").asText());assertEquals("UNCONFIRMED",p.path("paymentMethodsState").asText());assertTrue(p.path("enabledActions").isEmpty());
        assertTrue(Arrays.stream(FinanceController.class.getDeclaredMethods()).allMatch(m->m.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)));
    }
    @Test void unicodeProjectionLimitsAndInvalidLegacyScalarsDoNotForgeZero() {
        long m=member();long archive=source(m,UUID.randomUUID().toString(),"{\"实际缴纳费用\":\""+"📚".repeat(64)+"\",\"缴费状态\":\""+"📚".repeat(300)+"\"}");
        var s=source(m);assertEquals("VALUE",s.path("fields").get(0).path("presence").asText());assertEquals("VALUE",s.path("fields").get(2).path("presence").asText());
        jdbc.update("UPDATE member_profile SET paid_fee=-1 WHERE user_id=?",m);var invalid=source(m);assertTrue(invalid.path("profile").get("legacyPaidFee").isNull());assertTrue(invalid.path("reasonCodes").toString().contains("LEGACY_PROFILE_AMOUNT_INVALID"));
        jdbc.update("UPDATE member_profile SET paid_fee=-2 WHERE user_id=?",m);assertNotEquals(invalid.get("proofDigest"),source(m).get("proofDigest"));
        jdbc.update("UPDATE member_profile SET source_archive_id=0 WHERE user_id=?",m);var link=source(m);assertEquals("UNVERIFIED_LINK",link.path("sourceState").asText());assertTrue(link.path("profile").get("sourceArchiveId").isNull());
    }
}
