package com.library.finance;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import static com.library.finance.FinanceSupport.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=B40SyntheticAdministrator123","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
public class MySqlFinanceReadServiceTest extends FinanceReadServiceTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry r) {
        var ds=FinanceLedgerUpgradeTest.dataSource();
        r.add("spring.datasource.url",ds::getUrl);r.add("spring.datasource.driver-class-name",()->"com.mysql.cj.jdbc.Driver");
        r.add("spring.datasource.username",ds::getUsername);r.add("spring.datasource.password",ds::getPassword);
    }
    @Test void nativeNumericProjectionDocumentsLexicalNormalization()throws Exception {
        long m=member();String input="{\"实际缴纳费用\":1e2,\"实际缴纳押金\":1.2300}";source(m,"000NUM"+java.util.UUID.randomUUID(),input);
        var projected=source(m);assertEquals("NUMBER",projected.path("fields").get(0).path("jsonType").asText());assertEquals("100.0",projected.path("fields").get(0).path("rawText").asText());assertEquals("1.23",projected.path("fields").get(1).path("rawText").asText());
        String dir=System.getProperty("b40.evidence.dir");if(dir!=null)Files.writeString(Path.of(dir,"source-number-projection-clarification.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("mysqlVersion",jdbc.queryForObject("SELECT VERSION()",String.class),"originalSyntheticJson",input,"actualFixedProjectionSql",new FinanceSource(jdbc,true).projection(),"actualSourceEnvelope",envelope(projected),"semantics","STRING preserves decoded text; NUMBER preserves MySQL projected numeric meaning, not original numeric lexical spelling; full original archive SHA256 remains bound; no adoption or writes")));
    }
}
