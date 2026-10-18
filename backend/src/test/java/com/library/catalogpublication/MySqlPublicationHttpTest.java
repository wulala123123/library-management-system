package com.library.catalogpublication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.library.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real TCP HTTP fixture: credentials are generated only in memory and never exported. */
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=B36SyntheticAdministrator123","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlPublicationHttpTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r){MySqlPublicationServiceTest.mysql(r);}
    @LocalServerPort int port;
    @Autowired JdbcTemplate j;
    @Autowired JwtTokenProvider tokens;
    private final HttpClient client=HttpClient.newHttpClient();
    private JsonNode call(String method,String path,JsonNode body,String token,int expected)throws Exception{var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).header("Content-Type","application/json");if(token!=null)b.header("Authorization","Bearer "+token);var response=client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(write(body))).build(),HttpResponse.BodyHandlers.ofString());assertEquals(expected,response.statusCode(),response.body());return read(response.body());}
    @Test void realServiceResponseRecoveryAndErrorsExportWithoutCredentials()throws Exception{long actor=insert(j,"INSERT INTO sys_user(username,password,role,token_version) VALUES (?,'SYNTHETIC_UNUSED','ADMIN',0)","b36-synthetic-"+UUID.randomUUID()),other=insert(j,"INSERT INTO sys_user(username,password,role,token_version) VALUES (?,'SYNTHETIC_UNUSED','ADMIN',0)","b36-synthetic-"+UUID.randomUUID());String token=tokens.generateToken(actor,"synthetic","ADMIN",0L),otherToken=tokens.generateToken(other,"synthetic","ADMIN",0L);long a=insert(j,"INSERT INTO book(isbn,title) VALUES (?,?)","HTTP"+UUID.randomUUID().toString().replace("-","").substring(0,16),"合成HTTP书目📚"),b=insert(j,"INSERT INTO book(isbn,title) VALUES (?,?)","HTTP"+UUID.randomUUID().toString().replace("-","").substring(0,16),"合成HTTP第二书目");JsonNode first=call("GET","/catalog-publications/books/"+a,null,token,200),second=call("GET","/catalog-publications/books/"+b,null,token,200);ObjectNode evidence=obj("kind","MANUAL_REVIEW","reference","馆员/管理员填写的依据；此为合成资料");ObjectNode changes=obj("publishDate",obj("action","SET","value","2024-02-29"),"pages",obj("action","SET","value",0),"description",obj("action","SET","value","简介原文\n第二行\t📚 <script>原文</script>"));ObjectNode input=obj("operationKey",UUID.randomUUID().toString(),"mode","MANUAL","reason","合成真实服务验收","entries",List.of(obj("bookId",b,"expectedRevision",second.path("data").path("book").get("revision"),"expectedMetadataDigest",second.path("data").path("book").get("metadataDigest"),"changes",obj("publishDate",null,"pages",null,"description",obj("action","SET","value","")),"evidence",evidence),obj("bookId",a,"expectedRevision",first.path("data").path("book").get("revision"),"expectedMetadataDigest",first.path("data").path("book").get("metadataDigest"),"changes",changes,"evidence",evidence)));JsonNode preview=call("POST","/catalog-publications/previews",input,token,200),p=preview.path("data");String request="/catalog-publications/"+p.path("requestId").asLong();ObjectNode confirm=obj("operationKey",p.get("operationKey"),"previewDigest",p.get("previewDigest"));JsonNode receipt=call("POST",request+"/confirm",confirm,token,200),replayed=call("POST",request+"/confirm",confirm,token,200),byKey=call("GET","/catalog-publications/requests/by-key?key="+p.path("operationKey").asText(),null,token,200),events=call("GET","/catalog-publications/books/"+a+"/events",null,token,200),forbidden=call("GET",request,null,otherToken,403),anonymous=call("GET","/catalog-publications/books/"+a,null,null,403),bad=call("POST","/catalog-publications/previews",input.deepCopy().put("unknown",true),token,400);assertEquals(receipt,replayed);assertEquals(receipt.path("data"),byKey.path("data").get("receipt"));String dir=System.getProperty("b36.evidence.dir");if(dir!=null)Files.writeString(Path.of(dir,"real-service-synthetic-fixture.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("synthetic",true,"transport","real TCP HTTP / embedded Tomcat / MySQL 8.0.44","read",first,"previewInput",input,"preview",preview,"confirmInput",confirm,"receipt",receipt,"replayedReceipt",replayed,"byKey",byKey,"events",events,"ownerError",forbidden,"anonymousError",anonymous,"invalidInputError",bad)));}
}
