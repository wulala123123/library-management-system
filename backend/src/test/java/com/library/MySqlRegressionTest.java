package com.library;

import com.library.dto.*;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.service.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;
import static org.junit.jupiter.api.Assertions.*;

// A dedicated, disposable database is required; no H2 substitute for these tests.
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.datasource.url=${MYSQL_TEST_URL}", "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.username=${MYSQL_TEST_USER:root}", "spring.datasource.password=${MYSQL_TEST_PASSWORD:}",
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "bootstrap.admin.username=review_admin", "bootstrap.admin.password=ReviewAdministrator123",
        "spring.datasource.hikari.maximum-pool-size=16", "spring.datasource.hikari.transaction-isolation=TRANSACTION_READ_COMMITTED"
})
@ActiveProfiles("test")
class MySqlRegressionTest {
    @Autowired UserService users;
    @Autowired BookService books;
    @Autowired BorrowRecordService borrows;
    @Autowired BorrowCodeService codes;
    @Autowired PurchaseService purchases;
    @Autowired UserMapper userMapper;
    @Autowired BookMapper bookMapper;
    @Autowired BorrowRecordMapper borrowMapper;
    @Autowired PurchaseOrderMapper orderMapper;
    @Autowired PurchaseItemMapper itemMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.library.security.JwtTokenProvider tokens;

