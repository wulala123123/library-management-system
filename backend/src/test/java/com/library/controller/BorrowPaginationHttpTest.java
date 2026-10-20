package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BorrowPaginationHttpTest {
    private static final int PAGE_SIZE = 37;
    private static final Comparator<Row> PAGE_ORDER = Comparator.comparing(Row::createdAt).reversed()
            .thenComparing(Comparator.comparingLong(Row::id).reversed());

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired JwtTokenProvider jwt;

    private final List<Row> loans = new ArrayList<>();
    private final List<Row> codes = new ArrayList<>();
    private String keyword;
    private String firstTitle;
    private Actor admin;
    private Actor librarian;
    private Actor reader;
    private Actor otherReader;

    private record Actor(long id, String token) {}
    private record Row(long id, long userId, String status, LocalDateTime createdAt,
                       String title, boolean deleted) {}

    @BeforeEach
    void sameSecondRowsAcrossSeveralPages() {
        loans.clear();
        codes.clear();
        String unique = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        keyword = "pagination-" + unique;
        firstTitle = keyword + " first";
        admin = actor(unique + "_admin", "ADMIN");
        librarian = actor(unique + "_staff", "LIBRARIAN");
        reader = actor(unique + "_reader", "READER");
        otherReader = actor(unique + "_other", "READER");
        long firstBook = insert("INSERT INTO book(isbn,title,stock_total,stock_available) VALUES (?,?,0,0)",
                unique + "a", firstTitle);
        long secondBook = insert("INSERT INTO book(isbn,title,stock_total,stock_available) VALUES (?,?,0,0)",
                unique + "b", keyword + " second");
        // Existing code rows belong to the disposable test database. Include
        // them in the expected admin result without deleting other fixtures.
        codes.addAll(jdbc.query("SELECT id,user_id,status,create_time FROM borrow_code",
                (rs, n) -> new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getString("status"),
                        rs.getTimestamp("create_time").toLocalDateTime(), null, false)));
        long loanId = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM borrow_record", Long.class);
        long codeId = jdbc.queryForObject("SELECT COALESCE(MAX(id),0)+1 FROM borrow_code", Long.class);
        LocalDateTime tied = LocalDateTime.of(2026, 10, 20, 12, 34, 56);
        String[] loanStates = {"BORROWING", "OVERDUE", "RETURNED", "RETURNED"};
        String[] codeStates = {"VALID", "USED", "EXPIRED", "INVALID"};
        List<Object[]> loanValues = new ArrayList<>();
        List<Object[]> codeValues = new ArrayList<>();
        for (int i = 0; i < 252; i++) {
            long owner = i % 3 == 0 ? otherReader.id() : reader.id();
            boolean first = i % 2 == 0;
            long book = first ? firstBook : secondBook;
            String title = first ? firstTitle : keyword + " second";
            // Higher IDs in the old group must still follow newer timestamps.
            LocalDateTime at = i < 4 ? tied.plusDays(1) : i < 244 ? tied : tied.minusYears(1);
            boolean deleted = i % 17 == 0;
            String kind = i % 11 == 0 ? "UNKNOWN" : i % 13 == 0 ? "UNLIMITED" : "DATED";
            Object dueDate = "DATED".equals(kind) ? "2199-01-01" : null;
            String state = loanStates[i % loanStates.length];
            loans.add(new Row(loanId + i, owner, state, at, title, deleted));
            loanValues.add(new Object[]{loanId + i, owner, book, "2026-10-20", dueDate, kind,
                    state, deleted ? 1 : 0, at, at, "synthetic tied pagination"});
            String codeState = codeStates[i % codeStates.length];
            codes.add(new Row(codeId + i, owner, codeState, at, title, false));
            codeValues.add(new Object[]{codeId + i, unique + "-" + i, owner, book, codeState,
                    LocalDateTime.of(2199, 1, 1, 0, 0), at});
        }
        jdbc.batchUpdate("INSERT INTO borrow_record(id,user_id,book_id,borrow_date,due_date,due_date_kind,"
                + "status,deleted,create_time,update_time,remark) VALUES (?,?,?,?,?,?,?,?,?,?,?)", loanValues);
        jdbc.batchUpdate("INSERT INTO borrow_code(id,code,user_id,book_id,status,expire_time,create_time) "
                + "VALUES (?,?,?,?,?,?,?)", codeValues);
    }

    @Test
    void staffBorrowPagesHaveNoDuplicatesOrOmissionsAndKeepKeywordAndStatusFilters() throws Exception {
        for (Actor actor : List.of(admin, librarian)) {
            for (String state : List.of("", "ACTIVE", "BORROWING", "OVERDUE", "RETURNED")) {
                assertPages("/borrows", actor, loanRows(null, state, null), keyword, state);
            }
            assertPages("/borrows", actor, loanRows(null, "ACTIVE", firstTitle), firstTitle, "ACTIVE");
            assertPages("/borrows", actor, List.of(), keyword + "-missing", "");
        }
    }

    @Test
    void personalBorrowPagesStayWithinCurrentActorIncludingActiveAndHistoricalRows() throws Exception {
        for (Actor actor : List.of(reader, otherReader, admin)) {
            for (String state : List.of("", "ACTIVE", "BORROWING", "OVERDUE", "RETURNED")) {
                List<Row> expected = loanRows(actor.id(), state, null);
                List<JsonNode> rows = assertPages("/borrows/my", actor, expected, null, state);
                assertTrue(rows.stream().allMatch(row -> row.path("userId").asLong() == actor.id()));
                for (JsonNode row : rows) {
                    assertTrue(row.path("copyId").isMissingNode() || row.path("copyId").isNull());
                    if (!"DATED".equals(row.path("dueDateKind").asText())) {
                        assertTrue(row.path("dueDate").isMissingNode() || row.path("dueDate").isNull());
                    }
                }
            }
        }
        JsonNode data = page("/borrows/my", reader, 1, null, "", otherReader.id());
        assertTrue(data.path("records").findValues("userId").stream()
                .allMatch(value -> value.asLong() == reader.id()));
    }

    @Test
    void staffCodePagesHaveNoDuplicatesOrOmissionsAndRetainStatusFilters() throws Exception {
        for (Actor actor : List.of(admin, librarian)) {
            for (String state : List.of("", "VALID", "USED", "EXPIRED", "INVALID")) {
                assertPages("/borrow-codes", actor, codeRows(null, state), null, state);
            }
        }
    }

    @Test
    void personalCodePagesDoNotMixOtherReadersCodes() throws Exception {
        for (Actor actor : List.of(reader, otherReader, admin)) {
            List<JsonNode> rows = assertPages("/borrow-codes/my", actor, codeRows(actor.id(), ""), null, "");
            assertTrue(rows.stream().allMatch(row -> row.path("userId").asLong() == actor.id()));
        }
        JsonNode data = page("/borrow-codes/my", reader, 1, null, "", otherReader.id());
        assertTrue(data.path("records").findValues("userId").stream()
                .allMatch(value -> value.asLong() == reader.id()));
    }

    @Test
    void readersCannotUseStaffPagesAndAnonymousRequestsRemainDenied() throws Exception {
        for (String path : List.of("/borrows", "/borrow-codes")) {
            mvc.perform(get(path).header("Authorization", "Bearer " + reader.token()))
                    .andExpect(status().isForbidden());
        }
        for (String path : List.of("/borrows", "/borrows/my", "/borrow-codes", "/borrow-codes/my")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }

    private List<Row> loanRows(Long owner, String state, String title) {
        return loans.stream().filter(row -> !row.deleted())
                .filter(row -> owner == null || row.userId() == owner)
                .filter(row -> title == null || row.title().equals(title))
                .filter(row -> state.isEmpty() || ("ACTIVE".equals(state)
                        ? Set.of("BORROWING", "OVERDUE").contains(row.status()) : row.status().equals(state)))
                .sorted(PAGE_ORDER).toList();
    }

    private List<Row> codeRows(Long owner, String state) {
        return codes.stream().filter(row -> owner == null || row.userId() == owner)
                .filter(row -> state.isEmpty() || row.status().equals(state)).sorted(PAGE_ORDER).toList();
    }

    private List<JsonNode> assertPages(String path, Actor actor, List<Row> expected,
                                       String term, String state) throws Exception {
        List<Long> ids = new ArrayList<>();
        List<JsonNode> actual = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int pages = (expected.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        // Include one page beyond the last, and repeat the first full traversal.
        for (int round = 0; round < 2; round++) {
            ids.clear();
            actual.clear();
            seen.clear();
            for (int number = 1; number <= Math.max(1, pages) + 1; number++) {
                JsonNode data = page(path, actor, number, term, state, null);
                assertEquals(expected.size(), data.path("total").asLong(), path);
                assertEquals(pages, data.path("pages").asLong(), path);
                assertEquals(number, data.path("page").asInt());
                assertEquals(PAGE_SIZE, data.path("size").asInt());
                int start = Math.min((number - 1) * PAGE_SIZE, expected.size());
                int end = Math.min(number * PAGE_SIZE, expected.size());
                assertEquals(end - start, data.path("records").size(), path + " page " + number);
                List<Long> pageIds = new ArrayList<>();
                for (JsonNode row : data.path("records")) {
                    long id = row.path("id").asLong();
                    assertTrue(seen.add(id), path + " duplicate across pages: " + id);
                    ids.add(id);
                    pageIds.add(id);
                    actual.add(row);
                }
                assertEquals(expected.subList(start, end).stream().map(Row::id).toList(), pageIds,
                        path + " must keep timestamp order and use descending unique ID for ties");
            }
            assertEquals(expected.stream().map(Row::id).toList(), ids, path + " complete ordered coverage");
        }
        return new ArrayList<>(actual);
    }

    private JsonNode page(String path, Actor actor, int number, String term,
                           String state, Long claimedOwner) throws Exception {
        var request = get(path).param("page", Integer.toString(number)).param("size", Integer.toString(PAGE_SIZE))
                .header("Authorization", "Bearer " + actor.token());
        if (term != null) request.param("keyword", term);
        if (!state.isEmpty()) request.param("status", state);
        if (claimedOwner != null) request.param("userId", claimedOwner.toString());
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private Actor actor(String username, String role) {
        long id = insert("INSERT INTO sys_user(username,password,real_name,role,status) "
                + "VALUES (?,'synthetic-only','分页合成账号',?,1)", username, role);
        return new Actor(id, jwt.generateToken(id, username, role, 0L));
    }

    private long insert(String sql, Object... values) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }
}
