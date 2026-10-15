package com.library.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

public final class MembershipPlanRequests {
    private MembershipPlanRequests() {}

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Create extends MembershipPlanDefinition {
        @NotBlank @Size(max = 500) private String changeReason;
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class Draft extends Create {
        @NotNull @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long revision;
    }

    @Data
    public static class Publish {
        @NotNull @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long revision;
        @NotBlank @Size(max = 128) @Pattern(regexp = "[A-Za-z0-9_-]+") private String idempotencyKey;
        @NotBlank @Size(max = 2000) private String confirmationBasis;
        @NotBlank @Size(max = 500) private String changeReason;
        @JsonAnySetter
        public void unknown(String field, Object value) { throw new IllegalArgumentException("发布请求包含不支持的字段"); }
    }

    @Data
    public static class Selection {
        @NotNull @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long revision;
        @NotNull @Positive @JsonDeserialize(using = MembershipPlanNumberDeserializer.LongValue.class) private Long versionId;
        @NotNull private Boolean enabled;
        @NotBlank @Size(max = 500) private String changeReason;
        @JsonAnySetter
        public void unknown(String field, Object value) { throw new IllegalArgumentException("版本选择请求包含不支持的字段"); }
    }
}
