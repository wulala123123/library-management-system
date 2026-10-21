package com.library.managementreport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.library.borrowquery.BorrowInquiryContracts.Summary;
import java.util.List;

public final class ManagementReportContracts {
    private ManagementReportContracts() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Filters(String keyword, String status, String origin, String startDate, String endDate) {}
    public record Totals(long borrowDetails, long borrowedCopyEvents, long activeDetails, long returnedDetails,
                         long overdueDetails, long knownCounterOrders) {}
    public record Trend(String date, String label, long borrowDetails, long knownCounterOrders) {}
    public record HotBook(long bookId, String title, long borrowDetails, long knownCounterOrders) {}
    public record Report(String asOf, String asOfDate, Filters filters, Totals totals, Summary origins,
                         List<Trend> trend, List<HotBook> hotBooks, String scope) {}
    public record Export(byte[] bytes, long total, String asOf, String filename) {}
}
