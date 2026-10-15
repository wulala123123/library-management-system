package com.library.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.entity.User;
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

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:legacy-member-contract;DB_CLOSE_DELAY=-1;MODE=MYSQL;DATABASE_TO_LOWER=TRUE")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LegacyMemberContractTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserMapper users;
    @Autowired JwtTokenProvider tokens;

    @BeforeEach
    void isolatedProfiles() {
        jdbc.update("DELETE FROM member_profile");
    }

    @Test
    void explicitUnlimitedHasAPresentNullFieldWithTheActualNonNullJacksonConfiguration() throws Exception {
        assertEquals(JsonInclude.Include.NON_NULL,
                json.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion());
        profile(null, false);
        JsonNode member = members().get(0);
        assertTrue(member.has("total_uses"), "Explicit unlimited must not become a missing/unknown field");
        assertTrue(member.get("total_uses").isNull());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 17})
    void finiteAndActualZeroRemainNumeric(int total) throws Exception {
        profile(total, false);
        JsonNode member = members().get(0);
        assertTrue(member.has("total_uses"));
        assertTrue(member.get("total_uses").isIntegralNumber());
        assertEquals(total, member.get("total_uses").intValue());
    }

    @Test
    void retainsAllOtherOriginalColumnsValuesAndDeletedUserFiltering() throws Exception {
        Long userId = profile(null, false);
        profile(9, true);
        Map<String, Object> original = jdbc.queryForMap("SELECT p.*, u.real_name, u.phone, u.username "
                + "FROM member_profile p JOIN sys_user u ON p.user_id=u.id WHERE p.user_id=?", userId);
        ObjectNode expected = (ObjectNode) json.readTree(json.writeValueAsBytes(original));
        expected.putNull("total_uses");
        JsonNode result = members();
        assertEquals(1, result.size());
        assertEquals(expected, result.get(0), "Only the explicit total_uses null inclusion should change");
        assertEquals("000123", result.get(0).path("card_no").asText());
        assertEquals("huiben:synthetic-active", result.get(0).path("source_key").asText());
        assertFalse(result.get(0).has("password"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"librarian", "reader"})
    void originalMemberDataRemainsAdminOnly(String username) throws Exception {
        profile(null, false);
        mvc.perform(get("/legacy-data/members").header("Authorization", bearer(username)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestsRemainUnauthorized() throws Exception {
        mvc.perform(get("/legacy-data/members")).andExpect(status().isUnauthorized());
    }

    private JsonNode members() throws Exception {
        String body = mvc.perform(get("/legacy-data/members").header("Authorization", bearer("admin")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode response = json.readTree(body);
        assertEquals(200, response.path("code").asInt());
        return response.path("data");
    }

    private String bearer(String username) {
        User user = users.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return "Bearer " + tokens.generateToken(user.getId(), user.getUsername(), user.getRole(), user.getTokenVersion());
    }

    private Long profile(Integer totalUses, boolean deleted) {
        User user = new User();
        user.setUsername("legacy-contract-" + (deleted ? "deleted" : "active"));
        user.setPassword("synthetic-test-value");
        user.setRealName("合成会员");
        user.setPhone("13000000000");
        user.setRole("READER");
        user.setStatus(1);
        user.setDeleted(deleted ? 1 : 0);
        users.insert(user);
        jdbc.update("INSERT INTO member_profile (user_id,source_key,card_no,plan_name,service_start,service_end,"
                        + "used_uses,total_uses,deposit_amount,deposit_status,paid_fee) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                user.getId(), "huiben:synthetic-" + (deleted ? "deleted" : "active"), "000123", "合成套餐",
                "2020-01-02", "2020-02-28", 3, totalUses, "30.00", "合成缴纳状态", "120.50");
        return user.getId();
    }
}
