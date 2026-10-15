package com.library;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MembershipPlanMigrationTest {
    @Test
    void v5ToV6AddsOnlyEmptyPlanTablesAndPreservesEveryOriginalRowAndColumn() throws Exception {
        String source = System.getenv("MYSQL_TEST_URL");
        assertTrue(source != null && source.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"));
        String user = System.getenv().getOrDefault("MYSQL_TEST_USER", "root"), password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String schema = "library_repair_tests_plans_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String url = source.replace("/library_repair_tests", "/" + schema);
        try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        try {
            Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("5").load().migrate();
            Map<String, List<String>> original;
            Map<String, List<String>> columns;
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO sys_user(id,username,password,role,real_name,phone) VALUES (101,'preserved-plan-reader','synthetic-preserved-value','READER','合成原会员','13000000000')");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no,total_uses,used_uses,paid_fee,deposit_amount,service_start,service_end,notes,revision) "
                        + "VALUES (101,'huiben:101','000Plan',NULL,3,123.45,67.89,'2020-01-02','2020-03-04','合成人工备注',2)");
                s.executeUpdate("INSERT INTO member_child(user_id,name,birth_date_precision,source_birth_text) VALUES (101,'合成宝宝','YEAR','2020年')");
                s.executeUpdate("INSERT INTO member_profile_change(user_id,operator_id,change_source,change_reason,before_json,after_json) VALUES (101,1,'SYNTHETIC','保留原审计','null','{}')");
                s.executeUpdate("INSERT INTO book(id,isbn,title,stock_total,stock_available) VALUES (101,'synthetic-plan-isbn','合成原库存',5,4)");
                s.executeUpdate("INSERT INTO borrow_record(user_id,book_id,borrow_date,due_date,due_date_kind,status) VALUES (101,101,'2020-01-02',NULL,'UNLIMITED','BORROWING')");
                s.executeUpdate("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER','101','合成原始记录','{\"known\":true}',101)");
                original = snapshot(c, false); columns = snapshot(c, true);
            }
            Flyway migration = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("6").load();
            assertEquals(1, migration.migrate().migrationsExecuted); assertEquals(0, migration.migrate().migrationsExecuted);
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                assertEquals(original, snapshot(c, false)); assertEquals(columns, snapshot(c, true));
                for (String table : List.of("membership_plan", "membership_plan_version", "membership_plan_change")) {
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rs.next()); assertEquals(0, rs.getLong(1)); }
                }
            }
        } finally {
            try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) { s.execute("DROP DATABASE `" + schema + "`"); }
        }
    }

    private Map<String, List<String>> snapshot(Connection c, boolean schema) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT table_name AS name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY table_name")) {
            while (rs.next()) { String table = rs.getString("name"); if (!table.startsWith("membership_plan") && !table.equals("flyway_schema_history")) tables.add(table); }
        }
        Map<String, List<String>> result = new TreeMap<>();
        for (String table : tables) {
            assertTrue(table.matches("[A-Za-z0-9_]+")); List<String> rows = new ArrayList<>();
            String query = schema ? "SELECT column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='" + table + "' ORDER BY ordinal_position"
                    : "SELECT * FROM `" + table + "`";
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
                while (rs.next()) { List<String> cells = new ArrayList<>(); for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) cells.add(rs.getString(i)); rows.add(cells.toString()); }
            }
            if (!schema) Collections.sort(rows); result.put(table, rows);
        }
        return result;
    }
}
