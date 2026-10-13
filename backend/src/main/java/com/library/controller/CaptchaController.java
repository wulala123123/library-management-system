package com.library.controller;

import com.library.common.Result;
import com.library.service.CaptchaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/captcha")
@RequiredArgsConstructor
public class CaptchaController {
    private final CaptchaService captchaService;

    @GetMapping("/image")
    public Result<CaptchaService.CaptchaData> generateCaptcha() {
        return captchaService.generateCaptcha();
    }
}
