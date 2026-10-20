package com.library.controller;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.DriverManager;
import java.util.UUID;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "bootstrap.admin.username=pagination_bootstrap", "bootstrap.admin.password=PaginationSyntheticAdmin123"
})
class MySqlBorrowPaginationHttpTest extends BorrowPaginationHttpTest {
    private static String isolatedSchema;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry registry) throws Exception {
        String url = System.getenv("MYSQL_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?")) {
            throw new IllegalStateException("Dedicated disposable test database required");
        }
        isolatedSchema = "library_repair_tests_borrow_pages_"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        try (var connection = DriverManager.getConnection(url, username(), password());
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + isolatedSchema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        registry.add("spring.datasource.url", () -> url.replace("/library_repair_tests", "/" + isolatedSchema));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.datasource.username", MySqlBorrowPaginationHttpTest::username);
        registry.add("spring.datasource.password", MySqlBorrowPaginationHttpTest::password);
    }

    @AfterAll
    static void cleanSchema() throws Exception {
        if (isolatedSchema == null) return;
        try (var connection = DriverManager.getConnection(System.getenv("MYSQL_TEST_URL"), username(), password());
             var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + isolatedSchema + "`");
        }
    }

    private static String username() { return System.getenv().getOrDefault("MYSQL_TEST_USER", "root"); }
    private static String password() { return System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""); }
}
