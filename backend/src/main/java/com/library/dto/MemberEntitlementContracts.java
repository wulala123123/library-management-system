package com.library.dto;

import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public final class MemberEntitlementContracts {
    private MemberEntitlementContracts() {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Actual(
            @JsonProperty(value = "planName", required = true) @Size(max = 200) String planName,
            @JsonProperty(value = "serviceStart", required = true) LocalDate serviceStart,
            @JsonProperty(value = "serviceEnd", required = true) LocalDate serviceEnd,
            @JsonProperty(value = "usageType", required = true) @NotNull @Pattern(regexp = "UNKNOWN|LIMITED|UNLIMITED") String usageType,
            @JsonProperty(value = "usedUses", required = true) @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.IntegerValue.class) Integer usedUses,
            @JsonProperty(value = "totalUses", required = true) @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.IntegerValue.class) Integer totalUses) {
        @JsonAnySetter
        public void unknown(String field, Object value) { throw new IllegalArgumentException("权益事实包含不支持的字段"); }
    }

    @Data
    public static class Input {
        @NotNull @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long revision;
        @Positive @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long planVersionId;
        @NotNull @Valid private Actual actual;
        @NotNull @Valid private MembershipPlanDefinition.Rules rules;
        @NotNull @Size(max = 30) private List<@NotBlank @Size(max = 500) String> sourceReferences;
        @NotBlank @Size(max = 500) private String reason;
        @Size(max = 2000) private String historyBasis;
        @JsonAnySetter
        public void unknown(String field, Object value) { throw new IllegalArgumentException("权益核对请求包含不支持的字段"); }
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Confirmation extends Input {
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") private String previewHash;
        @NotNull @AssertTrue private Boolean confirmed;
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_-]+") private String idempotencyKey;
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Snapshot(Long id, Long memberId, long memberRevision, Long planVersionId,
                           MembershipPlanResponses.Version versionSnapshot, Actual factsAtConfirmation,
                           MembershipPlanDefinition.Rules rulesAtConfirmation, List<String> sourceReferences,
                           String reason, String historyBasis, LocalDateTime confirmedAt, Long confirmedBy) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminView(MemberSummary member, MembershipPlanDefinition.Rules effectiveRules, Snapshot snapshot) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReaderView(MemberSummary member, MembershipPlanDefinition.Rules effectiveRules, boolean snapshotPresent) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Proposed(Actual actual, MembershipPlanDefinition.Rules rules, Long planVersionId) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Difference(String field, JsonNode currentValue, JsonNode proposedValue, JsonNode versionValue) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Preview(String previewHash, AdminView current, Proposed proposed, MembershipPlanResponses.Version version,
                          List<Difference> differences, boolean historicalVersion) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Result(Snapshot snapshot, long revision, boolean replayed) {}
}
