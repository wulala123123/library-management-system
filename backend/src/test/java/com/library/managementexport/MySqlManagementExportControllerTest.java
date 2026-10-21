package com.library.managementexport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The unchanged management export assertions run against native MySQL and the real Flyway schema. */
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.sql.init.mode=never",
        "bootstrap.admin.username=mgtexport_bootstrap", "bootstrap.admin.password=HistorySyntheticAdministrator123",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=0"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MySqlManagementExportControllerTest extends ManagementExportControllerTest {
    private static String ownerUrl;
    private static String isolatedSchema;

    @DynamicPropertySource
    static void mysql(DynamicPropertyRegistry properties) {
        ownerUrl = requireTestUrl(System.getenv("MYSQL_TEST_URL"));
        String schema = "library_repair_tests_mgtexport_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String isolatedUrl = isolatedUrl(ownerUrl, schema);
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            isolatedSchema = schema;
        } catch (SQLException error) {
            throw new IllegalStateException("Cannot create the isolated management export test schema", error);
        }
        properties.add("spring.datasource.url", () -> isolatedUrl);
        properties.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        properties.add("spring.datasource.username", MySqlManagementExportControllerTest::username);
        properties.add("spring.datasource.password", MySqlManagementExportControllerTest::password);
    }

    @BeforeEach
    void syntheticActors() {
        // The base fixture deliberately uses IDs 1/2/3. A fresh schema guarantees the
        // bootstrap is ID 1, and these two explicit inserts participate in its rollback.
        assertEquals(1L, jdbc.queryForObject("SELECT id FROM sys_user WHERE username='mgtexport_bootstrap' AND role='ADMIN'", Long.class));
        jdbc.update("INSERT INTO sys_user(id,username,password,real_name,role,status,deleted) VALUES "
                + "(2,'mgtexport_synthetic_librarian','synthetic-only','合成馆员','LIBRARIAN',1,0),"
                + "(3,'mgtexport_synthetic_reader','synthetic-only','合成读者','READER',1,0)");
    }

    @AfterAll
    static void cleanOwnSchema() throws SQLException {
        if (isolatedSchema == null) return;
        // Revalidate both boundaries immediately before the only destructive action.
        isolatedUrl(ownerUrl, isolatedSchema);
        try (Connection connection = ownerConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + isolatedSchema + "`");
            isolatedSchema = null;
        }
    }

    static String requireTestUrl(String url) {
        if (url == null || !url.matches("jdbc:mysql://[^/?#\\s]+/library_repair_tests(?:\\?[^#\\s]*)?"))
            throw new IllegalStateException("Management export MySQL tests require the dedicated library_repair_tests database");
        return url;
    }

    static String isolatedUrl(String source, String schema) {
        requireTestUrl(source);
        if (schema == null || !schema.matches("library_repair_tests_mgtexport_[a-f0-9]{12}"))
            throw new IllegalStateException("Only an owned management export test schema is allowed");
        int start = source.indexOf("/library_repair_tests") + 1;
        return source.substring(0, start) + schema + source.substring(start + "library_repair_tests".length());
    }

    private static Connection ownerConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(requireTestUrl(ownerUrl), username(), password());
        try {
            if (!"MySQL".equals(connection.getMetaData().getDatabaseProductName())
                    || !"library_repair_tests".equals(connection.getCatalog()))
                throw new SQLException("Unexpected engine or catalog for disposable management export tests");
            return connection;
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
    }

    private static String username() { return System.getenv().getOrDefault("MYSQL_TEST_USER", "root"); }
    private static String password() { return System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", ""); }
}
