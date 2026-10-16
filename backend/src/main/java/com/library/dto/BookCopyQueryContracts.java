package com.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import com.library.service.CopyInventoryProjection.V2;

public final class BookCopyQueryContracts {
    private BookCopyQueryContracts() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BookInfo(Long id, String title, String isbn, String author, String publisher,
                           LocalDate publishDate, Integer pages, String coverImage, String location, Integer status) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CopyRow(Long id, Long bookId, String barcode, String status, String location,
                          boolean unassignedLocation, BookInfo book, long actionVersion, Long lastStateActionId,String stateClassification) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CurrentLoan(Long borrowId, String status, LocalDate borrowDate, LocalDate dueDate,
                              String dueDateKind, String memberName, String cardNo, boolean bookMatches) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CopyDetail(CopyRow copy, List<CurrentLoan> currentLoans, boolean needsVerification,V2 reconciliationV2) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CatalogSummary(BookInfo book, long totalCopies, long availableCopies, long registeredCopies,
                                 long registeredAvailableCopies, long registeredBorrowedCopies, long registeredOtherCopies,
                                 Map<String, Long> registeredByStatus, long borrowedCopies, long totalDifference,
                                 long availableDifference, long borrowedDifference, long stockBalanceDifference,
                                 boolean needsVerification, boolean countReconciliationOnly,V2 reconciliationV2) {}
}
