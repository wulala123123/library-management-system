package com.library.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@Component
public class XssSqlFilter extends OncePerRequestFilter {

    private static final Pattern XSS_PATTERN = Pattern.compile(
            "<script.*?>.*?</script>|javascript\\s*:|on\\w+\\s*=|eval\\s*\\(|expression\\s*\\(",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern SQL_INJECTION_PATTERN = Pattern.compile(
            "(?:'\\s*(?:or|and)\\s+.*?--)|(?:union\\s+(?:all\\s+)?select)|(?:insert\\s+into)|(?:delete\\s+from)|(?:drop\\s+table)|(?:update\\s+\\S+\\s+set)|(?:exec\\s*\\()|(?:execute\\s*\\()|(?:1\\s*=\\s*1)|(?:'\\s*;\\s*drop\\s)|(?:--\\s*$)|(?:/\\*.*?\\*/)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile(
            "(?:\\.\\./)|(?:\\.\\\\)|(?:%2e%2e%2f)|(?:%2e%2e/)|(?:..%2f)|(?:%2e%2e%5c)",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String pathInfo = request.getRequestURI();
        String queryString = request.getQueryString();
        boolean literalCatalog = "GET".equals(request.getMethod()) && "/catalog-books".equals(servletPath(request));
        boolean literalBarcode = "GET".equals(request.getMethod()) && "/book-copies".equals(servletPath(request));
        String[] barcodeValues = literalBarcode ? request.getParameterValues("barcode") : null;
        boolean duplicateBarcode = barcodeValues != null && barcodeValues.length != 1;
        if (containsSqlInjection(pathInfo) || containsPathTraversal(pathInfo)
                || duplicateBarcode
                || (literalCatalog ? unsafeNonCatalogParameters(request) : literalBarcode ? unsafeNonBarcodeParameters(request)
                    : queryString != null && (containsSqlInjection(queryString) || containsPathTraversal(queryString)))) {
            log.warn("请求路径检测到攻击模式，已拦截: path={}", pathInfo);
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"message\":\"请求包含非法字符\"}");
            return;
        }

        if (literalCatalog) { filterChain.doFilter(request, response); return; }

        if (literalBarcode) {
            filterChain.doFilter(new XssSqlRequestWrapper(request, true), response);
            return;
        }

        String contentType = request.getContentType();
        if (contentType != null && contentType.contains(MediaType.APPLICATION_JSON_VALUE)) {
            // These strict DTO endpoints treat barcodes and metadata as literal text. Rewriting JSON
            // would change identity (and the legacy chunk decoder may split a UTF-8 codepoint).
            // Keep the path/query checks above and leave all other routes on the existing wrappers.
            if (literalJsonRoute(request)) {
                filterChain.doFilter(request, response);
                return;
            }
            filterChain.doFilter(new XssSqlJsonRequestWrapper(request), response);
        } else {
            filterChain.doFilter(new XssSqlRequestWrapper(request), response);
        }
    }

    static boolean literalJsonRoute(HttpServletRequest request) {
        String path = servletPath(request);
        return ("POST".equals(request.getMethod()) &&
                (path.equals("/inventory-intakes/previews") || path.matches("/inventory-intakes/[0-9]+/confirm")
                || path.equals("/catalog-attributes/previews") || path.matches("/catalog-attributes/[0-9]+/confirm")
                || path.equals("/book-copy-actions/previews") || path.equals("/book-copy-actions/v2/previews") || path.matches("/book-copy-actions/[0-9]+/confirm")
                || path.equals("/stocktakes") || path.matches("/stocktakes/[0-9]+/(observations|corrections|coverage|change-checks|rechecks|prepares|confirm|cancel)")
                || path.equals("/stocktake-resolutions") || path.matches("/stocktake-resolutions/[0-9]+/(link|revoke)")
                || path.equals("/bookshelves") || path.equals("/bookshelves/storeys") || path.equals("/bookshelves/locations")))
                || ("PUT".equals(request.getMethod()) && (path.matches("/books/[0-9]+/metadata") || path.equals("/bookshelves") || path.equals("/bookshelves/storeys") || path.equals("/bookshelves/locations") || path.matches("/bookshelves/locations/[0-9]+/(clear|assign/[0-9]+)")))
                || ("DELETE".equals(request.getMethod()) && path.matches("/bookshelves/(?:storeys/|locations/)?[0-9]+"));
    }

    private static String servletPath(HttpServletRequest request) {
        String path = request.getServletPath();
        // Mock servlet requests and some servlet mappings expose only requestURI.
        if (path == null || path.isEmpty()) {
            String uri = request.getRequestURI(), context = request.getContextPath();
            if (uri == null || context == null || !uri.startsWith(context)) return "";
            path = uri.substring(context.length());
        }
        return path;
    }

