package com.library.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MemberUpdateRequest extends MemberWriteRequest {
    @NotNull @Min(0) private Long revision;
}
