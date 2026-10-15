package com.library.dto;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/** Keep the original member columns while distinguishing unlimited from a missing field. */
public class LegacyMemberResponse {
    private final Integer totalUses;
    private final Map<String, Object> originalColumns;

    public LegacyMemberResponse(Map<String, Object> row) {
        originalColumns = new LinkedHashMap<>(row);
        String totalColumn = row.keySet().stream().filter(key -> key.equalsIgnoreCase("total_uses"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Missing member total_uses column"));
        Number value = (Number) originalColumns.remove(totalColumn);
        totalUses = value == null ? null : value.intValue();
    }

    @JsonProperty("total_uses")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public Integer getTotalUses() {
        return totalUses;
    }

    @JsonAnyGetter
    public Map<String, Object> originalColumns() {
        return originalColumns;
    }
}
