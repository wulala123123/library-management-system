package com.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.service.CaptchaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "server.address=127.0.0.1",
        "spring.datasource.url=${MYSQL_TEST_URL}","spring.datasource.username=${MYSQL_TEST_USER:root}",
        "spring.datasource.password=${MYSQL_TEST_PASSWORD:}","spring.flyway.user=${MYSQL_TEST_USER:root}",
        "spring.flyway.password=${MYSQL_TEST_PASSWORD:}",
        "jwt.secret=production-http-test-key-with-at-least-sixty-four-characters-long-123",
        "bootstrap.admin.username=review_admin","bootstrap.admin.password=ReviewAdministrator123",
        "file.upload-dir=target/http-test-uploads","logging.file.name=target/http-test.log",
        "login.rate-limit.max-attempts=1000"
})
@ActiveProfiles("prod")
class MySqlProductionHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired CaptchaService captcha;
    @Autowired JdbcTemplate jdbc;
    final HttpClient client=HttpClient.newHttpClient();
    private HttpResponse<String> send(String path,String method,String body,String token) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path));
        if(token!=null) req.header("Authorization","Bearer "+token);
        req.header("Content-Type","application/json");
        req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login() throws Exception {
        var challenge=json.readTree(send("/captcha/image","GET",null,null).body()).path("data");
        String key=challenge.path("captchaKey").asText();
        var body=json.writeValueAsString(Map.of("username","review_admin","password","ReviewAdministrator123",
                "captchaKey",key,"captchaCode",captcha.getCaptchaStore().get(key).code));
        var response=send("/auth/login","POST",body,null); assertEquals(200,response.statusCode());
        return json.readTree(response.body()).path("data").path("token").asText();
    }
    @Test void productionHttpLoginCatalogAndEveryModuleWork() throws Exception {
        assertEquals(400,send("/auth/login","POST","{\"username\":\"review_admin\",\"password\":\"ReviewAdministrator123\"}",null).statusCode());
        String token=login(); assertFalse(token.isBlank());
        for(String path:List.of("/books","/categories","/users","/borrows","/borrow-codes","/publishers","/bookshelves",
                "/purchases","/notices","/permissions/logs","/book-normalize/report","/profile","/inventory","/dashboard/stats")) {
            var response=send(path,"GET",null,token);
            assertEquals(200,response.statusCode(),path+": "+response.body());
            assertEquals(200,json.readTree(response.body()).path("code").asInt(),path);
        }
    }
    @Test void productionRegisterCannotSelectAdminRole() throws Exception {
        String username="http_"+UUID.randomUUID().toString().substring(0,8);
        var response=send("/auth/register","POST",json.writeValueAsString(Map.of("username",username,"password","ReaderPassword123","realName","测试读者","role","ADMIN")),null);
        assertEquals(200,response.statusCode());
        assertEquals("READER",jdbc.queryForObject("SELECT role FROM sys_user WHERE username=?",String.class,username));
    }
    @Test void resourceLimitAccepts50MiBAndRejects51MiBOverHttp() throws Exception {
        String token=login(); String isbn="http-"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO book(isbn,title) VALUES (?,?)",isbn,"文件验证");
        long id=jdbc.queryForObject("SELECT id FROM book WHERE isbn=?",Long.class,isbn);
        for(int size:List.of(50,51)) {
            String boundary="repair-boundary";
            byte[] before=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"size-test.txt\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] after=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
            var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(before),
                    HttpRequest.BodyPublishers.ofByteArray(new byte[size*1024*1024]),HttpRequest.BodyPublishers.ofByteArray(after));
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/book-resources/upload?bookId="+id+"&resourceType=DOCUMENT"))
                    .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if(size==50) { assertEquals(200,response.statusCode()); assertEquals(200,json.readTree(response.body()).path("code").asInt()); }
            else assertTrue(response.statusCode()>=400,"Oversized file must be rejected by the servlet container");
        }
    }
}
