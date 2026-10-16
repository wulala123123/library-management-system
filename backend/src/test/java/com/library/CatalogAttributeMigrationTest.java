package com.library;
import com.library.catalogattributes.CatalogAttributeService;
import com.library.catalogattributes.CatalogAttributeSupport.Actor;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
public class CatalogAttributeMigrationTest {
    @Test void v11ToV12PreservesAllFiftyOldTablesDefinitionsValuesAndFrozenJson()throws Exception{
        String original=System.getenv("MYSQL_TEST_URL"),user=System.getenv().getOrDefault("MYSQL_TEST_USER","root"),password=System.getenv("MYSQL_TEST_PASSWORD");
        if(original==null||!original.matches("jdbc:mysql://[^/]+/library_repair_tests(?:\\?.*)?"))throw new IllegalStateException("Disposable schema required");
        String schema="library_repair_tests_b33a_"+UUID.randomUUID().toString().replace("-",""),url=original.replace("/library_repair_tests","/"+schema);
        try(Connection c=DriverManager.getConnection(original,user,password);Statement s=c.createStatement()){s.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");}
        try{
            assertEquals(11,Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("11").load().migrate().migrationsExecuted);
            JdbcTemplate j=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
            j.update("INSERT INTO book(id,isbn,title,stock_total,stock_available) VALUES (88001,'B33A-SYNTHETIC','  原登记名 📚 ',1,1)");
            j.update("INSERT INTO book_copy(id,book_id,barcode,action_version) VALUES (88001,88001,'000零Aa ',7)");
            j.update("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,details_json,book_id) VALUES ('huiben','BOOK','000零Aa ','SYNTHETIC','{\"适读年龄\":\"0-2岁\",\"装帧\":\"精装\",\"书籍名称\":\"合成原名\"}',88001)");
            j.update("INSERT INTO inventory_intake_request(idempotency_key,request_hash,preview_digest,source_type,operator_id,request_json,preview_json,state,expires_at) VALUES (X'4141',?,?,'MANUAL',88001,'{\"old\":null}','{\"title\":\"原登记\",\"version\":8}','PREVIEWED','2030-01-01')","a".repeat(64),"b".repeat(64));
            j.update("INSERT INTO book_copy_action_request(idempotency_key,operator_id,request_hash,preview_digest,schema_version,action_type,request_json,preview_json,state,expires_at,committed_at,receipt_json) VALUES (X'4142',88001,?,?,2,'MOVE','{\"old\":false}','{\"old\":0}','COMMITTED','2030-01-01','2020-01-01','{\"title\":\"历史书名\",\"schemaVersion\":2}')","c".repeat(64),"d".repeat(64));
            j.update("INSERT INTO stocktake_session(owner_id,scope_json,state,created_at) VALUES (88001,'{\"kind\":\"COPY_SET\",\"copyIds\":[88001]}','DRAFT','2020-01-01')");
            Map<String,String> definitions=definitions(url,user,password);Map<String,List<String>> before=values(url,user,password,definitions.keySet());assertEquals(50,definitions.size());
            Flyway next=Flyway.configure().dataSource(url,user,password).locations("classpath:db/migration").target("12").load();assertEquals(1,next.migrate().migrationsExecuted);assertEquals(0,next.migrate().migrationsExecuted);
            var after=definitions(url,user,password);assertEquals(54,after.size());definitions.forEach((t,d)->assertEquals(d,after.get(t),t));assertEquals(before,values(url,user,password,definitions.keySet()));
            for(String table:List.of("catalog_book_attribute","catalog_book_age_label","catalog_attribute_request","catalog_attribute_event"))assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));
            CatalogAttributeService attributes=new CatalogAttributeService(j,new DataSourceTransactionManager(j.getDataSource()));assertEquals(0,attributes.attributes(88001,new Actor(88001,true,false)).path("profile").path("revision").asInt());attributes.source(88001,new Actor(88001,true,false));assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM catalog_book_attribute",Integer.class));
            System.out.println("B33A_MIGRATION oldTables=50 allDefinitions=preserved allCells=preserved oldV8V2B32Json=preserved copyVersion7=preserved newTables=4 empty=true GETwrites=0");
        }finally{try(Connection c=DriverManager.getConnection(original,user,password);Statement s=c.createStatement()){s.execute("DROP DATABASE `"+schema+"`");}}
    }
    Map<String,String> definitions(String url,String user,String pass)throws Exception{Map<String,String> all=new TreeMap<>();try(Connection c=DriverManager.getConnection(url,user,pass);Statement s=c.createStatement()){List<String> names=new ArrayList<>();try(ResultSet r=s.executeQuery("SHOW TABLES")){while(r.next())names.add(r.getString(1));}for(String name:names)try(ResultSet r=s.executeQuery("SHOW CREATE TABLE `"+name+"`")){r.next();all.put(name,r.getString(2));}}return all;}
    Map<String,List<String>> values(String url,String user,String pass,Set<String> tables)throws Exception{Map<String,List<String>> all=new TreeMap<>();try(Connection c=DriverManager.getConnection(url,user,pass);Statement s=c.createStatement()){for(String table:tables){List<String> rows=new ArrayList<>();try(ResultSet r=s.executeQuery("SELECT * FROM `"+table+"`"+(table.equals("flyway_schema_history")?" WHERE version IS NULL OR version<>'12'":""))){while(r.next()){List<String> row=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++){byte[] b=r.getBytes(i);row.add(b==null?null:Base64.getEncoder().encodeToString(b));}rows.add(row.toString());}}Collections.sort(rows);all.put(table,rows);}}return all;}
}
