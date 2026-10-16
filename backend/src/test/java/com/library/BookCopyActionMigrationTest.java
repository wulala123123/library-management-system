package com.library;

import com.library.service.CopyInventoryProjection;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
class BookCopyActionMigrationTest {
    @Test void v8ToV9PreservesEveryOldCellDefinitionAndJsonAndNeverAdoptsSameNamedLegacyStates()throws Exception{
        String source=System.getenv("MYSQL_TEST_URL");assertTrue(source!=null&&source.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"));String user=System.getenv().getOrDefault("MYSQL_TEST_USER","root"),password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");String schema="library_repair_tests_actions_"+UUID.randomUUID().toString().replace("-","").substring(0,10),url=source.replace("/library_repair_tests","/"+schema);
        try(Connection c=DriverManager.getConnection(source,user,password);Statement s=c.createStatement()){s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");}
        try{
            Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("8").load().migrate();Map<String,List<String>> columns,rows,definitions;
            try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()){
                s.executeUpdate("INSERT INTO sys_user(id,username,password,real_name,role) VALUES (101,'action-preserved','synthetic-only','合成保留会员','READER')");
                s.executeUpdate("INSERT INTO book(id,isbn,title,stock_total,stock_available,price,pages,location) VALUES (101,'action-preserved','合成保留书目',10,8,NULL,0,NULL)");
                s.executeUpdate("INSERT INTO book_copy(id,book_id,barcode,status,location) VALUES (101,101,'000零Aa ','AVAILABLE',NULL),(102,101,'000零aa','BORROWED',''),(103,101,'000off','WRITTEN_OFF','  '),(104,101,'000disabled','DISABLED','原位置'),(105,101,'000variant','AVAILABLE ','原位'),(106,101,'000case','available','原位')");
                s.executeUpdate("INSERT INTO borrow_record(id,user_id,book_id,copy_id,borrow_date,due_date,due_date_kind,status,source_key) VALUES (101,101,101,102,'2020-01-02',NULL,'UNLIMITED','BORROWING','source:preserved-copy')");
                s.executeUpdate("INSERT INTO member_profile(user_id,source_key,card_no,usage_type,total_uses,used_uses) VALUES (101,'huiben:action-preserved','000Card','UNKNOWN',NULL,NULL)");
                s.executeUpdate("INSERT INTO stock_flow(id,book_id,flow_type,quantity,operator_id,remark) VALUES (101,101,'IN',1,101,'合成原流水')");
                s.executeUpdate("INSERT INTO inventory_intake_request(id,idempotency_key,request_hash,preview_digest,source_type,operator_id,request_json,preview_json,state,expires_at,confirmed_at,receipt_json) VALUES (101,'000-key',REPEAT('a',64),REPEAT('b',64),'MANUAL',101,'{\"reason\":\"原文零0\"}','{\"original\":null}','COMMITTED','2020-01-02','2020-01-01','{\"originalReceipt\":0}')");
                s.executeUpdate("INSERT INTO inventory_intake_copy(intake_id,line_key,copy_ordinal,book_id,copy_id,stock_flow_id,barcode_input) VALUES (101,'000-line',1,101,101,101,'SCANNED')");
                columns=columns(c);rows=rows(c,columns);definitions=definitions(c,columns);
            }
            Flyway next=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("9").load();assertEquals(1,next.migrate().migrationsExecuted);assertEquals(0,next.migrate().migrationsExecuted);
            try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()){
                assertEquals(rows,rows(c,columns));assertEquals(definitions,definitions(c,columns));Map<String,List<String>> after=columns(c);Set<String> added=new TreeSet<>(after.keySet());added.removeAll(columns.keySet());assertEquals(Set.of("book_copy_action_request","book_copy_action"),added);
                for(var table:columns.entrySet()){List<String> expected=new ArrayList<>(table.getValue());if(table.getKey().equals("book_copy"))expected.addAll(List.of("action_version","last_state_action_id"));assertEquals(expected,after.get(table.getKey()));}
                try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM book_copy WHERE action_version<>0 OR last_state_action_id IS NOT NULL")){r.next();assertEquals(0,r.getInt(1));}
                for(String table:added)try(ResultSet r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();assertEquals(0,r.getInt(1));}
                for(String query:List.of("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()","SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE()","SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE()","SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME IN ('book_copy_action','book_copy_action_request') AND REFERENCED_TABLE_NAME='sys_user'"))try(ResultSet r=s.executeQuery(query)){r.next();assertEquals(0,r.getInt(1));}
            }
            CopyInventoryProjection projection=new CopyInventoryProjection(new JdbcTemplate(new DriverManagerDataSource(url,user,password)));
            assertEquals("AVAILABLE",projection.classification(101L));assertEquals("BORROWED",projection.classification(102L));for(long id=103;id<=106;id++)assertEquals("UNKNOWN",projection.classification(id));
            var v2=projection.forBook(101L);assertEquals(6,v2.registeredCopies());assertEquals(6,v2.registeredInStockCopies());assertEquals(4,v2.registeredUnknownCopies());assertEquals(0,v2.registeredWrittenOffCopies());assertTrue(v2.hasUnknownStatus());
        }finally{try(Connection c=DriverManager.getConnection(source,user,password);Statement s=c.createStatement()){s.execute("DROP DATABASE `"+schema+"`");}}
    }
    private Map<String,List<String>> columns(Connection c)throws SQLException{Map<String,List<String>> result=new TreeMap<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position")){while(r.next())result.computeIfAbsent(r.getString(1),k->new ArrayList<>()).add(r.getString(2));}return result;}
    private Map<String,List<String>> rows(Connection c,Map<String,List<String>> columns)throws SQLException{Map<String,List<String>> result=new TreeMap<>();for(var table:columns.entrySet()){List<String> values=new ArrayList<>();try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT `"+String.join("`,`",table.getValue())+"` FROM `"+table.getKey()+"`")){while(r.next()){List<String> row=new ArrayList<>();for(int n=1;n<=table.getValue().size();n++){int type=r.getMetaData().getColumnType(n);if(type==Types.BINARY||type==Types.VARBINARY||type==Types.LONGVARBINARY){byte[] bytes=r.getBytes(n);row.add(bytes==null?null:HexFormat.of().formatHex(bytes));}else row.add(r.getString(n));}values.add(row.toString());}}Collections.sort(values);result.put(table.getKey(),values);}return result;}
    private Map<String,List<String>> definitions(Connection c,Map<String,List<String>> columns)throws SQLException{Map<String,List<String>> result=new TreeMap<>();for(var table:columns.entrySet()){List<String> values=new ArrayList<>();try(PreparedStatement s=c.prepareStatement("SELECT column_name,column_type,is_nullable,column_default,extra,character_set_name,collation_name,generation_expression FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ordinal_position")){s.setString(1,table.getKey());try(ResultSet r=s.executeQuery()){while(r.next())if(table.getValue().contains(r.getString(1))){List<String> row=new ArrayList<>();for(int n=1;n<=8;n++)row.add(r.getString(n));values.add(row.toString());}}}result.put(table.getKey(),values);}return result;}
}
