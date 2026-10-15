package com.library.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class XssSqlFilterIntakeTest {
    @Test void exactStructuredRoutesPreserveEveryUtf8ByteIncludingChunkBoundaries() throws Exception {
        String payload = "{\"text\":\"" + "a".repeat(4083) + "绘😀éjavascript:onload=<script>plain</script>\"}";
        for (String[] route : new String[][]{{"POST", "/inventory-intakes/previews"}, {"POST", "/inventory-intakes/42/confirm"}, {"PUT", "/books/42/metadata"}}) {
            var request = request(route[0], route[1], payload); var body = new AtomicReference<byte[]>();
            new XssSqlFilter().doFilter(request, new MockHttpServletResponse(), (r, s) -> body.set(r.getInputStream().readAllBytes()));
            assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), body.get());
            request.setServletPath(route[1]); assertTrue(XssSqlFilter.literalJsonRoute(request));
        }
    }
    @Test void similarPrefixesWrongMethodsAndOtherEndpointsKeepTheirExistingSanitization() throws Exception {
        for (String[] route : new String[][]{{"POST", "/inventory-intakes/previews-extra"}, {"POST", "/inventory-intakesXYZ/previews"}, {"POST", "/inventory-intakes/42/confirm/x"}, {"PUT", "/books/42/metadata-extra"}, {"POST", "/books/42/metadata"}, {"POST", "/books"}}) {
            var request = request(route[0], route[1], "{\"text\":\"javascript:keep onload=plain\"}"); var body = new AtomicReference<String>();
            assertFalse(XssSqlFilter.literalJsonRoute(request));
            new XssSqlFilter().doFilter(request, new MockHttpServletResponse(), (r, s) -> body.set(new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
            assertEquals("{\"text\":\"keep plain\"}", body.get());
        }
    }
    @Test void literalBodyRoutesStillRejectExistingPathAndQueryAttackPatterns() throws Exception {
        for (String query : new String[]{"x=1=1", "x=../private"}) {
            var request = request("POST", "/inventory-intakes/previews", "{}"); request.setQueryString(query);
            var response = new MockHttpServletResponse();
            new XssSqlFilter().doFilter(request, response, (r, s) -> fail("must be blocked")); assertEquals(400, response.getStatus());
        }
    }
    @Test void exactBarcodeQueryPreservesOnlyDecodedBarcodeAndRejectsDuplicatesAndOtherAttacks() throws Exception {
        for (String barcode : new String[]{"javascript:plain", "onload=plain", "' OR 1=1 --", "000零é "}) {
            var request = request("GET", "/book-copies", ""); request.setParameter("barcode", barcode); request.setQueryString("bar%63ode=container-decoded-value");
            request.addHeader("X-Text", "javascript:header"); request.setParameter("keyword", "onload=ordinary");
            new XssSqlFilter().doFilter(request, new MockHttpServletResponse(), (r, s) -> {
                assertEquals(barcode, r.getParameter("barcode")); assertArrayEquals(new String[]{barcode}, r.getParameterValues("barcode"));
                assertEquals("ordinary", r.getParameter("keyword")); assertEquals("header", ((jakarta.servlet.http.HttpServletRequest) r).getHeader("X-Text"));
            });
        }
        var duplicate = request("GET", "/book-copies", ""); duplicate.setParameter("barcode", "one", "two");
        var badOther = request("GET", "/book-copies", ""); badOther.setParameter("barcode", "' OR 1=1 --"); badOther.setParameter("keyword", "1=1");
        var otherPath = request("GET", "/book-copies-extra", ""); otherPath.setParameter("barcode", "' OR 1=1 --"); otherPath.setQueryString("barcode=1=1");
        for (var r : new MockHttpServletRequest[]{duplicate, badOther, otherPath}) { var response = new MockHttpServletResponse(); new XssSqlFilter().doFilter(r, response, (a,b) -> fail("must reject")); assertEquals(400, response.getStatus()); }
    }
    private MockHttpServletRequest request(String method, String path, String body) {
        MockHttpServletRequest r = new MockHttpServletRequest(method, "/api" + path); r.setContextPath("/api"); r.setContentType("application/json"); r.setContent(body.getBytes(StandardCharsets.UTF_8)); return r;
    }
}
