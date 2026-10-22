package com.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

public final class LocationContracts {
    private LocationContracts() {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AddressSnapshot(Long locationId,Long locationVersion,String code,Long storeyId,Long storeyVersion,
            String storeyName,Integer levelNum,Long bookshelfId,Long bookshelfVersion,String bookshelfCode,
            String bookshelfName,String bookshelfLocation,boolean selectable,String unavailableReason) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Placement(String kind,String text,AddressSnapshot registeredLocation) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PlacementSnapshot(Placement placement,String rawText) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LegacyBinding(Long bookId,String rawStatus,boolean needsReview) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AddressRow(AddressSnapshot address,LegacyBinding legacyBinding,long linkedCopyCount) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CopyPlacement(Placement placement,Long lastLocationActionId,boolean locationReferenceAnomaly) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record LocationChange(int snapshotVersion,PlacementSnapshot beforePlacement,PlacementSnapshot afterPlacement,
            Long beforeLocationActionId,String locationSourceBehavior,Long afterLocationActionId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StructureEvent(Long id,String entityType,Long entityId,String operation,Long operatorId,String occurredAt,
            String reason,Long beforeVersion,long afterVersion,int snapshotVersion,JsonNode before,JsonNode after) {}
}
