package com.library.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Full replacement of editable business fields; account/security/financial fields are excluded. */
@Data
public class MemberWriteRequest {
    @NotBlank @Size(max = 20) @Pattern(regexp = "^[\\p{L}\\s]*$") private String realName;
    @Size(max = 20) @Pattern(regexp = "^(?:|1[3-9]\\d{9})$") private String phone;
    @Size(max = 100) @Email private String email;
    @Size(max = 100) private String cardNo;
    @Size(max = 200) private String planName;
    private LocalDate serviceStart;
    private LocalDate serviceEnd;
    @NotNull @Pattern(regexp = "UNKNOWN|LIMITED|UNLIMITED") private String usageType = "UNKNOWN";
    @Min(0) private Integer usedUses;
    @Min(0) private Integer totalUses;
    @Size(max = 2000) private String notes;
    @Size(max = 500) private String address;
    @NotNull @Size(max = 30) @Valid private List<MemberChildRequest> children = List.of();
    @NotBlank @Size(max = 500) private String changeReason;

    @JsonAnySetter
    public void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("会员资料包含只读或不支持的字段");
    }
}
