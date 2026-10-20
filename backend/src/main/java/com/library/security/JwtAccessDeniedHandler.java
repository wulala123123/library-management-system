package com.library.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.common.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (com.library.finance.FinanceSupport.isFinancePath(request)) {
            com.library.finance.FinanceSupport.securityError(response,403,"ADMIN_REQUIRED"); return;
        }
        String uri = request.getRequestURI();
        log.warn("权限不足: uri={}, message={}", uri, accessDeniedException.getMessage());

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        Result<Map<String, Object>> result = Result.error(403, "权限不足，无法访问该资源");
        result.setData(Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "path", uri,
                "error", "Forbidden"
        ));

        ObjectMapper mapper = new ObjectMapper();
        response.getWriter().write(mapper.writeValueAsString(result));
    }
}