    private User user() {
        UserRequest r = new UserRequest(); r.setUsername("it_" + UUID.randomUUID().toString().substring(0,8));
        r.setPassword("ReviewReader123"); r.setRealName("测试读者"); r.setRole("READER");
        return users.create(r);
    }
    private Book book(int stock) {
        Book b = new Book(); b.setIsbn(UUID.randomUUID().toString().replace("-", "").substring(0,20));
        b.setTitle("并发验证图书"); b.setAuthor("测试作者"); b.setPublisher("测试出版社");
        b.setStockTotal(stock); b.setStockAvailable(stock); b.setStatus(1); bookMapper.insert(b);
        return b;
    }
    private BorrowRecord borrow(User u, Book b) {
        BorrowRequest r = new BorrowRequest(); r.setUserId(u.getId()); r.setBookId(b.getId());
        return borrows.borrow(r);
    }
    private int parallel(int n, IntConsumer work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n), start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i=0; i<n; i++) { final int index=i;
                futures.add(pool.submit(() -> { ready.countDown(); start.await();
                    try { work.accept(index); return true; } catch (RuntimeException e) {
                        // Expected business rejections must not mask SQL failures or deadlocks.
                        if (e instanceof org.springframework.dao.DataAccessException) throw e;
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            int success=0; for (Future<Boolean> f:futures) if(f.get(30,TimeUnit.SECONDS)) success++;
            return success;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private long active(User u) {
        return borrowMapper.selectCount(new LambdaQueryWrapper<BorrowRecord>().eq(BorrowRecord::getUserId,u.getId())
                .in(BorrowRecord::getStatus,"BORROWING","OVERDUE"));
    }

    @Test void allMappedEntityColumnsExist() {
        for (String name : List.of("User","Book","BookCategory","BorrowRecord","FineRecord","Publisher","Dept",
                "Bookshelf","BookshelfStorey","BookLocation","PurchaseTemplate","PurchaseOrder","PurchaseItem",
                "ApprovalRecord","BookResource","Notice","BorrowCode","StockFlow","PermissionLog","BookNormalizeLog")) {
            try {
                var info = com.baomidou.mybatisplus.core.metadata.TableInfoHelper.getTableInfo(Class.forName("com.library.entity."+name));
                assertNotNull(info, name);
                jdbc.queryForList("SELECT " + info.getAllSqlSelect() + " FROM " + info.getTableName() + " LIMIT 0");
            } catch (ClassNotFoundException e) { throw new AssertionError(e); }
        }
    }
    @Test void onlyOneReaderGetsLastCopy() throws Exception {
        Book b=book(1); List<User> readers=new ArrayList<>(); for(int i=0;i<8;i++)readers.add(user());
        assertEquals(1,parallel(8,i->borrow(readers.get(i),b)));
        assertEquals(0,bookMapper.selectById(b.getId()).getStockAvailable());
    }
    @Test void sameReaderCannotBypassLimitWithDifferentBooks() throws Exception {
        User u=user(); List<Book> titles=new ArrayList<>(); for(int i=0;i<12;i++)titles.add(book(1));
        assertEquals(10,parallel(12,i->borrow(u,titles.get(i)))); assertEquals(10,active(u));
    }
    @Test void duplicateReturnsIncreaseStockOnlyOnce() throws Exception {
        Book b=book(1); BorrowRecord r=borrow(user(),b);
        assertEquals(8,parallel(8,i->borrows.returnBook(r.getId())));
        assertEquals(1,bookMapper.selectById(b.getId()).getStockAvailable());
    }
    @Test void differentReturnsDoNotLoseStockIncrements() throws Exception {
        Book b=book(2); List<BorrowRecord> records=List.of(borrow(user(),b),borrow(user(),b));
        assertEquals(2,parallel(2,i->borrows.returnBook(records.get(i).getId())));
        assertEquals(2,bookMapper.selectById(b.getId()).getStockAvailable());
    }
    @Test void parallelRenewalsCannotExceedLimit() throws Exception {
        BorrowRecord r=borrow(user(),book(1));
        assertEquals(2,parallel(8,i->borrows.renew(r.getId())));
        assertEquals(2,borrowMapper.selectById(r.getId()).getRenewCount());
    }
    @Test void overdueStillProtectsUserBookAndDuplicateBorrow() {
        User u=user(); Book b=book(2); BorrowRecord r=borrow(u,b);
        jdbc.update("UPDATE borrow_record SET status='OVERDUE', due_date=? WHERE id=?",LocalDate.now().minusDays(1),r.getId());
        assertThrows(RuntimeException.class,()->borrow(u,b));
        assertThrows(RuntimeException.class,()->users.delete(u.getId()));
        assertThrows(RuntimeException.class,()->books.delete(b.getId()));
        assertEquals(1,active(u));
        assertEquals("OVERDUE",borrows.getMyBorrows(1,10,u.getId(),"ACTIVE").getRecords().get(0).getStatus());
    }
    @Test void generatingCodeDoesNotReserveStockAndRedemptionIsAtomic() throws Exception {
        User u=user(); Book b=book(1); BorrowCode c=codes.generateCode(u.getId(),b.getId());
        assertEquals(1,bookMapper.selectById(b.getId()).getStockAvailable()); assertEquals(0,active(u));
        assertEquals(1,parallel(4,i->codes.verifyAndBorrow(c.getCode(),u.getId())));
        assertEquals(1,active(u)); assertEquals(0,bookMapper.selectById(b.getId()).getStockAvailable());
    }
    @Test void failedRedemptionCanBeRetriedAfterStockReturns() {
        User u=user(); Book b=book(1); BorrowCode c=codes.generateCode(u.getId(),b.getId());
        BorrowRecord other=borrow(user(),b);
        assertThrows(RuntimeException.class,()->codes.verifyAndBorrow(c.getCode(),u.getId()));
        assertEquals(0,active(u)); borrows.returnBook(other.getId());
        assertEquals("USED",codes.verifyAndBorrow(c.getCode(),u.getId()).getStatus());
    }
    @Test void totalStockCannotFallBelowUnreturnedCopies() {
        Book b=book(2); borrow(user(),b);
        BookRequest r=new BookRequest(); org.springframework.beans.BeanUtils.copyProperties(b,r); r.setStockTotal(0);
        assertThrows(RuntimeException.class,()->books.update(r));
        assertEquals(2,bookMapper.selectById(b.getId()).getStockTotal());
    }
    private PurchaseOrder order(Book b,int quantity) {
        PurchaseOrder order=new PurchaseOrder(); order.setOrderNo(UUID.randomUUID().toString());
        order.setApplicantId(user().getId()); order.setStatus("APPROVED"); orderMapper.insert(order);
        PurchaseItem item=new PurchaseItem(); item.setOrderId(order.getId()); item.setBookTitle(b.getTitle());
        item.setIsbn(b.getIsbn()); item.setAuthor(b.getAuthor()); item.setPublisher(b.getPublisher());
        item.setQuantity(quantity); item.setInstockQuantity(0); itemMapper.insert(item); return order;
    }
    @Test void existingIsbnProcurementIsMergedAndOrderCannotBeRestockedTwice() throws Exception {
        Book b=book(2); PurchaseOrder order=order(b,3);
        assertEquals(1,parallel(4,i->purchases.quickInstock(order.getId())));
        Book current=bookMapper.selectById(b.getId()); assertEquals(5,current.getStockTotal()); assertEquals(5,current.getStockAvailable());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM stock_flow WHERE related_id=?",Integer.class,order.getId()));
    }
    @Test void twoOrdersWithSameIsbnBothIncreaseStock() throws Exception {
        Book b=book(1); List<PurchaseOrder> orders=List.of(order(b,2),order(b,3));
        assertEquals(2,parallel(2,i->purchases.quickInstock(orders.get(i).getId())));
        assertEquals(6,bookMapper.selectById(b.getId()).getStockAvailable());
    }
    @Test void procurementMetadataConflictRollsBack() {
        Book b=book(1); PurchaseOrder order=order(b,2);
        jdbc.update("UPDATE purchase_item SET book_title='不同书名' WHERE order_id=?",order.getId());
        assertThrows(RuntimeException.class,()->purchases.quickInstock(order.getId()));
        assertEquals(1,bookMapper.selectById(b.getId()).getStockAvailable());
        assertEquals("APPROVED",orderMapper.selectById(order.getId()).getStatus());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM stock_flow WHERE related_id=?",Integer.class,order.getId()));
    }

    @Test void auditWriteFailureRollsBackUserAndBookChanges() throws Exception {
        User admin=userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername,"review_admin"));
        String token=tokens.generateToken(admin.getId(),admin.getUsername(),admin.getRole(),admin.getTokenVersion());
        String username="rollback_"+UUID.randomUUID().toString().substring(0,8);
        jdbc.execute("CREATE TRIGGER repair_fail_permission BEFORE INSERT ON permission_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit failure'");
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/users")
                    .header("Authorization","Bearer "+token).contentType("application/json")
                    .content("{\"username\":\""+username+"\",\"password\":\"ReaderPassword123\",\"realName\":\"测试读者\",\"role\":\"READER\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is5xxServerError());
            assertEquals(0L,userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername,username)));
        } finally {jdbc.execute("DROP TRIGGER repair_fail_permission");}
        Book b=book(1);
        jdbc.execute("CREATE TRIGGER repair_fail_normalize BEFORE INSERT ON book_normalize_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test audit failure'");
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/book-normalize/"+b.getId())
                    .header("Authorization","Bearer "+token).contentType("application/json").content("{\"title\":\"新书名\"}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is5xxServerError());
            assertEquals(b.getTitle(),bookMapper.selectById(b.getId()).getTitle());
        } finally {jdbc.execute("DROP TRIGGER repair_fail_normalize");}
    }
}
