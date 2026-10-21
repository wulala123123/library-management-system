package com.library.borrowquery;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BorrowInquiryMySqlGuardTest {
    @Test void rewritesOnlyTheExactDisposableDatabasePath() {
        String url="jdbc:mysql://127.0.0.1:3306/library_repair_tests?useSSL=false&serverTimezone=Asia/Shanghai";
        assertEquals(url,MySqlBorrowInquiryControllerTest.requireTestUrl(url));
        assertEquals("jdbc:mysql://127.0.0.1:3306/library_repair_tests_inquiry_012345abcdef?useSSL=false&serverTimezone=Asia/Shanghai",
                MySqlBorrowInquiryControllerTest.isolatedUrl(url,"library_repair_tests_inquiry_012345abcdef"));
    }
    @Test void refusesOtherUrlsBeforeConnecting() {
        assertThrows(IllegalStateException.class,()->MySqlBorrowInquiryControllerTest.requireTestUrl(null));
        for(String url:new String[]{"","jdbc:h2:mem:library_repair_tests","jdbc:mysql://localhost/production",
                "jdbc:mysql://localhost/library_repair_tests_suffix","jdbc:mysql://localhost/Library_repair_tests",
                "jdbc:mysql://localhost/library_repair_tests/other","jdbc:mysql://localhost/library_repair_tests#other"})
            assertThrows(IllegalStateException.class,()->MySqlBorrowInquiryControllerTest.requireTestUrl(url));
    }
    @Test void cleanupRefusesTheOwnerAndUnownedSchemas() {
        for(String schema:new String[]{"library_repair_tests","production","library_repair_tests_inquiry_",
                "library_repair_tests_inquiry_012345abcdef`","library_repair_tests_inquiry_012345abcdeg"})
            assertThrows(IllegalStateException.class,()->MySqlBorrowInquiryControllerTest.isolatedUrl("jdbc:mysql://localhost/library_repair_tests",schema));
    }
}
