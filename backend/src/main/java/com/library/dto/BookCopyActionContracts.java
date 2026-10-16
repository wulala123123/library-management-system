package com.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.library.dto.InventoryIntakeContracts.StrictInput;
import com.library.dto.InventoryIntakeContracts.StringValue;
import com.library.dto.BookCopyQueryContracts.CopyRow;
import com.library.service.CopyInventoryProjection.V2;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

public final class BookCopyActionContracts {
    private BookCopyActionContracts() {}
    @Data @EqualsAndHashCode(callSuper=false)
    public static class PreviewRequest extends StrictInput {
        @JsonDeserialize(using=StringValue.class) private String idempotencyKey;
        @JsonDeserialize(using=StringValue.class) private String action;
        @JsonDeserialize(using=StringValue.class) private String reason;
        private List<CopyInput> copies;
    }
    @Data @EqualsAndHashCode(callSuper=false)
    public static class CopyInput extends StrictInput {
        @JsonDeserialize(using=MembershipPlanNumberDeserializer.LongValue.class) private Long copyId;
        @JsonDeserialize(using=MembershipPlanNumberDeserializer.LongValue.class) private Long expectedVersion;
        private LocationInput targetLocation;
        @JsonDeserialize(using=MembershipPlanNumberDeserializer.LongValue.class) private Long restoreOfActionId;
        @JsonDeserialize(using=StringValue.class) private String restoreTo;
    }
    @Data @EqualsAndHashCode(callSuper=false)
    public static class LocationInput extends StrictInput {
        @JsonDeserialize(using=StringValue.class) private String kind;
        @JsonDeserialize(using=StringValue.class) private String text;
    }
    @Data @EqualsAndHashCode(callSuper=false)
    public static class ConfirmRequest extends StrictInput {
        @JsonDeserialize(using=StringValue.class) private String idempotencyKey;
        @JsonDeserialize(using=StringValue.class) private String previewDigest;
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CopyChange(int ordinal,Long copyId,Long bookId,String barcode,String beforeStatus,String afterStatus,
            String beforeLocation,String afterLocation,long beforeVersion,long afterVersion,Long beforeStateActionId,
            Long restoreOfActionId,int deltaTotal,int deltaAvailable) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BookChange(Long bookId,String isbn,String title,int stockTotalBefore,int stockAvailableBefore,
            int stockTotalAfter,int stockAvailableAfter,int deltaTotal,int deltaAvailable,V2 reconciliationV2Before,V2 reconciliationV2After) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record View(Long requestId,String state,int schemaVersion,String action,String previewDigest,String expiresAt,
            List<CopyChange> copies,List<BookChange> books,List<String> warnings,Receipt receipt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CopyReceipt(Long eventId,Long stockFlowId,int ordinal,Long copyId,Long bookId,String barcode,
            String beforeStatus,String afterStatus,String beforeLocation,String afterLocation,long beforeVersion,long afterVersion,
            Long beforeStateActionId,Long restoreOfActionId,int deltaTotal,int deltaAvailable) {
        public CopyReceipt(Long id,Long flow,CopyChange c) { this(id,flow,c.ordinal(),c.copyId(),c.bookId(),c.barcode(),c.beforeStatus(),c.afterStatus(),c.beforeLocation(),c.afterLocation(),c.beforeVersion(),c.afterVersion(),c.beforeStateActionId(),c.restoreOfActionId(),c.deltaTotal(),c.deltaAvailable()); }
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Receipt(Long requestId,int schemaVersion,String idempotencyKey,String action,Long operatorId,String committedAt,
            List<CopyReceipt> copies,List<BookChange> books,List<String> warnings) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionPermission(String action,boolean allowed,String reasonCode,Long restoreOfActionId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Context(CopyRow copy,V2 reconciliationV2,List<ActionPermission> actions,List<String> warnings) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ActionEvent(Long eventId,Long requestId,String action,String reason,Long operatorId,String occurredAt,
            Long stockFlowId,Long restoredByActionId,int ordinal,Long copyId,Long bookId,String barcode,String beforeStatus,
            String afterStatus,String beforeLocation,String afterLocation,long beforeVersion,long afterVersion,
            Long beforeStateActionId,Long restoreOfActionId,int deltaTotal,int deltaAvailable) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record IntakeSource(Long intakeId,String sourceType,Long purchaseOrderId,Long purchaseItemId,Long stockFlowId,Long operatorId,String confirmedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record History(List<ActionEvent> records,long total,long page,long size,IntakeSource intakeSource) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ErrorData(String reasonCode,Long requestId,Long copyId,Integer ordinal) {}
}
