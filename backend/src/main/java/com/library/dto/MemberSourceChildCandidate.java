package com.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/** An administrator's read-only proposal; source values never carry a business row id. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record MemberSourceChildCandidate(String sourceKey, int sourceSlot, List<String> sourceFields,
        MemberChildRequest currentValue, Value sourceValue, boolean selectable, String reasonCode,
        boolean conflict, String reviewOnlyAge) {
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Value(String name, String gender, LocalDate birthDate, String birthDatePrecision,
            String sourceBirthText, String sourceKey, String notes) {}
}
