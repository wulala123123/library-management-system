package com.library.catalogpublication;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;
import java.nio.file.*;
import java.util.*;
import static com.library.catalogattributes.CatalogAttributeSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=B36SyntheticAdministrator123","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
@ActiveProfiles("test")
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlPublicationRecoveryTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r){String url=System.getenv().getOrDefault("B36_RESTORE_URL",System.getenv("MYSQL_TEST_URL"));if(url==null||!url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:_b36_restore)?(?:\\?.*)?"))throw new IllegalStateException("Disposable B36 schema only");r.add("spring.datasource.url",()->url);r.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");r.add("spring.datasource.username",()->System.getenv().getOrDefault("MYSQL_TEST_USER","root"));r.add("spring.datasource.password",()->System.getenv("MYSQL_TEST_PASSWORD"));}
    @Autowired PublicationService service;
    @Autowired JdbcTemplate j;
    @Test void durableReceiptPreviewAndEventsRecoverAfterFreshApplicationBoot()throws Exception{String dir=System.getProperty("b36.evidence.dir");Path fixture=dir==null?null:Path.of(dir,"real-service-synthetic-fixture.json");JsonNode receipt,preview;if(fixture!=null&&Files.exists(fixture)){JsonNode f=read(Files.readString(fixture));receipt=f.path("receipt").path("data");preview=f.path("preview").path("data");}else{long b=insert(j,"INSERT INTO book(isbn,title) VALUES (?,?)","REC"+UUID.randomUUID().toString().replace("-","").substring(0,17),"合成持久回执");var actor=new PublicationSupport.Actor(821099,true,false);JsonNode before=service.book(b,actor).path("book");preview=service.preview(obj("operationKey",UUID.randomUUID().toString(),"mode","MANUAL","reason","合成持久回执","entries",List.of(obj("bookId",b,"expectedRevision",before.get("revision"),"expectedMetadataDigest",before.get("metadataDigest"),"changes",obj("publishDate",null,"pages",obj("action","SET","value",0),"description",null),"evidence",obj("kind","MANUAL_REVIEW","reference","合成持久依据")))),actor);receipt=service.confirm(preview.path("requestId").asLong(),obj("operationKey",preview.get("operationKey"),"previewDigest",preview.get("previewDigest")),actor);}var actor=new PublicationSupport.Actor(receipt.path("actorId").asLong(),true,false);JsonNode byKey=service.getByKey(receipt.path("operationKey").asText(),actor),byId=service.getRequest(receipt.path("requestId").asLong(),actor),replayed=service.confirm(receipt.path("requestId").asLong(),obj("operationKey",preview.get("operationKey"),"previewDigest",preview.get("previewDigest")),actor);assertEquals(receipt,byKey.get("receipt"));assertEquals(receipt,byId.get("receipt"));assertEquals(receipt,replayed);assertEquals(preview.get("preview"),byId.get("preview"));for(JsonNode book:receipt.path("books")){assertEquals(hash(book.get("publication")),hash(service.book(book.path("bookId").asLong(),actor).get("book")));assertTrue(service.events(book.path("bookId").asLong(),1,100,actor).path("total").asLong()>0);}assertEquals(17,j.queryForObject("SELECT MAX(CAST(version AS SIGNED)) FROM flyway_schema_history",Integer.class));if(dir!=null)Files.writeString(Path.of(dir,"recovery-"+System.getProperty("b36.recovery.stage","fresh-boot")+".json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("synthetic",true,"stage",System.getProperty("b36.recovery.stage","fresh-boot"),"requestId",receipt.get("requestId"),"receiptDigest",hash(receipt),"previewDigest",preview.get("previewDigest"),"byIdAndByKeyAndConfirmExact",true,"nonemptyEvents",true,"flywayVersion",17)));}
}
