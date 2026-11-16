package com.zjlt.preaudit.common.security;

/**
 * 登录用户主体（令牌内仅承载 id/username/roleCode，realName 由 /auth/me 补齐）
 */
public record UserPrincipal(Long id, String username, String realName, String roleCode) {

    public boolean isManager() {
        return "MANAGER".equals(roleCode);
    }
}
