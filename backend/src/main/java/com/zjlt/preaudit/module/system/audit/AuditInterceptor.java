package com.zjlt.preaudit.module.system.audit;

import com.zjlt.preaudit.common.security.UserContext;
import com.zjlt.preaudit.common.security.UserPrincipal;
import com.zjlt.preaudit.common.util.TextUtil;
import com.zjlt.preaudit.module.system.entity.AuditLog;
import com.zjlt.preaudit.module.system.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDateTime;

/**
 * 审计日志拦截器（G3）：记录写操作，便于事后追溯
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements HandlerInterceptor {

    private static final String ATTR_USER = "audit.username";
    private static final String ATTR_UID = "audit.userId";

    private final AuditLogMapper auditLogMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UserPrincipal p = UserContext.get();
        if (p != null) {
            request.setAttribute(ATTR_UID, p.id());
            request.setAttribute(ATTR_USER, p.username());
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return;
        }
        try {
            AuditLog auditLog = new AuditLog();
            Object uid = request.getAttribute(ATTR_UID);
            Object username = request.getAttribute(ATTR_USER);
            auditLog.setUserId(uid instanceof Long id ? id : null);
            auditLog.setUsername(username == null ? "anonymous" : username.toString());
            auditLog.setAction(method);
            auditLog.setTarget(TextUtil.truncate(request.getRequestURI(), 500));
            String query = request.getQueryString();
            auditLog.setDetail(TextUtil.truncate(query, 1000));
            auditLog.setIp(clientIp(request));
            auditLog.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(auditLog);
        } catch (Exception e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }

    private String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
