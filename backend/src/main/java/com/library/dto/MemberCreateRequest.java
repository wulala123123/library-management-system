package com.library.dto;

import jakarta.validation.constraints.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MemberCreateRequest extends MemberWriteRequest {
    @NotBlank @Size(min = 3, max = 20)
    @Pattern(regexp = "^[a-zA-Z0-9_\\-\\u4e00-\\u9fa5]+$")
    private String username;
    @NotBlank @Size(min = 6, max = 50) private String password;
}
