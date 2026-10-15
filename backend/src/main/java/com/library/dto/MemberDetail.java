package com.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public class MemberDetail extends MemberSummary {
    private String notes;
    private String address;
    private List<MemberChildRequest> children;
    private FinancialFacts financialFacts;
    private String sourceKey;
    private String sourceSystem;
    private Long sourceArchiveId;
    private LocalDateTime updatedAt;
    private Long updatedBy;
    private List<BorrowSummary> borrowHistory;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FinancialFacts(BigDecimal paidFee, BigDecimal depositAmount, String depositStatus) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BorrowSummary(Long id, Long bookId, String bookTitle, java.time.LocalDate borrowDate,
                                java.time.LocalDate dueDate, String dueDateKind, java.time.LocalDate returnDate,
                                String status) {}
}
