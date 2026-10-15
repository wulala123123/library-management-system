package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.InventoryReconciliation;
import com.library.dto.StatisticsSummary;
import com.library.entity.Book;
import com.library.entity.BookCopy;
import com.library.entity.BorrowRecord;
import com.library.entity.User;
import com.library.mapper.BookCopyMapper;
import com.library.mapper.BookMapper;
import com.library.mapper.BorrowRecordMapper;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import com.library.service.StatisticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:statistics-acceptance;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StatisticsControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookMapper books;
    @Autowired BookCopyMapper copies;
    @Autowired BorrowRecordMapper borrows;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;
    @Autowired StatisticsService statistics;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private User admin;
    private User librarian;
    private User reader;
    private Book multiple;
    private Book historical;

    @BeforeEach
    void syntheticFixture() {
        // This test owns a distinct, in-memory H2 database; every test also rolls back.
        jdbc.update("DELETE FROM borrow_record");
        jdbc.update("DELETE FROM book_copy");
        jdbc.update("DELETE FROM book");
        jdbc.update("DELETE FROM member_profile");
        jdbc.update("DELETE FROM sys_user");

        admin = user("statistics-admin", "ADMIN", 1, 0);
        librarian = user("statistics-librarian", "LIBRARIAN", 1, 0);
        user("statistics-disabled-admin", "ADMIN", 0, 0);
        reader = member("valid-day", 1, 0, today, today, true);
        member("valid-start", 1, 0, today, today.plusDays(30), true);
        member("valid-end", 1, 0, today.minusDays(30), today, true);
        member("expired", 1, 0, today.minusDays(30), today.minusDays(1), true);
        member("future", 1, 0, today.plusDays(1), today.plusDays(30), true);
        member("disabled-valid", 0, 0, today.minusDays(1), today.plusDays(1), true);
        member("no-profile", 1, 0, null, null, false);
        member("no-start", 1, 0, null, today.plusDays(1), true);
        member("no-end", 1, 0, today.minusDays(1), null, true);
        member("disabled-unknown", 0, 0, null, null, true);
        member("reversed-dates", 1, 0, today.plusDays(1), today.minusDays(1), true);
        member("deleted-valid", 1, 1, today, today, true);

        multiple = book("多册书目", 5, 3, 1, 0);
        for (int i = 0; i < 3; i++) copy(multiple, "AVAILABLE");
        for (int i = 0; i < 2; i++) copy(multiple, "BORROWED");
        borrow(multiple, "BORROWING", today, 0);
        borrow(multiple, "OVERDUE", today.minusDays(2), 0);
        borrow(multiple, "RETURNED", today, 0);
        borrow(multiple, "OVERDUE", today, 1);
        Book single = book("单册在借", 1, 0, 1, 0);
        copy(single, "BORROWED");
        borrow(single, "BORROWING", today, 0);
        historical = book("仅历史汇总库存", 4, 4, 1, 0);
        book("零库存书目", 0, 0, 1, 0);
        Book disabled = book("停用书目", 100, 90, 0, 0);
        copy(disabled, "AVAILABLE");
        borrow(disabled, "BORROWING", today, 0);
        Book deleted = book("已删除书目", 200, 180, 1, 1);
        copy(deleted, "BORROWED");
        borrow(deleted, "OVERDUE", today, 0);
    }

    @Test
    void dashboardInventoryAndReportShareExplicitMetricsAndRetainLegacyFields() throws Exception {
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("catalogTitles", 4L);
        expected.put("totalCopies", 10L);
        expected.put("availableCopies", 7L);
        expected.put("borrowedCopies", 3L);
        expected.put("availableTitles", 2L);
        expected.put("lowStockTitles", 2L);
        expected.put("accountCount", 14L);
        expected.put("memberCount", 11L);
        expected.put("validMemberCount", 3L);
        expected.put("unknownValidityMemberCount", 4L);
        expected.put("todayBorrows", 3L);
        expected.put("overdueCount", 1L);
        expected.put("borrowRecordCount", 4L);
        for (String path : List.of("/dashboard/stats", "/inventory/stats", "/statistics")) {
            JsonNode data = data(path, admin);
            expected.forEach((key, count) -> {
                assertEquals(count.longValue(), data.path(key).asLong(-1), path + ": " + key);
                assertFalse(data.path("statisticalScope").path(key).asText().isBlank(), key);
            });
            assertEquals(today.toString(), data.path("asOfDate").asText());
            assertFalse(data.has("enabledReaderAccounts"));
        }
        JsonNode dashboard = data("/dashboard/stats", admin);
        assertEquals(4, dashboard.path("totalBooks").asLong());
        assertEquals(9, dashboard.path("totalUsers").asLong());
        assertEquals(3, dashboard.path("totalBorrows").asLong());
        assertEquals(2, dashboard.path("availableBooks").asLong());
        JsonNode report = data("/statistics", admin);
        assertEquals(14, report.path("totalUsers").asLong());
        assertEquals(4, report.path("totalBorrows").asLong());
        assertEquals(3, report.path("activeBorrows").asLong());
        JsonNode inventory = data("/inventory/stats", admin);
        assertEquals(4, inventory.path("totalBooks").asLong());
        assertEquals(2, inventory.path("availableBooks").asLong());
        assertEquals(3, inventory.path("borrowedBooks").asLong());
        assertEquals(2, inventory.path("lowStockBooks").asLong());
    }

    @Test
    void historicalAggregateWithoutCopiesRemainsAnExplicitReconciliationDifference() throws Exception {
        JsonNode result = data("/inventory/reconciliation", librarian);
        assertEquals(10, result.path("totalCopies").asLong());
        assertEquals(7, result.path("availableCopies").asLong());
        assertEquals(3, result.path("borrowedCopies").asLong());
        assertEquals(6, result.path("registeredCopies").asLong());
        assertEquals(3, result.path("registeredAvailableCopies").asLong());
        assertEquals(3, result.path("registeredBorrowedCopies").asLong());
        assertEquals(0, result.path("registeredOtherCopies").asLong());
        assertEquals(1, result.path("mismatchedTitles").asLong());
        assertEquals(1, result.path("items").size());
        JsonNode item = result.path("items").get(0);
        assertEquals(historical.getId().longValue(), item.path("bookId").asLong());
        assertEquals(4, item.path("totalDifference").asLong());
        assertEquals(4, item.path("availableDifference").asLong());
        assertEquals(0, item.path("borrowedDifference").asLong());
        assertEquals(0, item.path("stockBalanceDifference").asLong());
        assertFalse(item.path("consistent").asBoolean());
    }

    @Test
    void otherCopyStatesAndStockBalanceMismatchesAreNotReportedAsConsistent() {
        jdbc.update("UPDATE book_copy SET status='LOST' WHERE id=(SELECT MIN(id) FROM book_copy WHERE book_id=?)", multiple.getId());
        jdbc.update("UPDATE book SET stock_total=stock_total+1 WHERE id=?", multiple.getId());
        InventoryReconciliation result = statistics.getReconciliation();
        InventoryReconciliation.Item item = result.getItems().stream()
                .filter(row -> row.getBookId().equals(multiple.getId())).findFirst().orElseThrow();
        assertEquals(1, item.getRegisteredOtherCopies());
        assertEquals(1, item.getTotalDifference());
        assertEquals(1, item.getAvailableDifference());
        assertEquals(1, item.getStockBalanceDifference());
        assertFalse(item.isConsistent());
    }

    @Test
    void exportContainsTheSameTenMetricsDatesAndDefinitionsAsTheSummary() throws Exception {
        JsonNode summary = data("/statistics", librarian);
        String csv = mvc.perform(get("/statistics/export").header("Authorization", bearer(librarian)))
                .andExpect(status().isOk()).andExpect(content().contentType("text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=library-statistics.csv"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = csv.substring(1).split("\r\n");
        assertEquals("metric,label,value,unit,asOfDate,scope", lines[0]);
        assertEquals(11, lines.length);
        for (int i = 1; i < lines.length; i++) {
            String[] cells = lines[i].split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
            assertEquals(6, cells.length);
            String metric = unquote(cells[0]);
            assertEquals(summary.path(metric).asLong(), Long.parseLong(cells[2]), metric);
            assertEquals(summary.path("asOfDate").asText(), cells[4]);
            assertEquals(summary.path("statisticalScope").path(metric).asText(), unquote(cells[5]));
        }
    }

    @Test
    void chartsAlsoExcludeDisabledAndDeletedCatalogsAndDeletedBorrowRecords() throws Exception {
        JsonNode trend = data("/statistics/borrow-trend", reader);
        assertEquals(7, trend.size());
        assertEquals(3, trend.get(6).path("value").asLong());
        JsonNode hot = data("/statistics/hot-books", reader);
        assertEquals(2, hot.size());
        assertEquals("多册书目", hot.get(0).path("title").asText());
        assertEquals(3, hot.get(0).path("borrowCount").asLong());
        jdbc.update("UPDATE book SET category_id=1");
        JsonNode categories = data("/statistics/category-distribution", reader);
        assertEquals(1, categories.size());
        assertEquals(4, categories.get(0).path("count").asLong());
    }

    @Test
    void reportExportAndReconciliationRequireTheSameStaffPermissions() throws Exception {
        for (String path : List.of("/statistics", "/statistics/export", "/inventory/reconciliation")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", bearer(reader))).andExpect(status().isForbidden());
            mvc.perform(get(path).header("Authorization", bearer(admin))).andExpect(status().isOk());
            mvc.perform(get(path).header("Authorization", bearer(librarian))).andExpect(status().isOk());
        }
        // The existing reader dashboard remains readable.
        mvc.perform(get("/dashboard/stats").header("Authorization", bearer(reader))).andExpect(status().isOk());
    }

    @Test
    void emptyDatasetsReturnZeroRatherThanNullOrInventedMemberValidity() {
        jdbc.update("DELETE FROM book");
        jdbc.update("DELETE FROM sys_user");
        StatisticsSummary result = statistics.getSummary();
        for (Map.Entry<String, Object> entry : result.toMap().entrySet()) {
            if (entry.getValue() instanceof Number number) assertEquals(0, number.longValue(), entry.getKey());
        }
        assertNotNull(result.getTotalCopies());
        assertNotNull(result.getUnknownValidityMemberCount());
        assertTrue(statistics.getReconciliation().getItems().isEmpty());
    }

    private JsonNode data(String path, User account) throws Exception {
        String response = mvc.perform(get(path).header("Authorization", bearer(account)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).path("data");
    }

    private String bearer(User account) {
        return "Bearer " + tokens.generateToken(account.getId(), account.getUsername(), account.getRole(), account.getTokenVersion());
    }

    private User user(String username, String role, int status, int deleted) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("synthetic-test-value");
        user.setRealName("合成统计样例");
        user.setRole(role);
        user.setStatus(status);
        user.setDeleted(deleted);
        users.insert(user);
        return user;
    }

    private User member(String name, int status, int deleted, LocalDate start, LocalDate end, boolean profile) {
        User member = user(name, "READER", status, deleted);
        if (profile) jdbc.update("INSERT INTO member_profile(user_id,source_key,service_start,service_end) VALUES (?,?,?,?)",
                member.getId(), "statistics:" + name, start, end);
        return member;
    }

    private Book book(String title, int total, int available, int status, int deleted) {
        Book book = new Book();
        book.setTitle(title);
        book.setIsbn("stats-" + total + "-" + available + "-" + status);
        book.setStockTotal(total);
        book.setStockAvailable(available);
        book.setCategoryId(1L);
        book.setStatus(status);
        book.setDeleted(deleted);
        books.insert(book);
        return book;
    }

    private void copy(Book book, String status) {
        BookCopy copy = new BookCopy();
        copy.setBookId(book.getId());
        copy.setBarcode("000" + book.getId() + "-" + jdbc.queryForObject("SELECT COUNT(*) FROM book_copy", Long.class));
        copy.setStatus(status);
        copies.insert(copy);
    }

    private void borrow(Book book, String status, LocalDate date, int deleted) {
        BorrowRecord record = new BorrowRecord();
        record.setUserId(reader.getId());
        record.setBookId(book.getId());
        record.setBorrowDate(date);
        record.setDueDate(today.minusDays(1));
        record.setStatus(status);
        record.setDeleted(deleted);
        borrows.insert(record);
    }

    private String unquote(String value) {
        return value.substring(1, value.length() - 1).replace("\"\"", "\"");
    }
}
