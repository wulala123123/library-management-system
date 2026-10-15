package com.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

public final class MembershipPlanResponses {
    private MembershipPlanResponses() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Summary(Long id, String name, long revision, boolean enabled, Long selectedVersionId,
                          long versionCount, LocalDateTime updatedAt, Long updatedBy) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Detail(Long id, String name, long revision, boolean enabled, Long selectedVersionId,
                         long versionCount, LocalDateTime updatedAt, Long updatedBy,
                         MembershipPlanDefinition draft, List<Version> versions) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Version(Long id, Long planId, long versionNumber, String name, String notes,
                          List<String> sourceReferences, MembershipPlanDefinition.Rules rules,
                          String confirmationBasis, LocalDateTime createdAt, Long createdBy) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Publication(Version version, long revision, boolean replayed) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Change(Long id, Long planId, Long operatorId, String action, String changeReason,
                         String beforeJson, String afterJson, LocalDateTime createdAt) {}
}
