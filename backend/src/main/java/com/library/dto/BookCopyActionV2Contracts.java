package com.library.dto;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.library.dto.BookCopyActionContracts.BookChange;
import com.library.dto.LocationContracts.PlacementSnapshot;
import java.util.*;
public final class BookCopyActionV2Contracts {
    private BookCopyActionV2Contracts() {}
 @JsonInclude(JsonInclude.Include.ALWAYS) public record CopyChange(int ordinal,Long copyId,Long bookId,String barcode,String beforeStatus,String afterStatus,
            String beforeLocation,String afterLocation,long beforeVersion,long afterVersion,Long beforeStateActionId,
            Long restoreOfActionId,int deltaTotal,int deltaAvailable,PlacementSnapshot beforePlacement,PlacementSnapshot afterPlacement,Long beforeLocationActionId,String locationSourceBehavior) {}
 @JsonInclude(JsonInclude.Include.ALWAYS) public record CopyReceipt(Long eventId,Long stockFlowId,int ordinal,Long copyId,Long bookId,String barcode,
            String beforeStatus,String afterStatus,String beforeLocation,String afterLocation,long beforeVersion,long afterVersion,
            Long beforeStateActionId,Long restoreOfActionId,int deltaTotal,int deltaAvailable,PlacementSnapshot beforePlacement,PlacementSnapshot afterPlacement,Long beforeLocationActionId,String locationSourceBehavior,Long afterLocationActionId) {}
 @JsonInclude(JsonInclude.Include.ALWAYS) public record PlanV2(String action,List<CopyChange> copies,List<BookChange> books,List<String> warnings,Map<Long,String> metadataDigests) {}
 @JsonInclude(JsonInclude.Include.ALWAYS) public record View(Long requestId,String state,int schemaVersion,String action,String previewDigest,String expiresAt,List<CopyChange> copies,List<BookChange> books,List<String> warnings,Receipt receipt) {}
 @JsonInclude(JsonInclude.Include.ALWAYS) public record Receipt(Long requestId,int schemaVersion,String idempotencyKey,String action,Long operatorId,String committedAt,List<CopyReceipt> copies,List<BookChange> books,List<String> warnings) {}
}