package com.library;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.library.service.CaptchaService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySqlProductionHttpTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired CaptchaService captcha;
    @Autowired JdbcTemplate jdbc;
    @Autowired org.springframework.security.crypto.password.PasswordEncoder encoder;
    final HttpClient client=HttpClient.newHttpClient();
    int httpPort;
    String nginxContainer;
    Path nginxConfig;
    @BeforeAll void startProductionProxyWhenRequested() throws Exception {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username='review_admin'",Long.class)==0)
            jdbc.update("INSERT INTO sys_user(username,password,real_name,role,status) VALUES ('review_admin',?,'合成HTTP验收管理员','ADMIN',1)",encoder.encode("ReviewAdministrator123"));
        httpPort=port;
        if (!"true".equals(System.getenv("CI_NGINX_HTTP"))) return;
        // Linux CI only: reuse the shipped routing/limits without changing the production application.
        try (var socket=new ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress())) {
            httpPort=socket.getLocalPort();
        }
        nginxConfig=Files.createTempDirectory("library-http-nginx-").resolve("default.conf");
        String config=Files.readString(Path.of(System.getProperty("basedir","."),"nginx.conf"))
                .replace("listen 80;","listen 127.0.0.1:"+httpPort+";")
                .replace("${EDGE_PROXY_CIDR}","127.0.0.1/32")
                .replace("backend:8080","127.0.0.1:"+port);
        Files.writeString(nginxConfig,config);
        nginxContainer="library-http-"+UUID.randomUUID().toString().substring(0,8);
        docker("run","--detach","--rm","--name",nginxContainer,"--network","host",
                "--mount","type=bind,source="+nginxConfig+",target=/etc/nginx/conf.d/default.conf,readonly",
                "nginx:1.28-alpine");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime()<deadline) {
            try {
                if(send("/actuator/health","GET",null,null).statusCode()==200) {
                    System.out.println("Production HTTP tests use shipped Nginx routing and limits");
                    return;
                }
            } catch (java.io.IOException ignored) { }
            Thread.sleep(250);
        }
        fail("Nginx did not become ready: "+docker("logs",nginxContainer));
    }
    @AfterAll void stopProductionProxy() throws Exception {
        try {
            if(nginxContainer!=null) docker("rm","--force",nginxContainer);
        } finally {
            if(nginxConfig!=null) {
                Files.deleteIfExists(nginxConfig);
                Files.deleteIfExists(nginxConfig.getParent());
            }
        }
    }
    private String docker(String... args) throws Exception {
        var command=new ArrayList<>(List.of("docker")); command.addAll(List.of(args));
        var process=new ProcessBuilder(command).redirectErrorStream(true).start();
        String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        assertEquals(0,process.waitFor(),"Docker operation failed: "+output);
        return output.trim();
    }
    private HttpResponse<String> send(String path,String method,String body,String token) throws Exception {
        var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+httpPort+"/api"+path)).timeout(Duration.ofSeconds(120));
        if(token!=null) req.header("Authorization","Bearer "+token);
        req.header("Content-Type","application/json");
        req.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(req.build(),HttpResponse.BodyHandlers.ofString());
    }
    private String login() throws Exception {
        return login("review_admin","ReviewAdministrator123");
    }
    private String login(String username,String password) throws Exception {
        var challenge=json.readTree(send("/captcha/image","GET",null,null).body()).path("data");
        String key=challenge.path("captchaKey").asText();
        var body=json.writeValueAsString(Map.of("username",username,"password",password,
                "captchaKey",key,"captchaCode",captcha.getCaptchaStore().get(key).code));
        var response=send("/auth/login","POST",body,null); assertEquals(200,response.statusCode());
        assertEquals(200,json.readTree(response.body()).path("code").asInt());
        String token=json.readTree(response.body()).path("data").path("token").asText();
        assertFalse(token.isBlank());
        return token;
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
    private com.fasterxml.jackson.databind.JsonNode success(String path,String method,Object body,String token) throws Exception {
        var response=send(path,method,body==null?null:json.writeValueAsString(body),token);
        assertEquals(200,response.statusCode(),path+": "+response.body());
        var value=json.readTree(response.body());
        assertEquals(200,value.path("code").asInt(),path+": "+response.body());
        return value.path("data");
    }
    private com.fasterxml.jackson.databind.JsonNode createBook(String token) throws Exception {
        long category=success("/categories","POST",Map.of("name","验收"+UUID.randomUUID().toString().substring(0,8),
                "parentId",0,"sortOrder",0,"status",1),token).path("id").asLong();
        return createIntakeBook(Map.of("isbn",UUID.randomUUID().toString().substring(0,20),
                "title","生产验收图书","categoryId",category),token);
    }
    private com.fasterxml.jackson.databind.JsonNode intakePreview(Map<String,Object> source,Map<String,Object> target,int quantity,String key,String token) throws Exception {
        List<Map<String,Object>> copies=new ArrayList<>(); for(int n=0;n<quantity;n++)copies.add(Map.of("input","GENERATE"));
        return success("/inventory-intakes/previews","POST",Map.of("idempotencyKey",key,"reason","合成真实HTTP入库验收","source",source,
                "lines",List.of(Map.of("lineKey",UUID.randomUUID().toString(),"target",target,"copies",copies))),token);
    }
    private com.fasterxml.jackson.databind.JsonNode createIntakeBook(Map<String,Object> metadata,String token) throws Exception {
        String key=UUID.randomUUID().toString(); var preview=intakePreview(Map.of("type","MANUAL"),Map.of("newBook",metadata),2,key,token);
        var receipt=success("/inventory-intakes/"+preview.path("intakeId").asLong()+"/confirm","POST",Map.of("idempotencyKey",key,"previewDigest",preview.path("previewDigest").asText()),token);
        return success("/books/"+receipt.path("books").get(0).path("bookId").asLong(),"GET",null,token);
    }
    @Test void bookCrudAllowsOptionalLocationAndClearingAnExistingLocation() throws Exception {
        String token=login();
        long category=success("/categories","POST",Map.of("name","位置验收"+UUID.randomUUID().toString().substring(0,8),
                "parentId",0,"sortOrder",0,"status",1),token).path("id").asLong();
        for(String location:new String[]{null,"","A区-3层-01架"}) {
            var body=new LinkedHashMap<String,Object>();
            body.put("isbn",UUID.randomUUID().toString().substring(0,20));
            body.put("title","位置选填验收图书"); body.put("categoryId",category);
            if(location!=null) body.put("location",location);
            long id=createIntakeBook(body,token).path("id").asLong();
            assertEquals(location,jdbc.queryForObject("SELECT location FROM book WHERE id=?",String.class,id));
            String path="/books/"+id+"/metadata";
            String digest=success(path,"GET",null,token).path("metadataDigest").asText();
            var changed=success(path,"PUT",Map.of("expectedMetadataDigest",digest,"changes",Map.of("location","B区-2层-03架")),token);
            changed=success(path,"PUT",Map.of("expectedMetadataDigest",changed.path("metadataDigest").asText(),"changes",Map.of("location","")),token);
            assertEquals("",changed.path("book").path("location").asText());
            assertEquals("",jdbc.queryForObject("SELECT location FROM book WHERE id=?",String.class,id));
            for(String invalid:List.of("<script>","A".repeat(101))) {
                var invalidBody=Map.of("expectedMetadataDigest",changed.path("metadataDigest").asText(),"changes",Map.of("location",invalid));
                assertEquals(400,send(path,"PUT",json.writeValueAsString(invalidBody),token).statusCode());
                assertEquals("",jdbc.queryForObject("SELECT location FROM book WHERE id=?",String.class,id));
            }
        }
    }
    @Test void userCrudAllowsOptionalPhoneAndEditingWithoutChangingPassword() throws Exception {
        String token=login();
        for(String phone:new String[]{null,"","13800138000"}) {
            String username="contact_"+UUID.randomUUID().toString().substring(0,8);
            var body=new LinkedHashMap<String,Object>();
            body.put("username",username); body.put("password","ContactPassword123");
            body.put("realName","测试读者"); body.put("role","READER"); body.put("email","");
            if(phone!=null) body.put("phone",phone);
            long id=success("/users","POST",body,token).path("id").asLong();
            String hash=jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?",String.class,id);
            body.remove("password"); body.put("id",id); body.put("realName","修改姓名"); body.put("phone","");
            var updated=success("/users","PUT",body,token);
            assertEquals("修改姓名",updated.path("realName").asText());
            assertEquals("",jdbc.queryForObject("SELECT phone FROM sys_user WHERE id=?",String.class,id));
            assertEquals(hash,jdbc.queryForObject("SELECT password FROM sys_user WHERE id=?",String.class,id));
            assertEquals(username,success("/profile","GET",null,login(username,"ContactPassword123")).path("username").asText());
        }
        for(String phone:List.of("12345","23800138000")) {
            String username="invalid_"+UUID.randomUUID().toString().substring(0,8);
            var body=Map.of("username",username,"password","ContactPassword123","realName","测试读者","role","READER","phone",phone);
            var response=send("/users","POST",json.writeValueAsString(body),token);
            assertEquals(400,response.statusCode());
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?",Integer.class,username));
        }
    }
    @Test void readerLoginBorrowCodeRenewAndReturnUseRealHttp() throws Exception {
        String admin=login(), username="reader_"+UUID.randomUUID().toString().substring(0,8);
        success("/auth/register","POST",Map.of("username",username,"password","ReaderPassword123","realName","验收读者"),null);
        String reader=login(username,"ReaderPassword123");
        assertEquals("READER",success("/profile","GET",null,reader).path("role").asText());
        assertEquals(403,send("/users","GET",null,reader).statusCode());
        long book=createBook(admin).path("id").asLong();
        success("/books","GET",null,reader);
        var code=success("/borrow-codes/generate?bookId="+book,"POST",Map.of(),reader);
        assertEquals(2,success("/books/"+book,"GET",null,reader).path("stockAvailable").asInt());
        var record=success("/borrow-codes/verify?code="+code.path("code").asText(),"POST",Map.of(),reader).path("borrowRecord");
        assertEquals(1,success("/books/"+book,"GET",null,reader).path("stockAvailable").asInt());
        var renewed=success("/borrows/"+record.path("id").asLong()+"/renew","PUT",Map.of(),reader);
        assertEquals(1,renewed.path("renewCount").asInt());
        assertEquals(java.time.LocalDate.parse(record.path("dueDate").asText()).plusDays(30),
                java.time.LocalDate.parse(renewed.path("dueDate").asText()));
        var customRenewed=success("/borrows/"+record.path("id").asLong()+"/renew","PUT",Map.of("renewDays",7),reader);
        assertEquals(2,customRenewed.path("renewCount").asInt());
        assertEquals(java.time.LocalDate.parse(renewed.path("dueDate").asText()).plusDays(7),
                java.time.LocalDate.parse(customRenewed.path("dueDate").asText()));
        assertEquals("RETURNED",success("/borrows/"+record.path("id").asLong()+"/self-return","PUT",Map.of(),reader).path("status").asText());
        assertEquals(2,success("/books/"+book,"GET",null,reader).path("stockAvailable").asInt());
    }
    @Test void purchaseApprovalAndExistingIsbnRestockUseRealHttp() throws Exception {
        String token=login(); var book=createBook(token); long id=book.path("id").asLong();
        var item=Map.of("bookTitle",book.path("title").asText(),"isbn",book.path("isbn").asText(),
                "categoryId",book.path("categoryId").asLong(),"quantity",3,"price",10);
        long order=success("/purchases","POST",Map.of("items",List.of(item)),token).path("id").asLong();
        assertEquals("PENDING",success("/purchases/"+order+"/submit","PUT",Map.of(),token).path("status").asText());
        assertEquals("APPROVED",success("/purchases/"+order+"/approve","PUT",Map.of("action","APPROVE"),token).path("status").asText());
        long purchaseItem=success("/purchases/"+order,"GET",null,token).path("items").get(0).path("id").asLong();
        String key=UUID.randomUUID().toString(); var preview=intakePreview(Map.of("type","PURCHASE","purchaseOrderId",order),Map.of("purchaseItemId",purchaseItem),3,key,token);
        var confirmation=Map.of("idempotencyKey",key,"previewDigest",preview.path("previewDigest").asText());
        String confirmPath="/inventory-intakes/"+preview.path("intakeId").asLong()+"/confirm";
        var receipt=success(confirmPath,"POST",confirmation,token);
        assertEquals("COMPLETED",success("/purchases/"+order,"GET",null,token).path("status").asText());
        assertEquals(5,success("/books/"+id,"GET",null,token).path("stockTotal").asInt());
        var repeated=send("/purchases/"+order+"/instock","PUT","{}",token);
        assertEquals(409,repeated.statusCode());
        assertEquals(receipt,success(confirmPath,"POST",confirmation,token));
        assertEquals(3,receipt.path("copies").size());
        assertEquals(5,success("/books/"+id,"GET",null,token).path("stockAvailable").asInt());
    }
    @Test void exactRawBarcodeQueriesUseDecodedParameterNamesAndRejectDuplicateAndOtherAttackParameters() throws Exception {
        String token=login(); long book=createBook(token).path("id").asLong();
        String mark=UUID.randomUUID().toString().substring(0,8);
        String fullUtf8=UUID.randomUUID().toString().replace("-", "").codePoints().mapToObj(c->new String(Character.toChars(0x1F300+c))).collect(java.util.stream.Collectors.joining())+"😀".repeat(32);
        List<String> barcodes=List.of("000Aa"+mark+" ","零000绘本é😀"+mark,"javascript:plain"+mark,"onload=plain"+mark,"' OR 1=1 --"+mark,fullUtf8);
        List<Map<String,Object>> copies=new ArrayList<>();for(String barcode:barcodes)copies.add(Map.of("input","SCANNED","barcode",barcode));
        String key=UUID.randomUUID().toString();var preview=success("/inventory-intakes/previews","POST",Map.of("idempotencyKey",key,"reason","合成原文查询验收","source",Map.of("type","MANUAL"),
                "lines",List.of(Map.of("lineKey",UUID.randomUUID().toString(),"target",Map.of("bookId",book),"copies",copies))),token);
        success("/inventory-intakes/"+preview.path("intakeId").asLong()+"/confirm","POST",Map.of("idempotencyKey",key,"previewDigest",preview.path("previewDigest").asText()),token);
        for(String barcode:barcodes) {
            String encoded=java.net.URLEncoder.encode(barcode,StandardCharsets.UTF_8);
            for(String parameter:List.of("barcode","bar%63ode")) {
                var page=success("/book-copies?bookId="+book+"&"+parameter+"="+encoded,"GET",null,token);
                assertEquals(1,page.path("total").asLong());assertEquals(barcode,page.path("records").get(0).path("barcode").asText());
            }
        }
        assertEquals(400,send("/book-copies?barcode=first&bar%63ode=second","GET",null,token).statusCode());
        assertEquals(400,send("/book-copies?barcode=javascript%3Aplain&keyword=1%3D1","GET",null,token).statusCode());
        assertEquals(400,send("/book-copies-extra?barcode=1=1","GET",null,token).statusCode());
    }
    private HttpResponse<String> upload(String path,String token,byte[] content,String name,String mime) throws Exception {
        String boundary="repair-boundary";
        byte[] before=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+name+"\"\r\nContent-Type: "+mime+"\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] after=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        var body=HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(before),
                HttpRequest.BodyPublishers.ofByteArray(content),HttpRequest.BodyPublishers.ofByteArray(after));
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+httpPort+"/api"+path)).timeout(Duration.ofSeconds(120))
                .header("Authorization","Bearer "+token).header("Content-Type","multipart/form-data; boundary="+boundary).POST(body).build();
        return client.send(request,HttpResponse.BodyHandlers.ofString());
    }
    @Test void avatarAndCoverAccept5MiBAndRejectOneExtraByte() throws Exception {
        String token=login(); long id=createBook(token).path("id").asLong();
        byte[] png=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jX1kAAAAASUVORK5CYII=");
        for(String path:List.of("/profile/avatar","/upload/book-cover/"+id)) {
            for(int size:List.of(5*1024*1024,5*1024*1024+1)) {
                var response=upload(path,token,Arrays.copyOf(png,size),"limit.png","image/png");
                if(size==5*1024*1024) {
                    assertEquals(200,response.statusCode());
                    var value=json.readTree(response.body()); assertEquals(200,value.path("code").asInt());
                    String url=value.path("data").asText();
                    String context="true".equals(System.getenv("CI_NGINX_HTTP"))?"":"/api";
                    var downloaded=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+httpPort+context+url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
                    assertEquals(200,downloaded.statusCode()); assertArrayEquals(Arrays.copyOf(png,size),downloaded.body());
                } else assertTrue(response.statusCode()>=400 || json.readTree(response.body()).path("code").asInt()>=400);
            }
        }
    }
    @Test void resourceLimitAccepts50MiBAndRejectsOneExtraByteOverHttp() throws Exception {
        String token=login(); String isbn="http-"+UUID.randomUUID().toString().substring(0,8);
        jdbc.update("INSERT INTO book(isbn,title) VALUES (?,?)",isbn,"文件验证");
        long id=jdbc.queryForObject("SELECT id FROM book WHERE isbn=?",Long.class,isbn);
        List<Integer> sizes=new ArrayList<>(List.of(50*1024*1024,50*1024*1024+1));
        if("true".equals(System.getenv("CI_NGINX_HTTP"))) sizes.add(52*1024*1024);
        for(int size:sizes) {
            var response=upload("/book-resources/upload?bookId="+id+"&resourceType=DOCUMENT",token,new byte[size],"size-test.txt","text/plain");
            if(size==50*1024*1024) { assertEquals(200,response.statusCode()); assertEquals(200,json.readTree(response.body()).path("code").asInt()); }
            else assertTrue(response.statusCode()>=400,"Oversized file must be rejected by the servlet container");
            if(size==52*1024*1024 && "true".equals(System.getenv("CI_NGINX_HTTP"))) {
                assertEquals(413,response.statusCode());
                assertTrue(response.headers().firstValue("Server").orElse("").startsWith("nginx"));
            }
        }
    }
}
