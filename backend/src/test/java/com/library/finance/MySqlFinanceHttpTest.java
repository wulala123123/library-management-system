package com.library.finance;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static com.library.finance.FinanceSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=B40SyntheticAdministrator123","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
public class MySqlFinanceHttpTest {
    @LocalServerPort int port;@Autowired FinanceReadService service;@Autowired JdbcTemplate jdbc;@Autowired com.library.security.JwtTokenProvider jwt;
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r) {MySqlFinanceReadServiceTest.mysql(r);}
    final List<JsonNode> captures=new ArrayList<>();
    JsonNode http(String path,String token,int status,String schema)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api"+path)).timeout(Duration.ofSeconds(10)).GET();if(token!=null)request.header("Authorization","Bearer "+token);
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));assertEquals(status,response.statusCode(),response.body());var body=read(response.body());
        captures.add(obj("method","GET","path",path,"httpStatus",status,"schemaDefinition",schema,"body",body));return body;
    }
    @Test void actualTcpHttpNonemptySchemasPermissionsCursorAndOldApiCompatibility()throws Exception {
        var f=new FinanceReadServiceTest();f.jdbc=jdbc;f.service=service;f.prepare();long m=f.member(),a=f.account(m,"MEMBER_FEE"),o=f.entry(a,"OPENING","100","120","20",null,null,FinanceReadServiceTest.T0);f.entry(a,"RECEIPT","0.01",null,null,null,null,FinanceReadServiceTest.T1);long refund=f.entry(a,"REFUND","5.25",null,null,null,null,FinanceReadServiceTest.T2);jdbc.update("INSERT INTO member_finance_allocation VALUES (?,?,?,5.25)",refund,o,a);
        f.source(m,"000HTTP"+UUID.randomUUID(),"{\"实际缴纳费用\":\"120.00\",\"实际缴纳押金\":null,\"缴费状态\":\"已缴\",\"押金缴纳状态\":\"\",\"押金\":\"待核实\",\"家庭宝宝\":\"PRIVATE_SYNTHETIC\"}");
        String admin=jwt.generateToken(f.admin,"b40_synthetic_admin","ADMIN",0L),clerk=jwt.generateToken(f.clerk,"b40_synthetic_clerk","LIBRARIAN",0L),reader=jwt.generateToken(f.reader,"b40_synthetic_reader","READER",0L),base="/members/"+m+"/finance";
        var before=f.counts();var finance=http(base,admin,200,"FinanceEnvelope");assertEquals("94.76",finance.path("data").path("accounts").get(0).path("balance").path("amount").asText());assertFalse(http(base+"/source",admin,200,"SourceEnvelope").toString().contains("PRIVATE_SYNTHETIC"));
        var first=http(base+"/entries?fund=MEMBER_FEE&currency=CNY&size=2",admin,200,"EntriesEnvelope").path("data");
        http(base+"/entries?fund=MEMBER_FEE&currency=CNY&size=2&beforeId="+first.path("nextBeforeId").asText()+"&asRecordedAt="+first.path("asRecordedAt").asText(),admin,200,"EntriesEnvelope");
        http(base+"/entries?fund=DEPOSIT&currency=CNY",admin,200,"EntriesEnvelope");
        for(String suffix:List.of("","/source","/entries")) {http(base+suffix+"?unknown=1",null,401,"ErrorEnvelope");http(base+suffix,clerk,403,"ErrorEnvelope");http(base+suffix,reader,403,"ErrorEnvelope");}
        for(String query:List.of("size=1&size=2","size=01","fund=MEMBER_FEE&currency=CNY&beforeId=1","fund=MEMBER_FEE&currency=CNY&asRecordedAt=2026-02-30T00:00:00.000000Z","fund=MEMBER_FEE&currency=CNY&unknown=1"))http(base+"/entries?"+query,admin,400,"ErrorEnvelope");
        http("/members/9223372036854775807/finance",admin,404,"ErrorEnvelope");http("/members/9223372036854775808/finance",admin,400,"ErrorEnvelope");http(base+"?unknown=1",admin,400,"ErrorEnvelope");
        jdbc.update("UPDATE sys_user SET role='LIBRARIAN' WHERE id=?",f.admin);http(base,admin,403,"ErrorEnvelope");jdbc.update("UPDATE sys_user SET role='ADMIN',status=0 WHERE id=?",f.admin);http(base,admin,401,"ErrorEnvelope");jdbc.update("UPDATE sys_user SET status=1 WHERE id=?",f.admin);
        var old=http("/members/"+m,clerk,200,null);assertFalse(old.has("schemaVersion"));assertFalse(old.toString().contains("verifiedPaid"));assertFalse(old.toString().contains("proofDigest"));
        jdbc.update("UPDATE member_finance_entry SET evidence_json='{}' WHERE id=?",o);var safe=http(base,admin,500,"ErrorEnvelope");assertEquals("UNKNOWN",safe.path("data").path("certainty").asText());assertFalse(safe.toString().contains("SYNTHETIC_ONLY"));
        assertEquals(before,f.counts());String dir=System.getProperty("b40.evidence.dir");if(dir!=null)Files.writeString(Path.of(dir,"actual-http-captures.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("task","B40-A-backend-v1","transport","127.0.0.1 TCP / Spring Boot RANDOM_PORT / JWT / MySQL 8.4","syntheticOnly",true,"getWriteCountsUnchanged",true,"captures",captures)));
    }
}
