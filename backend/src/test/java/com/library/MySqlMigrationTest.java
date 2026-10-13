package com.library;

import com.library.config.DatabaseMigrationConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="MYSQL_TEST_URL",matches=".+")
class MySqlMigrationTest {
    @Test void applicationAccountHasDmlAccessWithoutSchemaPrivileges() throws Exception {
        String source=System.getenv("MYSQL_TEST_URL");
        assertTrue(source.contains("library_repair_tests"));
        String suffix=java.util.UUID.randomUUID().toString().substring(0,8);
        String schema="library_repair_priv_"+suffix, app="repair_app_"+suffix, migrator="repair_mig_"+suffix;
        String username=System.getenv().getOrDefault("MYSQL_TEST_USER","root"), password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");
        String url=source.replace("library_repair_tests",schema);
        try(var root=DriverManager.getConnection(source,username,password);var sql=root.createStatement()) {
            sql.execute("CREATE DATABASE "+schema);
            try {
                sql.execute("CREATE USER '"+app+"'@'%' IDENTIFIED BY 'DisposableAppPassword123'");
                sql.execute("CREATE USER '"+migrator+"'@'%' IDENTIFIED BY 'DisposableMigrationPassword123'");
                sql.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON "+schema+".* TO '"+app+"'@'%'");
                sql.execute("GRANT ALL PRIVILEGES ON "+schema+".* TO '"+migrator+"'@'%'");
                var migrationSource=new DriverManagerDataSource(url,migrator,"DisposableMigrationPassword123");
                Flyway.configure().dataSource(migrationSource).locations("classpath:db/migration").load().migrate();
                try(var appConn=DriverManager.getConnection(url,app,"DisposableAppPassword123");var stmt=appConn.createStatement()) {
                    stmt.executeUpdate("INSERT INTO sys_user(username,password) VALUES ('limited_reader','test-hash')");
                    stmt.executeUpdate("UPDATE sys_user SET real_name='读者' WHERE username='limited_reader'");
                    try(var rs=stmt.executeQuery("SELECT COUNT(*) FROM sys_user")){assertTrue(rs.next());assertEquals(1,rs.getInt(1));}
                    assertThrows(java.sql.SQLException.class,()->stmt.execute("CREATE TABLE forbidden_table(id INT)"));
                    stmt.executeUpdate("DELETE FROM sys_user WHERE username='limited_reader'");
                }
            } finally {
                sql.execute("DROP DATABASE "+schema);
                sql.execute("DROP USER IF EXISTS '"+app+"'@'%'");
                sql.execute("DROP USER IF EXISTS '"+migrator+"'@'%'");
            }
        }
    }
    @Test void oldCoreAndExtendedDatabasesUpgradeWithoutLosingData() throws Exception {
        for (int version=1;version<=2;version++) upgrade(version, false);
    }

    @Test void originalSqlFixturesUpgradeWithoutLosingData() throws Exception {
        for (int version=1;version<=2;version++) upgrade(version, true);
    }

    private void legacyScript(java.sql.Connection connection, String file) throws Exception {
        var path=java.nio.file.Path.of(System.getProperty("basedir", "."), "sql", file);
        String sql=java.nio.file.Files.readString(path)
                .replaceAll("(?m)^CREATE DATABASE[^;]*;\\s*", "")
                .replaceAll("(?m)^USE library_db;\\s*", "");
        // Only database selection is removed; original DDL and sample rows remain intact.
        ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void upgrade(int version, boolean original) throws Exception {
        String source=System.getenv("MYSQL_TEST_URL");
        assertTrue(source.contains("library_repair_tests"), "Use only a disposable library_repair_tests database");
        String schema="library_repair_upgrade_"+version;
        String username=System.getenv().getOrDefault("MYSQL_TEST_USER","root");
        String password=System.getenv().getOrDefault("MYSQL_TEST_PASSWORD","");
        try(var c=DriverManager.getConnection(source,username,password);var statement=c.createStatement()) {
            statement.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4");
        }
        String url=source.replace("library_repair_tests",schema);
        var ds=new DriverManagerDataSource(url,username,password);
        try {
            try(var c=ds.getConnection()) {
                if(original) {
                    legacyScript(c,"init.sql");
                    if(version==2) legacyScript(c,"migration_v2.sql");
                } else {
                    ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V1__core_schema.sql"));
                    if(version==2) ScriptUtils.executeSqlScript(c,new ClassPathResource("db/migration/V2__extended_schema.sql"));
                }
                c.createStatement().executeUpdate("INSERT INTO sys_user(username,password,role) VALUES ('legacy_admin','preserved-hash','ADMIN')");
                c.createStatement().executeUpdate("INSERT INTO book(isbn,title,stock_total,stock_available) VALUES ('legacy-isbn','旧库图书',2,1)");
                c.createStatement().executeUpdate("INSERT INTO borrow_record(user_id,book_id,borrow_date,due_date,status) SELECT u.id,b.id,CURRENT_DATE,CURRENT_DATE,'OVERDUE' FROM sys_user u CROSS JOIN book b WHERE u.username='legacy_admin' AND b.isbn='legacy-isbn'");
            }
            Flyway flyway=Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(true).load();
            assertThrows(Exception.class, flyway::migrate, "Unknown nonempty schemas must not be auto-baselined");
            try(var c=ds.getConnection()) {c.createStatement().execute("CREATE TABLE unknown_table(id INT)");}
            assertThrows(Exception.class,()->DatabaseMigrationConfig.migrate(flyway,String.valueOf(version)));
            try(var c=ds.getConnection()) {c.createStatement().execute("DROP TABLE unknown_table");}
            DatabaseMigrationConfig.migrate(flyway,String.valueOf(version));
            assertEquals(0,flyway.migrate().migrationsExecuted);
            try(var c=ds.getConnection();var s=c.createStatement()) {
                try(var r=s.executeQuery("SELECT password, token_version FROM sys_user WHERE username='legacy_admin'")) {
                    assertTrue(r.next()); assertEquals("preserved-hash",r.getString(1)); assertEquals(0,r.getLong(2));
                }
                try(var r=s.executeQuery("SELECT stock_available FROM book WHERE isbn='legacy-isbn'")) {assertTrue(r.next());assertEquals(1,r.getInt(1));}
                try(var r=s.executeQuery("SELECT br.status FROM borrow_record br JOIN sys_user u ON u.id=br.user_id WHERE u.username='legacy_admin'")) {assertTrue(r.next());assertEquals("OVERDUE",r.getString(1));}
                if(original) {
                    try(var r=s.executeQuery("SELECT COUNT(*) FROM book WHERE isbn<>'legacy-isbn'")) {assertTrue(r.next());assertTrue(r.getInt(1)>0);}
                    try(var r=s.executeQuery("SELECT COUNT(*) FROM sys_user WHERE username='admin'")) {assertTrue(r.next());assertEquals(1,r.getInt(1));}
                }
            }
        } finally {
            try(var c=DriverManager.getConnection(source,username,password);var s=c.createStatement()) {s.execute("DROP DATABASE "+schema);}
        }
    }
}
