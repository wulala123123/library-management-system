package com.library.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class XssSqlFilterCopyActionTest {
    @Test void onlyPreciseActionJsonRoutesPreserveRawUtf8WhileOtherSafetyRulesRemain()throws Exception{
        String raw="{\"reason\":\""+"a".repeat(4080)+"😀绘本javascript:onload=<script>文字</script>\"}";
        for(String path:new String[]{"/book-copy-actions/previews","/book-copy-actions/v2/previews","/book-copy-actions/42/confirm"}){
            MockHttpServletRequest r=request(path,raw);AtomicReference<byte[]> bytes=new AtomicReference<>();new XssSqlFilter().doFilter(r,new MockHttpServletResponse(),(req,res)->bytes.set(req.getInputStream().readAllBytes()));assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8),bytes.get());
        }
        for(String path:new String[]{"/book-copy-actions/previews-extra","/book-copy-actions-other/previews","/book-copy-actions/42/confirm/extra"}){MockHttpServletRequest r=request(path,"{\"reason\":\"javascript:onload=文字\"}");AtomicReference<String> body=new AtomicReference<>();assertFalse(XssSqlFilter.literalJsonRoute(r));new XssSqlFilter().doFilter(r,new MockHttpServletResponse(),(req,res)->body.set(new String(req.getInputStream().readAllBytes(),StandardCharsets.UTF_8)));assertFalse(body.get().contains("javascript:"));}
        var bad=request("/book-copy-actions/previews","{}");bad.setQueryString("other=1=1");var response=new MockHttpServletResponse();new XssSqlFilter().doFilter(bad,response,(req,res)->fail("query must still be checked"));assertEquals(400,response.getStatus());
    }
    @Test void structuredMaintenanceLiteralJsonExemptionUsesOnlyExactMethodAndResourcePatterns()throws Exception{
        String raw="{\"reason\":\"javascript:中文 onload=<script>文字</script> 😀\"}";
        for(String[] route:new String[][]{{"POST","/bookshelves"},{"POST","/bookshelves/storeys"},{"POST","/bookshelves/locations"},{"PUT","/bookshelves"},{"PUT","/bookshelves/storeys"},{"PUT","/bookshelves/locations"},{"PUT","/bookshelves/locations/1/clear"},{"PUT","/bookshelves/locations/1/assign/2"},{"DELETE","/bookshelves/1"},{"DELETE","/bookshelves/storeys/1"},{"DELETE","/bookshelves/locations/1"}}){var r=request(route[1],raw);r.setMethod(route[0]);assertTrue(XssSqlFilter.literalJsonRoute(r));AtomicReference<byte[]> bytes=new AtomicReference<>();new XssSqlFilter().doFilter(r,new MockHttpServletResponse(),(req,res)->bytes.set(req.getInputStream().readAllBytes()));assertArrayEquals(raw.getBytes(StandardCharsets.UTF_8),bytes.get());}
        for(String path:new String[]{"/bookshelves/extra","/bookshelves/locations/1/clear/extra","/bookshelves/locations/one/clear","/book-copy-actions/v2/previews/extra"})assertFalse(XssSqlFilter.literalJsonRoute(request(path,raw)));var get=request("/bookshelves",raw);get.setMethod("GET");assertFalse(XssSqlFilter.literalJsonRoute(get));
    }
    private MockHttpServletRequest request(String path,String body){var r=new MockHttpServletRequest("POST","/api"+path);r.setContextPath("/api");r.setContentType("application/json");r.setContent(body.getBytes(StandardCharsets.UTF_8));return r;}
}
