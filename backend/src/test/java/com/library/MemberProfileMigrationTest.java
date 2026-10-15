package com.library;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MemberProfileMigrationTest {
    @Test
    void upgradesV4WithoutChangingSourceTotalsMoneyDatesOrArchiveAndReservesExactHistoricalCards() throws Exception {
        isolated(false);
    }

    @Test
    void duplicateExistingCardsRejectUpgradeWithoutCleaningOrMergingProfiles() throws Exception {
        isolated(true);
    }

    private void isolated(boolean duplicate) throws Exception {
        String source = System.getenv("MYSQL_TEST_URL");
        assertTrue(source != null && source.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"));
        String user = System.getenv().getOrDefault("MYSQL_TEST_USER", "root"), password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String schema = "library_repair_tests_member_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String url = source.replace("/library_repair_tests", "/" + schema);
        try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        try {
            Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("4").load().migrate();
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO sys_user(id,username,password,role,status) VALUES (101,'preserved-reader','synthetic-preserved-value','READER',1)");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no,total_uses,used_uses,paid_fee,deposit_amount,service_start,service_end) VALUES (101,'huiben:101','000Ab',NULL,3,123.45,67.89,'2020-01-02','2020-03-04')");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no,total_uses) VALUES (102,'huiben:102','" + (duplicate ? "000Ab" : "000ab") + "',0)");
                s.executeUpdate("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER','101','合成原始记录','{\"known\":true}',101)");
            }
            Flyway next = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").load();
            if (duplicate) assertThrows(Exception.class, next::migrate);
            else { next.migrate(); assertEquals(0, next.migrate().migrationsExecuted); }
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                try (ResultSet rs = s.executeQuery("SELECT * FROM member_profile WHERE user_id=101")) {
                    assertTrue(rs.next()); assertEquals("000Ab", rs.getString("card_no")); assertNull(rs.getObject("total_uses"));
                    assertEquals(3, rs.getInt("used_uses")); assertEquals("123.45", rs.getBigDecimal("paid_fee").toPlainString());
                    assertEquals("67.89", rs.getBigDecimal("deposit_amount").toPlainString());
                    assertEquals("2020-01-02", rs.getDate("service_start").toString()); assertEquals("2020-03-04", rs.getDate("service_end").toString());
                    if (!duplicate) { assertEquals("UNKNOWN", rs.getString("usage_type")); assertEquals(0, rs.getLong("revision")); }
                }
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM member_profile")) { assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); }
                try (ResultSet rs = s.executeQuery("SELECT details_json FROM legacy_record WHERE source_key='101'")) { assertTrue(rs.next()); assertEquals("{\"known\":true}", rs.getString(1)); }
                if (!duplicate) {
                    try (ResultSet rs = s.executeQuery("SELECT member_revision,password FROM sys_user WHERE id=101")) { assertTrue(rs.next()); assertEquals(0, rs.getLong(1)); assertEquals("synthetic-preserved-value", rs.getString(2)); }
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM member_card_history")) { assertTrue(rs.next()); assertEquals(2, rs.getInt(1)); }
                    assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no) VALUES (103,'manual:103','000Ab')"));
                }
            }
        } finally {
            try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) { s.execute("DROP DATABASE `" + schema + "`"); }
        }
    }
}
