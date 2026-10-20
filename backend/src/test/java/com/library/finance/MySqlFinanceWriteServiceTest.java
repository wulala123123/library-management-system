package com.library.finance;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import java.util.concurrent.*;
import java.time.*;
import static com.library.finance.FinanceSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=B40BSyntheticAdministrator123","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
public class MySqlFinanceWriteServiceTest extends FinanceWriteServiceTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r){MySqlFinanceReadServiceTest.mysql(r);}
    @Test void concurrentSameFactAcrossDistinctActorsAndMembersUsesRealSavepoint()throws Exception {
        String fact=UUID.randomUUID().toString();var first=preview("4.56",fact);var body1=confirmBody(first);var auth1=auth;String key1=key;
        key=UUID.randomUUID().toString();member=f.member();actor=f.insert("INSERT INTO sys_user(username,password,role,status) VALUES (?,?,'ADMIN',1)","b40b_race_"+UUID.randomUUID(),"synthetic-hash");auth=auth(actor);editor=UUID.randomUUID().toString();var second=preview("4.56",fact);var body2=confirmBody(second);var auth2=auth;String key2=key;
        String trigger="b40b_slow_fact";jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON member_finance_entry FOR EACH ROW BEGIN IF NEW.root_fact_key=X'"+HexFormat.of().formatHex(bytes(fact))+"' THEN DO SLEEP(1); END IF; END");
        var pool=Executors.newFixedThreadPool(2);try {
            long rollbackBefore=Long.parseLong(jdbc.queryForMap("SHOW GLOBAL STATUS LIKE 'Com_rollback_to_savepoint'").get("Value").toString());
            var start=new CountDownLatch(1);var a=pool.submit(()->{start.await();return writer.command(key1,"confirm",FinanceCommands.validate(body1,"confirm"),auth1);});var b=pool.submit(()->{start.await();return writer.command(key2,"confirm",FinanceCommands.validate(body2,"confirm"),auth2);});start.countDown();
            var ra=a.get(15,TimeUnit.SECONDS);var rb=b.get(15,TimeUnit.SECONDS);assertEquals(1,(ra.failure()==null?1:0)+(rb.failure()==null?1:0));assertEquals("BUSINESS_FACT_EXISTS",(ra.failure()==null?rb:ra).failure().code);assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM member_finance_entry WHERE root_fact_key=?",Integer.class,bytes(fact)));
            long rollbackAfter=Long.parseLong(jdbc.queryForMap("SHOW GLOBAL STATUS LIKE 'Com_rollback_to_savepoint'").get("Value").toString());assertEquals(rollbackBefore+1,rollbackAfter,"The actual MySQL 1062 path must roll back to the savepoint exactly once");captures.add(obj("kind","actual-mysql-savepoint-status","rollbackBefore",rollbackBefore,"rollbackAfter",rollbackAfter));
            captures.add(obj("kind","actual-mysql-concurrent-fact-savepoint","schemaDefinition","ErrorEnvelope","body",error((ra.failure()==null?rb:ra).failure())));
        }finally {pool.shutdownNow();jdbc.execute("DROP TRIGGER "+trigger);}
    }
    @Test void allFinancialPersistenceFailuresRollbackEntryCutoffRevisionAuditAndResults()throws Exception {
        for(String phase:List.of("entry","revision","audit","request","operation")) {
            key=UUID.randomUUID().toString();editor=UUID.randomUUID().toString();member=f.member();var p=preview("7.89",UUID.randomUUID().toString());var body=confirmBody(p);var before=f.counts();String table=switch(phase){case "entry"->"member_finance_entry";case "revision"->"member_finance_account";case "audit"->"member_finance_audit";case "request"->"member_finance_request";default->"member_finance_operation";};
            String condition=phase.equals("request")||phase.equals("operation")?"IF NEW.state='COMMITTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic persistence failure'; END IF;":"SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic persistence failure';";
            jdbc.execute("CREATE TRIGGER b40b_fail BEFORE "+(phase.equals("entry")||phase.equals("audit")?"INSERT":"UPDATE")+" ON "+table+" FOR EACH ROW BEGIN "+condition+" END");try {
                assertThrows(RuntimeException.class,()->writer.command(key,"confirm",FinanceCommands.validate(body,"confirm"),auth));
                if(phase.equals("operation")) {var response=http("POST","/member-finance/operations/by-key/"+key+"/confirm",bytes(body.toString()),jwt.generateToken(actor,"SYNTHETIC_ADMIN","ADMIN",0L),500,"ErrorEnvelope");assertEquals("UNKNOWN",response.path("data").path("certainty").asText());assertEquals("READ_KEY",response.path("data").path("recovery").asText());assertEquals(key,response.path("data").path("operationKey").asText());}
            }finally {jdbc.execute("DROP TRIGGER b40b_fail");}
            assertEquals(before,f.counts(),phase);var account=reader.account(member,"MEMBER_FEE");assertEquals(0,account.revision());assertNull(account.cutoff());assertEquals("PREVIEWED",writer.observe(key,null,null,auth).path("state").asText());
            var committed=ok("confirm",body);assertEquals("COMMITTED",committed.path("state").asText());captures.add(obj("kind","actual-mysql-rollback-"+phase,"schemaDefinition","OperationEnvelope","body",envelope(committed)));
        }
    }
    @Test void expiredRequestConfirmCancelAndResolveContendWithoutFinancialWrite()throws Exception {
        var p=preview("0.01",UUID.randomUUID().toString());var confirm=confirmBody(p);var cancel=cancelBody(p.path("request").get("previewDigest"));var resolve=obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member));
        jdbc.update("UPDATE member_finance_request SET created_at=?,expires_at=? WHERE operation_key=?",reader.now().minusMinutes(20),reader.now().minusMinutes(1),bytes(key));long before=count("member_finance_entry");var pool=Executors.newFixedThreadPool(3);try {
            var start=new CountDownLatch(1);List<Future<FinanceWriteService.Result>> results=new ArrayList<>();for(String kind:List.of("confirm","cancel","resolve")){var body=kind.equals("confirm")?confirm:kind.equals("cancel")?cancel:resolve;results.add(pool.submit(()->{start.await();return writer.command(key,kind,FinanceCommands.validate(body,kind),auth);}));}start.countDown();for(var result:results)result.get(10,TimeUnit.SECONDS);
            assertEquals(before,count("member_finance_entry"));assertEquals("EXPIRED",writer.observe(key,null,null,auth).path("state").asText());assertEquals("EXPIRED",writer.observe(key,null,null,auth).path("request").path("state").asText());
        }finally {pool.shutdownNow();}
    }
    @Test void confirmCancelAndClaimRacesRespectOneSerializedOutcome()throws Exception {
        var pool=Executors.newFixedThreadPool(2);try {
            for(String race:List.of("cancel","claim"))for(int i=0;i<4;i++) {
                key=UUID.randomUUID().toString();editor=UUID.randomUUID().toString();var p=preview("0.01",UUID.randomUUID().toString());var confirm=confirmBody(p);
                var other=race.equals("cancel")?cancelBody(p.path("request").get("previewDigest")):obj("schemaVersion",1,"operationKey",key,"memberId",Long.toString(member),"editorId",UUID.randomUUID().toString(),"expectedEpoch","1","claimKey",UUID.randomUUID().toString());String opKey=key;long before=count("member_finance_entry");
                var gate=new CountDownLatch(1);var a=pool.submit(()->{gate.await();try{return writer.command(opKey,"confirm",FinanceCommands.validate(confirm,"confirm"),auth);}catch(Failure e){return new FinanceWriteService.Result(null,e);}});var b=pool.submit(()->{gate.await();return writer.command(opKey,race,FinanceCommands.validate(other,race),auth);});gate.countDown();var ra=a.get(15,TimeUnit.SECONDS);var rb=b.get(15,TimeUnit.SECONDS);assertNull(rb.failure());
                var state=writer.observe(opKey,null,null,auth).path("state").asText();if(state.equals("COMMITTED")){assertNull(ra.failure());assertEquals(before+1,count("member_finance_entry"));}else {assertEquals(before,count("member_finance_entry"));assertNotNull(ra.failure());assertEquals(race.equals("cancel")?"CANCELLED":"EDITOR_CHANGED",ra.failure().code);}
                if(state.equals("PREVIEWED")) {var current=writer.observe(opKey,null,null,auth);var cancel=cancelBody(current.path("request").get("previewDigest"));cancel.set("editor",current.get("editor"));ok("cancel",cancel);}
            }
        }finally {pool.shutdownNow();}
    }
    void awaitUserLock()throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(4);
        while(System.nanoTime()<until) {if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0)return;Thread.sleep(20);}fail("Expected an actual InnoDB row-lock wait");
    }
    @Test void permissionsTokenVersionAndExpiryAreRecheckedAfterActualUserLockWait()throws Exception {
        for(String change:List.of("role","version","expiry")) {
            key=UUID.randomUUID().toString();editor=UUID.randomUUID().toString();var p=preview("0.02",UUID.randomUUID().toString());var body=confirmBody(p);String opKey=key;var commandAuth=auth;
            if(change.equals("expiry"))commandAuth.setDetails(new TokenVersion(0,Instant.now().plusMillis(500)));
            var pool=Executors.newSingleThreadExecutor();try(var connection=jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM sys_user WHERE id=? FOR UPDATE")){statement.setLong(1,actor);statement.executeQuery().close();}
                var future=pool.submit(()->{try{return writer.command(opKey,"confirm",FinanceCommands.validate(body,"confirm"),commandAuth);}catch(Failure e){return new FinanceWriteService.Result(null,e);}});awaitUserLock();
                if(change.equals("expiry"))Thread.sleep(600);else try(var statement=connection.prepareStatement(change.equals("role")?"UPDATE sys_user SET role='LIBRARIAN' WHERE id=?":"UPDATE sys_user SET token_version=1 WHERE id=?")){statement.setLong(1,actor);statement.executeUpdate();}
                connection.commit();var result=future.get(10,TimeUnit.SECONDS);assertNotNull(result.failure());assertEquals(change.equals("role")?"ADMIN_REQUIRED":"UNAUTHENTICATED",result.failure().code);assertNull(result.failure().operation);
            }finally {pool.shutdownNow();jdbc.update("UPDATE sys_user SET role='ADMIN',token_version=0 WHERE id=?",actor);auth=auth(actor);}
            assertEquals("PREVIEWED",writer.observe(opKey,null,null,auth).path("state").asText());ok("cancel",cancelBody(p.path("request").get("previewDigest")));
        }
    }
    @Test void reserveAndCancelRecheckTrustedExpiryAfterActualAccountLockWait()throws Exception {
        for(String command:List.of("reserve-replay","cancel","cancel-replay")) {
            key=UUID.randomUUID().toString();editor=UUID.randomUUID().toString();epoch=1;var p=preview("0.01",UUID.randomUUID().toString());var cancel=cancelBody(p.path("request").get("previewDigest"));
            if(command.equals("reserve-replay"))ok("confirm",confirmBody(p));if(command.equals("cancel-replay"))ok("cancel",cancel);
            String opKey=key,kind=command.equals("reserve-replay")?"reserve":"cancel";var body=kind.equals("reserve")?reserveBody():cancel;var commandAuth=auth(actor);commandAuth.setDetails(new TokenVersion(0,Instant.now().plusMillis(500)));var before=f.counts();var state=writer.observe(key,null,null,auth).path("state").asText();
            long account=reader.account(member,"MEMBER_FEE").id();var pool=Executors.newSingleThreadExecutor();try(var connection=jdbc.getDataSource().getConnection()) {
                connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM member_finance_account WHERE id=? FOR UPDATE")){statement.setLong(1,account);statement.executeQuery().close();}
                var result=pool.submit(()->{try{return writer.command(kind.equals("reserve")?null:opKey,kind,FinanceCommands.validate(body,kind),commandAuth);}catch(Failure e){return new FinanceWriteService.Result(null,e);}});awaitUserLock();Thread.sleep(600);connection.commit();
                var rejected=result.get(10,TimeUnit.SECONDS);assertNotNull(rejected.failure());assertEquals("UNAUTHENTICATED",rejected.failure().code);assertNull(rejected.failure().operation);captures.add(obj("kind","actual-mysql-post-account-lock-expiry-"+command,"schemaDefinition","ErrorEnvelope","body",error(rejected.failure())));
            }finally {pool.shutdownNow();}
            assertEquals(before,f.counts());assertEquals(state,writer.observe(opKey,null,null,auth).path("state").asText());if(state.equals("PREVIEWED"))ok("cancel",cancel);
        }
    }
    void databaseTime(LocalDateTime instant)throws Exception {
        var connections=new ArrayList<java.sql.Connection>();try {
            // The test pool has 10 slots. Set every session's real MySQL clock deterministically.
            for(int i=0;i<10;i++)connections.add(jdbc.getDataSource().getConnection());
            long seconds=instant==null?0:instant.toEpochSecond(ZoneOffset.UTC);
            for(var connection:connections)try(var statement=connection.createStatement()){statement.execute("SET timestamp="+seconds);}
        }finally {for(var connection:connections)connection.close();}
    }
    @Test void actualDatabaseShanghaiMidnightRejectsNewConfirmButReplaysPriorReceipt()throws Exception {
        String date=FinanceWriteService.businessDay(reader.now());var midnight=LocalDate.parse(date).plusDays(1).atStartOfDay(FinanceReceiptPolicy.ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        auth.setDetails(new TokenVersion(0,Instant.parse("9999-01-01T00:00:00Z")));try {
            databaseTime(midnight.minusSeconds(3));var first=preview("0.03",UUID.randomUUID().toString());var committedBody=confirmBody(first);var receipt=ok("confirm",committedBody).path("result").get("receipt");String committedKey=key;
            key=UUID.randomUUID().toString();editor=UUID.randomUUID().toString();var second=preview("0.04",UUID.randomUUID().toString());var secondBody=confirmBody(second);databaseTime(midnight.plusSeconds(1));assertEquals("DEPENDENCIES_CHANGED",failure("confirm",secondBody));assertEquals("REJECTED",writer.observe(key,null,null,auth).path("state").asText());
            String saved=key;key=committedKey;assertEquals(receipt,ok("confirm",committedBody).path("result").get("receipt"));key=saved;
        }finally {databaseTime(null);}
    }
}
