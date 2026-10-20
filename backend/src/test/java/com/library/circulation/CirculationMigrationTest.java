package com.library.circulation;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.library.circulation.CirculationSupport.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
class CirculationMigrationTest {
    @Test void v17ToV18AddsOnlyThreeEmptyTablesAndPreservesAllOldCellsDefinitionsAndNextIds()throws Exception{
        String source=System.getenv("MYSQL_TEST_URL");assertTrue(source!=null&&source.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"));
        String user=System.getenv().getOrDefault("MYSQL_TEST_USER","root"),password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");String name="library_repair_tests_circulation_"+UUID.randomUUID().toString().replace("-","").substring(0,10),url=source.replace("/library_repair_tests","/"+name);
        try(Connection c=DriverManager.getConnection(source,user,password);Statement s=c.createStatement()){s.execute("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");}
        try{
            Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("17").load().migrate();
            Set<String> tables;Map<String,List<String>> rows,definitions;List<String> flywayBefore,flywayDefinition;
            try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()){
                s.executeUpdate("INSERT INTO sys_user(id,username,password,real_name,role,status) VALUES (101,'circulation-preserved','synthetic-only',NULL,'READER',0)");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no,usage_type,total_uses,used_uses) VALUES (101,'huiben:circulation-preserved','000Card','UNKNOWN',NULL,NULL)");
                s.executeUpdate("INSERT INTO book(id,isbn,title,stock_total,stock_available,pages,price,location) VALUES (101,'circulation-original','合成原图书',4,2,0,NULL,NULL)");
                s.executeUpdate("INSERT INTO book_copy(id,book_id,barcode,status,location) VALUES (101,101,'000Aa ','BORROWED',NULL),(102,101,'000aa','AVAILABLE',''),(103,101,'原待核实','other','  ')");
                s.executeUpdate("INSERT INTO borrow_record(id,user_id,book_id,copy_id,borrow_date,due_date,due_date_kind,status,source_key) VALUES (101,101,101,101,'2020-01-01',NULL,'UNLIMITED','BORROWING','circulation:loan-original'),(102,101,101,NULL,'2020-01-01',NULL,'UNKNOWN','RETURNED','circulation:historical-original')");
                s.executeUpdate("INSERT INTO stock_flow(id,book_id,flow_type,quantity,related_id,operator_id,remark) VALUES (101,101,'IN',4,101,101,'原流水')");
                tables=tables(c);rows=rows(c,tables);definitions=definitions(c,tables);
                flywayBefore=historyRows(c);assertEquals(17,flywayBefore.size());flywayDefinition=definitions(c,Set.of("flyway_schema_history")).get("flyway_schema_history");
                historyIdentity(c,17,17);
            }
            var flyway=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").load();assertEquals(1,flyway.migrate().migrationsExecuted);assertEquals(0,flyway.migrate().migrationsExecuted);
            try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()){
                assertEquals(rows,rows(c,tables));assertEquals(definitions,definitions(c,tables));Set<String> added=tables(c);added.removeAll(tables);assertEquals(Set.of("circulation_request","circulation_order","circulation_order_item"),added);
                List<String> flywayAfter=historyRows(c);assertEquals(18,flywayAfter.size());assertEquals(flywayBefore,flywayAfter.subList(0,17));
                assertEquals(flywayDefinition,definitions(c,Set.of("flyway_schema_history")).get("flyway_schema_history"));historyIdentity(c,18,18);
                try(ResultSet r=s.executeQuery("SELECT installed_rank,version,type,script,success FROM flyway_schema_history WHERE installed_rank=18")){assertTrue(r.next());assertEquals(18,r.getInt(1));assertEquals("18",r.getString(2));assertEquals("SQL",r.getString(3));assertEquals("V18__circulation_orders.sql",r.getString(4));assertTrue(r.getBoolean(5));assertFalse(r.next());}
                for(String t:added)try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();assertEquals(0,r.getLong(1));}
                for(String metadata:List.of("TRIGGERS","ROUTINES","EVENTS")){String column=metadata.equals("TRIGGERS")?"TRIGGER_SCHEMA":metadata.equals("ROUTINES")?"ROUTINE_SCHEMA":"EVENT_SCHEMA";try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM information_schema."+metadata+" WHERE "+column+"=DATABASE()")){r.next();assertEquals(0,r.getLong(1));}}
                Files.writeString(Path.of("target/circulation-migration-evidence.json"),write(obj("oldTableCount",tables.size(),"oldCellsPreserved",true,"oldDefinitionsAndNextIdsPreserved",true,"newTables",added,"newTablesEmpty",true,"secondMigrateExecuted",0,
                        "flywayHistory",obj("columns",historyColumns(c),"originalRows",17,"rowsAfter",18,"originalFullRowsHexBefore",flywayBefore,"originalFullRowsHexAfter",flywayAfter.subList(0,17),"originalFullRowsByteEqual",true,"v18OnlyAddedFullRowHex",flywayAfter.get(17),"tableDefinitionBefore",flywayDefinition,"tableDefinitionAfter",definitions(c,Set.of("flyway_schema_history")).get("flyway_schema_history"),"tableDefinitionPreserved",true,"autoIncrementBefore",null,"autoIncrementAfter",null,"nextIdRule","installed_rank is an explicit Flyway-managed integer primary key, not AUTO_INCREMENT; next successful migration uses MAX(installed_rank)+1","nextInstalledRankBefore",18,"nextInstalledRankAfter",19))));
            }
        }finally{try(Connection c=DriverManager.getConnection(source,user,password);Statement s=c.createStatement()){s.execute("DROP DATABASE `"+name+"`");}}
    }
    static Set<String> tables(Connection c)throws SQLException{Set<String> out=new TreeSet<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW TABLES")){while(r.next())if(!r.getString(1).equals("flyway_schema_history"))out.add(r.getString(1));}return out;}
    static List<String> historyRows(Connection c)throws SQLException{List<String> out=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM flyway_schema_history ORDER BY installed_rank")){while(r.next()){List<String> cells=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++){byte[] bytes=r.getBytes(i);cells.add(bytes==null?null:HexFormat.of().formatHex(bytes));}out.add(write(cells));}}return out;}
    static List<String> historyColumns(Connection c)throws SQLException{List<String> out=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM flyway_schema_history LIMIT 0")){for(int i=1;i<=r.getMetaData().getColumnCount();i++)out.add(r.getMetaData().getColumnName(i));}return out;}
    static void historyIdentity(Connection c,int rows,int max)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*),MAX(installed_rank),COUNT(DISTINCT version) FROM flyway_schema_history")){assertTrue(r.next());assertEquals(rows,r.getInt(1));assertEquals(max,r.getInt(2));assertEquals(rows,r.getInt(3));}try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='flyway_schema_history'")){assertTrue(r.next());assertNull(r.getObject(1));assertFalse(r.next());}}
    static Map<String,List<String>> rows(Connection c,Set<String> tables)throws SQLException{Map<String,List<String>> out=new TreeMap<>();for(String table:tables){List<String> rows=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT * FROM `"+table+"`")){while(r.next()){List<String> cells=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++){byte[] bytes=r.getBytes(i);cells.add(bytes==null?null:HexFormat.of().formatHex(bytes));}rows.add(write(cells));}}Collections.sort(rows);out.put(table,rows);}return out;}
    static Map<String,List<String>> definitions(Connection c,Set<String> tables)throws SQLException{Map<String,List<String>> out=new TreeMap<>();for(String table:tables){try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SHOW CREATE TABLE `"+table+"`")){r.next();out.put(table,List.of(r.getString(2)));}}return out;}
}
