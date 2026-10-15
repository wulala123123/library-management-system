package com.library.security;

import com.library.config.DataInitializer;
import com.library.dto.*;
import com.library.entity.User;
import com.library.mapper.UserMapper;
import com.library.service.CaptchaService;
import com.library.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityRegressionTest {
    @Autowired MockMvc mvc;
    @Autowired UserService users;
    @Autowired UserMapper mapper;
    @Autowired CaptchaService captchas;
    @Autowired JwtTokenProvider tokens;
    @Autowired DataInitializer initializer;

    private User account() {
        UserRequest req = new UserRequest();
        req.setUsername("sec_" + UUID.randomUUID().toString().substring(0, 8));
        req.setPassword("ReviewPassword123");
        req.setRealName("测试用户");
        req.setRole("ADMIN");
        return users.create(req);
    }

    private LoginRequest loginRequest(String username, String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        String key = captchas.generateCaptcha().getData().captchaKey();
        request.setCaptchaKey(key);
        request.setCaptchaCode(captchas.getCaptchaStore().get(key).code);
        return request;
    }

    private String token(User u) {
        return tokens.generateToken(u.getId(), u.getUsername(), "ADMIN", u.getTokenVersion());
    }

    private void rejected(String token) throws Exception {
        mvc.perform(get("/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test void anonymousRecoveryIsClosed() throws Exception {
        for (String path : new String[]{"send-code", "reset"}) {
            mvc.perform(post("/auth/forgot-password/" + path)
                    .contentType("application/json")
                    .content("{\"phone\":\"13800138000\",\"code\":\"123456\",\"newPassword\":\"TakenOver123\"}"))
                    .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.data.code").doesNotExist());
        }
    }

    @Test void missingCaptchaCannotLogin() {
        LoginRequest req = new LoginRequest();
        req.setUsername("admin"); req.setPassword("admin123");
        assertThrows(RuntimeException.class, () -> users.login(req));
    }

    @Test void challengeIsConsumedEvenWhenWrong() {
        LoginRequest req = loginRequest("admin", "admin123");
        String correct = req.getCaptchaCode();
        req.setCaptchaCode("!!!!");
        assertThrows(RuntimeException.class, () -> users.login(req));
        req.setCaptchaCode(correct);
        assertThrows(RuntimeException.class, () -> users.login(req));
    }

    @Test void expiredChallengeCannotLogin() {
        LoginRequest req = loginRequest("admin", "admin123");
        captchas.getCaptchaStore().put(req.getCaptchaKey(),
                new CaptchaService.CaptchaEntry(req.getCaptchaCode(), Instant.now().toEpochMilli() - 300001));
        assertThrows(RuntimeException.class, () -> users.login(req));
    }

    @Test void successfulChallengeCannotBeReplayed() {
        LoginRequest req = loginRequest("admin", "admin123");
        assertNotNull(users.login(req).getToken());
        assertThrows(RuntimeException.class, () -> users.login(req));
    }

    @Test void restartPreservesChangedAdminPassword() {
        users.adminChangePassword(1L, "ChangedAdministrator123");
        String hash = mapper.selectById(1L).getPassword();
        initializer.run();
        assertEquals(hash, mapper.selectById(1L).getPassword());
        assertNotNull(users.login(loginRequest("admin", "ChangedAdministrator123")).getToken());
    }

    @Test void passwordChangeRevokesToken() throws Exception {
        User u = account(); String old = token(u);
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("ReviewPassword123"); req.setNewPassword("ChangedPassword123");
        users.changePassword(u.getId(), req);
        rejected(old);
    }

    @Test void resetRevokesToken() throws Exception {
        User u = account(); String old = token(u);
        users.resetPassword(u.getId()); rejected(old);
    }

    @Test void disableThenEnableDoesNotRestoreToken() throws Exception {
        User u = account(); String old = token(u);
        users.updateStatus(u.getId(), 0); rejected(old);
        users.updateStatus(u.getId(), 1); rejected(old);
    }

    @Test void deletedAccountCannotAuthenticate() throws Exception {
        User u = account(); String old = token(u);
        users.delete(u.getId()); rejected(old);
    }

    @Test void roleChangeRevokesTokenAndDatabaseRoleWins() throws Exception {
        User u = account(); String old = token(u);
        UserRequest update = new UserRequest(); update.setId(u.getId()); update.setRole("READER");
        users.update(update); rejected(old);
        User current = mapper.selectById(u.getId());
        mvc.perform(get("/users").header("Authorization", "Bearer " + token(current)))
                .andExpect(status().isForbidden());
    }

    @Test void legacyTokenWithoutVersionIsRejected() throws Exception {
        User u = account();
        String old = io.jsonwebtoken.Jwts.builder().subject(u.getId().toString()).claim("role", "ADMIN")
                .expiration(new java.util.Date(System.currentTimeMillis() + 60000))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "a3F4ZjhtN2s5cDJ3eHVpMm5nYjdsb2twMTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnN0dXZ3eHl6".getBytes()))
                .compact();
        rejected(old);
    }

    @Test void librarianCanSearchBorrowersWithoutAccessToPrivateUserFields() throws Exception {
        User librarian=mapper.selectById(2L);
        String token=tokens.generateToken(librarian.getId(),librarian.getUsername(),librarian.getRole(),librarian.getTokenVersion());
        mvc.perform(get("/users/borrowers").param("keyword","reader").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].username").value("reader"))
                .andExpect(jsonPath("$.data.records[0].phone").exists())
                .andExpect(jsonPath("$.data.records[0].email").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].paidFee").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].children").doesNotExist())
                .andExpect(jsonPath("$.data.records[0].password").doesNotExist());
        mvc.perform(get("/users").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
    }
}
