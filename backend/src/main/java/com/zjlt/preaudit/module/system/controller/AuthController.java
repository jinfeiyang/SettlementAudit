package com.zjlt.preaudit.module.system.controller;

import com.zjlt.preaudit.common.api.R;
import com.zjlt.preaudit.module.system.dto.AuthDtos;
import com.zjlt.preaudit.module.system.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "认证")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "登录")
    @PostMapping("/login")
    public R<AuthDtos.LoginRes> login(@Valid @RequestBody AuthDtos.LoginReq req) {
        return R.ok(authService.login(req));
    }

    @Operation(summary = "当前登录用户")
    @GetMapping("/me")
    public R<AuthDtos.UserVO> me() {
        return R.ok(authService.me());
    }
}
