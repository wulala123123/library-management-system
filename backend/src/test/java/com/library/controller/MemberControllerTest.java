package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.dto.*;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:member-acceptance;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MemberControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberService members;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;
    @MockitoSpyBean MemberAuditWriter audit;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void newAndMigratedMembersUseTheSameEditableBusinessShapeWithoutChangingSourceOrFinancialFacts() throws Exception {
        MemberCreateRequest create = request();
        create.setChildren(List.of(child("日精度", today.minusYears(4), "DAY", "原日期"),
                child("月精度", null, "MONTH", "2020年5月")));
        JsonNode created = invoke("POST", "/members", create, "admin", 200);
        long id = created.path("id").asLong();
        assertTrue(created.path("profilePresent").asBoolean());
        assertEquals(1, created.path("revision").asLong());
        assertEquals(2, created.path("children").size());
        assertTrue(created.path("children").get(1).get("birthDate").isNull());
        assertTrue(created.path("financialFacts").get("paidFee").isNull());
        assertEquals("READER", users.selectById(id).getRole());
        assertEquals(1, users.selectById(id).getStatus());
        Long imported = legacy(null, true);
        String originalArchive = jdbc.queryForObject("SELECT details_json FROM legacy_record WHERE user_id=?", String.class, imported);
        MemberDetail old = members.detail(imported);
        MemberUpdateRequest edit = edit(old);
        edit.setRealName("更新姓名"); edit.setPhone("13000000009"); edit.setNotes("人工核对备注");
        JsonNode updated = invoke("PUT", "/members/" + imported, edit, "admin", 200);
        assertEquals("UNLIMITED", updated.path("usageType").asText());
        assertTrue(updated.get("totalUses").isNull());
        assertEquals(old.getSourceKey(), updated.path("sourceKey").asText());
        assertEquals("58.25", updated.path("financialFacts").path("paidFee").decimalValue().toPlainString());
        assertEquals(originalArchive, jdbc.queryForObject("SELECT details_json FROM legacy_record WHERE user_id=?", String.class, imported));
        assertEquals(1, jdbc.queryForObject("SELECT revision FROM member_profile WHERE user_id=?", Integer.class, imported));
        JsonNode changes = invoke("GET", "/members/" + imported + "/changes", null, "admin", 200);
        assertEquals(1, changes.path("total").asInt());
        JsonNode event = changes.path("records").get(0);
        assertEquals("ADMIN_MEMBER", event.path("changeSource").asText());
        assertEquals(old.getRealName(), json.readTree(event.path("beforeJson").asText()).path("realName").asText());
        assertEquals("更新姓名", json.readTree(event.path("afterJson").asText()).path("realName").asText());
    }

    @Test
    void realRoleProjectionsExcludeFamilyFinancialNotesAndSourceData() throws Exception {
        Long id = legacy(null, true);
        jdbc.update("UPDATE member_profile SET notes='家庭私密备注',address='家庭私密地址' WHERE user_id=?", id);
        jdbc.update("INSERT INTO member_child(user_id,name) VALUES (?,?)", id, "宝宝私密姓名");
        JsonNode admin = invoke("GET", "/members/" + id, null, "admin", 200);
        assertEquals("家庭私密地址", admin.path("address").asText());
        assertEquals(1, admin.path("children").size());
        for (String path : List.of("/members/" + id, "/members?keyword=" + admin.path("username").asText())) {
            JsonNode response = invoke("GET", path, null, "librarian", 200);
            JsonNode projected = path.contains("?") ? response.path("records").get(0) : response;
            assertSafe(projected);
            assertEquals(id.longValue(), projected.path("id").asLong());
            assertEquals("13000000000", projected.path("phone").asText());
        }
        User reader = users.selectById(id);
        JsonNode own = getAs("/members/me", reader, 200);
        assertSafe(own);
        assertEquals(id.longValue(), own.path("id").asLong());
        getAs("/members/" + id, reader, 403);
        getAs("/members/1", reader, 403);
        getAs("/members", reader, 403);
        for (String path : List.of("/members/" + id + "/source-preview", "/members/" + id + "/changes")) {
            invoke("GET", path, null, "librarian", 403); getAs(path, reader, 403);
        }
        invoke("POST", "/members", request(), "librarian", 403);
        invoke("PUT", "/members/" + id, edit(members.detail(id)), "librarian", 403);
        mvc.perform(get("/members")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingProfileDoesNotWriteOrInventZeroUnlimitedDatesOrChildren() throws Exception {
        User reader = account("bare_" + suffix(), "READER");
        JsonNode value = getAs("/members/me", reader, 200);
        assertFalse(value.path("profilePresent").asBoolean());
        assertEquals("UNKNOWN", value.path("usageType").asText());
        assertEquals("UNKNOWN", value.path("serviceStatus").asText());
        assertEquals(0, value.path("revision").asLong());
        for (String field : List.of("usedUses", "totalUses", "remainingUses", "serviceStart", "serviceEnd")) {
            assertTrue(value.has(field)); assertTrue(value.get(field).isNull());
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_profile WHERE user_id=?", Integer.class, reader.getId()));
        MemberUpdateRequest update = edit(members.detail(reader.getId()));
        invoke("PUT", "/members/" + reader.getId(), update, "admin", 200);
        assertEquals(1, members.summary(reader.getId()).getRevision());
        invoke("GET", "/members/1", null, "admin", 400); // An ADMIN account is not a member.
    }

    @Test
    void displaysShanghaiDateBoundsAndUnknownLimitedZeroUnlimitedAndOveruseIndependentlyOfAccountStatus() {
        MemberDetail member = members.create(request(), 1L);
        Long id = member.getId();
        jdbc.update("UPDATE member_profile SET service_start=?,service_end=?,usage_type='LIMITED',total_uses=0,used_uses=2 WHERE user_id=?", today, today, id);
        MemberSummary summary = members.summary(id);
        assertEquals("ACTIVE", summary.getServiceStatus()); assertEquals(-2, summary.getRemainingUses()); assertTrue(summary.isOverused());
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=?", id);
        assertEquals("ACTIVE", members.summary(id).getServiceStatus()); assertEquals(0, members.summary(id).getAccountStatus());
        jdbc.update("UPDATE member_profile SET service_start=?,service_end=? WHERE user_id=?", today.plusDays(1), today.plusDays(30), id);
        assertEquals("WAITING", members.summary(id).getServiceStatus());
        jdbc.update("UPDATE member_profile SET service_start=?,service_end=? WHERE user_id=?", today.minusDays(30), today.minusDays(1), id);
        assertEquals("EXPIRED", members.summary(id).getServiceStatus());
        jdbc.update("UPDATE member_profile SET service_start=NULL,usage_type='UNLIMITED',total_uses=NULL WHERE user_id=?", id);
        assertEquals("UNKNOWN", members.summary(id).getServiceStatus()); assertNull(members.summary(id).getRemainingUses());
        jdbc.update("UPDATE member_profile SET usage_type='UNKNOWN',used_uses=NULL WHERE user_id=?", id);
        assertEquals("UNKNOWN", members.summary(id).getUsageType()); assertNull(members.summary(id).getUsedUses());
        assertEquals(today, members.summary(id).getAsOfDate());
        assertEquals(1, members.list(1, 10, member.getUsername(), 0, "UNKNOWN").getTotal());
    }

    @Test
    void onlyProvenHuibenArchivesEnableCompatibilityAndReadsNeverRewriteTotals() throws Exception {
        Long unlimited = legacy(null, true), finite = legacy(0, true), unproven = legacy(null, false);
        assertEquals("UNLIMITED", members.summary(unlimited).getUsageType());
        assertEquals("LIMITED", members.summary(finite).getUsageType());
        assertEquals(0, members.summary(finite).getTotalUses());
        assertEquals("UNKNOWN", members.summary(unproven).getUsageType());
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT usage_type FROM member_profile WHERE user_id=?", String.class, unlimited));
        assertNull(jdbc.queryForObject("SELECT total_uses FROM member_profile WHERE user_id=?", Integer.class, unlimited));
        MemberUpdateRequest reviewedUnknown = edit(members.detail(unlimited));
        reviewedUnknown.setUsageType("UNKNOWN"); members.update(unlimited, reviewedUnknown, 1L);
        assertEquals("UNKNOWN", members.summary(unlimited).getUsageType(), "A manual unknown choice must not be inferred back to unlimited");
    }

    @Test
    void fourIdentitySearchesReturnSharedPhoneMembersWithoutMergingAndBorrowersExcludeStaffDisabledAndDeleted() throws Exception {
        String shared = "13900000001";
        MemberCreateRequest a = request(); a.setPhone(shared); a.setCardNo("000Card" + suffix());
        MemberDetail first = members.create(a, 1L);
        MemberCreateRequest b = request(); b.setPhone(shared); MemberDetail second = members.create(b, 1L);
        User staff = account("staff_" + suffix(), "ADMIN"); jdbc.update("UPDATE sys_user SET phone=? WHERE id=?", shared, staff.getId());
        for (String search : List.of(shared, first.getCardNo(), first.getUsername())) {
            JsonNode found = invoke("GET", "/members?keyword=" + search, null, "librarian", 200);
            assertTrue(found.path("total").asLong() >= 1);
        }
        JsonNode found = invoke("GET", "/users/borrowers?keyword=" + shared, null, "librarian", 200);
        assertEquals(2, found.path("total").asInt());
        assertTrue(found.path("records").get(0).has("phone")); assertTrue(found.path("records").get(0).has("cardNo") || found.path("records").get(1).has("cardNo"));
        assertSafe(found.path("records").get(0));
        jdbc.update("UPDATE sys_user SET status=0 WHERE id=?", second.getId());
        assertEquals(1, invoke("GET", "/users/borrowers?keyword=" + shared, null, "librarian", 200).path("total").asInt());
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", first.getId());
        assertEquals(0, invoke("GET", "/users/borrowers?keyword=" + shared, null, "librarian", 200).path("total").asInt());
        assertTrue(invoke("GET", "/users?keyword=" + shared, null, "admin", 200).path("total").asInt() >= 1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void revisionAndForeignChildErrorsRollbackEveryFieldAndAudit() throws Exception {
        MemberDetail a = members.create(request(), 1L), b = members.create(request(), 1L);
        MemberUpdateRequest stale = edit(a); stale.setRevision(0L); stale.setRealName("不应保存");
        invoke("PUT", "/members/" + a.getId(), stale, "admin", 409);
        assertEquals(a.getRealName(), members.summary(a.getId()).getRealName());
        MemberUpdateRequest bad = edit(a); bad.setCardNo("rollback-" + suffix()); bad.setRealName("不应保存");
        MemberChildRequest child = child("宝宝", null, "UNKNOWN", "原年龄未知"); child.setId(999999L); bad.setChildren(List.of(child));
        invoke("PUT", "/members/" + a.getId(), bad, "admin", 400);
        assertEquals(a.getRevision(), members.summary(a.getId()).getRevision());
        assertEquals(a.getRealName(), members.summary(a.getId()).getRealName());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_card_history WHERE card_no=?", Integer.class, bad.getCardNo().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(1, members.changes(a.getId(), 1, 10).getTotal());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void auditFailureRollsBackAccountProfileChildrenAndReservedCard() {
        MemberCreateRequest request = request(); request.setCardNo("audit-" + suffix());
        doThrow(new IllegalStateException("合成审计失败")).when(audit).save(anyLong(), anyLong(), anyString(), isNull(), any());
        assertThrows(IllegalStateException.class, () -> members.create(request, 1L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, request.getUsername()));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_card_history WHERE card_no=?", Integer.class, request.getCardNo().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void cardNumbersKeepLeadingZeroAndExactCaseAndCannotBeReassignedAfterRetirementOrDeletion() throws Exception {
        String card = "000Ab" + suffix();
        MemberCreateRequest request = request(); request.setCardNo(card); MemberDetail a = members.create(request, 1L);
        MemberCreateRequest caseDifferent = request(); caseDifferent.setCardNo(card.toLowerCase(Locale.ROOT));
        members.create(caseDifferent, 1L); // Exact string comparison, not MySQL's default case folding.
        MemberUpdateRequest retire = edit(a); retire.setCardNo(null); members.update(a.getId(), retire, 1L);
        jdbc.update("UPDATE sys_user SET deleted=1 WHERE id=?", a.getId());
        MemberCreateRequest reused = request(); reused.setCardNo(card);
        invoke("POST", "/members", reused, "admin", 409);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, reused.getUsername()));
    }

    @Test
    void readonlySecurityAndFinancialFieldsAreRejectedAndDatesKeepTheirKnownPrecision() throws Exception {
        MemberDetail member = members.create(request(), 1L);
        for (String field : List.of("password", "username", "role", "status", "accountStatus", "paidFee", "depositAmount", "financialFacts", "sourceArchiveId")) {
            var body = json.valueToTree(edit(member)); ((com.fasterxml.jackson.databind.node.ObjectNode) body).put(field, "forbidden");
            invoke("PUT", "/members/" + member.getId(), body, "admin", 400);
        }
        MemberUpdateRequest invalid = edit(member); invalid.setServiceStart(today.plusDays(5)); invalid.setServiceEnd(today);
        invoke("PUT", "/members/" + member.getId(), invalid, "admin", 400);
        invalid = edit(member); invalid.setChildren(List.of(child("宝宝", today.minusYears(2), "YEAR", "2024年")));
        invoke("PUT", "/members/" + member.getId(), invalid, "admin", 400);
        invalid = edit(member); invalid.setUsageType("LIMITED"); invalid.setTotalUses(null);
        invoke("PUT", "/members/" + member.getId(), invalid, "admin", 400);
        assertEquals(member.getRevision(), members.summary(member.getId()).getRevision());
    }

    @Test
    void sourcePreviewUsesOnlyConfirmedMappingAndNeverWritesOrInfersChildrenFromAge() throws Exception {
        Long id = legacy(null, true);
        String before = json.writeValueAsString(members.detail(id));
        JsonNode preview = invoke("GET", "/members/" + id + "/source-preview", null, "admin", 200);
        assertTrue(preview.path("sourceAvailable").asBoolean());
        Set<String> mapped = new HashSet<>(); preview.path("fields").forEach(field -> mapped.add(field.path("field").asText()));
        assertEquals(Set.of("realName", "phone", "cardNo"), mapped);
        assertTrue(preview.path("unmappedFields").toString().contains("宝宝年龄"));
        assertEquals(before, json.writeValueAsString(members.detail(id)));
        assertEquals(0, members.changes(id, 1, 10).getTotal());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_child WHERE user_id=?", Integer.class, id));
    }

    @Test
    void crossEntryContactChangesInvalidateMemberFormsWithAndWithoutAnExistingProfile() throws Exception {
        for (boolean profile : List.of(false, true)) {
            Long id = profile ? members.create(request(), 1L).getId() : account("bare_" + suffix(), "READER").getId();
            MemberDetail beforeAccountEdit = members.detail(id);
            User current = users.selectById(id);
            UserRequest accountEdit = new UserRequest();
            accountEdit.setId(id); accountEdit.setUsername(current.getUsername()); accountEdit.setRealName("账号修改姓名");
            accountEdit.setPhone("13000000007"); accountEdit.setRole("READER");
            invoke("PUT", "/users", accountEdit, "admin", 200);
            invoke("PUT", "/members/" + id, edit(beforeAccountEdit), "admin", 409);
            assertEquals("13000000007", members.summary(id).getPhone());
            assertEquals(beforeAccountEdit.getRevision() + 1, members.summary(id).getRevision());
            MemberDetail beforeSelfEdit = members.detail(id);
            current = users.selectById(id);
            long tokenVersion = current.getTokenVersion();
            mvc.perform(put("/profile").header("Authorization", bearer(current)).contentType("application/json")
                    .content("{\"phone\":\"13000000008\"}"))
                    .andExpect(status().isOk());
            invoke("PUT", "/members/" + id, edit(beforeSelfEdit), "admin", 409);
            assertEquals("13000000008", members.summary(id).getPhone());
            assertEquals(beforeSelfEdit.getRevision() + 1, members.summary(id).getRevision());
            assertEquals(tokenVersion, users.selectById(id).getTokenVersion());
            if (!profile) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_profile WHERE user_id=?", Integer.class, id));
        }
    }

    @Test
    void accountStatusChangesInvalidateOpenMemberFormsAndMatrixReflectsTheReadAndSensitiveBoundaries() throws Exception {
        MemberDetail member = members.create(request(), 1L);
        mvc.perform(put("/users/" + member.getId() + "/status").param("status", "0").header("Authorization", bearer(user("admin"))))
                .andExpect(status().isOk());
        invoke("PUT", "/members/" + member.getId(), edit(member), "admin", 409);
        assertEquals(0, members.summary(member.getId()).getAccountStatus());
        JsonNode matrix = invoke("GET", "/permissions/matrix", null, "admin", 200);
        Map<String, JsonNode> items = new HashMap<>(); matrix.forEach(item -> items.put(item.path("key").asText(), item));
        assertTrue(items.get("member_manage").path("LIBRARIAN").asBoolean());
        assertFalse(items.get("member_edit").path("LIBRARIAN").asBoolean());
        assertFalse(items.get("member_sensitive").path("READER").asBoolean());
        assertTrue(items.get("member_sensitive").path("ADMIN").asBoolean());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoConcurrentRevisionsHaveExactlyOneWinnerAndNoLostUpdate() throws Exception {
        MemberDetail old = members.create(request(), 1L);
        List<Boolean> results = parallel(() -> { members.update(old.getId(), edit(old), 1L); return true; });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(old.getRevision() + 1, members.summary(old.getId()).getRevision());
        assertEquals(2, members.changes(old.getId(), 1, 10).getTotal());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDuplicateCardReservationHasOneWinnerAndRollsBackTheOtherAccount() throws Exception {
        String card = "concurrent-" + suffix();
        var names = new ConcurrentLinkedQueue<String>();
        List<Boolean> results = parallel(() -> { MemberCreateRequest r = request(); r.setCardNo(card); names.add(r.getUsername()); members.create(r, 1L); return true; });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM member_profile WHERE card_no=?", Integer.class, card));
        int accounts = names.stream().mapToInt(name -> jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Integer.class, name)).sum();
        assertEquals(1, accounts);
    }

    private List<Boolean> parallel(Callable<Boolean> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) tasks.add(pool.submit(() -> { start.await(); try { return work.call(); } catch (MemberConflictException expected) { return false; } }));
            start.countDown(); List<Boolean> values = new ArrayList<>();
            for (Future<Boolean> f : tasks) values.add(f.get(30, TimeUnit.SECONDS)); return values;
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private void assertSafe(JsonNode value) {
        for (String secret : List.of("financialFacts", "paidFee", "depositAmount", "depositStatus", "address", "notes", "children", "sourceKey", "sourceSystem", "sourceArchiveId", "borrowHistory", "password", "tokenVersion"))
            assertFalse(value.has(secret), secret);
    }
    private MemberCreateRequest request() {
        MemberCreateRequest r = new MemberCreateRequest(); r.setUsername("member_" + suffix()); r.setPassword("SyntheticReader123");
        r.setRealName("合成会员"); r.setPhone("13000000000"); r.setEmail("synthetic@example.test");
        r.setUsageType("UNKNOWN"); r.setChangeReason("合成资料维护"); return r;
    }
    private MemberUpdateRequest edit(MemberDetail value) {
        MemberUpdateRequest r = new MemberUpdateRequest(); BeanUtils.copyProperties(value, r); r.setRevision(value.getRevision()); r.setChangeReason("合成资料维护"); return r;
    }
    private MemberChildRequest child(String name, LocalDate date, String precision, String source) {
        MemberChildRequest c = new MemberChildRequest(); c.setName(name); c.setBirthDate(date); c.setBirthDatePrecision(precision); c.setSourceBirthText(source); return c;
    }
    private User account(String name, String role) {
        User u = new User(); u.setUsername(name); u.setPassword("synthetic-value"); u.setRealName("合成会员"); u.setPhone("13000000000"); u.setRole(role); u.setStatus(1); users.insert(u); return u;
    }
    private Long legacy(Integer total, boolean archive) throws Exception {
        String uid = suffix(); User reader = account("huiben_" + uid, "READER");
        jdbc.update("INSERT INTO member_profile(user_id,source_key,total_uses,used_uses,paid_fee,deposit_amount,deposit_status) VALUES (?,?,?,3,58.25,20.50,'原缴纳事实')", reader.getId(), "huiben:" + uid, total);
        if (archive) jdbc.update("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER',?,'合成原始资料',?,?)",
                uid, json.writeValueAsString(Map.of("会员名字", "原始姓名", "手机号", "13000000001", "卡号", "000123", "宝宝年龄", "三岁")), reader.getId());
        return reader.getId();
    }
    private String suffix() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }
    private String bearer(User user) { return "Bearer " + tokens.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion()); }
    private User user(String name) { return users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>().eq(User::getUsername, name)); }
    private JsonNode getAs(String path, User user, int expected) throws Exception {
        String body = mvc.perform(get(path).header("Authorization", bearer(user))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }
    private JsonNode invoke(String method, String path, Object body, String account, int expected) throws Exception {
        var builder = switch (method) { case "POST" -> post(path); case "PUT" -> put(path); default -> get(path); };
        builder.header("Authorization", bearer(user(account)));
        if (body != null) builder.contentType("application/json").content(json.writeValueAsString(body));
        String text = mvc.perform(builder).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(text).path("data");
    }
}
