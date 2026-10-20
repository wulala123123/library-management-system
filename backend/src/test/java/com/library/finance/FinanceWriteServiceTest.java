package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static com.library.finance.FinanceSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:b40b-write;DB_CLOSE_DELAY=-1;MODE=MYSQL","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class FinanceWriteServiceTest {
    @Autowired FinanceWriteService writer;@Autowired FinanceReadService reader;@Autowired JdbcTemplate jdbc;@Autowired ConfigurableEnvironment env;
    @Autowired com.library.security.JwtTokenProvider jwt;@Autowired FinanceDraftCleanup cleanup;@LocalServerPort int port;
    FinanceReadServiceTest f;long actor,member,epoch;UsernamePasswordAuthenticationToken auth;String key,editor,captureRole="ADMIN";final List<JsonNode> captures=new CopyOnWriteArrayList<>();
    @BeforeEach void prepare()throws Exception {
        f=new FinanceReadServiceTest();f.jdbc=jdbc;f.service=reader;f.prepare();
        jdbc.execute("CREATE TABLE IF NOT EXISTS member_finance_activation(policy_key VARCHAR(32) PRIMARY KEY,activation_date DATE NOT NULL,cutoff_at TIMESTAMP(6) NOT NULL,financial_time_zone VARCHAR(64) NOT NULL,created_at TIMESTAMP(6) NOT NULL)");
        String date=FinanceWriteService.businessDay(reader.now());LocalDateTime cutoff=LocalDate.parse(date).atStartOfDay(FinanceReceiptPolicy.ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        if(jdbc.queryForObject("SELECT COUNT(*) FROM member_finance_activation",Long.class)==0)jdbc.update("INSERT INTO member_finance_activation VALUES ('B40_RECEIPT_V1',?,?, 'Asia/Shanghai',?)",LocalDate.parse(date),cutoff,reader.now());
        properties("true",date);
        actor=f.insert("INSERT INTO sys_user(username,password,role,status) VALUES (?,?,'ADMIN',1)","b40b_admin_"+UUID.randomUUID(),"synthetic-hash");member=f.member();auth=auth(actor);key=UUID.randomUUID().toString();editor=UUID.randomUUID().toString();epoch=1;
    }
    void properties(String enabled,String date) {env.getPropertySources().addFirst(new MapPropertySource("b40b-synthetic",Map.of("library.finance.receipts.enabled",enabled,"library.finance.receipts.start-date",date)));}
    UsernamePasswordAuthenticationToken auth(long id) {var a=new UsernamePasswordAuthenticationToken(id,null,List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));a.setDetails(new TokenVersion(0,Instant.now().plusSeconds(3600)));return a;}
    ObjectNode fence(){return obj("editorId",editor,"epoch",Long.toString(epoch));}
    ObjectNode reserveBody(){return obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member),"editorId",editor);}
    ObjectNode input(String amount,String fact) {
        var account=reader.account(member,"MEMBER_FEE");
        return obj("memberId",Long.toString(member),"memberRevision",Long.toString(jdbc.queryForObject("SELECT member_revision FROM sys_user WHERE id=?",Long.class,member)),"financeRevision",account==null?"0":Long.toString(account.revision()),"fund","MEMBER_FEE","currency","CNY","reason","合成已收款核实依据📚","evidenceRefs",List.of(obj("type","VERIFICATION_NOTE","reference","SYNTHETIC_ONLY","digest",null)),"action","RECEIPT","payload",obj("factKey",fact,"amount",amount,"actualAt",utc(reader.now().minusSeconds(1)),"methodCode","CASH","paymentState","RECORDED","businessRef",obj("type","COUNTER_REGISTRATION","id",fact)));
    }
    ObjectNode draftBody(JsonNode input){return obj("schemaVersion",1,"operationKey",key,"editor",fence(),"input",input);}
    ObjectNode previewBody(JsonNode op){return obj("schemaVersion",1,"operationKey",key,"editor",fence(),"draftHash",op.get("draftHash"));}
    ObjectNode confirmBody(JsonNode op){return obj("schemaVersion",1,"operationKey",key,"editor",fence(),"previewDigest",op.path("request").get("previewDigest"));}
    ObjectNode cancelBody(JsonNode digest){return obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member),"editor",fence(),"cancelKey",UUID.randomUUID().toString(),"previewDigest",digest,"reason","USER_CANCELLED");}
    FinanceWriteService.Result run(String kind,ObjectNode body) {
        try {
            var result=writer.command(kind.equals("reserve")?null:key,kind,FinanceCommands.validate(body.deepCopy(),kind),auth);
            captures.add(obj("kind",kind,"schemaDefinition",result.failure()==null?kind.equals("cancel")?"CancelEnvelope":"OperationEnvelope":"ErrorEnvelope","body",result.failure()==null?envelope(result.data()):error(result.failure())));return result;
        }catch(Failure e){captures.add(obj("kind",kind,"schemaDefinition","ErrorEnvelope","body",error(e)));throw e;}
    }
    JsonNode ok(String kind,ObjectNode body){var r=run(kind,body);assertNull(r.failure(),r.failure()==null?"":r.failure().code);return r.data();}
    JsonNode preview(String amount,String fact){ok("reserve",reserveBody());var d=ok("draft",draftBody(input(amount,fact)));return ok("preview",previewBody(d));}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Long.class);}
    String failure(String kind,ObjectNode body){try {var r=run(kind,body);assertNotNull(r.failure());return r.failure().code;}catch(Failure e){return e.code;}}
    @AfterAll void evidence()throws Exception {String dir=System.getProperty("b40b.evidence.dir");if(dir!=null)Files.writeString(Path.of(dir,reader.isMysql()?"mysql-command-captures.json":"h2-command-captures.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("syntheticOnly",true,"database",reader.isMysql()?jdbc.queryForObject("SELECT VERSION()",String.class):"H2","captures",captures)));}
    @Test void completeReceiptUnknownOpeningExactReplayAndBothReadFunds() {
        String fact=UUID.randomUUID().toString();JsonNode p=preview("12.3",fact);assertTrue(p.path("request").path("preview").path("canConfirm").asBoolean());
        assertEquals("12.30",p.path("input").path("payload").path("amount").asText());assertNull(jdbc.queryForObject("SELECT draft_input_json FROM member_finance_operation WHERE operation_key=?",String.class,bytes(key)));
        long entries=count("member_finance_entry"),audits=count("member_finance_audit");var body=confirmBody(p);JsonNode committed=ok("confirm",body),receipt=committed.path("result").get("receipt");
        assertEquals("COMMITTED",committed.path("state").asText());assertEquals("1",receipt.path("afterRevision").asText());assertEquals("UNKNOWN",receipt.path("audit").path("after").path("balance").path("state").asText());assertEquals("12.30",receipt.path("audit").path("after").path("newReceipts").asText());
        assertEquals(receipt,ok("confirm",body).path("result").get("receipt"));assertEquals(entries+1,count("member_finance_entry"));assertEquals(audits+1,count("member_finance_audit"));
        assertEquals("12.30",f.finance(member).path("accounts").get(0).path("newReceipts").asText());assertTrue(f.finance(member).path("accounts").get(1).get("accountId").isNull());
        String opResult=jdbc.queryForObject("SELECT result_json FROM member_finance_operation WHERE operation_key=?",String.class,bytes(key)),reqResult=jdbc.queryForObject("SELECT result_json FROM member_finance_request WHERE id=?",String.class,bytes(p.path("request").path("requestId").asText()));assertEquals(opResult,reqResult);
    }
    @Test void fifteenDigitEntriesAccumulateToSixteenDigitsWithoutFloatingPoint() {
        JsonNode p=preview("999999999999999.99",UUID.randomUUID().toString());ok("confirm",confirmBody(p));key=UUID.randomUUID().toString();editor=UUID.randomUUID().toString();
        p=preview("999999999999999.99",UUID.randomUUID().toString());assertEquals("1999999999999999.98",p.path("request").path("preview").path("impact").path("after").path("newReceipts").asText());ok("confirm",confirmBody(p));assertEquals("1999999999999999.98",f.finance(member).path("accounts").get(0).path("newReceipts").asText());
    }
    @Test void reserveDraftAndPreviewReplayDoNotStealExtendOrDuplicate() {
        var rb=reserveBody();var reserved=ok("reserve",rb);assertEquals(reserved.path("createdAt"),ok("reserve",rb).path("createdAt"));ObjectNode altered=rb.deepCopy();altered.put("editorId",UUID.randomUUID().toString());assertEquals("OPERATION_KEY_REUSED",failure("reserve",altered));
        var db=draftBody(input("1",UUID.randomUUID().toString()));var d=ok("draft",db);((ObjectNode)db.path("input").path("payload")).put("amount","1.00");assertEquals(d.path("draftHash"),ok("draft",db).path("draftHash"));((ObjectNode)db.path("input").path("payload")).put("amount","2");assertEquals("DRAFT_ALREADY_FROZEN",failure("draft",db));
        var p=ok("preview",previewBody(d));assertEquals(p.path("request"),ok("preview",previewBody(d)).path("request"));
        String saved=key;key=UUID.randomUUID().toString();assertEquals("ACTIVE_OPERATION_EXISTS",failure("reserve",reserveBody()));key=saved;
    }
    @Test void claimCasNarrowsReplayAndFencesOldWriter() {
        ok("reserve",reserveBody());String old=editor;ObjectNode claim=obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member),"editorId",UUID.randomUUID().toString(),"expectedEpoch","1","claimKey",UUID.randomUUID().toString());
        JsonNode c=ok("claim",claim);assertEquals("2",c.path("editor").path("epoch").asText());assertEquals(c.path("editor"),ok("claim",claim).path("editor"));
        var changed=claim.deepCopy();changed.put("editorId",UUID.randomUUID().toString());assertEquals("CLAIM_KEY_REUSED",failure("claim",changed));assertEquals("EDITOR_CHANGED",failure("draft",draftBody(input("1",UUID.randomUUID().toString()))));
        var next=claim.deepCopy();next.put("expectedEpoch","2").put("claimKey",UUID.randomUUID().toString()).put("editorId",old);ok("claim",next);assertEquals("EDITOR_CHANGED",failure("claim",claim));
    }
    @Test void missingKeyCancelCreatesPermanentTombstoneWithExactIntentReplay() {
        var body=cancelBody(null);((ObjectNode)body.get("editor")).put("epoch","0");long before=count("member_finance_account");var receipt=ok("cancel",body);assertEquals("CANCELLED",receipt.path("outcome").asText());assertEquals(receipt,ok("cancel",body));assertEquals(before,count("member_finance_account"));
        assertEquals("CANCELLED",failure("reserve",reserveBody()));var changed=body.deepCopy();changed.put("reason","RESTART_AFTER_UNKNOWN");assertEquals("CANCEL_KEY_REUSED",failure("cancel",changed));changed=body.deepCopy();changed.put("cancelKey",UUID.randomUUID().toString());assertEquals("CANCEL_INTENT_FROZEN",failure("cancel",changed));
    }
    @Test void epochZeroCannotCancelExistingAndPreviewRequiresBinding() {
        ok("reserve",reserveBody());var c=cancelBody(null);((ObjectNode)c.get("editor")).put("epoch","0");assertEquals("OPERATION_APPEARED",failure("cancel",c));
        var d=ok("draft",draftBody(input("1",UUID.randomUUID().toString())));var p=ok("preview",previewBody(d));assertEquals("PREVIEW_BINDING_REQUIRED",failure("cancel",cancelBody(null)));assertNull(jdbc.queryForObject("SELECT cancel_hash FROM member_finance_operation WHERE operation_key=?",String.class,bytes(key)));
        var cancelled=ok("cancel",cancelBody(p.path("request").get("previewDigest")));assertEquals("CANCELLED",cancelled.path("state").asText());assertEquals("CANCELLED",failure("confirm",confirmBody(p)));
    }
    @Test void cancelAfterCommitFreezesAlreadyCommittedReceipt() {
        var p=preview("2.01",UUID.randomUUID().toString());var result=ok("confirm",confirmBody(p));var b=cancelBody(p.path("request").get("previewDigest"));var c=ok("cancel",b);assertEquals("ALREADY_COMMITTED",c.path("outcome").asText());assertEquals(result.path("result").get("receipt"),c.get("receipt"));assertEquals(c,ok("cancel",b));
    }
    @Test void getOnlyObservesAndResolveOrCleanupClearsExpiredDraftWithoutRole() {
        ok("reserve",reserveBody());var d=ok("draft",draftBody(input("1",UUID.randomUUID().toString())));jdbc.update("UPDATE member_finance_operation SET created_at=?,draft_expires_at=? WHERE operation_key=?",reader.now().minusDays(2),reader.now().minusDays(1),bytes(key));
        assertEquals("DRAFT_READY",writer.observe(key,null,null,auth).path("state").asText());jdbc.update("UPDATE sys_user SET role='LIBRARIAN',status=0 WHERE id=?",actor);assertTrue(cleanup.cleanBatch()>=1);assertNull(jdbc.queryForObject("SELECT draft_input_json FROM member_finance_operation WHERE operation_key=?",String.class,bytes(key)));assertEquals(d.path("draftHash").asText(),jdbc.queryForObject("SELECT draft_hash FROM member_finance_operation WHERE operation_key=?",String.class,bytes(key)));
    }
    @Test void previewUsesRequestTtlAndExpiresPersistently() {
        var p=preview("1",UUID.randomUUID().toString());jdbc.update("UPDATE member_finance_operation SET created_at=?,draft_expires_at=? WHERE operation_key=?",reader.now().minusDays(2),reader.now().minusDays(1),bytes(key));assertEquals("PREVIEWED",ok("resolve",obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member))).path("state").asText());
        jdbc.update("UPDATE member_finance_request SET created_at=?,expires_at=? WHERE operation_key=?",reader.now().minusDays(2),reader.now().minusDays(1),bytes(key));assertEquals("EXPIRED",failure("confirm",confirmBody(p)));assertEquals("EXPIRED",writer.observe(key,null,null,auth).path("request").path("state").asText());
    }
    @Test void defaultOffInvalidConfigGuardMismatchAndDisabledReaderAreBlocked() {
        ok("reserve",reserveBody());properties("false",FinanceWriteService.businessDay(reader.now()));assertEquals("RULE_NOT_CONFIRMED",failure("draft",draftBody(input("1",UUID.randomUUID().toString()))));
        properties("true","");assertEquals("RULE_NOT_CONFIRMED",failure("draft",draftBody(input("1",UUID.randomUUID().toString()))));
        properties("true",LocalDate.parse(FinanceWriteService.businessDay(reader.now())).minusDays(1).toString());assertEquals("RULE_NOT_CONFIRMED",failure("draft",draftBody(input("1",UUID.randomUUID().toString()))));
        properties("true",FinanceWriteService.businessDay(reader.now()));jdbc.update("UPDATE sys_user SET status=0 WHERE id=?",member);assertEquals("RULE_NOT_CONFIRMED",failure("draft",draftBody(input("1",UUID.randomUUID().toString()))));
    }
    @Test void absentActivationAnchorBlocksDraftAndDoesNotInitializeAnything() {
        ok("reserve",reserveBody());
        var anchor=jdbc.queryForMap("SELECT * FROM member_finance_activation WHERE policy_key='B40_RECEIPT_V1'");jdbc.update("DELETE FROM member_finance_activation WHERE policy_key='B40_RECEIPT_V1'");
        try {ok("reserve",reserveBody());assertEquals("RULE_NOT_CONFIRMED",failure("draft",draftBody(input("1",UUID.randomUUID().toString()))));assertEquals(0,count("member_finance_activation"));assertEquals("RESERVED",writer.observe(key,null,null,auth).path("state").asText());}
        finally {jdbc.update("INSERT INTO member_finance_activation(policy_key,activation_date,cutoff_at,financial_time_zone,created_at) VALUES ('B40_RECEIPT_V1',?,?,?,?)",anchor.get("activation_date"),anchor.get("cutoff_at"),anchor.get("financial_time_zone"),anchor.get("created_at"));}
    }
    @Test void futureHistoricalMethodAndClosedActionsNeverFreezeInput() {
        ok("reserve",reserveBody());for(String time:List.of(utc(reader.now().plusHours(1)),utc(reader.now().minusDays(1)))) {var input=input("1",UUID.randomUUID().toString());((ObjectNode)input.get("payload")).put("actualAt",time);assertEquals("INVALID_INPUT",failure("draft",draftBody(input)));}
        var input=input("1",UUID.randomUUID().toString());((ObjectNode)input.get("payload")).put("methodCode","OTHER");assertEquals("RULE_NOT_CONFIRMED",failure("draft",draftBody(input)));
        for(String action:List.of("OPENING","REFUND","CORRECT")){input=input("1",UUID.randomUUID().toString());input.put("action",action);assertEquals("RULE_NOT_CONFIRMED",failure("draft",draftBody(input)));}assertEquals("RESERVED",writer.observe(key,null,null,auth).path("state").asText());
    }
    @Test void roleVersionDatePolicyAndCutoffChangesRejectFrozenPreview() {
        var p=preview("1",UUID.randomUUID().toString());jdbc.update("UPDATE sys_user SET member_revision=member_revision+1 WHERE id=?",member);assertEquals("DEPENDENCIES_CHANGED",failure("confirm",confirmBody(p)));assertEquals("REJECTED",writer.observe(key,null,null,auth).path("state").asText());
        key=UUID.randomUUID().toString();p=preview("1",UUID.randomUUID().toString());properties("false",FinanceWriteService.businessDay(reader.now()));assertEquals("RULE_NOT_CONFIRMED",failure("confirm",confirmBody(p)));
        properties("true",FinanceWriteService.businessDay(reader.now()));key=UUID.randomUUID().toString();p=preview("1",UUID.randomUUID().toString());jdbc.update("UPDATE sys_user SET deleted=1,role='LIBRARIAN' WHERE id=?",member);assertEquals("DEPENDENCIES_CHANGED",failure("confirm",confirmBody(p)));
    }
    @Test void committedReplaySurvivesOldTargetTtlAndDisabledPolicy() {
        var p=preview("1",UUID.randomUUID().toString());var c=confirmBody(p);var receipt=ok("confirm",c).path("result").get("receipt");properties("false","");jdbc.update("UPDATE sys_user SET deleted=1,role='LIBRARIAN',status=0 WHERE id=?",member);assertEquals(receipt,ok("confirm",c).path("result").get("receipt"));assertEquals("COMMITTED",ok("reserve",reserveBody()).path("state").asText());
    }
    @Test void newReserveRequiresActivationAndActiveReaderWithoutLeavingOperation()throws Exception {
        String day=FinanceWriteService.businessDay(reader.now()),token=jwt.generateToken(actor,"SYNTHETIC_ADMIN","ADMIN",0L);var anchor=jdbc.queryForMap("SELECT * FROM member_finance_activation WHERE policy_key='B40_RECEIPT_V1'");long before=count("member_finance_operation");
        for(String condition:List.of("off","empty-date","invalid-date","mismatched-date","missing-anchor","disabled-reader","deleted-reader","wrong-role")) {
            properties(condition.equals("off")?"false":"true",switch(condition){case "empty-date"->"";case "invalid-date"->"2026-02-30";case "mismatched-date"->LocalDate.parse(day).minusDays(1).toString();default->day;});
            if(condition.equals("missing-anchor"))jdbc.update("DELETE FROM member_finance_activation WHERE policy_key='B40_RECEIPT_V1'");
            jdbc.update("UPDATE sys_user SET status=?,deleted=?,role=? WHERE id=?",condition.equals("disabled-reader")?0:1,condition.equals("deleted-reader")?1:0,condition.equals("wrong-role")?"LIBRARIAN":"READER",member);
            try {
                int status=Set.of("deleted-reader","wrong-role").contains(condition)?404:422;var result=http("POST","/member-finance/operations",bytes(reserveBody().toString()),token,status,"ErrorEnvelope");assertEquals(status==404?"MEMBER_NOT_FOUND":"RULE_NOT_CONFIRMED",result.path("data").path("errorCode").asText());
                if(status==422)assertEquals(condition.equals("disabled-reader")?"D06_UNCONFIRMED":"ACTIVATION_UNCONFIRMED",result.path("data").path("fieldErrors").get(0).path("code").asText());
                assertEquals(before,count("member_finance_operation"),condition);assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM member_finance_operation WHERE actor_id=? AND operation_key=?",Integer.class,actor,bytes(key)));
            }finally {if(condition.equals("missing-anchor"))jdbc.update("INSERT INTO member_finance_activation(policy_key,activation_date,cutoff_at,financial_time_zone,created_at) VALUES ('B40_RECEIPT_V1',?,?,?,?)",anchor.get("activation_date"),anchor.get("cutoff_at"),anchor.get("financial_time_zone"),anchor.get("created_at"));}
        }
        properties("true",day);jdbc.update("UPDATE sys_user SET status=1,deleted=0,role='READER' WHERE id=?",member);ok("reserve",reserveBody());properties("false","");jdbc.update("UPDATE sys_user SET status=0 WHERE id=?",member);
        assertEquals("RESERVED",ok("reserve",reserveBody()).path("state").asText());ok("cancel",cancelBody(null));assertEquals("CANCELLED",ok("reserve",reserveBody()).path("state").asText());
    }
    @Test void dependencyStoresCompleteMemberEligibilityRuleAndDigestBindsIt() {
        var p=preview("1",UUID.randomUUID().toString());String request=p.path("request").path("requestId").asText();var deps=read(jdbc.queryForObject("SELECT dependencies_json FROM member_finance_request WHERE id=?",String.class,bytes(request)));
        JsonNode expected=obj("version","B40B_MEMBER_ELIGIBILITY_V1","activeReader","ALLOWED","disabledReader","UNCONFIRMED","disabledReaderRuleRef",null,"target",obj("memberId",Long.toString(member),"accountStatus",1,"role","READER","deleted",false,"memberRevision",p.path("request").path("preview").path("identity").get("memberRevision")));
        assertEquals(expected,deps.get("memberEligibility"));String original=p.path("request").path("previewDigest").asText();JsonNode recomputed=obj("inputHash",p.get("draftHash"),"dependencies",deps,"impact",FinanceWriteService.stable(p.path("request").path("preview").get("impact")));assertEquals(original,FinanceCommands.canonicalHash(recomputed));
        ((ObjectNode)deps.get("memberEligibility")).put("version","B40B_MEMBER_ELIGIBILITY_DIFFERENT");assertNotEquals(original,FinanceCommands.canonicalHash(obj("inputHash",p.get("draftHash"),"dependencies",deps,"impact",FinanceWriteService.stable(p.path("request").path("preview").get("impact")))));
        jdbc.update("UPDATE member_finance_request SET dependencies_json=? WHERE id=?",deps.toString(),bytes(request));assertEquals("DEPENDENCIES_CHANGED",failure("confirm",confirmBody(p)));
    }
    @Test void separateDepositReceiptPreservesSourceAndEntitlementBytes() {
        f.source(member,"000B40B"+UUID.randomUUID(),"{\"实际缴纳费用\":\"001.230\",\"实际缴纳押金\":null,\"家庭宝宝\":\"PRIVATE_SYNTHETIC\"}");
        var user=jdbc.queryForMap("SELECT * FROM sys_user WHERE id=?",member);var profile=jdbc.queryForMap("SELECT * FROM member_profile WHERE user_id=?",member);var source=f.source(member);
        ok("reserve",reserveBody());var input=input("5.67",UUID.randomUUID().toString());input.put("fund","DEPOSIT");((ObjectNode)input.get("payload")).put("methodCode","WECHAT");var d=ok("draft",draftBody(input));var p=ok("preview",previewBody(d));ok("confirm",confirmBody(p));
        assertEquals(user,jdbc.queryForMap("SELECT * FROM sys_user WHERE id=?",member));assertEquals(profile,jdbc.queryForMap("SELECT * FROM member_profile WHERE user_id=?",member));assertEquals(source.get("proofDigest"),f.source(member).get("proofDigest"));
        var balances=f.finance(member).path("accounts");assertTrue(balances.get(0).get("accountId").isNull());assertEquals("5.67",balances.get(1).path("newReceipts").asText());assertTrue(balances.get(1).get("feeHeadroom").isNull());assertEquals("UNKNOWN",balances.get(1).path("balance").path("state").asText());
    }
    @Test void accountCutoffMismatchBlocksWithoutResetAndHistoryCannotBeClaimedByOtherActor() {
        var p=preview("1",UUID.randomUUID().toString());long account=reader.account(member,"MEMBER_FEE").id();LocalDateTime old=reader.now().minusDays(10);jdbc.update("UPDATE member_finance_account SET scope_cutoff_at=? WHERE id=?",old,account);assertEquals("DEPENDENCIES_CHANGED",failure("confirm",confirmBody(p)));assertEquals(old,jdbc.queryForObject("SELECT scope_cutoff_at FROM member_finance_account WHERE id=?",LocalDateTime.class,account));
        long other=f.insert("INSERT INTO sys_user(username,password,role,status) VALUES (?,?,'ADMIN',1)","b40b_owner_"+UUID.randomUUID(),"synthetic-hash");var otherAuth=auth(other);
        assertEquals("OPERATION_NOT_FOUND",assertThrows(Failure.class,()->writer.observe(key,null,null,otherAuth)).code);assertEquals("REQUEST_NOT_FOUND",assertThrows(Failure.class,()->writer.observe(null,p.path("request").path("requestId").asText(),null,otherAuth)).code);
    }
    @Test void sequentialFactCollisionAcrossActorMemberAndFundNeverDoubleBooks() {
        String fact=UUID.randomUUID().toString();var p=preview("3",fact);ok("confirm",confirmBody(p));long before=count("member_finance_entry");key=UUID.randomUUID().toString();member=f.member();actor=f.insert("INSERT INTO sys_user(username,password,role,status) VALUES (?,?,'ADMIN',1)","b40b_other_"+UUID.randomUUID(),"synthetic-hash");auth=auth(actor);
        p=preview("3",fact);assertFalse(p.path("request").path("preview").path("canConfirm").asBoolean());assertEquals("BUSINESS_FACT_EXISTS",failure("confirm",confirmBody(p)));assertEquals(before,count("member_finance_entry"));
    }
    @Test void strictCanonicalMoneyTimeReasonUnicodeAndUnknownFields() {
        var valid=input("1.2",UUID.randomUUID().toString());String utc=valid.path("payload").path("actualAt").asText();String offset=OffsetDateTime.parse(utc).withOffsetSameInstant(ZoneOffset.ofHours(8)).toString();var other=valid.deepCopy();((ObjectNode)other.get("payload")).put("actualAt",offset).put("amount","1.20");assertEquals(FinanceCommands.input(valid),FinanceCommands.input(other));
        for(String amount:List.of("0","01","+1","1e2","1."," 1","1.000","1000000000000000")){var v=input(amount,UUID.randomUUID().toString());assertThrows(Failure.class,()->FinanceCommands.input(v));}
        for(String time:List.of("2026-02-30T00:00:00Z","2026-01-01T00:00:60Z","2026-01-01T00:00:00.1234567Z","2026-01-01T00:00:00+14:01","1000-01-01T00:00:00+14:00"))assertThrows(Failure.class,()->FinanceCommands.time(JSON.valueToTree(time)));
        var bad=valid.deepCopy();bad.put("reason"," 合成");assertThrows(Failure.class,()->FinanceCommands.input(bad));var extra=valid.deepCopy();extra.put("extra",1);assertEquals("UNKNOWN_FIELD",assertThrows(Failure.class,()->FinanceCommands.input(extra)).code);
    }
    JsonNode http(String method,String path,byte[] body,String token,int expected,String schema)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(15));if(token!=null)request.header("Authorization","Bearer "+token);
        if(method.equals("GET"))request.GET();else request.header("Content-Type","application/json;charset=UTF-8").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));assertEquals(expected,response.statusCode(),response.body());JsonNode value=read(response.body());captures.add(obj("transport","actual-TCP-HTTP","method",method,"path",path,"callerRole",token==null?"ANONYMOUS":captureRole,"httpStatus",expected,"schemaDefinition",schema,"body",value));return value;
    }
    @Test void actualHttpAllRoutesStrictBodyAndPermissionBeforeParsing()throws Exception {
        String token=jwt.generateToken(actor,"SYNTHETIC_ADMIN","ADMIN",0L),base="/member-finance/operations";
        for(String path:List.of(base,base+"/by-key/"+key+"/draft",base+"/by-key/"+key+"/confirm",base+"/by-key/"+key+"/cancel")){var e=http("POST",path,bytes("{broken"),null,401,"ErrorEnvelope");assertTrue(e.path("data").get("operation").isNull());}
        String clerk=jwt.generateToken(f.clerk,"SYNTHETIC_CLERK","LIBRARIAN",0L),readerToken=jwt.generateToken(f.reader,"SYNTHETIC_READER","READER",0L);captureRole="LIBRARIAN";http("POST",base,bytes("{broken"),clerk,403,"ErrorEnvelope");
        for(String role:List.of("ANONYMOUS","LIBRARIAN","READER")) {
            String roleToken=role.equals("ANONYMOUS")?null:role.equals("LIBRARIAN")?clerk:readerToken;captureRole=role;int status=role.equals("ANONYMOUS")?401:403;
            for(String kind:List.of("reserve","claim","draft","preview","confirm","cancel","resolve"))http("POST",kind.equals("reserve")?base:base+"/by-key/"+key+"/"+kind,bytes("{broken"),roleToken,status,"ErrorEnvelope");
            for(String path:List.of(base+"/active",base+"/by-key/"+key,"/member-finance/requests/"+UUID.randomUUID(),"/members/"+member+"/finance","/members/"+member+"/finance/source","/members/"+member+"/finance/entries"))http("GET",path+"?unknown=union%20select",null,roleToken,status,"ErrorEnvelope");
        }
        captureRole="ADMIN";
        http("POST",base,bytes("{\"schemaVersion\":1,\"schemaVersion\":1}"),token,400,"ErrorEnvelope");http("POST",base,new byte[]{(byte)0xc3,0x28},token,400,"ErrorEnvelope");http("POST",base,bytes("{\"schemaVersion\":\"\\ud800\"}"),token,400,"ErrorEnvelope");http("POST",base,new byte[65537],token,400,"ErrorEnvelope");
        for(String bad:List.of(reserveBody()+" {}",reserveBody().toString().replace("\"schemaVersion\":1","\"schemaVersion\":1.0"),reserveBody().toString().replace("\"schemaVersion\":1","\"schemaVersion\":null"),reserveBody().toString().replace("\"schemaVersion\":1","\"schemaVersion\":NaN"),reserveBody().toString().replace("\"schemaVersion\":1","\"schemaVersion\":1,\"unexpected\":true")))http("POST",base,bytes(bad),token,400,"ErrorEnvelope");
        JsonNode reserved=http("POST",base,bytes(reserveBody().toString()),token,200,"OperationEnvelope").get("data");http("GET",base+"/active",null,token,200,"ActiveEnvelope");http("GET",base+"/by-key/"+key,null,token,200,"OperationEnvelope");
        editor=UUID.randomUUID().toString();epoch=2;http("POST",base+"/by-key/"+key+"/claim",bytes(obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member),"editorId",editor,"expectedEpoch","1","claimKey",UUID.randomUUID().toString()).toString()),token,200,"OperationEnvelope");
        var in=input("1.23",UUID.randomUUID().toString());in.put("reason","合成literal <script>x</script> union select 📚");var d=http("POST",base+"/by-key/"+key+"/draft",bytes(draftBody(in).toString()),token,200,"OperationEnvelope").get("data");assertEquals(in.path("reason"),d.path("input").get("reason"));
        var p=http("POST",base+"/by-key/"+key+"/preview",bytes(previewBody(d).toString()),token,200,"OperationEnvelope").get("data");http("GET","/member-finance/requests/"+p.path("request").path("requestId").asText(),null,token,200,"RequestEnvelope");
        var c=http("POST",base+"/by-key/"+key+"/confirm",bytes(confirmBody(p).toString()),token,200,"OperationEnvelope").get("data");assertEquals(c.path("result"),http("POST",base+"/by-key/"+key+"/confirm",bytes(confirmBody(p).toString()),token,200,"OperationEnvelope").path("data").get("result"));
        http("POST",base+"/by-key/"+key+"/cancel",bytes(cancelBody(p.path("request").get("previewDigest")).toString()),token,200,"CancelEnvelope");http("POST",base+"/by-key/"+key+"/resolve",bytes(obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member)).toString()),token,200,"OperationEnvelope");
        http("GET","/members/"+member+"/finance",null,token,200,"FinanceEnvelope");http("GET","/members/"+member+"/finance/source",null,token,200,"SourceEnvelope");http("GET","/members/"+member+"/finance/entries?fund=MEMBER_FEE&currency=CNY",null,token,200,"EntriesEnvelope");
    }
}
