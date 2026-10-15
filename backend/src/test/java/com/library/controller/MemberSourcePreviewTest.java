package com.library.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.dto.*;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.security.JwtTokenProvider;
import com.library.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
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
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:member-source-preview;DB_CLOSE_DELAY=-1;MODE=MYSQL")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MemberSourcePreviewTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberService members;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @Test
    void threeSlotsKeepIndependentIdentityAndSourceValueHasNoBusinessId() throws Exception {
        ObjectNode archive = json.createObjectNode();
        archive.put("宝宝名字", "合成 小宝").put("宝宝生日", "2020-02-29").put("宝宝性别", "合成性别").put("宝宝年龄", "六岁");
        archive.put("二宝名字", "合成小宝").put("二宝生日", "2022-08-03");
        archive.put("三宝性别", "合成性别").put("三宝年龄", "未设置生日");
        Long id = legacy(archive);
        JsonNode preview = preview(id);
        assertEquals(today.toString(), preview.path("asOfDate").asText());
        assertEquals(3, preview.path("childCandidates").size());
        String source = members.detail(id).getSourceKey();
        for (int slot : List.of(1, 2)) {
            JsonNode item = candidate(preview, slot);
            assertEquals(source + ":child:" + slot, item.path("sourceKey").asText());
            assertEquals(slot, item.path("sourceSlot").asInt());
            assertEquals(4, item.path("sourceFields").size());
            assertTrue(item.path("selectable").asBoolean());
            assertEquals("READY", item.path("reasonCode").asText());
            assertEquals("DAY", item.path("sourceValue").path("birthDatePrecision").asText());
            assertFalse(item.path("sourceValue").has("id"));
            assertTrue(item.get("currentValue").isNull());
        }
        assertEquals("合成 小宝", candidate(preview, 1).path("sourceValue").path("name").asText());
        assertEquals("六岁", candidate(preview, 1).path("reviewOnlyAge").asText());
        assertEquals("INSUFFICIENT_CHILD_IDENTITY", candidate(preview, 3).path("reasonCode").asText());
        assertFalse(candidate(preview, 3).path("selectable").asBoolean());
        assertTrue(preview.path("unmappedFields").toString().contains("三宝性别"));
        assertTrue(preview.path("unmappedFields").toString().contains("宝宝年龄"));
        assertFalse(preview.path("unmappedFields").toString().contains("宝宝生日"));
    }

    @Test
    void ageOrGenderWithoutNameAndDateNeverCreatesASelectableChild() throws Exception {
        Long id = legacy(json.createObjectNode().put("宝宝年龄", "五岁").put("宝宝性别", "合成性别"));
        JsonNode first = candidate(preview(id), 1);
        assertEquals("INSUFFICIENT_CHILD_IDENTITY", first.path("reasonCode").asText());
        assertFalse(first.path("selectable").asBoolean());
        assertTrue(first.path("sourceValue").get("birthDate").isNull());
        assertTrue(first.path("sourceValue").get("sourceBirthText").isNull());
        assertEquals("UNKNOWN", first.path("sourceValue").path("birthDatePrecision").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM member_child WHERE user_id=?", Integer.class, id));
    }

    @Test
    void invalidPartialFutureAndNumericDatesBlockTheWholeCandidateWithoutInventingADay() throws Exception {
        for (String raw : List.of("2021-02-29", "2020-02", "2020", "三岁", "2020/02/29", "2020-02-29 00:00:00", "0000-02-29", today.plusDays(1).toString())) {
            Long id = legacy(json.createObjectNode().put("宝宝名字", "合成宝宝").put("宝宝生日", raw));
            JsonNode item = candidate(preview(id), 1);
            assertFalse(item.path("selectable").asBoolean(), raw);
            assertEquals(raw.equals(today.plusDays(1).toString()) ? "FUTURE_SOURCE_DATE" : "INVALID_SOURCE_DATE", item.path("reasonCode").asText());
            assertTrue(item.path("sourceValue").get("birthDate").isNull(), raw);
            assertEquals("UNKNOWN", item.path("sourceValue").path("birthDatePrecision").asText());
            assertEquals(raw, item.path("sourceValue").path("sourceBirthText").asText());
            assertTrue(preview(id).path("unmappedFields").toString().contains("宝宝生日"));
        }
        Long numeric = legacy(json.createObjectNode().put("宝宝名字", "合成宝宝").put("宝宝生日", 45000));
        JsonNode item = candidate(preview(numeric), 1);
        assertEquals("INVALID_SOURCE_TYPE", item.path("reasonCode").asText());
        assertTrue(item.path("sourceValue").get("sourceBirthText").isNull());
    }

    @Test
    void validNameOnlyAndValidDateOnlyAreExplicitCandidatesButBadTypesAndLengthsAreNot() throws Exception {
        Long valid = legacy(json.createObjectNode().put("宝宝名字", "合成宝宝").put("二宝生日", "2020-02-29"));
        assertTrue(candidate(preview(valid), 1).path("selectable").asBoolean());
        assertEquals("UNKNOWN", candidate(preview(valid), 1).path("sourceValue").path("birthDatePrecision").asText());
        assertTrue(candidate(preview(valid), 2).path("selectable").asBoolean());
        for (String field : List.of("宝宝名字", "宝宝生日", "宝宝性别", "宝宝年龄")) {
            ObjectNode source = json.createObjectNode().put("宝宝名字", "合成宝宝"); source.set(field, json.createObjectNode().put("unsupported", true));
            JsonNode item = candidate(preview(legacy(source)), 1);
            assertEquals("INVALID_SOURCE_TYPE", item.path("reasonCode").asText()); assertFalse(item.path("selectable").asBoolean());
        }
        Map<String, Integer> bounds = Map.of("宝宝名字", 101, "宝宝生日", 301, "宝宝性别", 51, "宝宝年龄", 301);
        for (var bound : bounds.entrySet()) {
            ObjectNode source = json.createObjectNode().put("宝宝名字", "合成宝宝").put(bound.getKey(), "字".repeat(bound.getValue()));
            assertEquals("SOURCE_TOO_LONG", candidate(preview(legacy(source)), 1).path("reasonCode").asText());
        }
    }

    @Test
    void currentChildrenMatchExactSourceKeyAndNeverBySameName() throws Exception {
        Long id = legacy(json.createObjectNode().put("宝宝名字", "合成同名").put("宝宝生日", "2020-02-29"));
        String key = members.detail(id).getSourceKey() + ":child:1";
        jdbc.update("INSERT INTO member_child(user_id,name,notes) VALUES (?,?,'人工备注')", id, "合成同名");
        assertTrue(candidate(preview(id), 1).get("currentValue").isNull());
        jdbc.update("INSERT INTO member_child(user_id,name,source_key,notes) VALUES (?,?,?,'来源记录人工备注')", id, "已有名字", key);
        JsonNode item = candidate(preview(id), 1);
        assertTrue(item.path("currentValue").has("id"));
        assertEquals("来源记录人工备注", item.path("currentValue").path("notes").asText());
        assertTrue(item.path("conflict").asBoolean());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM member_child WHERE user_id=?", Integer.class, id));
        jdbc.update("INSERT INTO member_child(user_id,name,source_key) VALUES (?,?,?)", id, "重复来源", key);
        item = candidate(preview(id), 1);
        assertEquals("DUPLICATE_CURRENT_SOURCE_KEY", item.path("reasonCode").asText()); assertFalse(item.path("selectable").asBoolean());
    }

    @Test
    void blankAddressAndNotesCannotClearKnownValuesAndUnsupportedScalarsRemainUnmapped() throws Exception {
        ObjectNode source = json.createObjectNode().putNull("地址").put("备注", "  ").put("会员昵称", "原昵称").put("推荐人", "合成推荐信息");
        Long id = legacy(source);
        jdbc.update("UPDATE member_profile SET address='现有地址',notes='现有备注' WHERE user_id=?", id);
        JsonNode preview = preview(id);
        for (String field : List.of("address", "notes")) {
            JsonNode item = field(preview, field);
            assertEquals("SOURCE_EMPTY", item.path("reasonCode").asText());
            assertFalse(item.path("selectable").asBoolean()); assertTrue(item.path("reviewOnly").asBoolean());
            assertEquals(field.equals("address") ? "现有地址" : "现有备注", item.path("currentValue").asText());
        }
        assertTrue(preview.path("unmappedFields").toString().contains("地址"));
        assertTrue(preview.path("unmappedFields").toString().contains("会员昵称"));
        assertTrue(preview.path("unmappedFields").toString().contains("推荐人"));
        for (String key : List.of("地址", "备注")) {
            ObjectNode scalar = json.createObjectNode().put(key, "合成文本\n保留换行");
            assertTrue(field(preview(legacy(scalar)), key.equals("地址") ? "address" : "notes").path("selectable").asBoolean());
            scalar.put(key, 7);
            assertEquals("INVALID_SOURCE_TYPE", field(preview(legacy(scalar)), key.equals("地址") ? "address" : "notes").path("reasonCode").asText());
            scalar.put(key, "字".repeat(key.equals("地址") ? 501 : 2001));
            assertEquals("SOURCE_TOO_LONG", field(preview(legacy(scalar)), key.equals("地址") ? "address" : "notes").path("reasonCode").asText());
        }
    }

    @Test
    void previewPreservesLegacyFieldsAndNeverOffersTotalUsePrefillOrWritesAnyBusinessData() throws Exception {
        for (JsonNode total : List.of(json.nullNode(), json.getNodeFactory().numberNode(0), json.getNodeFactory().numberNode(12), json.getNodeFactory().textNode("不限"))) {
            ObjectNode source = json.createObjectNode().put("会员名字", "来源姓名").put("手机号", "13000000001").put("卡号", "000123")
                    .put("借阅计划类型", "来源套餐").put("服务开始时间", "2020-01-01").put("服务结束时间", "2030-01-01").put("已借阅次数", 0).put("宝宝名字", "来源宝宝");
            source.set("总次数", total); Long id = legacy(source);
            String before = json.writeValueAsString(members.detail(id));
            String archive = jdbc.queryForObject("SELECT details_json FROM legacy_record WHERE user_id=?", String.class, id);
            JsonNode preview = preview(id);
            Set<String> fields = new HashSet<>(); preview.path("fields").forEach(v -> fields.add(v.path("field").asText()));
            assertEquals(Set.of("realName", "phone", "cardNo", "planName", "serviceStart", "serviceEnd", "usedUses", "totalUses"), fields);
            JsonNode item = field(preview, "totalUses");
            assertTrue(item.path("reviewOnly").asBoolean()); assertFalse(item.path("selectable").asBoolean());
            assertEquals("REVIEW_ONLY_TOTAL_USES", item.path("reasonCode").asText());
            assertEquals(before, json.writeValueAsString(members.detail(id)));
            assertEquals(archive, jdbc.queryForObject("SELECT details_json FROM legacy_record WHERE user_id=?", String.class, id));
            assertEquals(0, members.changes(id, 1, 10).getTotal());
        }
    }

    @Test
    void duplicateNonemptyChildSourceKeysAreRejectedAtomicallyButManualForeignKeysRemainCompatible() throws Exception {
        Long id = legacy(json.createObjectNode().put("宝宝名字", "来源宝宝"));
        MemberDetail before = members.detail(id); MemberUpdateRequest request = edit(before);
        MemberChildRequest first = child("合成甲", "external:manual:1"), second = child("合成乙", "external:manual:1");
        request.setChildren(List.of(first, second));
        assertThrows(IllegalArgumentException.class, () -> members.update(id, request, 1L));
        assertEquals(json.writeValueAsString(before), json.writeValueAsString(members.detail(id)));
        assertEquals(0, members.changes(id, 1, 10).getTotal());
        second.setSourceKey("EXTERNAL:manual:1"); // Exact keys retain existing foreign/manual compatibility.
        request.setChildren(List.of(first, second, child("合成丙", null), child("合成丁", null)));
        MemberDetail saved = members.update(id, request, 1L);
        assertEquals(4, saved.getChildren().size());
        MemberUpdateRequest repeat = edit(saved);
        members.update(id, repeat, 1L);
        assertEquals(4, members.detail(id).getChildren().size());
    }

    @Test
    void onlyAdminsCanReadFamilySourceAndMismatchedArchiveIdentityIsRejected() throws Exception {
        Long id = legacy(json.createObjectNode().put("宝宝名字", "来源宝宝"));
        for (String role : List.of("LIBRARIAN", "READER")) {
            User user = account(role);
            mvc.perform(get("/members/" + id + "/source-preview").header("Authorization", bearer(user))).andExpect(status().isForbidden());
        }
        mvc.perform(get("/members/" + id + "/source-preview")).andExpect(status().isUnauthorized());
        Long archiveId = members.detail(id).getSourceArchiveId();
        jdbc.update("UPDATE member_profile SET source_system='huiben',source_archive_id=? WHERE user_id=?", archiveId, id);
        jdbc.update("UPDATE legacy_record SET source_key='other-source' WHERE id=?", archiveId);
        assertThrows(IllegalArgumentException.class, () -> members.sourcePreview(id));
    }

    private Long legacy(ObjectNode archive) throws Exception {
        User reader = account("READER"); String uid = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO member_profile(user_id,source_key,used_uses,total_uses,paid_fee,deposit_amount) VALUES (?,?,0,NULL,0,0)", reader.getId(), "huiben:" + uid);
        jdbc.update("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER',?,'合成来源',?,?)", uid, json.writeValueAsString(archive), reader.getId());
        return reader.getId();
    }
    private User account(String role) {
        User value = new User(); value.setUsername("source_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        value.setRealName("合成会员"); value.setPassword("synthetic-only"); value.setRole(role); value.setStatus(1); users.insert(value); return value;
    }
    private JsonNode preview(Long id) throws Exception {
        User admin = users.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>().eq(User::getUsername, "admin"));
        String body = mvc.perform(get("/members/" + id + "/source-preview").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).path("data");
    }
    private JsonNode candidate(JsonNode preview, int slot) { return preview.path("childCandidates").get(slot - 1); }
    private JsonNode field(JsonNode preview, String name) {
        for (JsonNode field : preview.path("fields")) if (field.path("field").asText().equals(name)) return field;
        fail("Missing source field " + name); return null;
    }
    private MemberUpdateRequest edit(MemberDetail value) {
        MemberUpdateRequest request = new MemberUpdateRequest(); BeanUtils.copyProperties(value, request);
        request.setRevision(value.getRevision()); request.setChangeReason("合成来源审阅"); return request;
    }
    private MemberChildRequest child(String name, String source) {
        MemberChildRequest child = new MemberChildRequest(); child.setName(name); child.setSourceKey(source); return child;
    }
    private String bearer(User user) { return "Bearer " + tokens.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion()); }
}
