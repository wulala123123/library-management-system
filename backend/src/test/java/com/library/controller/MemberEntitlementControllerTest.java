package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.*;
import com.library.dto.MemberEntitlementContracts.*;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import com.library.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:entitlement-acceptance;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MemberEntitlementControllerTest {
    @Autowired MockMvc mvc;
    @MockitoSpyBean JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MemberEntitlementService entitlements;
    @Autowired MemberService members;
    @Autowired MembershipPlanService plans;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;
    @MockitoSpyBean MemberEntitlementAuditWriter audit;
    @MockitoSpyBean MemberAuditWriter memberAudit;

    @Test
    void readAndPreviewNeverCreateProfilesOrSnapshotsAndExplicitActualKeysAreRequired() throws Exception {
        User user = bare();
        JsonNode current = request("GET", path(user.getId()), null, "admin", 200);
        assertFalse(current.path("member").path("profilePresent").asBoolean()); assertTrue(current.get("snapshot").isNull());
        Input input = input(user.getId(), null);
        Preview preview = entitlements.preview(user.getId(), input);
        assertEquals(64, preview.previewHash().length());
        assertEquals(0, count("member_profile", user.getId())); assertEquals(0, count("member_entitlement_snapshot", user.getId()));
        for (String key : List.of("planName", "serviceStart", "serviceEnd", "usageType", "usedUses", "totalUses")) {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.valueToTree(input);
            ((com.fasterxml.jackson.databind.node.ObjectNode) body.path("actual")).remove(key);
            request("POST", path(user.getId()) + "/preview", body, "admin", 400);
        }
        Confirmation confirmation = confirm(user.getId(), input);
        confirmation.setConfirmed(false); request("POST", path(user.getId()) + "/confirm", confirmation, "admin", 400);
    }

    @Test
    void reasonLengthBoundaryMatchesForPreviewAndCompleteConfirmation() throws Exception {
        Long id = bare().getId(); Input input = input(id, null);
        input.setReason("据".repeat(501));
        request("POST", path(id) + "/preview", input, "admin", 400);
        assertEquals(0, members.summary(id).getRevision()); assertEquals(0, count("member_profile", id));
        assertEquals(0, count("member_entitlement_snapshot", id));
        input.setReason("据".repeat(500));
        JsonNode preview = request("POST", path(id) + "/preview", input, "admin", 200);
        Confirmation confirmation = new Confirmation(); BeanUtils.copyProperties(input, confirmation);
        confirmation.setPreviewHash(preview.path("previewHash").asText()); confirmation.setConfirmed(true);
        confirmation.setIdempotencyKey(UUID.randomUUID().toString());
        JsonNode result = request("POST", path(id) + "/confirm", confirmation, "admin", 200);
        assertEquals(input.getReason(), result.path("snapshot").path("reason").asText());
        assertEquals(1, result.path("revision").asLong()); assertEquals(1, count("member_entitlement_snapshot", id));
        assertEquals(input.getReason(), jdbc.queryForObject("SELECT change_reason FROM member_profile_change WHERE user_id=?", String.class, id));
    }

    @Test
    void historicalDisabledVersionNeedsBasisAndConfirmationKeepsActualDatesRawUsageAndAllOriginalFinancialSourceFacts() throws Exception {
        Long id = legacy(9, "UNKNOWN");
        Long version = version(false);
        Input input = input(id, version);
        input.setHistoryBasis(null);
        request("POST", path(id) + "/preview", input, "admin", 400);
        input.setHistoryBasis("原合同已确认与该保留版本关联");
        input.setSourceReferences(List.of("legacy-reference:0001"));
        MemberDetail before = members.detail(id);
        Map<String, Object> finances = financial(id);
        Actual requested = input.getActual();
        var result = json.treeToValue(request("POST", path(id) + "/confirm", confirm(id, input), "admin", 200), MemberEntitlementContracts.Result.class);
        assertFalse(result.replayed()); assertEquals(input.getRevision() + 1, result.revision());
        Snapshot snapshot = result.snapshot();
        assertEquals(version, snapshot.planVersionId()); assertEquals(requested, snapshot.factsAtConfirmation());
        assertEquals(9, snapshot.factsAtConfirmation().totalUses());
        assertEquals("UNKNOWN", snapshot.rulesAtConfirmation().getUsageType()); assertNull(snapshot.rulesAtConfirmation().getTotalUses());
        assertEquals(finances, financial(id));
        assertEquals(before.getServiceStart(), members.summary(id).getServiceStart());
        assertEquals(before.getServiceEnd(), members.summary(id).getServiceEnd());
        assertEquals(before.getUsedUses(), members.summary(id).getUsedUses());
        assertEquals(9, members.summary(id).getTotalUses()); assertNull(members.summary(id).getRemainingUses());
        assertEquals(1, entitlements.history(id, 1, 10).getTotal());
        assertEquals(snapshot, entitlements.current(id).snapshot());
    }

    @Test
    void confirmsNewMissingProfileThroughUnifiedMemberServiceAndPreservesLimitedZeroUnlimitedAndOveruse() {
        Long id = bare().getId();
        Input input = input(id, null);
        var created = entitlements.confirm(id, confirm(id, input), 1L);
        assertTrue(members.summary(id).isProfilePresent()); assertEquals("UNKNOWN", members.summary(id).getUsageType());
        assertEquals(1, count("member_profile_change", id)); assertEquals(1, count("member_entitlement_change", id));
        Input limited = input(id, null);
        limited.setActual(new Actual(null, null, null, "LIMITED", 2, 0)); limited.getRules().setUsageType("LIMITED"); limited.getRules().setTotalUses(0);
        entitlements.confirm(id, confirm(id, limited), 1L);
        assertEquals(-2, members.summary(id).getRemainingUses()); assertTrue(members.summary(id).isOverused());
        Input unlimited = input(id, null); unlimited.setActual(new Actual(null, null, null, "UNLIMITED", 2, null));
        unlimited.getRules().setUsageType("UNLIMITED"); unlimited.getRules().setTotalUses(null);
        entitlements.confirm(id, confirm(id, unlimited), 1L);
        assertNull(members.summary(id).getTotalUses()); assertNull(members.summary(id).getRemainingUses());
        assertEquals(3, entitlements.history(id, 1, 10).getTotal());
        assertTrue(created.snapshot().factsAtConfirmation().totalUses() == null);
    }

    @Test
    void publishingAndSelectionNeverMutateMembersOrTheirImmutableSnapshots() {
        Long id = bare().getId(), version = version(true);
        Input input = input(id, version);
        var confirmed = entitlements.confirm(id, confirm(id, input), 1L);
        String before = encode(entitlements.current(id));
        Long planId = confirmed.snapshot().versionSnapshot().planId();
        var plan = plans.detail(planId);
        var disable = new MembershipPlanRequests.Selection(); disable.setRevision(plan.revision()); disable.setVersionId(version); disable.setEnabled(false); disable.setChangeReason("合成停用");
        plans.select(planId, disable, 1L);
        var draft = new MembershipPlanRequests.Draft(); BeanUtils.copyProperties(plan.draft(), draft);
        draft.setRevision(plans.detail(planId).revision()); draft.setChangeReason("合成新草稿"); draft.setName("合成新定义");
        plans.saveDraft(planId, draft, 1L);
        var publish = publication(plans.detail(planId).revision()); plans.publish(planId, publish, 1L);
        assertEquals(before, encode(entitlements.current(id)));
    }

    @Test
    void memberFactsEditsAppendASnapshotInTheSameTransactionButContactOnlyEditsDoNot() {
        Long id = bare().getId(); entitlements.confirm(id, confirm(id, input(id, null)), 1L);
        MemberDetail detail = members.detail(id);
        MemberUpdateRequest contact = edit(detail); contact.setPhone("13000000008"); members.update(id, contact, 1L);
        assertEquals(1, entitlements.history(id, 1, 20).getTotal());
        detail = members.detail(id); MemberUpdateRequest facts = edit(detail); facts.setPlanName("核实名称"); facts.setUsageType("UNKNOWN"); facts.setTotalUses(11);
        members.update(id, facts, 1L);
        assertEquals(2, entitlements.history(id, 1, 20).getTotal());
        assertEquals(11, members.summary(id).getTotalUses()); assertNull(members.summary(id).getRemainingUses());
        assertEquals(11, entitlements.current(id).snapshot().factsAtConfirmation().totalUses());
        MemberDetail newMember = members.create(createMemberWithKnownFacts(), 1L);
        assertEquals(1, entitlements.history(newMember.getId(), 1, 20).getTotal(), "New member known facts share the same snapshot path");
    }

    @Test
    void stalePreviewAndCrossEntryRevisionChangesRejectWithoutAnyPartialSave() throws Exception {
        Long id = bare().getId(); Input original = input(id, null); Confirmation stale = confirm(id, original);
        mvc.perform(put("/profile").header("Authorization", token(users.selectById(id))).contentType("application/json").content("{\"phone\":\"13000000007\"}"))
                .andExpect(status().isOk());
        request("POST", path(id) + "/confirm", stale, "admin", 409);
        assertEquals("13000000007", members.summary(id).getPhone()); assertEquals(0, count("member_profile", id));
        Confirmation changedBody = confirm(id, input(id, null)); changedBody.setReason("modified without another preview");
        request("POST", path(id) + "/confirm", changedBody, "admin", 409);
        assertEquals(0, count("member_entitlement_snapshot", id));
        Long version = version(true); Input planBound = input(id, version); planBound.setHistoryBasis(null); Confirmation oldChoice = confirm(id, planBound);
        var plan = plans.detail(jdbc.queryForObject("SELECT plan_id FROM membership_plan_version WHERE id=?", Long.class, version));
        var off = new MembershipPlanRequests.Selection(); off.setRevision(plan.revision()); off.setVersionId(version); off.setEnabled(false); off.setChangeReason("selection changed"); plans.select(plan.id(), off, 1L);
        request("POST", path(id) + "/confirm", oldChoice, "admin", 409);
    }

    @Test
    void replayReturnsOriginalSnapshotBeforeNewRevisionAndKeyReuseWithDifferentBodyConflicts() {
        Long id = bare().getId(); Confirmation request = confirm(id, input(id, null));
        var first = entitlements.confirm(id, request, 1L);
        MemberUpdateRequest contact = edit(members.detail(id)); contact.setPhone("13000000008"); members.update(id, contact, 1L);
        var replay = entitlements.confirm(id, request, 1L);
        assertTrue(replay.replayed()); assertEquals(first.snapshot(), replay.snapshot()); assertEquals(first.revision(), replay.revision());
        assertTrue(members.summary(id).getRevision() > replay.revision());
        request.setReason("different request"); assertThrows(MemberEntitlementConflictException.class, () -> entitlements.confirm(id, request, 1L));
        assertEquals(1, count("member_entitlement_request", id)); assertEquals(1, entitlements.history(id, 1, 10).getTotal());
    }

    @Test
    void librarianGetsOnlyExistingSummaryAndReaderOnlyOwnApplicableRulesWithoutReasonsSourcesOrOtherPeople() throws Exception {
        Long id = legacy(0, "LIMITED"); entitlements.confirm(id, confirm(id, input(id, null)), 1L);
        JsonNode staff = request("GET", path(id), null, "librarian", 200);
        assertEquals(id.longValue(), staff.path("id").asLong());
        assertFalse(staff.has("effectiveRules")); assertFalse(staff.has("snapshot"));
        String own = mvc.perform(get("/members/me/entitlement").header("Authorization", token(users.selectById(id))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode reader = json.readTree(own).path("data"); assertTrue(reader.path("snapshotPresent").asBoolean());
        assertTrue(reader.has("effectiveRules"));
        for (String field : List.of("reason", "historyBasis", "sourceReferences", "confirmedBy", "snapshot", "financialFacts", "children")) {
            assertFalse(reader.has(field)); assertFalse(reader.path("member").has(field));
        }
        mvc.perform(get(path(id)).header("Authorization", token(users.selectById(id)))).andExpect(status().isForbidden());
        for (String suffix : List.of("/history", "/preview", "/confirm")) {
            Object validBody = suffix.equals("/confirm") ? confirm(id, input(id, null)) : input(id, null);
            request(suffix.equals("/history") ? "GET" : "POST", path(id) + suffix, validBody, "librarian", 403);
            mvc.perform((suffix.equals("/history") ? get(path(id) + suffix) : post(path(id) + suffix)).header("Authorization", token(users.selectById(id)))
                    .contentType("application/json").content(json.writeValueAsBytes(validBody))).andExpect(status().isForbidden());
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void snapshotInsertAndRequestInsertFailuresRollbackTheEntireMemberTransaction() {
        Long id = bare().getId(); Confirmation request = confirm(id, input(id, null));
        doThrow(new IllegalStateException("synthetic snapshot write failure")).when(jdbc)
                .update(any(org.springframework.jdbc.core.PreparedStatementCreator.class), any(org.springframework.jdbc.support.KeyHolder.class));
        assertThrows(IllegalStateException.class, () -> entitlements.confirm(id, request, 1L));
        reset(jdbc);
        assertEquals(0, members.summary(id).getRevision()); assertEquals(0, count("member_profile", id));
        assertEquals(0, count("member_entitlement_snapshot", id)); assertEquals(0, count("member_profile_change", id));
        doThrow(new IllegalStateException("synthetic request write failure")).when(jdbc).update(startsWith("INSERT INTO member_entitlement_request"),
                anyLong(), any(byte[].class), anyString(), anyLong(), anyLong());
        assertThrows(IllegalStateException.class, () -> entitlements.confirm(id, request, 1L));
        reset(jdbc);
        assertEquals(0, members.summary(id).getRevision()); assertEquals(0, count("member_profile", id));
        assertEquals(0, count("member_entitlement_snapshot", id)); assertEquals(0, count("member_entitlement_request", id));
        assertEquals(0, count("member_profile_change", id)); assertEquals(0, count("member_entitlement_change", id));
        assertFalse(entitlements.confirm(id, request, 1L).replayed());
        assertEquals(1, entitlements.history(id, 1, 10).getTotal());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void entitlementAuditAndMemberAuditFailuresRollbackFactsRevisionProfileSnapshotPointerAndIdempotency() {
        Long id = bare().getId(); Confirmation request = confirm(id, input(id, null));
        doThrow(new IllegalStateException("synthetic entitlement audit failure")).when(audit).save(anyLong(), anyLong(), anyString(), anyString(), any(), any());
        assertThrows(IllegalStateException.class, () -> entitlements.confirm(id, request, 1L));
        assertEquals(0, members.summary(id).getRevision()); assertEquals(0, count("member_profile", id));
        assertEquals(0, count("member_entitlement_snapshot", id)); assertEquals(0, count("member_entitlement_request", id));
        assertEquals(0, count("member_profile_change", id));
        reset(audit);
        doThrow(new IllegalStateException("synthetic member audit failure")).when(memberAudit).save(anyLong(), anyLong(), anyString(), any(), any());
        assertThrows(IllegalStateException.class, () -> entitlements.confirm(id, request, 1L));
        assertEquals(0, members.summary(id).getRevision()); assertEquals(0, count("member_profile", id));
        assertEquals(0, count("member_entitlement_snapshot", id)); assertEquals(0, count("member_entitlement_request", id));
        reset(memberAudit);
        MemberUpdateRequest ordinary = edit(members.detail(id)); ordinary.setPlanName("失败后正常资料修订");
        members.update(id, ordinary, 1L);
        assertEquals(1, count("member_entitlement_snapshot", id), "A failed confirmation must clear its thread scope");
        assertEquals(1, count("member_profile_change", id)); assertEquals(1, count("member_entitlement_change", id));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentSameKeyIsOneSnapshotAndCompetingKeysRejectStaleRevision() throws Exception {
        Long id = bare().getId(); Confirmation one = confirm(id, input(id, null));
        var results = parallel(() -> entitlements.confirm(id, one, 1L), () -> entitlements.confirm(id, one, 1L));
        assertEquals(results.get(0).snapshot(), results.get(1).snapshot());
        assertEquals(1, results.stream().filter(MemberEntitlementContracts.Result::replayed).count());
        assertEquals(1, entitlements.history(id, 1, 20).getTotal());
        Input input = input(id, null); Confirmation a = confirm(id, input), b = confirm(id, input);
        List<Boolean> distinct = parallel(() -> attempt(id, a), () -> attempt(id, b));
        assertEquals(1, distinct.stream().filter(Boolean::booleanValue).count());
        assertEquals(2, entitlements.history(id, 1, 20).getTotal());
    }

    private boolean attempt(Long id, Confirmation value) { try { entitlements.confirm(id, value, 1L); return true; } catch (MemberEntitlementConflictException expected) { return false; } }
    private <T> List<T> parallel(Callable<T> a, Callable<T> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try { List<Future<T>> tasks = List.of(pool.submit(() -> { start.await(); return a.call(); }), pool.submit(() -> { start.await(); return b.call(); })); start.countDown();
            List<T> result = new ArrayList<>(); for (Future<T> task : tasks) result.add(task.get(30, TimeUnit.SECONDS)); return result;
        } finally { start.countDown(); pool.shutdownNow(); }
    }
    private Input input(Long id, Long version) {
        AdminView current = entitlements.current(id); MemberSummary m = current.member();
        Input input = new Input(); input.setRevision(m.getRevision()); input.setPlanVersionId(version);
        input.setActual(new Actual(m.getPlanName(), m.getServiceStart(), m.getServiceEnd(), m.getUsageType(), m.getUsedUses(), m.getTotalUses()));
        input.setRules(current.effectiveRules()); input.setSourceReferences(List.of()); input.setReason("合成历史权益核对"); input.setHistoryBasis("合成历史依据"); return input;
    }
    private Confirmation confirm(Long id, Input input) { Confirmation value = new Confirmation(); BeanUtils.copyProperties(input, value); value.setPreviewHash(entitlements.preview(id, input).previewHash()); value.setConfirmed(true); value.setIdempotencyKey(UUID.randomUUID().toString()); return value; }
    private MemberUpdateRequest edit(MemberDetail member) { MemberUpdateRequest r = new MemberUpdateRequest(); BeanUtils.copyProperties(member, r); r.setRevision(member.getRevision()); r.setChangeReason("合成人工修订"); return r; }
    private MemberCreateRequest createMemberWithKnownFacts() { MemberCreateRequest r = new MemberCreateRequest(); r.setUsername("ent_" + suffix()); r.setPassword("SyntheticReader123"); r.setRealName("合成会员"); r.setUsageType("LIMITED"); r.setTotalUses(0); r.setUsedUses(0); r.setChangeReason("合成已知新资料"); return r; }
    private Long legacy(Integer raw, String usage) {
        User user = bare(); jdbc.update("INSERT INTO member_profile(user_id,source_key,plan_name,service_start,service_end,usage_type,total_uses,used_uses,paid_fee,deposit_amount,deposit_status) VALUES (?,?,?,'2020-01-02','2020-03-04',?,?,3,123.45,67.89,'合成原缴纳状态')",
                user.getId(), "manual:" + suffix(), "原套餐名称", usage, raw); return user.getId();
    }
    private User bare() { User u = new User(); u.setUsername("ent_" + suffix()); u.setPassword("synthetic-value"); u.setRealName("合成会员"); u.setRole("READER"); u.setStatus(1); users.insert(u); return u; }
    private Long version(boolean selected) {
        var r = new MembershipPlanRequests.Create(); r.setName("合成版本" + suffix()); r.setChangeReason("合成来源"); var rule = r.getRules();
        rule.setServiceDurationDays(30); rule.getPerOrderLimit().setMode("UNLIMITED"); rule.getActiveLoanLimit().setMode("UNLIMITED");
        rule.setUsageType("LIMITED"); rule.setTotalUses(9); rule.setDueDateType("UNLIMITED"); rule.setRenewalPermission("FORBIDDEN"); rule.getLanguages().setMode("ANY"); rule.getPickupMethods().setMode("ANY");
        var plan = plans.create(r, 1L); var published = plans.publish(plan.id(), publication(0), 1L);
        if (selected) { var choice = new MembershipPlanRequests.Selection(); choice.setRevision(published.revision()); choice.setVersionId(published.version().id()); choice.setEnabled(true); choice.setChangeReason("合成可选"); plans.select(plan.id(), choice, 1L); }
        return published.version().id();
    }
    private MembershipPlanRequests.Publish publication(long revision) { var r = new MembershipPlanRequests.Publish(); r.setRevision(revision); r.setIdempotencyKey(UUID.randomUUID().toString()); r.setConfirmationBasis("合成全部核实"); r.setChangeReason("合成发布"); return r; }
    private Map<String, Object> financial(Long id) { return jdbc.queryForMap("SELECT paid_fee,deposit_amount,deposit_status,source_key FROM member_profile WHERE user_id=?", id); }
    private long count(String table, Long id) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, id); }
    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private String encode(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    private String path(Long id) { return "/members/" + id + "/entitlement"; }
    private String token(User u) { return "Bearer " + tokens.generateToken(u.getId(), u.getUsername(), u.getRole(), u.getTokenVersion()); }
    private JsonNode request(String method, String path, Object body, String role, int status) throws Exception {
        User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, role)); var request = method.equals("GET") ? get(path) : post(path);
        request.header("Authorization", token(user)); if (body != null) request.contentType("application/json").content(json.writeValueAsBytes(body));
        String content = mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8); return json.readTree(content).path("data");
    }
}
