package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.MembershipPlanDefinition;
import com.library.dto.MembershipPlanDefinition.*;
import com.library.dto.MembershipPlanRequests.*;
import com.library.dto.MembershipPlanResponses.*;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import com.library.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:membership-plan-acceptance;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MembershipPlanControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MembershipPlanService plans;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;
    @MockitoSpyBean MembershipPlanAuditWriter audit;

    @Test
    void unknownDraftsRetainSourceStringsAndExplicitNullsButCannotBePublished() throws Exception {
        Create request = unknown();
        request.setNotes("原值 0 / 不限，尚未核实");
        request.setSourceReferences(List.of("原套餐页面: 未设置", "manual-reference:0001"));
        JsonNode created = call("POST", "/membership-plans", request, "admin", 200);
        long id = created.path("id").asLong();
        assertFalse(created.path("enabled").asBoolean()); assertTrue(created.has("selectedVersionId"));
        assertTrue(created.get("selectedVersionId").isNull()); assertEquals(0, created.path("revision").asLong());
        JsonNode draft = created.path("draft");
        assertEquals(request.getSourceReferences(), json.convertValue(draft.path("sourceReferences"), List.class));
        assertEquals(request.getNotes(), draft.path("notes").asText());
        assertTrue(draft.path("rules").has("serviceDurationDays")); assertTrue(draft.path("rules").get("serviceDurationDays").isNull());
        call("POST", "/membership-plans/" + id + "/versions", publish(0), "admin", 400);
        assertEquals(0, plans.detail(id).versionCount()); assertEquals(0, plans.detail(id).revision());
        JsonNode list = call("GET", "/membership-plans?keyword=" + request.getName(), null, "admin", 200);
        assertEquals(1, list.path("total").asLong());
        assertFalse(list.path("records").get(0).has("draft"));
    }

    @Test
    void publishesImmutableZeroAndUnlimitedRulesThenSeparatelySelectsAndDisablesWithoutChangingAnyOldBusinessRows() throws Exception {
        Map<String, List<Map<String, Object>>> oldBusiness = oldBusiness();
        Create request = complete();
        Detail created = plans.create(request, 1L);
        Publish publish = publish(created.revision());
        Publication published = plans.publish(created.id(), publish, 1L);
        Version original = published.version();
        assertEquals(0, original.rules().getPerOrderLimit().getValue());
        assertEquals("UNLIMITED", original.rules().getActiveLoanLimit().getMode());
        assertEquals("LIMITED", original.rules().getUsageType()); assertEquals(0, original.rules().getTotalUses());
        assertEquals("UNLIMITED", original.rules().getDueDateType()); assertNull(original.rules().getBorrowDays());
        assertEquals("START_INCLUDED", original.rules().getDayCountConvention());
        assertFalse(plans.detail(created.id()).enabled()); assertNull(plans.detail(created.id()).selectedVersionId());
        Selection selection = select(published.revision(), original.id(), true);
        Detail selected = plans.select(created.id(), selection, 1L);
        assertTrue(selected.enabled()); assertEquals(original.id(), selected.selectedVersionId());
        Draft edit = draft(selected); edit.setName("新定义" + suffix()); edit.getRules().setTotalUses(7);
        Detail saved = plans.saveDraft(created.id(), edit, 1L);
        Publication next = plans.publish(created.id(), publish(saved.revision()), 1L);
        assertEquals(2, next.version().versionNumber()); assertEquals(7, next.version().rules().getTotalUses());
        assertEquals(original, plans.detail(created.id()).versions().get(1));
        assertEquals(original.id(), plans.detail(created.id()).selectedVersionId(), "Publishing must not auto-select");
        Detail disabled = plans.select(created.id(), select(next.revision(), original.id(), false), 1L);
        assertFalse(disabled.enabled()); assertEquals(2, disabled.versions().size());
        assertEquals(oldBusiness, oldBusiness(), "Plan actions must never alter members, dates, counts, money, inventory or old audit rows");
        var changes = plans.changes(created.id(), 1, 20);
        assertEquals(6, changes.getTotal());
        assertEquals("DISABLE_SELECTION", changes.getRecords().get(0).action());
        assertTrue(changes.getRecords().get(0).beforeJson().contains("selectedVersionId"));
        call("PUT", "/membership-plans/" + created.id() + "/versions/" + original.id(), Map.of(), "admin", 404);
        call("DELETE", "/membership-plans/" + created.id() + "/versions/" + original.id(), null, "admin", 404);
    }

    @Test
    void oldBusinessSnapshotComparesEveryBinaryByteByValueAndStillDetectsChangedBytes() {
        String isbn = "snapshot-" + suffix();
        jdbc.update("INSERT INTO book(isbn,title,stock_total,stock_available) VALUES (?,'合成快照二进制验证',1,1)", isbn);
        long bookId = jdbc.queryForObject("SELECT id FROM book WHERE isbn=?", Long.class, isbn);
        String barcode = "000零Aa-" + suffix() + " ";
        jdbc.update("INSERT INTO book_copy(book_id,barcode,status) VALUES (?,?,'AVAILABLE')", bookId, barcode);
        Map<String, List<Map<String, Object>>> before = oldBusiness();
        String copyTable = before.keySet().stream().filter(table -> table.equalsIgnoreCase("book_copy")).findFirst().orElseThrow();
        Map<String, Object> copy = before.get(copyTable).stream().filter(row -> ((Number) row.entrySet().stream()
                .filter(cell -> cell.getKey().equalsIgnoreCase("book_id")).findFirst().orElseThrow().getValue()).longValue() == bookId).findFirst().orElseThrow();
        Object captured = copy.entrySet().stream().filter(cell -> cell.getKey().equalsIgnoreCase("barcode_key")).findFirst().orElseThrow().getValue();
        // The generated binary column stays in the full snapshot, including zeros, Unicode and trailing space.
        assertEquals(HexFormat.of().formatHex(barcode.getBytes(StandardCharsets.UTF_8)), captured);
        assertEquals(before, oldBusiness(), "Repeated reads of unchanged binary cells must compare by value");
        jdbc.update("UPDATE book_copy SET barcode=? WHERE book_id=?", barcode.replace("Aa", "aa"), bookId);
        assertNotEquals(before, oldBusiness(), "A real case-sensitive byte change must remain visible");
        jdbc.update("UPDATE book_copy SET barcode=? WHERE book_id=?", barcode, bookId);
        assertEquals(before, oldBusiness(), "Restoring the exact bytes must restore the complete snapshot");
    }

    @Test
    void idempotencyReplaysBeforeRevisionAndCurrentDraftChecksAndRejectsDifferentRequestsForTheSameKey() {
        Detail created = plans.create(complete(), 1L);
        Publish request = publish(created.revision());
        Publication first = plans.publish(created.id(), request, 1L);
        Publication replay = plans.publish(created.id(), request, 1L);
        assertTrue(replay.replayed()); assertEquals(first.version(), replay.version());
        Detail current = plans.detail(created.id());
        Draft edit = draft(current); edit.setRules(new Rules());
        plans.saveDraft(created.id(), edit, 1L);
        Publication afterEdit = plans.publish(created.id(), request, 1L);
        assertTrue(afterEdit.replayed()); assertEquals(first.version(), afterEdit.version());
        assertEquals(1, plans.detail(created.id()).versionCount());
        Publish reused = publish(request.getRevision()); reused.setIdempotencyKey(request.getIdempotencyKey());
        reused.setConfirmationBasis("different approved basis");
        assertThrows(MembershipPlanConflictException.class, () -> plans.publish(created.id(), reused, 1L));
        assertEquals(3, plans.changes(created.id(), 1, 20).getTotal(), "A replay must not append another audit");
    }

    @Test
    void invalidModesNumbersCombinationsAndEmptyListsCannotBecomeUnlimited() throws Exception {
        List<Create> invalid = new ArrayList<>();
        Create r = complete(); r.getRules().getPerOrderLimit().setMode("GUESS"); invalid.add(r);
        r = complete(); r.getRules().getPerOrderLimit().setValue(-1); invalid.add(r);
        r = complete(); r.getRules().getActiveLoanLimit().setValue(0); invalid.add(r);
        r = complete(); r.getRules().setTotalUses(null); invalid.add(r);
        r = complete(); r.getRules().setUsageType("UNLIMITED"); invalid.add(r);
        r = complete(); r.getRules().setBorrowDays(0); invalid.add(r);
        r = complete(); r.getRules().setBorrowDays(7); invalid.add(r);
        r = complete(); r.getRules().setRenewalDays(2); invalid.add(r);
        r = complete(); r.getRules().getLanguages().setMode("ALLOW_LIST"); invalid.add(r);
        r = complete(); r.getRules().getPickupMethods().setValues(List.of("快递")); invalid.add(r);
        r = complete(); r.getRules().getLanguages().setMode("ALLOW_LIST"); r.getRules().getLanguages().setValues(List.of("英文", "英文")); invalid.add(r);
        r = complete(); r.getRules().setDayCountConvention("START_EXCLUDED"); invalid.add(r);
        r = complete(); r.getRules().setServiceDurationDays(0); invalid.add(r);
        for (Create value : invalid) call("POST", "/membership-plans", value, "admin", 400);
        var body = json.valueToTree(complete()); ((com.fasterxml.jackson.databind.node.ObjectNode) body).put("memberId", 3);
        call("POST", "/membership-plans", body, "admin", 400);
        for (String field : List.of("serviceDurationDays", "totalUses", "borrowDays", "renewalDays")) {
            var fractional = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(complete());
            ((com.fasterxml.jackson.databind.node.ObjectNode) fractional.path("rules")).put(field, 1.5);
            call("POST", "/membership-plans", fractional, "admin", 400);
        }
        var fractionalLimit = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(complete());
        ((com.fasterxml.jackson.databind.node.ObjectNode) fractionalLimit.path("rules").path("perOrderLimit")).put("value", 0.5);
        call("POST", "/membership-plans", fractionalLimit, "admin", 400);
        Detail plan = plans.create(complete(), 1L);
        var fractionalRevision = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(publish(0));
        fractionalRevision.put("revision", 0.5);
        call("POST", "/membership-plans/" + plan.id() + "/versions", fractionalRevision, "admin", 400);
    }

    @Test
    void everyApplicableUnknownRuleBlocksPublicationAndAllowedRenewalRequiresBothLimitAndPositiveDays() {
        List<Create> drafts = new ArrayList<>();
        Create r = complete(); r.getRules().setServiceDurationDays(null); drafts.add(r);
        r = complete(); r.getRules().getPerOrderLimit().setMode("UNKNOWN"); r.getRules().getPerOrderLimit().setValue(null); drafts.add(r);
        r = complete(); r.getRules().getActiveLoanLimit().setMode("UNKNOWN"); drafts.add(r);
        r = complete(); r.getRules().setUsageType("UNKNOWN"); r.getRules().setTotalUses(null); drafts.add(r);
        r = complete(); r.getRules().setDueDateType("UNKNOWN"); drafts.add(r);
        r = complete(); r.getRules().setDueDateType("DAYS"); drafts.add(r);
        r = complete(); r.getRules().setRenewalPermission("UNKNOWN"); drafts.add(r);
        r = complete(); r.getRules().setRenewalPermission("ALLOWED"); drafts.add(r);
        r = complete(); r.getRules().getLanguages().setMode("UNKNOWN"); drafts.add(r);
        r = complete(); r.getRules().getPickupMethods().setMode("UNKNOWN"); drafts.add(r);
        for (Create draft : drafts) {
            Detail created = plans.create(draft, 1L);
            assertThrows(IllegalArgumentException.class, () -> plans.publish(created.id(), publish(0), 1L));
        }
        Create known = complete(); known.getRules().setUsageType("UNLIMITED"); known.getRules().setTotalUses(null);
        known.getRules().setDueDateType("DAYS"); known.getRules().setBorrowDays(30);
        known.getRules().setRenewalPermission("ALLOWED"); known.getRules().setRenewalDays(15);
        Limit limit = new Limit(); limit.setMode("LIMITED"); limit.setValue(0); known.getRules().setRenewalLimit(limit);
        known.getRules().getLanguages().setMode("ALLOW_LIST"); known.getRules().getLanguages().setValues(List.of("中文", "English"));
        Detail created = plans.create(known, 1L); Version version = plans.publish(created.id(), publish(0), 1L).version();
        assertEquals("UNLIMITED", version.rules().getUsageType()); assertNull(version.rules().getTotalUses());
        assertEquals(0, version.rules().getRenewalLimit().getValue());
        assertEquals(List.of("中文", "English"), version.rules().getLanguages().getValues());
    }

    @Test
    void selectionRequiresAnExplicitPublishedVersionFromThisPlanAndRevisionConflictsPreserveInput() throws Exception {
        Detail first = plans.create(complete(), 1L), other = plans.create(complete(), 1L);
        Publication foreign = plans.publish(other.id(), publish(0), 1L);
        call("PUT", "/membership-plans/" + first.id() + "/selection", select(0, foreign.version().id(), true), "admin", 400);
        Publication version = plans.publish(first.id(), publish(0), 1L);
        call("PUT", "/membership-plans/" + first.id() + "/selection", select(0, version.version().id(), true), "admin", 409);
        Draft stale = draft(first); stale.setNotes("unsaved client content");
        call("PUT", "/membership-plans/" + first.id() + "/draft", stale, "admin", 409);
        assertEquals(first.draft(), plans.detail(first.id()).draft());
    }

    @Test
    void allEndpointsAreAdminOnlyAndNoSensitiveDatabaseErrorIsReturned() throws Exception {
        Detail plan = plans.create(complete(), 1L);
        List<String> reads = List.of("/membership-plans", "/membership-plans/" + plan.id(), "/membership-plans/" + plan.id() + "/changes");
        for (String role : List.of("reader", "librarian")) {
            for (String path : reads) call("GET", path, null, role, 403);
            call("POST", "/membership-plans", unknown(), role, 403);
            call("PUT", "/membership-plans/" + plan.id() + "/draft", draft(plan), role, 403);
            call("POST", "/membership-plans/" + plan.id() + "/versions", publish(0), role, 403);
            call("PUT", "/membership-plans/" + plan.id() + "/selection", select(0, 999L, false), role, 403);
        }
        mvc.perform(get("/membership-plans")).andExpect(status().isUnauthorized());
        doThrow(new DataIntegrityViolationException("PRIVATE_SOURCE_CONTENT_SQL")).when(audit).save(anyLong(), anyLong(), anyString(), anyString(), any(), any());
        JsonNode response = call("PUT", "/membership-plans/" + plan.id() + "/draft", draft(plan), "admin", 500);
        assertTrue(response.isMissingNode());
        String error = mvc.perform(post("/membership-plans").header("Authorization", bearer("admin")).contentType("application/json")
                        .content(json.writeValueAsString(unknown())))
                .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(error.contains("PRIVATE_SOURCE_CONTENT_SQL"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackCreateDraftPublicationAndSelectionInTheSameTransaction() {
        Detail initial = plans.create(complete(), 1L);
        Publication version = plans.publish(initial.id(), publish(0), 1L);
        Detail original = plans.detail(initial.id());
        long total = plans.list(1, 100, null).getTotal();
        doThrow(new IllegalStateException("synthetic audit failure")).when(audit).save(anyLong(), anyLong(), anyString(), anyString(), any(), any());
        assertThrows(IllegalStateException.class, () -> plans.create(unknown(), 1L));
        Draft changed = draft(original); changed.setName("must roll back");
        assertThrows(IllegalStateException.class, () -> plans.saveDraft(original.id(), changed, 1L));
        Publish attempted = publish(original.revision());
        assertThrows(IllegalStateException.class, () -> plans.publish(original.id(), attempted, 1L));
        assertThrows(IllegalStateException.class, () -> plans.select(original.id(), select(original.revision(), version.version().id(), true), 1L));
        assertEquals(total, plans.list(1, 100, null).getTotal()); assertEquals(original, plans.detail(original.id()));
        assertEquals(2, plans.changes(original.id(), 1, 10).getTotal());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM membership_plan_version WHERE plan_id=? AND idempotency_key=?", Long.class,
                original.id(), attempted.getIdempotencyKey().getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousSameKeyRequestsPublishExactlyOneVersionAndOneAudit() throws Exception {
        Detail plan = plans.create(complete(), 1L); Publish body = publish(0);
        List<Publication> results = parallel(() -> plans.publish(plan.id(), body, 1L), () -> plans.publish(plan.id(), body, 1L));
        assertEquals(results.get(0).version(), results.get(1).version());
        assertEquals(1, results.stream().filter(Publication::replayed).count());
        assertEquals(1, plans.detail(plan.id()).versionCount()); assertEquals(2, plans.changes(plan.id(), 1, 10).getTotal());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void competingPublishKeysAndDraftWritesRejectStaleRevisionsAndVersionNumbersRemainUnique() throws Exception {
        Detail plan = plans.create(complete(), 1L);
        List<Boolean> keys = parallel(() -> attempt(() -> plans.publish(plan.id(), publish(0), 1L)),
                () -> attempt(() -> plans.publish(plan.id(), publish(0), 1L)));
        assertEquals(1, keys.stream().filter(Boolean::booleanValue).count());
        Detail current = plans.detail(plan.id()); Draft draft = draft(current);
        List<Boolean> race = parallel(() -> attempt(() -> plans.saveDraft(plan.id(), draft, 1L)),
                () -> attempt(() -> plans.publish(plan.id(), publish(current.revision()), 1L)));
        assertEquals(1, race.stream().filter(Boolean::booleanValue).count());
        Detail after = plans.detail(plan.id()); assertEquals(current.revision() + 1, after.revision());
        Set<Long> numbers = new HashSet<>(); after.versions().forEach(v -> assertTrue(numbers.add(v.versionNumber())));
        assertEquals(1, numbers.stream().mapToLong(Long::longValue).min().orElseThrow());
    }

    private boolean attempt(Callable<?> work) throws Exception { try { work.call(); return true; } catch (MembershipPlanConflictException expected) { return false; } }
    private <T> List<T> parallel(Callable<T> a, Callable<T> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> work : List.of(a, b)) futures.add(pool.submit(() -> { ready.countDown(); start.await(); return work.call(); }));
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown(); List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(30, TimeUnit.SECONDS)); return results;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private Map<String, List<Map<String, Object>>> oldBusiness() {
        Map<String, List<Map<String, Object>>> result = new TreeMap<>();
        List<String> tables = jdbc.query("SELECT table_name AS name FROM information_schema.tables WHERE table_schema=SCHEMA() AND table_type='BASE TABLE'", (rs, i) -> rs.getString("name"));
        for (String table : tables) {
            if (table.toLowerCase(Locale.ROOT).startsWith("membership_plan")) continue;
            assertTrue(table.matches("[a-zA-Z0-9_]+"));
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM `" + table + "`");
            // JDBC creates fresh byte[] objects on each read. Keep every column, comparing binary content by value.
            for (Map<String, Object> row : rows) row.replaceAll((column, value) -> value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value);
            result.put(table, rows);
        }
        return result;
    }
    protected Create unknown() { Create r = new Create(); r.setName("合成套餐" + suffix()); r.setChangeReason("synthetic creation basis"); return r; }
    protected Create complete() {
        Create r = unknown(); Rules rules = r.getRules(); rules.setServiceDurationDays(30);
        rules.getPerOrderLimit().setMode("LIMITED"); rules.getPerOrderLimit().setValue(0);
        rules.getActiveLoanLimit().setMode("UNLIMITED"); rules.setUsageType("LIMITED"); rules.setTotalUses(0);
        rules.setDueDateType("UNLIMITED"); rules.setRenewalPermission("FORBIDDEN");
        rules.getLanguages().setMode("ANY"); rules.getPickupMethods().setMode("ANY"); return r;
    }
    private Draft draft(Detail value) {
        Draft request = new Draft(); MembershipPlanDefinition source = value.draft();
        request.setName(source.getName()); request.setNotes(source.getNotes()); request.setSourceReferences(source.getSourceReferences());
        request.setRules(json.convertValue(source.getRules(), Rules.class)); request.setRevision(value.revision()); request.setChangeReason("synthetic draft reason"); return request;
    }
    private Publish publish(long revision) { Publish r = new Publish(); r.setRevision(revision); r.setIdempotencyKey(UUID.randomUUID().toString()); r.setConfirmationBasis("all applicable rules explicitly checked"); r.setChangeReason("synthetic publish reason"); return r; }
    private Selection select(long revision, Long version, boolean enabled) { Selection r = new Selection(); r.setRevision(revision); r.setVersionId(version); r.setEnabled(enabled); r.setChangeReason("synthetic selection reason"); return r; }
    private String suffix() { return UUID.randomUUID().toString().substring(0, 8); }
    private String bearer(String name) { User u = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, name)); return "Bearer " + tokens.generateToken(u.getId(), u.getUsername(), u.getRole(), u.getTokenVersion()); }
    private JsonNode call(String method, String path, Object body, String role, int expected) throws Exception {
        var request = switch (method) { case "POST" -> post(path); case "PUT" -> put(path); case "DELETE" -> delete(path); default -> get(path); };
        request.header("Authorization", bearer(role)); if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        String text = mvc.perform(request).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(text).path("data");
    }
}
