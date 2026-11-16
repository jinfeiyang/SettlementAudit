package com.zjlt.preaudit.common.security;

import com.zjlt.preaudit.common.exception.BizException;

/**
 * 当前请求用户上下文（请求线程内传递；4A 接入后由统一认证透传替换）
 */
public final class UserContext {

    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(UserPrincipal principal) {
        HOLDER.set(principal);
    }

    public static UserPrincipal get() {
        return HOLDER.get();
    }

    public static UserPrincipal require() {
        UserPrincipal p = HOLDER.get();
        if (p == null) {
            throw new BizException(401, "未登录或登录已过期");
        }
        return p;
    }

    public static Long userId() {
        UserPrincipal p = HOLDER.get();
        return p == null ? null : p.id();
    }

    public static String username() {
        UserPrincipal p = HOLDER.get();
        return p == null ? "anonymous" : p.username();
    }

    public static String roleCode() {
        UserPrincipal p = HOLDER.get();
        return p == null ? null : p.roleCode();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
