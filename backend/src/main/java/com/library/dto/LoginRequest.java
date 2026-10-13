package com.library.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度3-50个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 100, message = "密码长度6-100个字符")
    private String password;

    @NotBlank(message = "验证码不能为空")
    @Size(min = 4, max = 4, message = "验证码为4位字符")
    private String captchaCode;

    @NotBlank(message = "验证码标识不能为空")
    @Size(max = 64)
    private String captchaKey;
}
