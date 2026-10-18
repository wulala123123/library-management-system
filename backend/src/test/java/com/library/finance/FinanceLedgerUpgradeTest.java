package com.library.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact disposable schema only. Creates and removes its own empty schema; independent of shared service tests. */
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
public class FinanceLedgerUpgradeTest {
    static DriverManagerDataSource dataSource() {
        String url=System.getenv("MYSQL_TEST_URL");
        if(url==null||!url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost):[0-9]+/library_repair_tests(?:\\?.*)?"))throw new IllegalStateException("Dedicated loopback schema only");
        return new DriverManagerDataSource(url,System.getenv().getOrDefault("MYSQL_TEST_USER","root"),System.getenv().getOrDefault("MYSQL_TEST_PASSWORD",""));
    }
    static List<Map<String,Object>> rows(JdbcTemplate j,String table) {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var raw:j.queryForList("SELECT * FROM `"+table+"`")) {Map<String,Object> row=new TreeMap<>();raw.forEach((k,v)->row.put(k,v instanceof byte[] b?HexFormat.of().formatHex(b):v instanceof java.util.Date||v instanceof java.time.temporal.TemporalAccessor?v.toString():v));rows.add(row);}
        rows.sort(Comparator.comparing(r->JSON.valueToTree(r).toString()));return rows;
    }
    @Test void actualV15ToV16PreservesAll70OldTablesAndOriginalBytes()throws Exception {
        var ownerDs=dataSource();var owner=new JdbcTemplate(ownerDs);String schema="library_repair_b40_upgrade_"+UUID.randomUUID().toString().replace("-", "");
        owner.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");try{
        var ds=new DriverManagerDataSource(ownerDs.getUrl().replace("/library_repair_tests", "/"+schema),ownerDs.getUsername(),ownerDs.getPassword());var j=new JdbcTemplate(ds);
        assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class),"Fresh dedicated schema required; never reset an existing schema");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("15").load().migrate();
        j.update("INSERT INTO sys_user(id,username,password,role,member_revision) VALUES (894001,'B40_V15_FROZEN','synthetic-hash','READER',7)");
        j.update("INSERT INTO member_profile(user_id,source_key,paid_fee,deposit_amount,deposit_status,revision,notes) VALUES (894001,'huiben:000Aa',0,NULL,'原押金文本',9,?)","旧原文\n\t📚");
        j.update("INSERT INTO legacy_record(source_system,record_type,source_key,display_name,user_id,details_json) VALUES ('huiben','MEMBER','000Aa','合成旧档案',894001,?)","{\"实际缴纳费用\":null,\"实际缴纳押金\":\"\",\"家庭宝宝\":\"合成原始边界\"}");
        j.update("INSERT INTO book(id,isbn,title,stock_total,stock_available,publication_revision,catalog_set_book_revision) VALUES (894001,'B40_V15_FROZEN','合成旧书目',1,0,3,5)");
        j.update("INSERT INTO book_copy(book_id,barcode,status,action_version) VALUES (894001,'000B40','BORROWED',8)");
        j.update("INSERT INTO borrow_record(user_id,book_id,borrow_date,due_date,status) VALUES (894001,894001,'2025-01-01',NULL,'BORROWING')");
        j.update("INSERT INTO catalog_set(id,name,normalized_name,status,revision,evidence_json,created_at,updated_at) VALUES (894001,'合成V15套装','合成v15套装','ACTIVE',1,'{\"reference\":\"合成旧依据\"}','2026-01-01','2026-01-01')");
        var tables=j.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name",String.class);assertEquals(70,tables.size());
        Map<String,Object> before=new TreeMap<>();for(String table:tables)before.put(table,rows(j,table));
        var flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").load();assertEquals(1,flyway.migrate().migrationsExecuted);assertEquals(0,flyway.migrate().migrationsExecuted);
        Map<String,Object> preservation=new TreeMap<>();
        for(String table:tables) {
            var original=(List<?>)before.get(table);var after=rows(j,table);
            if(table.equals("flyway_schema_history"))after.removeIf(x->Objects.equals(x.get("version"),"16"));
            assertEquals(original,after,table);preservation.put(table,obj("rows",original.size(),"beforeSha256",hash(JSON.valueToTree(original)),"afterSha256",hash(JSON.valueToTree(after)),"matches",true));
        }
        assertEquals(76,j.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class));
        Map<String,Object> ddl=new TreeMap<>();for(String table:List.of("member_finance_operation","member_finance_account","member_finance_request","member_finance_entry","member_finance_allocation","member_finance_audit")) {
            assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class));ddl.put(table,j.queryForMap("SHOW CREATE TABLE "+table).get("Create Table"));
        }
        String dir=System.getProperty("b40.evidence.dir");if(dir!=null)Files.writeString(Path.of(dir,"actual-v15-v16-ddl-and-preservation.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("mysqlVersion",j.queryForObject("SELECT VERSION()",String.class),"actualUpgrade",true,"oldTables",70,"newTables",6,"preservation",preservation,"ddl",ddl,"newTablesEmpty",true,"repeatMigrationCount",0)));
        }finally{owner.execute("DROP DATABASE `"+schema+"`");}
    }
}
