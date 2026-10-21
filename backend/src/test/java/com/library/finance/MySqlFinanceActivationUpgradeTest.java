package com.library.finance;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import static com.library.finance.FinanceSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Isolated upgrade schema on the guarded disposable loopback MySQL test instance. */
class MySqlFinanceActivationUpgradeTest {
    @EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
    @Test void v16ToV17KeepsAll76TablesMetadataNextIdsAndLeavesGuardEmpty()throws Exception {
        var ownerDs=FinanceLedgerUpgradeTest.dataSource();var owner=new JdbcTemplate(ownerDs);String schema="library_repair_b40b_activation_"+UUID.randomUUID().toString().replace("-","");
        owner.execute("CREATE DATABASE `"+schema+"` CHARACTER SET utf8mb4");try {
        var ds=new DriverManagerDataSource(ownerDs.getUrl().replace("/library_repair_tests","/"+schema),ownerDs.getUsername(),ownerDs.getPassword());var j=new JdbcTemplate(ds);
        assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class),"Fresh task-specific datadir/schema is required");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("16").load().migrate();
        var f=new FinanceReadServiceTest();f.jdbc=j;f.service=new FinanceReadService(ds);f.prepare();long member=f.member(),account=f.account(member,"MEMBER_FEE"),opening=f.entry(account,"OPENING","10","12","2",null,null,FinanceReadServiceTest.T0),refund=f.entry(account,"REFUND","1",null,null,null,null,FinanceReadServiceTest.T1);
        j.update("INSERT INTO member_finance_allocation VALUES (?,?,?,1)",refund,opening,account);
        byte[] request=j.queryForObject("SELECT request_id FROM member_finance_entry WHERE id=?",byte[].class,refund);
        j.update("INSERT INTO member_finance_audit(request_id,account_id,operator_id,action,before_revision,after_revision,before_json,after_json,reason,recorded_at) VALUES (?,?,?,'REFUND',0,1,'{}','{}','synthetic-v16',?)",request,account,f.admin,FinanceReadServiceTest.T1);
        f.source(member,"000B40V17", "{\"实际缴纳费用\":\"001.230\",\"家庭宝宝\":\"PRIVATE_SYNTHETIC\"}");
        var tables=j.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name",String.class);assertEquals(76,tables.size());
        Map<String,Object> before=new TreeMap<>(),ddl=new TreeMap<>();for(String table:tables){before.put(table,FinanceLedgerUpgradeTest.rows(j,table));ddl.put(table,j.queryForMap("SHOW CREATE TABLE `"+table+"`").get("Create Table"));}
        var next=j.queryForList("SELECT table_name,auto_increment FROM information_schema.tables WHERE table_schema=DATABASE() ORDER BY table_name");
        var metadata=metadata(j);
        assertEquals(1,Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("17").load().migrate().migrationsExecuted);
        Map<String,Object> proof=new TreeMap<>();for(String table:tables) {
            var after=FinanceLedgerUpgradeTest.rows(j,table);if(table.equals("flyway_schema_history"))after.removeIf(x->Objects.equals(x.get("version"),"17"));assertEquals(before.get(table),after,table);assertEquals(ddl.get(table),j.queryForMap("SHOW CREATE TABLE `"+table+"`").get("Create Table"),table);
            proof.put(table,obj("rows",after.size(),"beforeSha256",hash(JSON.valueToTree(before.get(table))),"afterSha256",hash(JSON.valueToTree(after)),"ddlSha256",hash(JSON.valueToTree(ddl.get(table)))));
        }
        assertEquals(metadata,metadata(j));assertEquals(next,j.queryForList("SELECT table_name,auto_increment FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name<>'member_finance_activation' ORDER BY table_name"));
        assertEquals(0,j.queryForObject("SELECT COUNT(*) FROM member_finance_activation",Integer.class));assertEquals(0,Flyway.configure().dataSource(ds).locations("classpath:db/migration").target("17").load().migrate().migrationsExecuted);
        String dir=System.getProperty("b40b.evidence.dir");if(dir!=null)Files.writeString(Path.of(dir,"mysql-v16-v17-preservation.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(obj("database",j.queryForObject("SELECT VERSION()",String.class),"syntheticOnly",true,"oldTables",76,"preserved",proof,"metadata",metadata,"nextIds",next,"guardEmpty",true,"repeatMigrationCount",0)));
        }finally {owner.execute("DROP DATABASE `"+schema+"`");}
    }
    Map<String,Object> metadata(JdbcTemplate j) {
        Map<String,Object> result=new TreeMap<>();for(String table:List.of("COLUMNS","STATISTICS","KEY_COLUMN_USAGE","TABLE_CONSTRAINTS"))result.put(table,sortedMetadataRows(table,j.queryForList("SELECT * FROM information_schema."+table+" WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'member_finance_activation'")));
        return result;
    }
    // information_schema has no implicit row order. Compare every original column
    // and duplicate row after ordering by the complete identity of each view row.
    static List<Map<String,Object>> sortedMetadataRows(String view,List<Map<String,Object>> rows) {
        List<String> keys=switch(view) {
            case "COLUMNS" -> List.of("TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","ORDINAL_POSITION","COLUMN_NAME");
            case "STATISTICS" -> List.of("TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","INDEX_SCHEMA","INDEX_NAME","SEQ_IN_INDEX");
            case "KEY_COLUMN_USAGE" -> List.of("CONSTRAINT_CATALOG","CONSTRAINT_SCHEMA","CONSTRAINT_NAME","TABLE_CATALOG","TABLE_SCHEMA","TABLE_NAME","ORDINAL_POSITION","COLUMN_NAME");
            case "TABLE_CONSTRAINTS" -> List.of("CONSTRAINT_CATALOG","CONSTRAINT_SCHEMA","CONSTRAINT_NAME","TABLE_SCHEMA","TABLE_NAME");
            default -> throw new IllegalArgumentException("Unexpected metadata view: "+view);
        };
        Comparator<Map<String,Object>> order=(left,right)->0;
        for(String key:keys)order=order.thenComparing((left,right)->compareMetadataKey(left.get(key),right.get(key)));
        List<Map<String,Object>> result=new ArrayList<>(rows);result.sort(order);return result;
    }
    private static int compareMetadataKey(Object left,Object right) {
        if(left==right)return 0;if(left==null)return -1;if(right==null)return 1;
        if(left instanceof Number a&&right instanceof Number b)return Long.compare(a.longValue(),b.longValue());
        return left.toString().compareTo(right.toString());
    }
    @Test void metadataOrderingKeepsEveryColumnAndDuplicateRow() {
        for(String view:List.of("COLUMNS","STATISTICS","KEY_COLUMN_USAGE","TABLE_CONSTRAINTS")) {
            Map<String,Object> first=metadataExample("a",1),second=metadataExample("a",2),last=metadataExample("z",1);
            List<Map<String,Object>> original=List.of(last,second,first,first);
            assertEquals(List.of(first,first,second,last),sortedMetadataRows(view,original),view);
            assertEquals(List.of(last,second,first,first),original,"The source snapshot must remain unchanged");
        }
    }
    @Test void metadataComparisonStillRejectsChangedColumnsMissingColumnsAndDuplicateCounts() {
        for(String view:List.of("COLUMNS","STATISTICS","KEY_COLUMN_USAGE","TABLE_CONSTRAINTS")) {
            Map<String,Object> row=metadataExample("a",1),changed=new LinkedHashMap<>(row),missing=new LinkedHashMap<>(row);
            changed.put("EXTRA","changed-value");missing.remove("EXTRA");
            var before=sortedMetadataRows(view,List.of(row,row));
            for(var after:List.of(List.of(row,changed),List.of(row,missing),List.of(row)))
                assertThrows(AssertionError.class,()->assertEquals(before,sortedMetadataRows(view,after)),view);
        }
    }
    private static Map<String,Object> metadataExample(String table,int ordinal) {
        Map<String,Object> row=new LinkedHashMap<>();
        for(String key:List.of("TABLE_CATALOG","TABLE_SCHEMA","CONSTRAINT_CATALOG","CONSTRAINT_SCHEMA","CONSTRAINT_NAME","INDEX_SCHEMA","INDEX_NAME"))row.put(key,"same");
        row.put("CONSTRAINT_NAME",table+"_constraint_"+ordinal);
        row.put("TABLE_NAME",table);row.put("ORDINAL_POSITION",ordinal);row.put("SEQ_IN_INDEX",ordinal);
        row.put("COLUMN_NAME","column_"+ordinal);row.put("EXTRA",null);return row;
    }
}
