package com.library.borrowquery;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

public final class BorrowInquiryContracts {
    private BorrowInquiryContracts() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Filters(String keyword, String status, String origin) {}
    public record Summary(long total, long imported, long counterOrder, long system, long unverified,
                          String unit, String scope) {}
    public record Page(List<Row> records, long total, long page, long size, long pages, String asOf,
                       Filters filters, Summary summary) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Row(long id, long userId, long bookId, Long copyId, String userName, String userRealName,
                      String bookTitle, String bookIsbn, String bookCoverImage, String copyBarcode,
                      String borrowDate, String dueDate, String returnDate, String dueDateKind,
                      int renewCount, String status, String remark, BigDecimal fineAmount,
                      String origin, Long counterOrderId) {}
    public record Export(byte[] bytes, long total, String asOf, String filename) {}
}
