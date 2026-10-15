package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.dto.BorrowRequest;
import com.library.dto.InventoryIntakeContracts.Receipt;
import com.library.entity.Book;
import com.library.entity.BorrowRecord;
import com.library.service.InventoryIntakeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "bootstrap.admin.username=admin", "bootstrap.admin.password=IntakeTestAdministrator123"})
class MySqlInventoryIntakeControllerTest extends InventoryIntakeControllerTest {
    @Autowired PlatformTransactionManager transactions;
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r) {
        String url = System.getenv("MYSQL_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?")) throw new IllegalStateException("Dedicated disposable library_repair_tests required");
        r.add("spring.datasource.url", () -> url); r.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        r.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_TEST_USER", "root"));
        r.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
    }
    @BeforeEach void fixtures() {
        for (String[] role : new String[][]{{"admin", "ADMIN"}, {"reader", "READER"}, {"librarian", "LIBRARIAN"}})
            if (count("sys_user", "username=?", role[0]) == 0) jdbc.update("INSERT INTO sys_user(username,password,real_name,role,status) VALUES (?,'synthetic-only','合成入库验收',?,1)", role[0], role[1]);
        if (count("book_category", "deleted=0") == 0) jdbc.update("INSERT INTO book_category(name,status) VALUES ('合成入库验收分类',1)");
    }
    @Override protected void installFailureTrigger() { jdbc.execute("CREATE TRIGGER intake_receipt_fail BEFORE UPDATE ON inventory_intake_request FOR EACH ROW BEGIN IF NEW.state='COMMITTED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='private-sql-audit-fixture'; END IF; END"); }

    @Test void differentKeysContendingForSamePurchaseRemainderCannotOverReceive() throws Exception {
        Book b = book(2, 2); long order = order(b, 5), item = item(order);
        jdbc.update("UPDATE purchase_item SET instock_quantity=2 WHERE id=?", item);
        ObjectNode first = purchase(order, item, 2), second = purchase(order, item, 2);
        JsonNode firstPreview = preview(first), secondPreview = preview(second);
        assertNotEquals(first.path("idempotencyKey").asText(), second.path("idempotencyKey").asText());
        // First confirmation has written, but is held before commit while the other key waits on its lock.
        List<Object> results = contendingTransactions(() -> serviceConfirm(first, firstPreview), () -> serviceConfirm(second, secondPreview));
        assertInstanceOf(Receipt.class, results.get(0));
        InventoryIntakeException rejected = assertInstanceOf(InventoryIntakeException.class, results.get(1));
        assertEquals("QUANTITY_EXCEEDS_PENDING", rejected.reasonCode());
        assertEquals(4, jdbc.queryForObject("SELECT instock_quantity FROM purchase_item WHERE id=?", Integer.class, item));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order));
        assertEquals(4, books.selectById(b.getId()).getStockTotal()); assertEquals(4, books.selectById(b.getId()).getStockAvailable());
        assertEquals(2, count("book_copy", "book_id=?", b.getId())); assertEquals(1, count("stock_flow", "related_id=?", order));
        assertEquals(0, count("inventory_intake_copy", "intake_id=?", secondPreview.path("intakeId").asLong()));
        assertEquals(secondPreview, data(get("/inventory-intakes/" + secondPreview.path("intakeId").asLong()), "admin", 200));
        assertEquals(results.get(0), json.treeToValue(confirm(first, firstPreview), Receipt.class));
        ObjectNode remainder = purchase(order, item, 1); JsonNode receipt = confirm(remainder, preview(remainder));
        assertEquals(5, jdbc.queryForObject("SELECT instock_quantity FROM purchase_item WHERE id=?", Integer.class, item));
        assertEquals(0, receipt.path("purchaseProgress").get(0).path("pendingQuantity").asInt());
        assertEquals("COMPLETED", jdbc.queryForObject("SELECT status FROM purchase_order WHERE id=?", String.class, order));
        assertEquals(3, count("book_copy", "book_id=?", b.getId())); assertEquals(5, books.selectById(b.getId()).getStockAvailable());
    }

    @Test void returnAndIntakeContendInBothCommitOrdersWithoutLostAvailableIncrement() throws Exception {
        for (boolean intakeFirst : List.of(true, false)) {
            Book b = book(0, 0); ObjectNode initial = manual(b, 2); confirm(initial, preview(initial));
            BorrowRequest request = new BorrowRequest(); request.setBookId(b.getId()); request.setUserId(newReader());
            BorrowRecord loan = borrows.borrow(request); assertNotNull(loan.getCopyId());
            assertEquals(2, books.selectById(b.getId()).getStockTotal()); assertEquals(1, books.selectById(b.getId()).getStockAvailable());
            ObjectNode addition = manual(b, 1); JsonNode additionPreview = preview(addition);
            Callable<Object> intake = () -> serviceConfirm(addition, additionPreview), returned = () -> borrows.returnBook(loan.getId());
            List<Object> results = intakeFirst ? contendingTransactions(intake, returned) : contendingTransactions(returned, intake);
            if (intakeFirst) {
                Receipt receipt = assertInstanceOf(Receipt.class, results.get(0)); assertInstanceOf(BorrowRecord.class, results.get(1));
                assertEquals(2, receipt.books().get(0).stockAvailableAfter()); // Return is a later independent increment.
                assertEquals(receipt, json.treeToValue(confirm(addition, additionPreview), Receipt.class));
            } else {
                assertInstanceOf(BorrowRecord.class, results.get(0));
                InventoryIntakeException stale = assertInstanceOf(InventoryIntakeException.class, results.get(1)); assertEquals("PREVIEW_STALE", stale.reasonCode());
                assertEquals(2, books.selectById(b.getId()).getStockTotal()); assertEquals(2, books.selectById(b.getId()).getStockAvailable());
                assertEquals(0, count("inventory_intake_copy", "intake_id=?", additionPreview.path("intakeId").asLong()));
                assertEquals(additionPreview, data(get("/inventory-intakes/" + additionPreview.path("intakeId").asLong()), "admin", 200));
                ObjectNode refreshed = addition.deepCopy().put("idempotencyKey", UUID.randomUUID().toString()); confirm(refreshed, preview(refreshed));
            }
            Book after = books.selectById(b.getId()); assertEquals(3, after.getStockTotal()); assertEquals(3, after.getStockAvailable());
            assertEquals(3, count("book_copy", "book_id=? AND status='AVAILABLE'", b.getId()));
            assertEquals("RETURNED", jdbc.queryForObject("SELECT status FROM borrow_record WHERE id=?", String.class, loan.getId()));
            assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM book_copy WHERE id=?", String.class, loan.getCopyId()));
            borrows.returnBook(loan.getId()); assertEquals(3, books.selectById(b.getId()).getStockAvailable());
        }
    }

    /** Observe an actual InnoDB lock wait before releasing the first transaction to commit. */
    private List<Object> contendingTransactions(Callable<Object> first, Callable<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch staged = new CountDownLatch(1), commit = new CountDownLatch(1);
        Future<Object> firstResult = pool.submit(() -> new TransactionTemplate(transactions).execute(transaction -> {
            try {
                Object result = first.call(); staged.countDown();
                if (!commit.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Concurrent test did not release its transaction");
                return result;
            } catch (RuntimeException e) { throw e; }
            catch (Exception e) { throw new IllegalStateException(e); }
        }));
        try {
            assertTrue(staged.await(10, TimeUnit.SECONDS), "First transaction must stage its mutation before competing");
            Future<Object> secondResult = pool.submit(() -> { try { return second.call(); } catch (InventoryIntakeException e) { return e; } });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
            while (System.nanoTime() < deadline) {
                waiting = jdbc.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits w JOIN performance_schema.data_locks l ON l.ENGINE=w.ENGINE AND l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID WHERE l.OBJECT_SCHEMA=DATABASE()", Integer.class) > 0;
                if (waiting) break;
                Thread.sleep(10);
            }
            assertTrue(waiting, "The competing operation must actually wait for an InnoDB lock"); assertFalse(secondResult.isDone());
            commit.countDown(); return List.of(firstResult.get(20, TimeUnit.SECONDS), secondResult.get(20, TimeUnit.SECONDS));
        } finally { commit.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)); }
    }
}
