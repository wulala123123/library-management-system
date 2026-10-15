package com.library.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never", "bootstrap.admin.username=admin", "bootstrap.admin.password=EntitlementTestAdministrator123"})
class MySqlMemberEntitlementControllerTest extends MemberEntitlementControllerTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("MYSQL_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?")) throw new IllegalStateException("Use a dedicated library_repair_tests database");
        registry.add("spring.datasource.url", () -> url); registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_TEST_USER", "root")); registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
    }
    @BeforeEach
    void roles() { for (String[] role : new String[][]{{"admin", "ADMIN"}, {"reader", "READER"}, {"librarian", "LIBRARIAN"}})
        if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Long.class, role[0]) == 0) jdbc.update("INSERT INTO sys_user(username,password,real_name,role,status) VALUES (?,'synthetic-value','合成权益验证',?,1)", role[0], role[1]); }
}
