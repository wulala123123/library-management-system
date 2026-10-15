package com.library.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The same contract and race tests, against the migrated MySQL schema. */
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "bootstrap.admin.username=admin", "bootstrap.admin.password=MemberTestAdministrator123"})
class MySqlMemberControllerTest extends MemberControllerTest {
    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry properties) {
        String url = System.getenv("MYSQL_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"))
            throw new IllegalStateException("Member MySQL tests require a dedicated library_repair_tests database");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username", () -> System.getenv().getOrDefault("MYSQL_TEST_USER", "root"));
        properties.add("spring.datasource.password", () -> System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""));
    }

    @BeforeEach
    void testRoles() {
        for (String[] role : new String[][]{{"admin", "ADMIN"}, {"librarian", "LIBRARIAN"}, {"reader", "READER"}}) {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username=?", Long.class, role[0]) == 0)
                jdbc.update("INSERT INTO sys_user(username,password,real_name,role,status) VALUES (?,'synthetic-test-value','合成验证账号',?,1)", role[0], role[1]);
        }
    }
}
