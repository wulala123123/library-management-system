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
@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
class MySqlFinanceActivationUpgradeTest {
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
        Map<String,Object> result=new TreeMap<>();for(String table:List.of("COLUMNS","STATISTICS","KEY_COLUMN_USAGE","TABLE_CONSTRAINTS"))result.put(table,j.queryForList("SELECT * FROM information_schema."+table+" WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'member_finance_activation'"));
        return result;
    }
}
