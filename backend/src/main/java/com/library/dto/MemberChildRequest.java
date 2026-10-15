package com.library.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;

@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class MemberChildRequest {
    @Positive private Long id;
    @Size(max = 100) private String name;
    @Size(max = 50) private String gender;
    private LocalDate birthDate;
    @NotNull @Pattern(regexp = "DAY|MONTH|YEAR|UNKNOWN") private String birthDatePrecision = "UNKNOWN";
    @Size(max = 300) private String sourceBirthText;
    @Size(max = 128) private String sourceKey;
    @Size(max = 1000) private String notes;

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("宝宝资料包含不支持的字段");
    }
}
