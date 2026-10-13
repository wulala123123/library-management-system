package com.library.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.*;
import java.util.regex.Pattern;

@Configuration
public class DatabaseMigrationConfig {
    @Bean
    FlywayMigrationStrategy migrationStrategy(@Value("${library.legacy-baseline-version:}") String version) {
        return flyway -> migrate(flyway, version);
    }

    public static void migrate(Flyway flyway, String legacyVersion) {
        if (!legacyVersion.isBlank()) {
            if (!Set.of("1", "2").contains(legacyVersion)) throw new IllegalArgumentException("旧库基线只能是1或2");
            try (Connection c = flyway.getConfiguration().getDataSource().getConnection()) {
                Set<String> actual = new HashSet<>();
                try (var rs = c.getMetaData().getTables(c.getCatalog(), null, "%", new String[]{"TABLE"})) {
                    while (rs.next()) actual.add(rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT));
                }
                if (!actual.contains("flyway_schema_history")) {
                    Map<String, Set<String>> expected = new HashMap<>();
                    readSchema("V1__core_schema.sql", expected);
                    if (legacyVersion.equals("2")) readSchema("V2__extended_schema.sql", expected);
                    if (!actual.equals(expected.keySet())) throw new IllegalStateException("旧库表集合与指定基线不一致，拒绝自动接管");
                    for (var table : expected.entrySet()) {
                        Set<String> columns = new HashSet<>();
                        try (var rs = c.getMetaData().getColumns(c.getCatalog(), null, table.getKey(), "%")) {
                            while (rs.next()) columns.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                        if (!columns.equals(table.getValue())) throw new IllegalStateException("旧库字段不匹配: " + table.getKey());
                    }
                    // The operator explicitly chooses this after schema/backup review; unknown schemas fail closed.
                    Flyway.configure().configuration(flyway.getConfiguration()).baselineVersion(legacyVersion).load().baseline();
                }
            } catch (Exception e) { throw new IllegalStateException("旧库基线检查失败，请先备份并核对结构", e); }
        }
        flyway.migrate();
    }

    private static void readSchema(String file, Map<String, Set<String>> tables) throws Exception {
        String sql;
        try (var in = new ClassPathResource("db/migration/" + file).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var matches = Pattern.compile("CREATE TABLE IF NOT EXISTS `([^`]+)` \\(([\\s\\S]*?)\\) ENGINE").matcher(sql);
        while (matches.find()) {
            Set<String> columns = new HashSet<>();
            var fields = Pattern.compile("(?m)^\\s+`([^`]+)` ").matcher(matches.group(2));
            while (fields.find()) columns.add(fields.group(1));
            tables.put(matches.group(1), columns);
        }
    }
}
