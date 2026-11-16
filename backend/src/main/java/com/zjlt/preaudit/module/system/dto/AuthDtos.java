package com.zjlt.preaudit.module.system.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 认证相关 DTO
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginReq(@NotBlank(message = "用户名不能为空") String username,
                           @NotBlank(message = "密码不能为空") String password) {
    }

    public record UserVO(Long id, String username, String realName, String roleCode, String roleName) {
    }

    public record LoginRes(String token, UserVO user) {
    }
}
