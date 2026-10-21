package com.library.history;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** No database or network is used to check the isolation guard. */
class HistoryContextMySqlGuardTest {
    @Test void acceptsOnlyTheExactDisposableDatabasePath() {
        String source = "jdbc:mysql://127.0.0.1:3306/library_repair_tests?useSSL=false&serverTimezone=Asia/Shanghai";
        assertEquals(source, MySqlHistoryContextControllerTest.requireTestUrl(source));
        assertEquals("jdbc:mysql://127.0.0.1:3306/library_repair_tests_history_012345abcdef?useSSL=false&serverTimezone=Asia/Shanghai",
                MySqlHistoryContextControllerTest.isolatedUrl(source, "library_repair_tests_history_012345abcdef"));
    }

    @Test void refusesMissingOrDifferentDatabasesBeforeConnecting() {
        assertThrows(IllegalStateException.class, () -> MySqlHistoryContextControllerTest.requireTestUrl(null));
        for (String source : new String[]{"", "jdbc:h2:mem:library_repair_tests", "jdbc:mysql://localhost/library",
                "jdbc:mysql://localhost/library_repair_tests_suffix", "jdbc:mysql://localhost/Library_repair_tests",
                "jdbc:mysql://localhost/library_repair_tests/other", "jdbc:mysql://localhost/library_repair_tests#other",
                "jdbc:mysql://localhost/other?database=library_repair_tests"})
            assertThrows(IllegalStateException.class, () -> MySqlHistoryContextControllerTest.requireTestUrl(source));
    }

    @Test void cleanupCannotTargetTheOwnerDatabaseOrAnyUnownedSchema() {
        for (String schema : new String[]{"library_repair_tests", "library", "library_repair_tests_history_",
                "library_repair_tests_history_012345abcdef`", "library_repair_tests_history_012345abcdeg"})
            assertThrows(IllegalStateException.class, () -> MySqlHistoryContextControllerTest.isolatedUrl(
                    "jdbc:mysql://localhost/library_repair_tests", schema));
        assertThrows(IllegalStateException.class, () -> MySqlHistoryContextControllerTest.isolatedUrl(
                "jdbc:mysql://localhost/production", "library_repair_tests_history_012345abcdef"));
    }
}
