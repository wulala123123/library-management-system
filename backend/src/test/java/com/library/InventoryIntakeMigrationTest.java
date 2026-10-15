package com.library;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class InventoryIntakeMigrationTest {
    @Test void v7ToV8PreservesEveryExistingCellAndAddsEmptyIntakesAndExactBarcodeIdentity() throws Exception {
        String source = System.getenv("MYSQL_TEST_URL"); assertTrue(source != null && source.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"));
        String user = System.getenv().getOrDefault("MYSQL_TEST_USER", "root"), password = System.getenv().getOrDefault("MYSQL_TEST_PASSWORD", "");
        String schema = "library_repair_tests_intake_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), url = source.replace("/library_repair_tests", "/" + schema);
        try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) { s.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"); }
        try {
            Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("7").load().migrate();
            Map<String, List<String>> columns, original, definitions;
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO sys_user(id,username,password,role,real_name) VALUES (101,'intake-preserved-reader','synthetic-preserved-value','READER','合成保留会员')");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no,usage_type,total_uses,used_uses) VALUES (101,'huiben:intake-preserved','000Card','UNKNOWN',NULL,NULL)");
                s.executeUpdate("INSERT INTO book(id,isbn,title,author,stock_total,stock_available,pages,price,location) VALUES (101,'intake-preserved','合成原库存',NULL,7,4,0,NULL,NULL)");
                s.executeUpdate("INSERT INTO book_copy(id,book_id,barcode,status,location) VALUES (101,101,'000Aa ','BORROWED',NULL),(102,101,'零é','原待核实','  ')");
                s.executeUpdate("INSERT INTO borrow_record(id,user_id,book_id,copy_id,borrow_date,due_date,due_date_kind,status,source_key) VALUES (101,101,101,101,'2020-01-02',NULL,'UNLIMITED','BORROWING','source:intake-borrow')");
                s.executeUpdate("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,user_id) VALUES ('huiben','MEMBER','intake-101','合成来源','{\"original\":true}',101)");
                s.executeUpdate("INSERT INTO purchase_order(id,order_no,applicant_id,status) VALUES (101,'preserve-order',101,'APPROVED')");
                s.executeUpdate("INSERT INTO purchase_item(id,order_id,book_title,isbn,quantity,instock_quantity,price) VALUES (101,101,'合成原库存','intake-preserved',5,2,0.00)");
                s.executeUpdate("INSERT INTO stock_flow(id,book_id,flow_type,quantity,related_id,operator_id,remark) VALUES (101,101,'IN',2,101,101,'原流水')");
                columns = columns(c); original = rows(c, columns); definitions = definitions(c, columns);
            }
            Flyway next = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration").target("8").load();
            assertEquals(1, next.migrate().migrationsExecuted); assertEquals(0, next.migrate().migrationsExecuted);
            try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
                assertEquals(original, rows(c, columns)); assertEquals(definitions, definitions(c, columns));
                Map<String, List<String>> after = columns(c); Set<String> added = new TreeSet<>(after.keySet()); added.removeAll(columns.keySet());
                assertEquals(Set.of("inventory_intake_request", "inventory_intake_copy"), added);
                for (String table : columns.keySet()) { List<String> expected = new ArrayList<>(columns.get(table)); if (table.equals("book_copy")) expected.add("barcode_key"); assertEquals(expected, after.get(table), table); }
                for (String table : added) try (ResultSet r = s.executeQuery("SELECT COUNT(*) FROM " + table)) { r.next(); assertEquals(0, r.getLong(1)); }
                try (ResultSet r = s.executeQuery("SELECT HEX(barcode_key)=HEX(CONVERT(barcode USING binary)) FROM book_copy")) { while (r.next()) assertEquals(1, r.getInt(1)); }
                // Case and PAD SPACE variants are distinct after upgrade; exact duplicates still fail.
                s.executeUpdate("INSERT INTO book_copy(book_id,barcode,status) VALUES (101,'000Aa','AVAILABLE'),(101,'000aa','AVAILABLE')");
                assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO book_copy(book_id,barcode,status) VALUES (101,'000Aa ','AVAILABLE')"));
                try (ResultSet r = s.executeQuery("SELECT copy_id,due_date_kind FROM borrow_record WHERE id=101")) { r.next(); assertEquals(101, r.getLong(1)); assertEquals("UNLIMITED", r.getString(2)); }
            }
        } finally { try (Connection c = DriverManager.getConnection(source, user, password); Statement s = c.createStatement()) { s.execute("DROP DATABASE `" + schema + "`"); } }
    }
    private Map<String,List<String>> columns(Connection c) throws SQLException {
        Map<String,List<String>> result = new TreeMap<>(); try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position")) { while(r.next()) result.computeIfAbsent(r.getString(1), k -> new ArrayList<>()).add(r.getString(2)); } return result;
    }
    private Map<String,List<String>> rows(Connection c, Map<String,List<String>> columns) throws SQLException {
        Map<String,List<String>> result = new TreeMap<>(); for(var table : columns.entrySet()) { List<String> rows = new ArrayList<>(); try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT `" + String.join("`,`", table.getValue()) + "` FROM `" + table.getKey() + "`")) { while(r.next()) { List<String> values = new ArrayList<>(); for(int n=1;n<=table.getValue().size();n++) values.add(r.getString(n)); rows.add(values.toString()); } } Collections.sort(rows); result.put(table.getKey(), rows); } return result;
    }
    private Map<String,List<String>> definitions(Connection c, Map<String,List<String>> columns) throws SQLException {
        Map<String,List<String>> result = new TreeMap<>(); for(var table : columns.entrySet()) { List<String> rows = new ArrayList<>(); try (PreparedStatement s = c.prepareStatement("SELECT column_name,column_type,is_nullable,column_default,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position")) { s.setString(1,table.getKey()); try(ResultSet r=s.executeQuery()) { while(r.next()) if(table.getValue().contains(r.getString(1))) { List<String> row=new ArrayList<>();for(int n=1;n<=5;n++)row.add(r.getString(n));rows.add(row.toString()); } } } result.put(table.getKey(),rows); } return result;
    }
}
