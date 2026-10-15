package com.library.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDate;

@Data
@JsonInclude(JsonInclude.Include.ALWAYS)
public class MemberSummary {
    private Long id;
    private String username;
    private String realName;
    private String phone;
    private String email;
    private Integer accountStatus;
    private boolean profilePresent;
    private long revision;
    private String cardNo;
    private String planName;
    private LocalDate serviceStart;
    private LocalDate serviceEnd;
    private String serviceStatus;
    private LocalDate asOfDate;
    private String usageType;
    private Integer usedUses;
    private Integer totalUses;
    private Long remainingUses;
    private boolean overused;
}
