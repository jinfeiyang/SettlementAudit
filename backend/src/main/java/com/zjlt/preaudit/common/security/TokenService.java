package com.zjlt.preaudit.common.security;

import com.zjlt.preaudit.common.exception.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * 令牌服务：HMAC-SHA256 签名（无状态，不依赖 Redis）。
 * 一期为安全基线实现；接入 4A 后由统一认证下发的用户态替换。
 */
@Component
public class TokenService {

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret;
    private final long ttlMillis;

    public TokenService(@Value("${preaudit.security.token-secret:preaudit-dev-secret}") String secret,
                        @Value("${preaudit.security.token-ttl-hours:12}") long ttlHours) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlMillis = Math.max(1, ttlHours) * 3600_000L;
    }

    public String issue(UserPrincipal principal) {
        long exp = System.currentTimeMillis() + ttlMillis;
        String payload = principal.id() + "|" + principal.username() + "|" + principal.roleCode() + "|" + exp;
        String p = B64E.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return p + "." + sign(p);
    }

    public UserPrincipal parse(String token) {
        int dot = token == null ? -1 : token.indexOf('.');
        if (dot <= 0) {
            throw new BizException(401, "令牌无效");
        }
        String p = token.substring(0, dot);
        String sig = token.substring(dot + 1);
        if (!MessageDigest.isEqual(sign(p).getBytes(StandardCharsets.UTF_8), sig.getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(401, "令牌签名校验失败");
        }
        String payload = new String(B64D.decode(p), StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|");
        if (parts.length != 4) {
            throw new BizException(401, "令牌格式错误");
        }
        long exp = Long.parseLong(parts[3]);
        if (System.currentTimeMillis() > exp) {
            throw new BizException(401, "登录已过期，请重新登录");
        }
        return new UserPrincipal(Long.valueOf(parts[0]), parts[1], null, parts[2]);
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64E.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }
}
