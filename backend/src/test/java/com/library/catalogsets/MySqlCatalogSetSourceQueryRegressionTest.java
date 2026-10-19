package com.library.catalogsets;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
@SpringBootTest(properties={"spring.flyway.enabled=true","spring.sql.init.mode=never","bootstrap.admin.username=admin","bootstrap.admin.password=B34SyntheticAdministrator123","mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"})
public class MySqlCatalogSetSourceQueryRegressionTest extends CatalogSetSourceQueryRegressionTest {
    @DynamicPropertySource static void mysql(DynamicPropertyRegistry properties){MySqlCatalogSetServiceTest.mysql(properties);}
}
