package com.library.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared definitions for dashboard, inventory, reports and exports. */
@Data
public class StatisticsSummary {
    private Long catalogTitles;
    private Long totalCopies;
    private Long availableCopies;
    private Long borrowedCopies;
    private Long availableTitles;
    private Long lowStockTitles;
    private Long accountCount;
    private Long memberCount;
    private Long validMemberCount;
    private Long unknownValidityMemberCount;
    private Long todayBorrows;
    private Long overdueCount;
    private Long borrowRecordCount;
    @JsonIgnore
    private Long enabledReaderAccounts;
    private LocalDate asOfDate;
    private Map<String, String> statisticalScope;

    public Map<String, Object> toMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("catalogTitles", catalogTitles);
        values.put("totalCopies", totalCopies);
        values.put("availableCopies", availableCopies);
        values.put("borrowedCopies", borrowedCopies);
        values.put("availableTitles", availableTitles);
        values.put("lowStockTitles", lowStockTitles);
        values.put("accountCount", accountCount);
        values.put("memberCount", memberCount);
        values.put("validMemberCount", validMemberCount);
        values.put("unknownValidityMemberCount", unknownValidityMemberCount);
        values.put("todayBorrows", todayBorrows);
        values.put("overdueCount", overdueCount);
        values.put("borrowRecordCount", borrowRecordCount);
        values.put("asOfDate", asOfDate);
        values.put("statisticalScope", statisticalScope);
        return values;
    }
}
