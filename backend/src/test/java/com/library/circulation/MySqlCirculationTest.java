package com.library.circulation;

import com.fasterxml.jackson.databind.JsonNode;
import com.library.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.library.circulation.CirculationSupport.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=circulation_bootstrap","bootstrap.admin.password=CirculationSyntheticAdmin123","spring.datasource.hikari.maximum-pool-size=12"})
class MySqlCirculationTest extends CirculationServiceTest {
    private static String isolatedSchema;
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r){String url=System.getenv("MYSQL_TEST_URL");if(url==null||!url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"))throw new IllegalStateException("Dedicated disposable schema required");isolatedSchema="library_repair_tests_circulation_runtime_"+UUID.randomUUID().toString().replace("-","").substring(0,8);try(Connection c=DriverManager.getConnection(url,System.getenv().getOrDefault("MYSQL_TEST_USER","root"),System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""));Statement s=c.createStatement()){s.execute("CREATE DATABASE `"+isolatedSchema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");}catch(SQLException e){throw new IllegalStateException("Cannot create isolated circulation schema",e);}String isolated=url.replace("/library_repair_tests","/"+isolatedSchema);r.add("spring.datasource.url",()->isolated);r.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");r.add("spring.datasource.username",()->System.getenv().getOrDefault("MYSQL_TEST_USER","root"));r.add("spring.datasource.password",()->System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""));}
    @AfterAll static void cleanSchema()throws Exception{if(isolatedSchema!=null)try(Connection c=DriverManager.getConnection(System.getenv("MYSQL_TEST_URL"),System.getenv().getOrDefault("MYSQL_TEST_USER","root"),System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""));Statement s=c.createStatement()){s.execute("DROP DATABASE `"+isolatedSchema+"`");}}
    @LocalServerPort int port;@Autowired JwtTokenProvider jwt;
    @Autowired com.library.service.BookCopyActionService actions;
    @Autowired com.library.service.InventoryIntakeService intakes;
    @Autowired com.library.stocktake.StocktakeService stocktake;
    @Override protected void installReceiptFailure(){jdbc.execute("CREATE TRIGGER circulation_receipt_fail BEFORE UPDATE ON circulation_request FOR EACH ROW BEGIN IF NEW.state='COMMITTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic circulation receipt failure'; END IF; END");}
    @Test void actualHttpHasLiteralBoundedBodiesCurrentPermissionsAndOriginalReceiptRecovery()throws Exception{
        service.clock=Clock.systemUTC();Copy c=copy();String k=UUID.randomUUID().toString();JsonNode in=borrow(c);((com.fasterxml.jackson.databind.node.ObjectNode)in).put("remark","<script>原文</script> union select /* 原文 */");
        String token=jwt.generateToken(admin,"synthetic", "ADMIN",0L),reader=jwt.generateToken(member,"synthetic","READER",0L);
        HttpClient client=HttpClient.newHttpClient();List<JsonNode> captures=new ArrayList<>();
        for(long staff:List.of(admin,clerk)){var invalidTarget=in.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)invalidTarget).put("userId",staff);assertEquals("MEMBER_NOT_FOUND",http(client,"POST","/circulation/requests/"+UUID.randomUUID()+"/preview",write(invalidTarget),token,404,captures).path("data").path("reasonCode").asText());}
        String roleKey=UUID.randomUUID().toString();JsonNode rolePreview=http(client,"POST","/circulation/requests/"+roleKey+"/preview",write(in),token,200,captures).path("data");jdbc.update("UPDATE sys_user SET role='ADMIN' WHERE id=?",member);
        assertEquals("PREVIEW_STALE",http(client,"POST","/circulation/requests/"+roleKey+"/confirm",write(obj("previewDigest",rolePreview.get("previewDigest"))),token,409,captures).path("data").path("reasonCode").asText());jdbc.update("UPDATE sys_user SET role='READER' WHERE id=?",member);http(client,"POST","/circulation/requests/"+roleKey+"/cancel","{}",token,200,captures);
        JsonNode p=http(client,"POST","/circulation/requests/"+k+"/preview",write(in),token,200,captures).path("data");
        JsonNode receipt=http(client,"POST","/circulation/requests/"+k+"/confirm",write(obj("previewDigest",p.get("previewDigest"))),token,200,captures).path("data");
        assertEquals(receipt,http(client,"GET","/circulation/requests/"+k,null,token,200,captures).path("data").path("receipt"));
        http(client,"GET","/circulation/orders/"+receipt.path("orderId").asLong(),null,token,200,captures);
        http(client,"GET","/circulation/orders?userId="+member,null,token,200,captures);
        Copy historical=copy();jdbc.update("UPDATE book SET stock_available=0 WHERE id=?",historical.book());
        jdbc.update("UPDATE sys_user SET real_name=NULL,status=0,role='LIBRARIAN' WHERE id=?",member);
        jdbc.update("INSERT INTO borrow_record(user_id,book_id,copy_id,borrow_date,due_date,due_date_kind,status) VALUES (?,?,NULL,'2020-01-01',NULL,'UNKNOWN','BORROWING')",member,historical.book());
        long old=jdbc.queryForObject("SELECT id FROM borrow_record WHERE book_id=?",Long.class,historical.book());
        String returned=UUID.randomUUID().toString();JsonNode rp=http(client,"POST","/circulation/requests/"+returned+"/preview",write(returns(receipt.path("items").get(0).path("recordId").asLong(),old)),token,200,captures).path("data");
        http(client,"GET","/circulation/requests/"+returned,null,token,200,captures);
        http(client,"POST","/circulation/requests/"+returned+"/confirm",write(obj("previewDigest",rp.get("previewDigest"))),token,200,captures);
        http(client,"GET","/circulation/orders/"+receipt.path("orderId").asLong(),null,token,200,captures);
        http(client,"GET","/circulation/orders?userId="+member+"&state=RETURNED",null,token,200,captures);
        String cancelled=UUID.randomUUID().toString();http(client,"POST","/circulation/requests/"+cancelled+"/cancel","{}",token,200,captures);http(client,"GET","/circulation/requests/"+cancelled,null,token,200,captures);
        jdbc.update("UPDATE sys_user SET status=1,role='READER' WHERE id=?",member);
        assertEquals("ROLE_REQUIRED",http(client,"POST","/circulation/requests/not-uuid/preview","bad".repeat(20000),reader,403,captures).path("data").path("reasonCode").asText());
        http(client,"POST","/circulation/requests/"+UUID.randomUUID()+"/cancel","{\"x\":1,\"x\":2}",token,400,captures);
        Files.createDirectories(Path.of("target"));Files.writeString(Path.of("target/circulation-mysql-http-captures.json"),write(captures));
    }
    private JsonNode http(HttpClient c,String method,String path,String body,String token,int expected,List<JsonNode> captures)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));var r=c.send(b.build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,r.statusCode(),r.body());JsonNode n=read(r.body());captures.add(obj("method",method,"path",path,"requestBodyUtf8",body,"status",r.statusCode(),"body",n));return n;}
    @Test void expiryAndRoleRevocationAreRecheckedAfterActualMysqlLockWait()throws Exception{
        Copy c=copy();String k=UUID.randomUUID().toString();JsonNode p=preview(k,borrow(c));
        blocked(c.book(),()->service.clock=Clock.fixed(Instant.parse("2026-10-20T02:15:00Z"),ZoneOffset.UTC),()->confirm(k,p),"PREVIEW_EXPIRED");assertEquals(1,available(c));
        service.clock=Clock.fixed(Instant.parse("2026-10-20T02:00:00Z"),ZoneOffset.UTC);String role=UUID.randomUUID().toString();JsonNode rp=preview(role,borrow(c));
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        try{Future<?> holder=pool.submit(()->new TransactionTemplate(tm).executeWithoutResult(s->{jdbc.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE",admin);locked.countDown();await(release);jdbc.update("UPDATE sys_user SET role='READER' WHERE id=?",admin);}));assertTrue(locked.await(5,TimeUnit.SECONDS));Future<Object> waiting=pool.submit(()->{try{return confirm(role,rp);}catch(Failure e){return e;}});waitForDatabaseWait();release.countDown();holder.get(8,TimeUnit.SECONDS);Object result=waiting.get(8,TimeUnit.SECONDS);assertInstanceOf(Failure.class,result);assertEquals("ROLE_REQUIRED",((Failure)result).reason);assertEquals(1,available(c));}finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(8,TimeUnit.SECONDS));}
    }
    private void blocked(long book,Runnable change,Callable<Object> action,String code)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);try{Future<?> holder=pool.submit(()->new TransactionTemplate(tm).executeWithoutResult(s->{jdbc.queryForList("SELECT id FROM book WHERE id=? FOR UPDATE",book);locked.countDown();await(release);}));assertTrue(locked.await(5,TimeUnit.SECONDS));Future<Object> waiting=pool.submit(()->{try{return action.call();}catch(Failure e){return e;}});waitForDatabaseWait();change.run();release.countDown();holder.get(8,TimeUnit.SECONDS);Object result=waiting.get(8,TimeUnit.SECONDS);assertInstanceOf(Failure.class,result);assertEquals(code,((Failure)result).reason);}finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(8,TimeUnit.SECONDS));}}
    @Test void expiryIsCheckedAfterLastCopyOfWholeBatchIsLocked()throws Exception{Copy first=copy(),last=copy();String key=UUID.randomUUID().toString();JsonNode p=preview(key,borrow(first,last));ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);try{Future<?> holder=pool.submit(()->new TransactionTemplate(tm).executeWithoutResult(s->{jdbc.queryForList("SELECT id FROM book_copy WHERE id=? FOR UPDATE",last.copy());locked.countDown();await(release);}));assertTrue(locked.await(5,TimeUnit.SECONDS));Future<Object> waiting=pool.submit(()->{try{return confirm(key,p);}catch(Failure e){return e;}});waitForDatabaseWait();service.clock=Clock.fixed(Instant.parse("2026-10-20T02:15:00Z"),ZoneOffset.UTC);release.countDown();holder.get(8,TimeUnit.SECONDS);Object result=waiting.get(8,TimeUnit.SECONDS);assertInstanceOf(Failure.class,result);assertEquals("PREVIEW_EXPIRED",((Failure)result).reason);assertEquals(1,available(first));assertEquals(1,available(last));assertEquals("PREVIEWED",service.get(key,admin).path("state").asText());}finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(8,TimeUnit.SECONDS));}}
    private void waitForDatabaseWait()throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);do{if(jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0)return;Thread.sleep(20);}while(System.nanoTime()<until);fail("Expected actual MySQL lock wait");}
    private void await(CountDownLatch latch){try{assertTrue(latch.await(8,TimeUnit.SECONDS));}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    @Test void secondLoanInsertFailureRollsBackFirstLoanItsFlowAndBothCopyVersions(){Copy a=copy(),b=copy();String k=UUID.randomUUID().toString();JsonNode p=preview(k,borrow(a,b));long records=count("borrow_record"),flows=count("stock_flow"),orders=count("circulation_order");jdbc.execute("CREATE TRIGGER circulation_loan_fail BEFORE INSERT ON borrow_record FOR EACH ROW BEGIN IF NEW.book_id="+b.book()+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic second loan failure'; END IF; END");try{reason("CIRCULATION_SAVE_FAILED",()->confirm(k,p));}finally{jdbc.execute("DROP TRIGGER circulation_loan_fail");}assertEquals(records,count("borrow_record"));assertEquals(flows,count("stock_flow"));assertEquals(orders,count("circulation_order"));assertEquals(1,available(a));assertEquals(0,jdbc.queryForObject("SELECT action_version FROM book_copy WHERE id=?",Integer.class,a.copy()));}
    @Test void newBatchVersusCopyDisableAndReturnVersusIntakeUseCompatibleLocks()throws Exception{
        Copy a=copy();String k=UUID.randomUUID().toString();JsonNode p=preview(k,borrow(a));
        var disable=new com.library.dto.BookCopyActionContracts.PreviewRequest();disable.setIdempotencyKey(UUID.randomUUID().toString());disable.setAction("DISABLE");disable.setReason("synthetic circulation competition");var ci=new com.library.dto.BookCopyActionContracts.CopyInput();ci.setCopyId(a.copy());ci.setExpectedVersion(0L);disable.setCopies(List.of(ci));var dp=actions.preview(disable,admin,true);var dc=new com.library.dto.BookCopyActionContracts.ConfirmRequest();dc.setIdempotencyKey(disable.getIdempotencyKey());dc.setPreviewDigest(dp.previewDigest());
        List<Object> raced=parallel(()->confirm(k,p),()->actions.confirm(dp.requestId(),dc,admin,true));assertEquals(1,raced.stream().filter(x->x instanceof JsonNode||x instanceof com.library.dto.BookCopyActionContracts.Receipt).count());assertEquals(0,available(a));
        Copy b=copy();JsonNode loan=lend(b);long id=loan.path("items").get(0).path("recordId").asLong();String ret=UUID.randomUUID().toString();JsonNode rp=preview(ret,returns(id));
        var req=new com.library.dto.InventoryIntakeContracts.PreviewRequest();req.setIdempotencyKey(UUID.randomUUID().toString());req.setReason("synthetic circulation intake competition");var source=new com.library.dto.InventoryIntakeContracts.SourceInput();source.setType("MANUAL");req.setSource(source);var line=new com.library.dto.InventoryIntakeContracts.LineInput();line.setLineKey("synthetic-line");var target=new com.library.dto.InventoryIntakeContracts.TargetInput();target.setBookId(b.book());line.setTarget(target);var cp=new com.library.dto.InventoryIntakeContracts.CopyInput();cp.setInput("GENERATE");line.setCopies(List.of(cp));req.setLines(List.of(line));var ip=intakes.preview(req,admin);var ic=new com.library.dto.InventoryIntakeContracts.ConfirmRequest();ic.setIdempotencyKey(req.getIdempotencyKey());ic.setPreviewDigest(ip.previewDigest());
        List<Object> returned=parallel(()->confirm(ret,rp),()->intakes.confirm(ip.intakeId(),ic,admin));assertEquals(1,returned.stream().filter(x->x instanceof JsonNode||x instanceof com.library.dto.InventoryIntakeContracts.Receipt).count());assertEquals(1,available(b));
    }
    @Test void newBatchInvalidatesPreparedStocktakeAndConcurrentSealNeverMutatesInventory()throws Exception{
        Copy c=copy();var actor=new com.library.stocktake.StocktakeService.Actor(admin,true,false);JsonNode session=stocktake.create(obj("operationKey",UUID.randomUUID().toString(),"scope",obj("kind","COPY_SET","copyIds",List.of(c.copy())),"campaignKey",null,"reason","synthetic circulation scope"),actor).path("result").path("session");long id=session.path("sessionId").asLong();
        JsonNode prepared=stocktake.check(id,obj("operationKey",UUID.randomUUID().toString(),"expectedRevision",0,"reason","synthetic prepare","completionKind","PARTIAL"),actor,true).path("result").path("review");
        lend(c);var failure=assertThrows(com.library.stocktake.StocktakeSupport.Failure.class,()->stocktake.confirm(id,obj("operationKey",UUID.randomUUID().toString(),"expectedRevision",0,"reason","synthetic seal","reviewSnapshotId",prepared.path("snapshotId").asLong(),"previewDigest",prepared.path("digest").asText()),actor));assertEquals("STOCKTAKE_CHANGED",failure.code);assertEquals(0,available(c));
    }
}
