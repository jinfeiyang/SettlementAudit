package com.zjlt.preaudit.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 密码摘要（一期安全基线）：SHA-256(username + ':' + password + ':' + 固定盐)。
 * 接入 4A 统一认证后由平台托管，本地账号仅用于演示与应急。
 */
public final class PasswordUtil {

    private static final String SALT = "preaudit@2026";

    private PasswordUtil() {
    }

    public static String hash(String username, String rawPassword) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((username + ":" + rawPassword + ":" + SALT).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("密码摘要失败", e);
        }
    }

    public static boolean matches(String username, String rawPassword, String stored) {
        return stored != null && stored.equals(hash(username, rawPassword));
    }
}
