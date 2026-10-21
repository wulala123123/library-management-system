package com.library.managementreport;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagementReportMySqlGuardTest {
    @Test void rewritesOnlyTheExactDisposableDatabasePath() {
        String url="jdbc:mysql://127.0.0.1:3306/library_repair_tests?useSSL=false&serverTimezone=Asia/Shanghai";
        assertEquals(url,MySqlManagementReportControllerTest.requireTestUrl(url));
        assertEquals("jdbc:mysql://127.0.0.1:3306/library_repair_tests_report_012345abcdef?useSSL=false&serverTimezone=Asia/Shanghai",
                MySqlManagementReportControllerTest.isolatedUrl(url,"library_repair_tests_report_012345abcdef"));
    }
    @Test void refusesOtherUrlsBeforeConnecting() {
        assertThrows(IllegalStateException.class,()->MySqlManagementReportControllerTest.requireTestUrl(null));
        for(String url:new String[]{"","jdbc:h2:mem:library_repair_tests","jdbc:mysql://localhost/production",
                "jdbc:mysql://localhost/library_repair_tests_suffix","jdbc:mysql://localhost/Library_repair_tests",
                "jdbc:mysql://localhost/library_repair_tests/other","jdbc:mysql://localhost/library_repair_tests#other"})
            assertThrows(IllegalStateException.class,()->MySqlManagementReportControllerTest.requireTestUrl(url));
    }
    @Test void cleanupRefusesTheOwnerAndUnownedSchemas() {
        for(String schema:new String[]{"library_repair_tests","production","library_repair_tests_report_",
                "library_repair_tests_report_012345abcdef`","library_repair_tests_report_012345abcdeg"})
            assertThrows(IllegalStateException.class,()->MySqlManagementReportControllerTest.isolatedUrl("jdbc:mysql://localhost/library_repair_tests",schema));
    }
}
