package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.entity.*;
import com.library.mapper.*;
import com.library.security.JwtTokenProvider;
import com.library.service.BookCopyQueryService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:book-copy-query-acceptance;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BookCopyQueryControllerTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired BookMapper books;
    @Autowired BookCopyMapper copies;
    @Autowired BorrowRecordMapper borrows;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;

    @Test
    void managementEndpointsAllowOnlyAdminAndLibrarianAndDoNotExpandTheLegacyCopyContract() throws Exception {
        Book book = book("合成权限", 1, 1); BookCopy copy = copy(book, unique(), "AVAILABLE", null);
        for (String endpoint : List.of("/book-copies", "/book-copies/" + copy.getId(), "/book-copies/catalog-summary")) {
            request(get(endpoint), "admin", 200); request(get(endpoint), "librarian", 200);
            request(get(endpoint), "reader", 403);
            mvc.perform(get(endpoint)).andExpect(status().isUnauthorized());
        }
        JsonNode legacy = request(get("/books/" + book.getId() + "/copies"), "librarian", 200);
        assertTrue(legacy.isArray()); assertEquals(copy.getBarcode(), legacy.get(0).path("barcode").asText());
        request(get("/books/" + book.getId() + "/copies"), "reader", 403);
    }

    @Test
    void exactBarcodeUsesTheWholeUtf8StringIncludingZeroCaseAndTrailingSpace() throws Exception {
        Book book = book("合成精确条码", 5, 5);
        String suffix = unique().substring(0, 6);
        List<String> barcodes = List.of("000Aa" + suffix, "000aa" + suffix, "00Aa" + suffix, "000Aa" + suffix + " ", "零000" + suffix);
        for (String barcode : barcodes) copy(book, barcode, "AVAILABLE", null);
        for (String barcode : barcodes) {
            JsonNode page = request(get("/book-copies").param("barcode", barcode), "admin", 200);
            assertEquals(1, page.path("total").asLong()); assertEquals(barcode, page.path("records").get(0).path("barcode").asText());
        }
        assertEquals(0, request(get("/book-copies").param("barcode", "0Aa" + suffix), "admin", 200).path("total").asLong());
        assertEquals(0, request(get("/book-copies").param("barcode", "' OR 1=1 --"), "admin", 200).path("total").asLong());
    }

    @Test
    void keywordAndLocationTreatPercentUnderscoreAndEscapeCharacterLiterally() throws Exception {
        String marker = unique(); Book target = book(marker + "%_!绘本", 1, 1), other = book(marker + "ABC绘本", 1, 1);
        copy(target, "Q%_!" + marker, "AVAILABLE", "库位%_!原值"); copy(other, unique(), "AVAILABLE", "库位AB原值");
        assertSingleBook(request(get("/book-copies").param("keyword", "%_!"), "librarian", 200), target);
        assertSingleBook(request(get("/book-copies").param("location", "%_!"), "admin", 200), target);
        assertEquals(1, request(get("/book-copies/catalog-summary").param("keyword", "%_!"), "admin", 200).path("total").asLong());
        request(get("/book-copies").param("keyword", "' OR 1=1 --"), "admin", 400);
        assertEquals(0, new BookCopyQueryService(jdbc).copies(1, 20, "' OR 1=1 --", null, null, null, null, null).getTotal());
    }

    @Test
    void bookStatusAndLocationFiltersKeepOriginalBlankLocationsAndUnknownStatus() throws Exception {
        Book book = book("合成状态及空位置", 4, 2); book.setLocation("原书目位置"); book.setStatus(0); books.updateById(book);
        copy(book, unique(), "AVAILABLE", null); copy(book, unique(), "BORROWED", "");
        copy(book, unique(), "原待核实", "   "); copy(book, unique(), "AVAILABLE", "A-01");
        JsonNode unassigned = request(get("/book-copies").param("bookId", book.getId().toString()).param("unassignedLocation", "true"), "admin", 200);
        assertEquals(3, unassigned.path("total").asLong());
        for (JsonNode row : unassigned.path("records")) {
            assertTrue(row.path("unassignedLocation").asBoolean()); assertEquals("原书目位置", row.path("book").path("location").asText());
            assertEquals(0, row.path("book").path("status").asInt());
            assertTrue(row.get("location").isNull() || row.get("location").asText().isBlank());
        }
        JsonNode other = request(get("/book-copies").param("bookId", book.getId().toString()).param("status", "OTHER"), "admin", 200);
        assertEquals(1, other.path("total").asLong()); assertEquals("原待核实", other.path("records").get(0).path("status").asText());
        assertEquals(2, request(get("/book-copies").param("bookId", book.getId().toString()).param("status", "AVAILABLE"), "admin", 200).path("total").asLong());
        assertEquals(4, request(get("/book-copies").param("bookId", book.getId().toString()).param("unassignedLocation", "false"), "admin", 200).path("total").asLong());
    }

    @Test
    void metadataNullAndKnownZeroRemainDifferentAndZeroCopyCatalogsRemainVisible() throws Exception {
        Book unknown = book("合成空元数据", 0, 0), zero = book("合成已知零元数据", 1, 1);
        zero.setPages(0); books.updateById(zero); BookCopy copy = copy(zero, "000" + unique(), "AVAILABLE", null);
        JsonNode detail = request(get("/book-copies/" + copy.getId()), "admin", 200);
        assertEquals(0, detail.path("copy").path("book").path("pages").asInt());
        JsonNode summary = summary(unknown);
        assertTrue(summary.path("book").get("pages").isNull()); assertTrue(summary.path("book").get("publishDate").isNull());
        assertTrue(summary.path("book").get("author").isNull()); assertTrue(summary.path("book").get("location").isNull());
        assertEquals(0, summary.path("totalCopies").asLong()); assertEquals(0, summary.path("registeredCopies").asLong());
        assertFalse(summary.path("needsVerification").asBoolean()); assertTrue(summary.path("countReconciliationOnly").asBoolean());
        assertEquals(0, request(get("/book-copies").param("bookId", unknown.getId().toString()), "admin", 200).path("total").asLong());
    }

    @Test
    void summaryReportsOriginalDifferencesAndEveryStoredStatusWithoutRepairingThem() throws Exception {
        Book book = book("合成汇总差额", 6, 4); User reader = reader();
        copy(book, unique(), "AVAILABLE", null); BookCopy borrowed = copy(book, unique(), "BORROWED", null);
        copy(book, unique(), "原待核实", null);
        loan(book, borrowed, reader, "OVERDUE", null, "UNLIMITED");
        loan(book, borrowed, reader, "RETURNED", LocalDate.of(2020, 1, 1), "DATED");
        BorrowRecord deleted = loan(book, borrowed, reader, "BORROWING", null, "UNKNOWN");
        jdbc.update("UPDATE borrow_record SET deleted=1 WHERE id=?", deleted.getId());
        JsonNode summary = summary(book);
        for (var entry : Map.of("totalCopies", 6, "availableCopies", 4, "registeredCopies", 3, "registeredAvailableCopies", 1,
                "registeredBorrowedCopies", 1, "registeredOtherCopies", 1, "borrowedCopies", 1, "totalDifference", 3,
                "availableDifference", 3, "borrowedDifference", 0).entrySet()) assertEquals(entry.getValue(), summary.path(entry.getKey()).asInt(), entry.getKey());
        assertEquals(1, summary.path("stockBalanceDifference").asLong()); assertTrue(summary.path("needsVerification").asBoolean());
        assertEquals(1, summary.path("registeredByStatus").path("原待核实").asLong());
        assertEquals(6, books.selectById(book.getId()).getStockTotal()); assertEquals(3, copies.selectCount(new LambdaQueryWrapper<BookCopy>().eq(BookCopy::getBookId, book.getId())));
    }

    @Test
    void detailShowsOnlyNecessaryCurrentBorrowerFactsAndPreservesUnknownAndUnlimitedDueDates() throws Exception {
        Book book = book("合成借阅投影", 2, 0); User reader = reader();
        jdbc.update("INSERT INTO member_profile(user_id,source_key,card_no,address,notes,paid_fee,deposit_amount) VALUES (?,?,?,'private-family-address','private-note',123.45,67.89)", reader.getId(), "manual:" + unique(), "000Card");
        jdbc.update("INSERT INTO member_child(user_id,name) VALUES (?,'private-child-name')", reader.getId());
        BookCopy unlimited = copy(book, unique(), "BORROWED", null), unknown = copy(book, unique(), "BORROWED", null);
        loan(book, unlimited, reader, "OVERDUE", null, "UNLIMITED"); loan(book, unknown, reader, "BORROWING", null, "UNKNOWN");
        for (BookCopy copy : List.of(unlimited, unknown)) {
            JsonNode detail = request(get("/book-copies/" + copy.getId()), "librarian", 200);
            assertFalse(detail.path("needsVerification").asBoolean()); assertEquals(1, detail.path("currentLoans").size());
            JsonNode loan = detail.path("currentLoans").get(0);
            assertEquals("合成读者", loan.path("memberName").asText()); assertEquals("000Card", loan.path("cardNo").asText()); assertTrue(loan.get("dueDate").isNull());
            assertEquals(copy == unlimited ? "UNLIMITED" : "UNKNOWN", loan.path("dueDateKind").asText());
            assertEquals(Set.of("borrowId", "status", "borrowDate", "dueDate", "dueDateKind", "memberName", "cardNo", "bookMatches"), fields(loan));
            assertFalse(detail.toString().contains("private-")); assertFalse(detail.toString().contains(reader.getUsername()));
        }
    }

    @Test
    void detailKeepsAllAnomalousCurrentAssociationsAndFlagsThemWithoutInventingASingleBorrower() throws Exception {
        Book book = book("合成异常关联", 3, 1), other = book("合成另一书目", 1, 0); User reader = reader();
        BookCopy duplicate = copy(book, unique(), "BORROWED", null), missing = copy(book, unique(), "BORROWED", null), available = copy(book, unique(), "AVAILABLE", null);
        loan(book, duplicate, reader, "BORROWING", null, "UNKNOWN"); loan(other, duplicate, reader, "OVERDUE", null, "UNLIMITED");
        loan(book, available, reader, "BORROWING", null, "UNKNOWN");
        JsonNode detail = request(get("/book-copies/" + duplicate.getId()), "admin", 200);
        assertEquals(2, detail.path("currentLoans").size()); assertTrue(detail.path("needsVerification").asBoolean());
        assertFalse(detail.path("currentLoans").get(1).path("bookMatches").asBoolean());
        for (BookCopy copy : List.of(missing, available)) assertTrue(request(get("/book-copies/" + copy.getId()), "admin", 200).path("needsVerification").asBoolean());
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", reader.getId());
        JsonNode hidden = request(get("/book-copies/" + available.getId()), "admin", 200).path("currentLoans").get(0);
        assertTrue(hidden.get("memberName").isNull()); assertTrue(hidden.get("cardNo").isNull());
    }

    @Test
    void paginationIsBoundedAndStableAndDeletedCatalogsAreExcluded() throws Exception {
        Book book = book("合成分页", 103, 103);
        for (int i = 0; i < 103; i++) copy(book, unique(), "AVAILABLE", null);
        JsonNode bounded = request(get("/book-copies").param("bookId", book.getId().toString()).param("page", "0").param("size", "5000"), "admin", 200);
        assertEquals(1, bounded.path("page").asLong()); assertEquals(100, bounded.path("size").asLong()); assertEquals(100, bounded.path("records").size()); assertEquals(103, bounded.path("total").asLong());
        JsonNode second = request(get("/book-copies").param("bookId", book.getId().toString()).param("page", "2").param("size", "100"), "admin", 200);
        assertEquals(3, second.path("records").size()); assertTrue(second.path("records").get(0).path("id").asLong() > bounded.path("records").get(99).path("id").asLong());
        Long copyId = bounded.path("records").get(0).path("id").asLong(); books.deleteById(book.getId());
        request(get("/book-copies/" + copyId), "admin", 404);
        assertEquals(0, request(get("/book-copies/catalog-summary").param("bookId", book.getId().toString()), "admin", 200).path("total").asLong());
    }

    @Test
    void invalidFiltersRejectAndMissingCopyReturns404WithoutSqlDetails() throws Exception {
        for (String[] invalid : new String[][]{{"status", "AVAILABLE' OR 1=1"}, {"status", "UNSUPPORTED"}, {"bookId", "0"},
                {"barcode", ""}, {"barcode", "A".repeat(65)}, {"keyword", "A".repeat(201)}, {"location", "A".repeat(201)}})
            request(get("/book-copies").param(invalid[0], invalid[1]), "admin", 400);
        request(get("/book-copies").param("unassignedLocation", "true").param("location", "A-01"), "admin", 400);
        request(get("/book-copies/999999999"), "admin", 404);
    }

    @Test
    void everyGetIncludingMissingLookupsLeavesEveryBusinessTableUnchanged() throws Exception {
        Book book = book("合成只读", 4, 3); BookCopy copy = copy(book, unique(), "AVAILABLE", null);
        Map<String, List<String>> before = databaseSnapshot();
        for (int i = 0; i < 2; i++) {
            request(get("/book-copies").param("bookId", book.getId().toString()), "admin", 200);
            request(get("/book-copies").param("barcode", copy.getBarcode()), "librarian", 200);
            request(get("/book-copies/" + copy.getId()), "librarian", 200);
            request(get("/book-copies/catalog-summary").param("bookId", book.getId().toString()), "admin", 200);
            request(get("/book-copies/999999999"), "admin", 404);
        }
        assertEquals(before, databaseSnapshot());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
    void mysqlBinaryBarcodeBranchPreservesUtf8IdentityUnderCaseInsensitiveCollation() throws Exception {
        String source = System.getenv("MYSQL_TEST_URL");
        assertTrue(source != null && source.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"));
        String user = System.getenv().getOrDefault("MYSQL_TEST_USER", "root"), password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String schema = "library_repair_tests_copy_" + unique().substring(0, 16);
        String url = source.replace("/library_repair_tests", "/" + schema);
        try (Connection connection = DriverManager.getConnection(source, user, password); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        try {
            Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("9").load().migrate();
            JdbcTemplate mysql = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
            BookCopyQueryService query = new BookCopyQueryService(mysql);
            // Isolated fixture only: exercise both case folding and PAD SPACE behavior of a legacy collation.
            mysql.execute("ALTER TABLE book_copy MODIFY barcode VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL");
            mysql.update("INSERT INTO book(id,isbn,title,stock_total,stock_available) VALUES (101,'query-mysql-exact','合成真实MySQL查询',4,3)");
            mysql.update("INSERT INTO book_copy(id,book_id,barcode,status,location) VALUES (101,101,'000Aa','AVAILABLE',NULL)");
            String collation = mysql.queryForObject("SELECT COLLATION_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='book_copy' AND COLUMN_NAME='barcode'", String.class);
            assertTrue(collation.endsWith("_ci"), "Verify the query against a case-insensitive source column");
            assertEquals(1, mysql.queryForObject("SELECT COUNT(*) FROM book_copy WHERE barcode='000aa'", Integer.class));
            assertEquals(1, query.copies(1, 20, null, 101L, null, null, null, "000Aa").getTotal());
            for (String missing : List.of("000aa", "00Aa", "000Aa "))
                assertEquals(0, query.copies(1, 20, null, 101L, null, null, null, missing).getTotal(), missing);
            mysql.update("UPDATE book_copy SET barcode='000Aa ' WHERE id=101");
            assertEquals(1, mysql.queryForObject("SELECT COUNT(*) FROM book_copy WHERE barcode='000Aa'", Integer.class));
            assertEquals(0, query.copies(1, 20, null, 101L, null, null, null, "000Aa").getTotal());
            assertEquals("000Aa ", query.copies(1, 20, null, 101L, null, null, null, "000Aa ").getRecords().get(0).barcode());
            mysql.update("UPDATE book_copy SET barcode=? WHERE id=101", "零000绘本é");
            assertEquals(1, query.copies(1, 20, null, 101L, null, null, null, "零000绘本é").getTotal());
            assertEquals(0, query.copies(1, 20, null, 101L, null, null, null, "零000绘本e").getTotal());
            mysql.update("INSERT INTO book_copy(book_id,barcode,status,location) VALUES (101,'mysql-blank','AVAILABLE','   '),(101,'mysql-other','原待核实','架%_!')");
            assertEquals(2, query.copies(1, 20, null, 101L, null, null, true, null).getTotal());
            assertEquals(1, query.copies(1, 20, null, 101L, "OTHER", "%_!", null, null).getTotal());
            var summary = query.catalogSummary(1, 20, null, 101L).getRecords().get(0);
            assertEquals(3, summary.registeredCopies()); assertEquals(2, summary.registeredAvailableCopies());
            assertEquals(1, summary.registeredOtherCopies()); assertEquals(1, summary.registeredByStatus().get("原待核实"));
            assertEquals(1, summary.totalDifference()); assertEquals(1, summary.availableDifference()); assertTrue(summary.needsVerification());
            assertNull(summary.book().pages()); assertNull(summary.book().publishDate());
        } finally {
            try (Connection connection = DriverManager.getConnection(source, user, password); Statement statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }

    private JsonNode summary(Book book) throws Exception { return request(get("/book-copies/catalog-summary").param("bookId", book.getId().toString()), "admin", 200).path("records").get(0); }
    private void assertSingleBook(JsonNode page, Book book) { assertEquals(1, page.path("total").asLong()); assertEquals(book.getId().longValue(), page.path("records").get(0).path("bookId").asLong()); }
    private Set<String> fields(JsonNode value) { Set<String> fields = new HashSet<>(); value.fieldNames().forEachRemaining(fields::add); return fields; }
    private Book book(String title, int total, int available) { Book b = new Book(); b.setTitle(title); b.setIsbn("Q" + unique().substring(0, 19)); b.setStockTotal(total); b.setStockAvailable(available); b.setStatus(1); books.insert(b); return b; }
    private BookCopy copy(Book book, String barcode, String status, String location) { BookCopy c = new BookCopy(); c.setBookId(book.getId()); c.setBarcode(barcode); c.setStatus(status); c.setLocation(location); copies.insert(c); return c; }
    private User reader() { User u = new User(); u.setUsername("copy_" + unique().substring(0, 12)); u.setPassword("synthetic-only"); u.setRealName("合成读者"); u.setPhone("13000000000"); u.setRole("READER"); u.setStatus(1); users.insert(u); return u; }
    private BorrowRecord loan(Book book, BookCopy copy, User user, String status, LocalDate due, String dueKind) { BorrowRecord r = new BorrowRecord(); r.setBookId(book.getId()); r.setCopyId(copy.getId()); r.setUserId(user.getId()); r.setStatus(status); r.setBorrowDate(LocalDate.of(2020, 1, 1)); r.setDueDate(due); r.setDueDateKind(dueKind); r.setRemark("private-source-remark"); borrows.insert(r); return r; }
    private String unique() { return UUID.randomUUID().toString().replace("-", ""); }
    private JsonNode request(MockHttpServletRequestBuilder request, String role, int expected) throws Exception {
        User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, role));
        request.header("Authorization", "Bearer " + tokens.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion()));
        String response = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(response).path("data");
    }
    private Map<String, List<String>> databaseSnapshot() {
        Map<String, List<String>> snapshot = new TreeMap<>();
        List<String> tables = jdbc.queryForList("SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'", String.class);
        for (String table : tables) {
            assertTrue(table.matches("[A-Z0-9_]+"));
            List<String> rows = jdbc.query("SELECT * FROM " + table, (rs, i) -> { List<String> cells = new ArrayList<>();
                for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) cells.add(rs.getString(c)); return cells.toString(); });
            Collections.sort(rows); snapshot.put(table, rows);
        }
        return snapshot;
    }
}
