package com.library;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MemberEntitlementMigrationTest {
    @Test
    void v6ToV7PreservesEveryExistingCellAndAddsOnlyEmptySnapshotsAndANullPointer() throws Exception {
        String source = System.getenv("MYSQL_TEST_URL");
        assertTrue(source != null && source.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"));
        String user = System.getenv().getOrDefault("MYSQL_TEST_USER", "root"), password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String schema = "library_repair_tests_ent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String url = source.replace("/library_repair_tests", "/" + schema);
        try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4");
        }
        try {
            Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("6").load().migrate();
            Map<String, List<String>> columns;
            Map<String, List<String>> original;
            Map<String, List<String>> definitions;
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO sys_user(id,username,password,role,real_name,phone,member_revision) VALUES (101,'ent-preserved-reader','synthetic-preserved-value','READER','合成原会员','13000000000',2)");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no,plan_name,usage_type,total_uses,used_uses,paid_fee,deposit_amount,service_start,service_end,notes,revision) "
                        + "VALUES (101,'huiben:101','000Ent','原名称','UNKNOWN',9,3,123.45,67.89,'2020-01-02','2020-03-04','合成人工备注',2)");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,usage_type,total_uses,used_uses) VALUES (102,'manual:102','LIMITED',0,2),(103,'manual:103','UNLIMITED',NULL,NULL),(104,'manual:104','UNKNOWN',NULL,NULL)");
                s.executeUpdate("INSERT INTO member_child(user_id,name,birth_date_precision,source_birth_text,source_key) VALUES (101,'合成宝宝','YEAR','2020年','source:child-01')");
                s.executeUpdate("INSERT INTO member_profile_change(user_id,operator_id,change_source,change_reason,before_json,after_json) VALUES (101,1,'SYNTHETIC','保留原审计','null','{}')");
                s.executeUpdate("INSERT INTO book(id,isbn,title,stock_total,stock_available) VALUES (101,'synthetic-ent-isbn','合成原库存',5,4)");
                s.executeUpdate("INSERT INTO borrow_record(user_id,book_id,borrow_date,due_date,due_date_kind,status) VALUES (101,101,'2020-01-02',NULL,'UNLIMITED','BORROWING')");
                s.executeUpdate("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER','101','合成原始记录','{\"known\":true}',101)");
                s.executeUpdate("INSERT INTO membership_plan(id,name,draft_json,updated_by) VALUES (101,'合成保留套餐','{}',1)");
                s.executeUpdate("INSERT INTO membership_plan_version(id,plan_id,version_number,definition_json,confirmation_basis,idempotency_key,request_hash,created_by) VALUES (101,101,1,'{}','合成保留版本','preserve-key','preserve-hash',1)");
                s.executeUpdate("UPDATE membership_plan SET selected_version_id=101,enabled=1 WHERE id=101");
                columns = columns(c); original = rows(c, columns); definitions = definitions(c, columns);
            }
            Flyway next = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("7").load();
            assertEquals(1, next.migrate().migrationsExecuted); assertEquals(0, next.migrate().migrationsExecuted);
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                assertEquals(original, rows(c, columns)); assertEquals(definitions, definitions(c, columns));
                Map<String, List<String>> after = columns(c);
                assertEquals(Set.of("member_entitlement_snapshot", "member_entitlement_request", "member_entitlement_change"), difference(after.keySet(), columns.keySet()));
                for (String table : columns.keySet()) {
                    List<String> expected = new ArrayList<>(columns.get(table));
                    if (table.equals("member_profile")) expected.add("current_entitlement_snapshot_id");
                    assertEquals(expected, after.get(table), table);
                }
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM member_profile WHERE current_entitlement_snapshot_id IS NOT NULL")) {
                    assertTrue(rs.next()); assertEquals(0, rs.getLong(1));
                }
                for (String table : List.of("member_entitlement_snapshot", "member_entitlement_request", "member_entitlement_change")) {
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rs.next()); assertEquals(0, rs.getLong(1)); }
                }
            }
        } finally {
            try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) { s.execute("DROP DATABASE `" + schema + "`"); }
        }
    }

    private Set<String> difference(Set<String> after, Set<String> before) { Set<String> result = new TreeSet<>(after); result.removeAll(before); return result; }
    private Map<String, List<String>> columns(Connection c) throws SQLException {
        Map<String, List<String>> result = new TreeMap<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position")) {
            while (rs.next()) result.computeIfAbsent(rs.getString(1), ignored -> new ArrayList<>()).add(rs.getString(2));
        }
        return result;
    }
    private Map<String, List<String>> rows(Connection c, Map<String, List<String>> columns) throws SQLException {
        Map<String, List<String>> result = new TreeMap<>();
        for (var table : columns.entrySet()) {
            List<String> cells = new ArrayList<>();
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT `" + String.join("`,`", table.getValue()) + "` FROM `" + table.getKey() + "`")) {
                while (rs.next()) { List<String> row = new ArrayList<>(); for (int i = 1; i <= table.getValue().size(); i++) row.add(rs.getString(i)); cells.add(row.toString()); }
            }
            Collections.sort(cells); result.put(table.getKey(), cells);
        }
        return result;
    }
    private Map<String, List<String>> definitions(Connection c, Map<String, List<String>> columns) throws SQLException {
        Map<String, List<String>> result = new TreeMap<>();
        for (var table : columns.entrySet()) {
            List<String> definitions = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position")) {
                s.setString(1, table.getKey());
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) if (table.getValue().contains(rs.getString(1))) {
                        List<String> row = new ArrayList<>(); for (int i = 1; i <= 5; i++) row.add(rs.getString(i)); definitions.add(row.toString());
                    }
                }
            }
            result.put(table.getKey(), definitions);
        }
        return result;
    }
}
