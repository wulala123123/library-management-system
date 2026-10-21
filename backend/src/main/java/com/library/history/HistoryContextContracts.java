package com.library.history;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Read models only. IDs and source keys never become list ordinals or order numbers. */
public final class HistoryContextContracts {
    private HistoryContextContracts() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Identity(String memberId, String username, String memberName, String cardNo,
                           String bookId, String bookTitle, String isbn, String borrowId,
                           String copyId, String barcode, String status, String borrowDate,
                           String dueDate, String returnDate, String dueDateKind) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Provenance(String classification, String sourceKey, String sourceSystem,
                             String associationState, List<String> basis, List<String> warnings) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Links(String memberId, String bookId, String copyId, String counterOrderId,
                        String memberArchiveId) {}
    public record ArchiveType(String recordType, long total) {}
    public record Context(String entityType, String entityId, Identity identity, Provenance provenance,
                          Links links, List<ArchiveType> archiveTypes, boolean readOnly) {}
    public record Association(String scope, String basis, String state) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Archive(String id, String sourceSystem, String recordType, String sourceKey,
                          String displayName, String summary, String userId, String bookId,
                          String sourceTime, Association association) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ArchiveDetail(String id, String sourceSystem, String recordType, String sourceKey,
                                String displayName, String summary, String userId, String bookId,
                                String sourceTime, Association association, String detailsJson) {}
}