    private static boolean unsafeNonBarcodeParameters(HttpServletRequest request) {
        // Let the servlet container decode parameter names/values exactly once. Encoded names
        // therefore cannot disguise a second barcode or another SQL-bearing parameter.
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            String name = parameter.getKey();
            if (containsSqlInjection(name) || containsPathTraversal(name)) return true;
            if ("barcode".equals(name)) continue;
            for (String value : parameter.getValue()) if (containsSqlInjection(value) || containsPathTraversal(value)) return true;
        }
        return false;
    }

    private static boolean unsafeNonCatalogParameters(HttpServletRequest request) {
        for (Map.Entry<String, String[]> parameter : request.getParameterMap().entrySet()) {
            if (containsSqlInjection(parameter.getKey()) || containsPathTraversal(parameter.getKey())) return true;
            if ("keyword".equals(parameter.getKey())) continue;
            for (String value : parameter.getValue()) if (containsSqlInjection(value) || containsPathTraversal(value)) return true;
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && (path.contains("/swagger-ui") || path.contains("/v3/api-docs")
                || path.contains("/webjars") || path.contains("/api-docs"));
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) return value;
        String cleaned = value;
        if (XSS_PATTERN.matcher(cleaned).find()) {
            cleaned = XSS_PATTERN.matcher(cleaned).replaceAll("");
        }
        return cleaned;
    }

    static boolean containsSqlInjection(String value) {
        if (value == null || value.isEmpty()) return false;
        return SQL_INJECTION_PATTERN.matcher(value).find();
    }

    static boolean containsPathTraversal(String value) {
        if (value == null || value.isEmpty()) return false;
        return PATH_TRAVERSAL_PATTERN.matcher(value).find();
    }

    private static class XssSqlRequestWrapper extends HttpServletRequestWrapper {
        private final boolean literalBarcode;

        public XssSqlRequestWrapper(HttpServletRequest request) {
            this(request, false);
        }

        public XssSqlRequestWrapper(HttpServletRequest request, boolean literalBarcode) {
            super(request); this.literalBarcode = literalBarcode;
        }

        @Override
        public String getParameter(String name) {
            String value = super.getParameter(name);
            if (literalBarcode && "barcode".equals(name)) return value;
            return sanitize(value);
        }

        @Override
        public String[] getParameterValues(String name) {
            String[] values = super.getParameterValues(name);
            if (values == null) return null;
            if (literalBarcode && "barcode".equals(name)) return values.clone();
            String[] sanitized = new String[values.length];
            for (int i = 0; i < values.length; i++) {
                sanitized[i] = sanitize(values[i]);
            }
            return sanitized;
        }

        @Override
        public String getHeader(String name) {
            String value = super.getHeader(name);
            if ("Authorization".equalsIgnoreCase(name) || "Content-Type".equalsIgnoreCase(name)) {
                return value;
            }
            return sanitize(value);
        }
    }

    private class XssSqlJsonRequestWrapper extends HttpServletRequestWrapper {

        private byte[] cachedBody;

        public XssSqlJsonRequestWrapper(HttpServletRequest request) throws IOException {
            super(request);
            String requestBody = readRequestBody(request);
            if (StringUtils.hasText(requestBody)) {
                requestBody = sanitizeJsonBody(requestBody);
            }
            this.cachedBody = requestBody != null ? requestBody.getBytes(StandardCharsets.UTF_8) : new byte[0];
        }

        private String readRequestBody(HttpServletRequest request) throws IOException {
            StringBuilder sb = new StringBuilder();
            try (ServletInputStream is = request.getInputStream()) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = is.read(buffer)) != -1) {
                    sb.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
                }
            }
            return sb.toString();
        }

        private String sanitizeJsonBody(String jsonBody) {
            try {
                JsonNode rootNode = objectMapper.readTree(jsonBody);
                JsonNode sanitizedNode = sanitizeJsonNode(rootNode);
                return objectMapper.writeValueAsString(sanitizedNode);
            } catch (Exception e) {
                log.warn("JSON体净化失败，使用原始内容: {}", e.getMessage());
                return sanitize(jsonBody);
            }
        }

        private JsonNode sanitizeJsonNode(JsonNode node) {
            if (node.isTextual()) {
                String text = node.asText();
                if (containsSqlInjection(text)) {
                    log.warn("检测到SQL注入攻击模式: {}", text.length() > 50 ? text.substring(0, 50) + "..." : text);
                }
                if (containsPathTraversal(text)) {
                    log.warn("检测到路径遍历攻击模式: {}", text.length() > 50 ? text.substring(0, 50) + "..." : text);
                }
                return TextNode.valueOf(sanitize(text));
            } else if (node.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    if (field.getValue().isTextual()) {
                        String text = field.getValue().asText();
                        if (containsSqlInjection(text)) {
                            log.warn("检测到SQL注入 - 字段[{}]: {}", field.getKey(),
                                    text.length() > 50 ? text.substring(0, 50) + "..." : text);
                        }
                        if (containsPathTraversal(text)) {
                            log.warn("检测到路径遍历 - 字段[{}]: {}", field.getKey(),
                                    text.length() > 50 ? text.substring(0, 50) + "..." : text);
                        }
                        ((com.fasterxml.jackson.databind.node.ObjectNode) node)
                                .set(field.getKey(), TextNode.valueOf(sanitize(text)));
                    } else if (field.getValue().isObject() || field.getValue().isArray()) {
                        sanitizeJsonNode(field.getValue());
                    }
                }
            } else if (node.isArray()) {
                for (int i = 0; i < node.size(); i++) {
                    JsonNode child = node.get(i);
                    if (child.isTextual()) {
                        String text = child.asText();
                        if (containsSqlInjection(text)) {
                            log.warn("检测到SQL注入 - 数组[{}]: {}", i,
                                    text.length() > 50 ? text.substring(0, 50) + "..." : text);
                        }
                        ((com.fasterxml.jackson.databind.node.ArrayNode) node)
                                .set(i, TextNode.valueOf(sanitize(text)));
                    } else {
                        sanitizeJsonNode(child);
                    }
                }
            }
            return node;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(cachedBody);
        }

        @Override
        public int getContentLength() {
            return cachedBody.length;
        }

        @Override
        public long getContentLengthLong() {
            return cachedBody.length;
        }
    }

    private static class CachedBodyServletInputStream extends ServletInputStream {

        private final ByteArrayInputStream inputStream;

        public CachedBodyServletInputStream(byte[] cachedBody) {
            this.inputStream = new ByteArrayInputStream(cachedBody);
        }

        @Override
        public boolean isFinished() {
            return inputStream.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read() {
            return inputStream.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return inputStream.read(b, off, len);
        }
    }
}
