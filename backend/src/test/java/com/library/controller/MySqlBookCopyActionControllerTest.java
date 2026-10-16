package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.dto.BookCopyActionContracts.*;
import com.library.dto.BorrowRequest;
import com.library.entity.*;
import com.library.service.BookCopyActionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=CopyActionSyntheticAdmin123"})
class MySqlBookCopyActionControllerTest extends BookCopyActionControllerTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r){String url=System.getenv("MYSQL_TEST_URL");if(url==null||!url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"))throw new IllegalStateException("Dedicated disposable schema required");r.add("spring.datasource.url",()->url);r.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");r.add("spring.datasource.username",()->System.getenv().getOrDefault("MYSQL_TEST_USER","root"));r.add("spring.datasource.password",()->System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""));}
    @BeforeEach void roles(){for(String[] r:new String[][]{{"admin","ADMIN"},{"librarian","LIBRARIAN"},{"reader","READER"}})if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",Long.class,r[0])==0)jdbc.update("INSERT INTO sys_user(username,password,real_name,role,status) VALUES (?,'synthetic-only','合成动作验证',?,1)",r[0],r[1]);}
    @Override protected void installFailure(){jdbc.execute("CREATE TRIGGER copy_action_fail BEFORE UPDATE ON book_copy_action_request FOR EACH ROW BEGIN IF NEW.state='COMMITTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic-action-audit-failure';END IF;END");}
    @Test void sameKeyWriteOffAndRestoreContentionNeverDuplicatesEventsOrStock()throws Exception{
        Book b=book(1,1);BookCopy c=copy(b,"AVAILABLE",null);ObjectNode r=request("WRITE_OFF",c);JsonNode p=preview(r,"admin");List<Object> replay=parallel(()->commit(r,p),()->commit(r,p));assertInstanceOf(Receipt.class,replay.get(0));assertEquals(replay.get(0),replay.get(1));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM book_copy_action WHERE copy_id=?",Integer.class,c.getId()));
        ObjectNode first=request("RESTORE",c),second=request("RESTORE",c);restore(first,c,"找到");restore(second,c,"找到");JsonNode ap=preview(first,"admin"),bp=preview(second,"admin");List<Object> restored=parallel(()->commit(first,ap),()->commit(second,bp));assertEquals(1,restored.stream().filter(Receipt.class::isInstance).count());assertEquals(1,restored.stream().filter(BookCopyActionException.class::isInstance).count());assertEquals(1,books.selectById(b.getId()).getStockTotal());assertEquals(0,books.selectById(b.getId()).getStockAvailable());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM book_copy_action WHERE action_type='RESTORE' AND copy_id=?",Integer.class,c.getId()));
    }
    @Test void twoDifferentWriteOffKeysAndOppositeInputOrderMovesHaveOneWinnerWithoutDeadlock()throws Exception{
        Book a=book(1,1),b=book(1,1);BookCopy x=copy(a,"AVAILABLE",null),y=copy(b,"AVAILABLE",null);ObjectNode one=request("MOVE",x,y),two=request("MOVE",y,x);
        for(ObjectNode r:List.of(one,two))for(JsonNode c:r.path("copies"))((ObjectNode)c).set("targetLocation",json.createObjectNode().put("kind","TEXT").put("text",r==one?"甲位置":"乙位置"));
        JsonNode ap=preview(one,"admin"),bp=preview(two,"admin");List<Object> moved=parallel(()->commit(one,ap),()->commit(two,bp));assertEquals(1,moved.stream().filter(Receipt.class::isInstance).count());assertEquals(1,moved.stream().filter(BookCopyActionException.class::isInstance).count());
        ObjectNode off1=request("WRITE_OFF",x),off2=request("WRITE_OFF",x);JsonNode p1=preview(off1,"admin"),p2=preview(off2,"admin");List<Object> off=parallel(()->commit(off1,p1),()->commit(off2,p2));assertEquals(1,off.stream().filter(Receipt.class::isInstance).count());assertEquals(0,books.selectById(a.getId()).getStockTotal());assertEquals(0,books.selectById(a.getId()).getStockAvailable());
    }
    @Test void borrowingAndDisablingSerializeOnBookAndReturnNeverAllowsAvailableAba()throws Exception{
        Book b=book(1,1);BookCopy c=copy(b,"AVAILABLE",null);ObjectNode disable=request("DISABLE",c);JsonNode p=preview(disable,"admin");BorrowRequest br=new BorrowRequest();br.setBookId(b.getId());br.setCopyId(c.getId());br.setUserId(reader());
        List<Object> results=parallel(()->commit(disable,p),()->{try{return borrows.borrow(br);}catch(org.springframework.dao.DataAccessException e){throw e;}catch(RuntimeException e){return e;}});long events=results.stream().filter(Receipt.class::isInstance).count(),loans=results.stream().filter(BorrowRecord.class::isInstance).count();assertEquals(1,events+loans);assertEquals(0,books.selectById(b.getId()).getStockAvailable());
        Book fresh=book(1,1);BookCopy available=copy(fresh,"AVAILABLE",null);ObjectNode old=request("WRITE_OFF",available);JsonNode oldPreview=preview(old,"admin");BorrowRequest borrow=new BorrowRequest();borrow.setBookId(fresh.getId());borrow.setCopyId(available.getId());borrow.setUserId(reader());BorrowRecord loan=borrows.borrow(borrow);
        List<Object> race=parallel(()->commit(old,oldPreview),()->borrows.returnBook(loan.getId()));assertInstanceOf(BookCopyActionException.class,race.get(0));assertInstanceOf(BorrowRecord.class,race.get(1));assertEquals("PREVIEW_STALE",((BookCopyActionException)race.get(0)).reasonCode());assertEquals(1,books.selectById(fresh.getId()).getStockAvailable());assertEquals(2,copies.selectById(available.getId()).getActionVersion());
    }
    private Receipt commit(ObjectNode r,JsonNode p)throws Exception{return actions.confirm(p.path("requestId").asLong(),json.treeToValue(confirmation(r,p),ConfirmRequest.class),actor("admin"),true);}
    @SafeVarargs private final List<Object> parallel(Callable<Object>...work)throws Exception{ExecutorService pool=Executors.newFixedThreadPool(work.length);CountDownLatch ready=new CountDownLatch(work.length),start=new CountDownLatch(1);List<Future<Object>> futures=new ArrayList<>();try{for(Callable<Object> action:work)futures.add(pool.submit(()->{ready.countDown();start.await();try{return action.call();}catch(BookCopyActionException e){return e;}}));assertTrue(ready.await(10,TimeUnit.SECONDS));start.countDown();List<Object> result=new ArrayList<>();for(var future:futures)result.add(future.get(30,TimeUnit.SECONDS));return result;}finally{start.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}}
}
