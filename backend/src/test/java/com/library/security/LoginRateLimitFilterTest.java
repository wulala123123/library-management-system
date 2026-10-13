package com.library.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimitFilterTest {
    @Test void spoofedForwardedHeadersCannotBypassLimit() throws Exception {
        LoginRateLimitFilter filter = new LoginRateLimitFilter();
        ReflectionTestUtils.setField(filter, "maxAttempts", 1);
        ReflectionTestUtils.setField(filter, "windowSeconds", 300);
        ReflectionTestUtils.setField(filter, "trustedProxies", "");
        for (int i = 0; i < 2; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
            request.setRemoteAddr("192.0.2.1");
            request.addHeader("X-Forwarded-For", "198.51.100." + i);
            request.addHeader("X-Real-IP", "198.51.100." + i);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {});
            assertEquals(i == 0 ? 200 : 429, response.getStatus());
        }
    }

    @Test void missingOrShortProductionSecretFails() {
        JwtTokenProvider provider = new JwtTokenProvider();
        ReflectionTestUtils.setField(provider, "jwtSecret", "");
        assertThrows(IllegalStateException.class, provider::init);
        ReflectionTestUtils.setField(provider, "jwtSecret", "short");
        assertThrows(IllegalStateException.class, provider::init);
    }
}
