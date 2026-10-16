package com.library.catalogidentity;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=B35SyntheticAdministrator123","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
public class MySqlCatalogIdentityServiceTest extends CatalogIdentityServiceTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r){String url=System.getenv("MYSQL_TEST_URL");if(url==null||!url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"))throw new IllegalStateException("Disposable schema only");r.add("spring.datasource.url",()->url);r.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");r.add("spring.datasource.username",()->System.getenv().getOrDefault("MYSQL_TEST_USER","root"));r.add("spring.datasource.password",()->System.getenv("MYSQL_TEST_PASSWORD"));}
    @Test void selectedTargetLegacyWriterSharesMutexAndChangesInvalidatePreview()throws Exception{long b=book(),p=publisher();JsonNode request=preview(b,link(p));CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();try{Future<?> writer=pool.submit(()->{authenticate();try{new TransactionTemplate(transactions).execute(s->{rename(p,"合成并发"+UUID.randomUUID());held.countDown();try{assertTrue(release.await(15,TimeUnit.SECONDS));}catch(InterruptedException e){throw new RuntimeException(e);}return null;});}finally{clear();}});assertTrue(held.await(5,TimeUnit.SECONDS));reason("CATALOG_IDENTITY_BUSY",()->confirm(request));assertEquals("PREVIEWED",service.getRequest(request.path("requestId").asLong(),admin).path("state").asText());release.countDown();writer.get(10,TimeUnit.SECONDS);reason("CATALOG_IDENTITY_CHANGED",()->confirm(request));}finally{release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}}
    @Test void twoDifferentRequestsForOneBookCommitOnlyOne()throws Exception{long b=book(),p=publisher(),other=publisher();JsonNode first=preview(b,link(p)),second=preview(b,link(other));ExecutorService pool=Executors.newFixedThreadPool(2);try{Callable<String>x=()->outcome(first),y=()->outcome(second);var a=pool.submit(x);var z=pool.submit(y);List<String> results=List.of(a.get(15,TimeUnit.SECONDS),z.get(15,TimeUnit.SECONDS));assertEquals(1,results.stream().filter("COMMITTED"::equals).count());assertEquals(1,results.stream().filter("CATALOG_IDENTITY_CHANGED"::equals).count());assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM catalog_book_publisher WHERE book_id=?",Integer.class,b));}finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}}
    private String outcome(JsonNode p){try{confirm(p);return "COMMITTED";}catch(com.library.catalogattributes.CatalogAttributeSupport.Failure e){return e.code;}}
}
