package com.library.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class MembershipPlanDefinition {
    @NotBlank @Size(max = 200) private String name;
    @Size(max = 2000) private String notes;
    @NotNull @Size(max = 30) private List<@NotBlank @Size(max = 500) String> sourceReferences = List.of();
    @NotNull @Valid private Rules rules = new Rules();

    @JsonAnySetter
    public void unknown(String field, Object value) { throw new IllegalArgumentException("套餐资料包含不支持的字段"); }

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Rules {
        @Positive @JsonDeserialize(using = MembershipPlanNumberDeserializer.IntegerValue.class) private Integer serviceDurationDays;
        @NotNull @Pattern(regexp = "START_INCLUDED") private String dayCountConvention = "START_INCLUDED";
        @NotNull @Valid private Limit perOrderLimit = new Limit();
        @NotNull @Valid private Limit activeLoanLimit = new Limit();
        @NotNull @Pattern(regexp = "UNKNOWN|LIMITED|UNLIMITED") private String usageType = "UNKNOWN";
        @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.IntegerValue.class) private Integer totalUses;
        @NotNull @Pattern(regexp = "UNKNOWN|DAYS|UNLIMITED") private String dueDateType = "UNKNOWN";
        @Positive @JsonDeserialize(using = MembershipPlanNumberDeserializer.IntegerValue.class) private Integer borrowDays;
        @NotNull @Pattern(regexp = "UNKNOWN|ALLOWED|FORBIDDEN") private String renewalPermission = "UNKNOWN";
        @Valid private Limit renewalLimit;
        @Positive @JsonDeserialize(using = MembershipPlanNumberDeserializer.IntegerValue.class) private Integer renewalDays;
        @NotNull @Valid private Choice languages = new Choice();
        @NotNull @Valid private Choice pickupMethods = new Choice();
        @JsonAnySetter
        public void unknown(String field, Object value) { throw new IllegalArgumentException("套餐规则包含不支持的字段"); }
    }

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Limit {
        @NotNull @Pattern(regexp = "UNKNOWN|LIMITED|UNLIMITED") private String mode = "UNKNOWN";
        @Min(0) @JsonDeserialize(using = MembershipPlanNumberDeserializer.IntegerValue.class) private Integer value;
        @JsonAnySetter
        public void unknown(String field, Object value) { throw new IllegalArgumentException("套餐限额包含不支持的字段"); }
    }

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static class Choice {
        @NotNull @Pattern(regexp = "UNKNOWN|ANY|ALLOW_LIST") private String mode = "UNKNOWN";
        @NotNull @Size(max = 50) private List<@NotBlank @Size(max = 100) String> values = List.of();
        @JsonAnySetter
        public void unknown(String field, Object value) { throw new IllegalArgumentException("套餐选项包含不支持的字段"); }
    }
}
