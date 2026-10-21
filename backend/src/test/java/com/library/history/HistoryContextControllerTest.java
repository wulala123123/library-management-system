package com.library.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.entity.User;
import com.library.controller.HistoryContextController;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:history-context-only;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class HistoryContextControllerTest {
    static final long MEMBER = 61001, OTHER_MEMBER = 61002, BOOK = 62001, OTHER_BOOK = 62002;
    static final long COPY = 63001, SECOND_COPY = 63002, OTHER_COPY = 63003, BORROW = 64001;
    static final long MEMBER_ARCHIVE = 65001, OTHER_MEMBER_ARCHIVE = 65002;
    static final long BOOK_ARCHIVE = 65010, SECOND_BOOK_ARCHIVE = 65011, ORDER_ARCHIVE = 65020, OTHER_ORDER = 65021;
    static final String BARCODE = "000Aa ";
    static final String ORDER_RAW = "{ \"状态\" : \"原已完成\", \"原订单号\":\"000009\", \"金额\":\"009.00\", \"unknown\":null }";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;

    @BeforeEach void syntheticOnly() {
        user(MEMBER, "synthetic-history-member"); user(OTHER_MEMBER, "synthetic-history-other");
        jdbc.update("INSERT INTO member_profile(user_id,source_key,source_system,source_archive_id,card_no) VALUES (?,?,?,?,?)",
                MEMBER, "huiben:synthetic-1", null, MEMBER_ARCHIVE, "000007");
        jdbc.update("INSERT INTO member_profile(user_id,source_key,source_system,source_archive_id,card_no) VALUES (?,?,?,?,?)",
                OTHER_MEMBER, "huiben:synthetic-2", "huiben", OTHER_MEMBER_ARCHIVE, "000008");
        jdbc.update("INSERT INTO book(id,isbn,title) VALUES (?,?,?)", BOOK, "SYNTHETIC-HISTORY-1", "合成书目一");
        jdbc.update("INSERT INTO book(id,isbn,title) VALUES (?,?,?)", OTHER_BOOK, "SYNTHETIC-HISTORY-2", "合成书目二");
        copy(COPY, BOOK, BARCODE); copy(SECOND_COPY, BOOK, "000002"); copy(OTHER_COPY, OTHER_BOOK, "000003");
        loan(BORROW, MEMBER, BOOK, COPY, "huiben:synthetic-1:7");
        archive(MEMBER_ARCHIVE, "MEMBER", "synthetic-1", MEMBER, null, "{\"原状态\":\"保留\"}");
        archive(OTHER_MEMBER_ARCHIVE, "MEMBER", "synthetic-2", OTHER_MEMBER, null, "{\"标记\":\"别的会员\"}");
        archive(BOOK_ARCHIVE, "BOOK", BARCODE, null, BOOK, "{\"馆内编码\":\"000Aa \"}");
        archive(SECOND_BOOK_ARCHIVE, "BOOK", "000002", null, BOOK, "{}");
        archive(ORDER_ARCHIVE, "ORDER", "old-order-0009", MEMBER, null, ORDER_RAW);
        archive(OTHER_ORDER, "ORDER", "old-order-0010", OTHER_MEMBER, null, "{\"标记\":\"别的会员\"}");
    }

    @Test void importedMemberUsesExactStableIdentityAndExplicitArchive() throws Exception {
        JsonNode context = getData("members/" + MEMBER);
        assertEquals("MEMBER", context.path("entityType").asText());
        assertEquals(Long.toString(MEMBER), context.path("entityId").asText());
        assertEquals("000007", context.at("/identity/cardNo").asText());
        assertEquals("huiben:synthetic-1", context.at("/provenance/sourceKey").asText());
        assertEquals("IMPORTED", context.at("/provenance/classification").asText());
        assertEquals("VERIFIED", context.at("/provenance/associationState").asText());
        assertEquals(Long.toString(MEMBER_ARCHIVE), context.at("/links/memberArchiveId").asText());
        assertTrue(context.at("/links/counterOrderId").isNull());
        assertTrue(context.path("readOnly").asBoolean());
        assertFalse(context.toString().contains("password"));
        assertFalse(context.toString().contains("13000000000"));
    }

    @Test void bookHasNoOwnSourceKeyButStoredBookArchivesProveImport() throws Exception {
        JsonNode context = getData("books/" + BOOK);
        assertEquals("IMPORTED", context.at("/provenance/classification").asText());
        assertTrue(context.at("/provenance/sourceKey").isNull());
        assertEquals(2, getData("books/" + BOOK + "/records").path("total").asInt());
        assertEquals("BOOK_CONTEXT", getData("books/" + BOOK + "/records").at("/records/0/association/scope").asText());
    }

    @Test void importedBorrowPreservesOrdinalKeyAndDoesNotGuessAnOldOrder() throws Exception {
        JsonNode context = getData("borrows/" + BORROW);
        assertEquals("IMPORTED", context.at("/provenance/classification").asText());
        assertEquals("huiben:synthetic-1:7", context.at("/provenance/sourceKey").asText());
        assertEquals(BARCODE, context.at("/identity/barcode").asText());
        assertEquals("UNKNOWN", context.at("/identity/dueDateKind").asText());
        assertTrue(context.at("/identity/dueDate").isNull());
        assertTrue(context.at("/links/counterOrderId").isNull());
        assertTrue(context.at("/provenance/warnings").toString().contains("不是原订单号"));
        JsonNode records = getData("borrows/" + BORROW + "/records");
        assertEquals(Set.of(MEMBER_ARCHIVE, BOOK_ARCHIVE, ORDER_ARCHIVE), ids(records));
        JsonNode oldOrder = getData("borrows/" + BORROW + "/records/" + ORDER_ARCHIVE);
        assertEquals("MEMBER_CONTEXT", oldOrder.at("/association/scope").asText());
        assertTrue(oldOrder.at("/association/basis").asText().contains("不代表本条借阅的旧订单"));
        assertEquals(ORDER_RAW, oldOrder.path("detailsJson").asText());
        assertFalse(records.at("/records/0").has("detailsJson"));
    }

    @Test void archivesAreIsolatedByStoredMemberIdEvenWithSameNamePhoneAndDates() throws Exception {
        assertEquals(Set.of(MEMBER_ARCHIVE, ORDER_ARCHIVE), ids(getData("members/" + MEMBER + "/records")));
        assertEquals(Set.of(OTHER_MEMBER_ARCHIVE, OTHER_ORDER), ids(getData("members/" + OTHER_MEMBER + "/records")));
        expect404("members/" + MEMBER + "/records/" + OTHER_ORDER);
        expect404("borrows/" + BORROW + "/records/" + OTHER_MEMBER_ARCHIVE);
        expect404("books/" + BOOK + "/records/" + ORDER_ARCHIVE);
        expect404("borrows/" + BORROW + "/records/" + SECOND_BOOK_ARCHIVE);
    }

    @Test void onlyAnExactCounterItemCanExposeItsOrderId() throws Exception {
        jdbc.update("UPDATE borrow_record SET source_key=NULL WHERE id=?", BORROW);
        counterOrder(MEMBER, BOOK, COPY, BARCODE);
        JsonNode context = getData("borrows/" + BORROW);
        assertEquals("COUNTER_ORDER", context.at("/provenance/classification").asText());
        assertEquals("66001", context.at("/links/counterOrderId").asText());
        assertTrue(context.at("/provenance/basis").toString().contains("circulation_order_item.record_id"));
    }

    @ParameterizedTest @ValueSource(strings = {"member", "book", "copy", "barcode", "source", "missing-order"})
    void conflictingCounterIdentityIsUnverified(String conflict) throws Exception {
        jdbc.update("UPDATE borrow_record SET source_key=NULL WHERE id=?", BORROW);
        counterOrder("member".equals(conflict) ? OTHER_MEMBER : MEMBER,
                "book".equals(conflict) ? OTHER_BOOK : BOOK,
                "copy".equals(conflict) ? OTHER_COPY : COPY,
                "barcode".equals(conflict) ? "000aa" : BARCODE);
        if ("source".equals(conflict)) jdbc.update("UPDATE borrow_record SET source_key='huiben:synthetic-1:7' WHERE id=?", BORROW);
        if ("missing-order".equals(conflict)) jdbc.update("DELETE FROM circulation_order WHERE id=66001");
        JsonNode context = getData("borrows/" + BORROW);
        assertEquals("UNVERIFIED", context.at("/provenance/classification").asText());
        assertEquals("UNVERIFIED", context.at("/provenance/associationState").asText());
        assertTrue(context.at("/links/counterOrderId").isNull());
    }

    @ParameterizedTest @ValueSource(strings = {"wrong-member", "archive-id", "source-key", "source-system", "source-type", "archive-book"})
    void memberSourceConflictRemainsReadableAndUnverified(String conflict) throws Exception {
        switch (conflict) {
            case "wrong-member" -> jdbc.update("UPDATE legacy_record SET user_id=? WHERE id=?", OTHER_MEMBER, MEMBER_ARCHIVE);
            case "archive-id" -> jdbc.update("UPDATE member_profile SET source_archive_id=? WHERE user_id=?", OTHER_MEMBER_ARCHIVE, MEMBER);
            case "source-key" -> jdbc.update("UPDATE legacy_record SET source_key='Synthetic-1 ' WHERE id=?", MEMBER_ARCHIVE);
            case "source-system" -> jdbc.update("UPDATE legacy_record SET source_system='Huiben' WHERE id=?", MEMBER_ARCHIVE);
            case "source-type" -> jdbc.update("UPDATE legacy_record SET record_type='member' WHERE id=?", MEMBER_ARCHIVE);
            case "archive-book" -> jdbc.update("UPDATE legacy_record SET book_id=? WHERE id=?", BOOK, MEMBER_ARCHIVE);
            default -> fail("Unknown fixture");
        }
        JsonNode context = getData("members/" + MEMBER);
        assertEquals("UNVERIFIED", context.at("/provenance/classification").asText());
        assertEquals("huiben:synthetic-1", context.at("/provenance/sourceKey").asText());
        assertTrue(context.at("/links/memberArchiveId").isNull());
        assertEquals("UNVERIFIED", getData("borrows/" + BORROW).at("/provenance/classification").asText());
        if (!"wrong-member".equals(conflict) && !"source-type".equals(conflict))
            assertEquals("UNVERIFIED", getData("members/" + MEMBER + "/records/" + MEMBER_ARCHIVE).at("/association/state").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"000aa", "000Aa", "000Aa  "})
    void barcodeMatchingPreservesCaseLeadingZerosAndAllSpaces(String wrongBarcode) throws Exception {
        jdbc.update("UPDATE legacy_record SET source_key=? WHERE id=?", wrongBarcode, BOOK_ARCHIVE);
        assertEquals("UNVERIFIED", getData("books/" + BOOK).at("/provenance/classification").asText());
        assertEquals("UNVERIFIED", getData("borrows/" + BORROW).at("/provenance/classification").asText());
        JsonNode records = getData("borrows/" + BORROW + "/records?type=BOOK");
        assertEquals(0, records.path("total").asInt());
        assertTrue(records.path("records").isEmpty());
        expect404("borrows/" + BORROW + "/records/" + BOOK_ARCHIVE);
        assertEquals("UNVERIFIED", getData("books/" + BOOK + "/records/" + BOOK_ARCHIVE).at("/association/state").asText());
    }

    @ParameterizedTest @ValueSource(strings = {"other-book", "missing-copy"})
    void wrongOrMissingCopyDoesNotExposeAnotherBookOrAnotherCopyArchive(String conflict) throws Exception {
        jdbc.update("UPDATE borrow_record SET copy_id=? WHERE id=?", "other-book".equals(conflict) ? OTHER_COPY : 63999, BORROW);
        JsonNode context = getData("borrows/" + BORROW);
        assertEquals("UNVERIFIED", context.at("/provenance/classification").asText());
        assertTrue(context.at("/identity/barcode").isNull());
        assertEquals(Set.of(MEMBER_ARCHIVE, ORDER_ARCHIVE), ids(getData("borrows/" + BORROW + "/records")));
        expect404("borrows/" + BORROW + "/records/" + BOOK_ARCHIVE);
    }

    @Test void sourceBookForeignKeyMustAgreeWithCopyBarcode() throws Exception {
        jdbc.update("UPDATE legacy_record SET book_id=? WHERE id=?", OTHER_BOOK, BOOK_ARCHIVE);
        assertEquals("UNVERIFIED", getData("borrows/" + BORROW).at("/provenance/classification").asText());
        assertEquals("UNVERIFIED", getData("books/" + OTHER_BOOK).at("/provenance/classification").asText());
        expect404("borrows/" + BORROW + "/records/" + BOOK_ARCHIVE);
    }

    @Test void unrelatedCounterOrderWithSameMemberIsNotABorrowOrder() throws Exception {
        loan(64002, MEMBER, BOOK, COPY, null);
        jdbc.update("UPDATE borrow_record SET source_key=NULL WHERE id=?", BORROW);
        counterOrder(MEMBER, BOOK, COPY, BARCODE);
        jdbc.update("UPDATE circulation_order_item SET record_id=64002 WHERE record_id=?", BORROW);
        JsonNode context = getData("borrows/" + BORROW);
        assertEquals("SYSTEM", context.at("/provenance/classification").asText());
        assertTrue(context.at("/links/counterOrderId").isNull());
    }

    @Test void manualAndNoArchiveContextsHaveNormalEmptyPages() throws Exception {
        jdbc.update("DELETE FROM legacy_record WHERE user_id=?", MEMBER);
        jdbc.update("UPDATE member_profile SET source_system='manual',source_key='manual:synthetic',source_archive_id=NULL WHERE user_id=?", MEMBER);
        assertEquals("SYSTEM", getData("members/" + MEMBER).at("/provenance/classification").asText());
        JsonNode page = getData("members/" + MEMBER + "/records?page=9&type=ORDER");
        assertEquals(0, page.path("total").asInt()); assertEquals(0, page.path("pages").asInt());
        assertTrue(page.path("records").isEmpty());
        assertEquals("SYSTEM", getData("books/" + OTHER_BOOK).at("/provenance/classification").asText());
    }

    @Test void missingProfileWithKnownHistoricalArchivesIsUnverifiedRatherThanNew() throws Exception {
        jdbc.update("DELETE FROM member_profile WHERE user_id=?", MEMBER);
        jdbc.update("DELETE FROM legacy_record WHERE id=?", MEMBER_ARCHIVE);
        JsonNode context = getData("members/" + MEMBER);
        assertEquals("UNVERIFIED", context.at("/provenance/classification").asText());
        assertEquals(Set.of(ORDER_ARCHIVE), ids(getData("members/" + MEMBER + "/records")));
    }

    @Test void invalidHistoricalJsonAndUnknownSourceKeyAreReturnedWithoutReinterpretation() throws Exception {
        String raw = "original-invalid-json { 状态:原状 }\r\n ";
        String key = "huiben:synthetic-1:7 ";
        jdbc.update("UPDATE legacy_record SET details_json=? WHERE id=?", raw, ORDER_ARCHIVE);
        jdbc.update("UPDATE borrow_record SET source_key=? WHERE id=?", key, BORROW);
        JsonNode context = getData("borrows/" + BORROW);
        assertEquals("UNVERIFIED", context.at("/provenance/classification").asText());
        assertEquals(key, context.at("/provenance/sourceKey").asText());
        assertEquals(raw, getData("borrows/" + BORROW + "/records/" + ORDER_ARCHIVE).path("detailsJson").asText());
    }

    @Test void paginationUsesUniqueIdForTiedAndUnknownSourceTimes() throws Exception {
        for (long i = 0; i < 5; i++) archive(65100 + i, "ORDER", "synthetic-page-" + i, MEMBER, null, "{}");
        jdbc.update("UPDATE legacy_record SET source_time=NULL WHERE id IN (65100,65101)");
        JsonNode first = getData("members/" + MEMBER + "/records?type=ORDER&size=2&page=1");
        JsonNode second = getData("members/" + MEMBER + "/records?type=ORDER&size=2&page=2");
        JsonNode third = getData("members/" + MEMBER + "/records?type=ORDER&size=2&page=3");
        assertEquals(List.of(65104L, 65103L), orderedIds(first));
        assertEquals(List.of(65102L, ORDER_ARCHIVE), orderedIds(second));
        assertEquals(List.of(65101L, 65100L), orderedIds(third));
        assertEquals(6, first.path("total").asInt()); assertEquals(3, first.path("pages").asInt());
        assertEquals(100, getData("members/" + MEMBER + "/records?size=1000").path("size").asInt());
        JsonNode clamped = getData("members/" + MEMBER + "/records?page=-1&size=0");
        assertEquals(1, clamped.path("size").asInt()); assertEquals(1, clamped.path("page").asInt());
        assertTrue(getData("members/" + MEMBER + "/records?page=2147483647&size=100").path("records").isEmpty());
        assertEquals(0, getData("books/" + BOOK + "/records?type=ORDER").path("total").asInt());
        mvc.perform(get("/history-context/members/" + MEMBER + "/records").param("type", "BAD").header("Authorization", bearer(1)))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest @ValueSource(strings = {"members", "books", "borrows"})
    void deletedAndMissingRootEntitiesAreRejected(String kind) throws Exception {
        long id = "members".equals(kind) ? MEMBER : "books".equals(kind) ? BOOK : BORROW;
        String table = "members".equals(kind) ? "sys_user" : "books".equals(kind) ? "book" : "borrow_record";
        jdbc.update("UPDATE " + table + " SET deleted=1 WHERE id=?", id);
        expect404(kind + "/" + id); expect404(kind + "/" + id + "/records");
        expect404(kind + "/" + id + "/records/" + MEMBER_ARCHIVE);
        jdbc.update("DELETE FROM " + table + " WHERE id=?", id);
        expect404(kind + "/" + id);
    }

    @Test void deletedLinkedMemberOrBookCannotLeakTheirArchives() throws Exception {
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", MEMBER);
        assertEquals(Set.of(BOOK_ARCHIVE), ids(getData("borrows/" + BORROW + "/records")));
        assertEquals("UNVERIFIED", getData("borrows/" + BORROW).at("/provenance/classification").asText());
        jdbc.update("UPDATE book SET deleted=1 WHERE id=?", BOOK);
        assertTrue(getData("borrows/" + BORROW + "/records").path("records").isEmpty());
    }

    @ParameterizedTest @ValueSource(longs = {2, 3})
    void librarianAndReaderCannotReadAnyNewOrOldRawEndpoint(long actor) throws Exception {
        for (String path : List.of("members/" + MEMBER, "books/" + BOOK, "borrows/" + BORROW)) {
            for (String suffix : List.of("", "/records", "/records/" + ORDER_ARCHIVE))
                mvc.perform(get("/history-context/" + path + suffix).header("Authorization", bearer(actor)))
                        .andExpect(status().isForbidden());
        }
        mvc.perform(get("/legacy-data/records/" + ORDER_ARCHIVE).header("Authorization", bearer(actor)))
                .andExpect(status().isForbidden());
    }

    @Test void anonymousCannotReadAndNoWriteEndpointExists() throws Exception {
        for (String suffix : List.of("", "/records", "/records/" + ORDER_ARCHIVE))
            mvc.perform(get("/history-context/members/" + MEMBER + suffix)).andExpect(status().isUnauthorized());
        for (var method : HistoryContextController.class.getDeclaredMethods()) {
            assertNotNull(method.getAnnotation(GetMapping.class), "Every exposed controller method must be GET only");
            assertNull(method.getAnnotation(RequestMapping.class));
        }
    }

    @Test void allSuccessfulAndRejectedReadsLeaveBusinessRowsByteForByteUnchanged() throws Exception {
        Map<String, String> before = businessSnapshot();
        for (String path : List.of("members/" + MEMBER, "books/" + BOOK, "borrows/" + BORROW)) {
            getData(path); getData(path + "/records?page=1&size=1"); getData(path + "/records?page=2&size=1");
        }
        getData("members/" + MEMBER + "/records/" + ORDER_ARCHIVE);
        getData("books/" + BOOK + "/records/" + BOOK_ARCHIVE);
        getData("borrows/" + BORROW + "/records/" + MEMBER_ARCHIVE);
        expect404("members/" + MEMBER + "/records/" + OTHER_ORDER);
        mvc.perform(get("/history-context/members/" + MEMBER).header("Authorization", bearer(2))).andExpect(status().isForbidden());
        assertEquals(before, businessSnapshot());
    }

    private void user(long id, String username) {
        jdbc.update("INSERT INTO sys_user(id,username,password,real_name,phone,role,status,deleted) VALUES (?,?,?,'同名合成会员','13000000000','READER',1,0)",
                id, username, "synthetic-only");
    }
    private void copy(long id, long book, String barcode) {
        jdbc.update("INSERT INTO book_copy(id,book_id,barcode,status) VALUES (?,?,?,'AVAILABLE')", id, book, barcode);
    }
    private void loan(long id, long member, long book, long copy, String key) {
        jdbc.update("INSERT INTO borrow_record(id,user_id,book_id,copy_id,source_key,borrow_date,due_date,due_date_kind,status,deleted) "
                + "VALUES (?,?,?,?,?,'2026-01-02',NULL,'UNKNOWN','RETURNED',0)", id, member, book, copy, key);
    }
    private void archive(long id, String type, String key, Long member, Long book, String raw) {
        jdbc.update("INSERT INTO legacy_record(id,source_system,record_type,source_key,display_name,summary,details_json,user_id,book_id,source_time) "
                + "VALUES (?,'huiben',?,?,'同名合成资料','原文摘要',?,?,?,'2026-01-02 10:00:00')", id, type, key, raw, member, book);
    }
    private void counterOrder(long member, long book, long copy, String barcode) {
        jdbc.update("INSERT INTO circulation_order(id,request_key,user_id,actor_id,created_at,business_date,item_count,remark) "
                + "VALUES (66001,?,?,?,?,?,1,'合成订单')", "synthetic-history-order".getBytes(StandardCharsets.UTF_8), member, 1,
                "2026-01-02 10:00:00", "2026-01-02");
        jdbc.update("INSERT INTO circulation_order_item(order_id,ordinal,record_id,book_id,copy_id,book_title,barcode) VALUES (66001,1,?,?,?,'合成书目',?)",
                BORROW, book, copy, barcode);
    }
    private String bearer(long id) {
        User user = users.selectById(id);
        return "Bearer " + tokens.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion());
    }
    private JsonNode getData(String path) throws Exception {
        String body = mvc.perform(get("/history-context/" + path).header("Authorization", bearer(1)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }
    private void expect404(String path) throws Exception {
        mvc.perform(get("/history-context/" + path).header("Authorization", bearer(1))).andExpect(status().isNotFound());
    }
    private List<Long> orderedIds(JsonNode page) {
        List<Long> ids = new ArrayList<>(); page.path("records").forEach(r -> ids.add(r.path("id").asLong())); return ids;
    }
    private Set<Long> ids(JsonNode page) { return new HashSet<>(orderedIds(page)); }
    private Map<String, String> businessSnapshot() throws Exception {
        Map<String, String> result = new TreeMap<>();
        for (String table : List.of("sys_user", "member_profile", "book", "book_copy", "borrow_record", "legacy_record", "circulation_order", "circulation_order_item")) {
            String order = "member_profile".equals(table) ? "user_id" : "circulation_order_item".equals(table) ? "order_id,ordinal" : "id";
            result.put(table, json.writeValueAsString(jdbc.queryForList("SELECT * FROM " + table + " ORDER BY " + order)));
        }
        return result;
    }
}
